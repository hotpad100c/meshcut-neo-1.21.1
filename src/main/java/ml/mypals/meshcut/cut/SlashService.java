package ml.mypals.meshcut.cut;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.block.MimicBlock;
import ml.mypals.meshcut.block.MimicBlockEntity;
import ml.mypals.meshcut.geom.CellGrid;
import ml.mypals.meshcut.geom.Patch;
import ml.mypals.meshcut.geom.PieceFit;
import ml.mypals.meshcut.geom.Polytope;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cuts with the surface a blade sweeps.
 * <p>
 * A swing sweeps out a surface. Where it crosses a block it leaves a {@link Patch}: a plane and the part of it that
 * was actually swept, which may stop inside the block. Every block that gets one remembers it and stays whole
 * (a cut right through shows as a glowing slit, one that stops midway as a scratch on the block's faces).
 * <p>
 * Then the structure is analysed. Each block is cut into small cells; two neighbouring cells are joined unless a
 * patch runs between them, and touching blocks are joined cell to cell. A patch that goes right through a block
 * therefore separates its two sides, one that stops midway does not (the cells join up around its end), and
 * several scratches that close a loop seal off what is inside. If this severs the structure, the blocks whose
 * parts ended up in different groups are split, and each group that came apart becomes its own sub-level. Only
 * the parts that leave must have a convex shape (they are the block clipped by half-spaces); what stays behind is
 * the block with those parts cut out of it. A block that cannot be split that way stays whole.
 * <p>
 * In the static world a group is only released if it is small enough to be an isolated island: one that reaches the
 * ground (or anything too big to follow, or unloaded) is treated as anchored and stays where it is.
 */
public final class SlashService {
    private static final double SAMPLE_STEP = 0.3;
    private static final int MAX_SAMPLES = 900;
    /** Groups larger than this are treated as anchored in the static world. */
    private static final int STATIC_CAP = 4096;
    private static final int SUBLEVEL_CAP = 20_000;
    /** Cuts remembered per block; beyond this the oldest is forgotten. */
    private static final int MAX_PATCHES = 6;
    /**
     * Each side of a cut must have at least this much volume (in blocks), so the blade merely grazing a corner
     * of a block doesn't count as cutting it.
     */
    private static final double MIN_SLICE_VOLUME = 0.05;
    /** ...and each side must reach at least this far (in blocks) from the plane, so a thin layer is not a cut. */
    private static final double MIN_SLICE_EXTENT = 0.15;
    /** A patch smaller than this (in square blocks) is not worth remembering. */
    private static final double MIN_PATCH_AREA = 0.03;
    private static final double SEPARATION_SPEED = 1.5;
    /**
     * A cut block held by fewer solid neighbours than this does not stay fixed: all its pieces break away together
     * (each with whatever is attached only to it), even if the rest of the structure is still connected.
     */
    private static final int MIN_FIXING_NEIGHBOURS = 3;
    /**
     * A block that has been cut this many times is shattered: every piece breaks away from everything but the
     * matching pieces of the blocks next to it. (Fewer cuts leave it whole until what holds it is gone.)
     */
    private static final int SHATTER_CUTS = 5;
    /** Group id for everything anchored or too big to follow; all such groups count as one. */
    private static final int HUGE = -2;

    /** The blade between two instants. */
    public record Swipe(Blade from, Blade to) {}

    /** A plane {@code n·p = d} in some frame (world, or a sub-level's plot space). */
    private record Plane(Vector3d n, double d) {}

    /** One swipe's swept surface: the plane through the blade and its motion, its outline, and sample points on it. */
    private record Sweep(Vec3 n, Vec3 center, Vec3[] quad, List<Vec3> samples) {}

    /** One block position: what it holds, its cuts, and the cell groups (pieces) those cuts divide it into. */
    private static final class Info {
        final BlockPos pos;
        @Nullable
        final CutService.Piece piece;
        final List<Patch> patches;
        final CellGrid grid;
        /** Pieces that break away from everything except other broken-away pieces. */
        final Set<Integer> detached = new HashSet<>();
        /** The block cannot be split into convex pieces, so it counts as one piece whatever its cuts say. */
        boolean whole;

        Info(BlockPos pos, @Nullable CutService.Piece piece, List<Patch> patches) {
            this.pos = pos;
            this.piece = piece;
            this.patches = patches;
            this.grid = piece == null ? CellGrid.FULL : CellGrid.of(piece.planes(), piece.excluded(), patches);
        }

        /** The graph node a cell group belongs to. */
        int node(int component) {
            return whole ? 0 : component;
        }

        /** The graph nodes of this block. */
        List<Integer> nodes() {
            List<Integer> nodes = new ArrayList<>();
            int count = whole ? Math.min(1, grid.componentCount()) : grid.componentCount();
            for (int i = 0; i < count; i++) {
                nodes.add(i);
            }
            return nodes;
        }

        /** The cell groups a node stands for. */
        Set<Integer> groupsOf(int node) {
            Set<Integer> groups = new HashSet<>();
            if (whole) {
                for (int i = 0; i < grid.componentCount(); i++) {
                    groups.add(i);
                }
            } else {
                groups.add(node);
            }
            return groups;
        }

        void detachAll() {
            detached.addAll(nodes());
        }
    }

    private record Node(BlockPos pos, int node) {}

    /** The convex shape of a piece that leaves a block: all its half-spaces, and those beyond the block's own cuts. */
    private record Fit(List<CutPlane> planes, List<CutPlane> extra) {}

    /** What one group of a block will be once it has been split. */
    private record Spec(BlockPos pos, Info info, List<CutPlane> planes, List<List<CutPlane>> excluded, List<Patch> patches) {}

    /** The blocks of one world (static) or one sub-level's plot, looked up lazily. */
    private static final class Domain {
        final ServerLevel level;
        final CapMode mode;
        final int cap;
        final boolean isStatic;
        @Nullable
        final ServerSubLevel sub;
        final Map<BlockPos, Info> infos = new HashMap<>();
        final Set<BlockPos> empty = new HashSet<>();

        Domain(ServerLevel level, CapMode mode, int cap, @Nullable ServerSubLevel sub) {
            this.level = level;
            this.mode = mode;
            this.cap = cap;
            this.isStatic = sub == null;
            this.sub = sub;
        }

        /** Whether the position is somewhere this domain may look: loaded static chunks, or inside the plot. */
        boolean inside(BlockPos pos) {
            if (sub != null) {
                return sub.getPlot().getBoundingBox().contains(pos.getX(), pos.getY(), pos.getZ());
            }
            return level.isInWorldBounds(pos);
        }

        /** Static chunks may be unloaded; looking at them would load them. A plot is loaded as a whole. */
        boolean loaded(BlockPos pos) {
            return sub != null || level.isLoaded(pos);
        }

        @Nullable
        Info info(BlockPos pos) {
            Info cached = infos.get(pos);
            if (cached != null) {
                return cached;
            }
            if (empty.contains(pos)) {
                return null;
            }
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.getBlock() instanceof LiquidBlock) {
                empty.add(pos);
                return null;
            }
            CutService.Piece piece = CutService.pieceAt(level, pos, mode);
            Info info = piece == null ? new Info(pos, null, List.of()) : new Info(pos, piece, piece.patches());
            infos.put(pos, info);
            return info;
        }
    }

    // ---------------------------------------------------------------- entry points

    public static CutService.Result slash(ServerLevel level, Blade from, Blade to, CapMode mode) {
        return slashSwing(level, List.of(new Swipe(from, to)), mode);
    }

    /**
     * Cuts with a whole swing at once: the surface it swept leaves its patches on every block it crossed, and the
     * result is analysed once.
     *
     * @return {@code null} if the blade never moved across itself, so there was no swept surface
     */
    public static CutService.Result slashSwing(ServerLevel level, List<Swipe> swipes, CapMode mode) {
        List<Sweep> sweeps = new ArrayList<>();
        for (Swipe swipe : swipes) {
            Sweep sweep = sweepOf(swipe.from(), swipe.to());
            if (sweep != null) {
                sweeps.add(sweep);
            }
        }
        if (sweeps.isEmpty()) {
            return null;
        }

        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (Sweep sweep : sweeps) {
            for (Vec3 p : sweep.samples()) {
                minX = Math.min(minX, p.x);
                minY = Math.min(minY, p.y);
                minZ = Math.min(minZ, p.z);
                maxX = Math.max(maxX, p.x);
                maxY = Math.max(maxY, p.y);
                maxZ = Math.max(maxZ, p.z);
            }
        }
        List<ServerSubLevel> subLevels = new ArrayList<>();
        for (SubLevel s : Sable.HELPER.getAllIntersecting(level,
                new BoundingBox3d(minX - 1, minY - 1, minZ - 1, maxX + 1, maxY + 1, maxZ + 1))) {
            if (s instanceof ServerSubLevel ss && !ss.isRemoved()) {
                subLevels.add(ss);
            }
        }

        int[] created = {0};
        var container = SubLevelContainer.getContainer(level);
        Vec3 representative = sweeps.get(0).n();

        Map<BlockPos, List<Patch>> worldCells = new LinkedHashMap<>();
        for (Sweep sweep : sweeps) {
            Vector3d n = new Vector3d(sweep.n().x, sweep.n().y, sweep.n().z);
            Plane plane = new Plane(n, n.dot(sweep.center().x, sweep.center().y, sweep.center().z));
            Vector3d[] quad = new Vector3d[4];
            for (int i = 0; i < 4; i++) {
                quad[i] = new Vector3d(sweep.quad()[i].x, sweep.quad()[i].y, sweep.quad()[i].z);
            }
            Set<BlockPos> seen = new HashSet<>();
            for (Vec3 p : sweep.samples()) {
                BlockPos cell = BlockPos.containing(p);
                if (level.isInWorldBounds(cell) && !(container != null && container.inBounds(cell)) && seen.add(cell)) {
                    addPatch(worldCells, cell, plane, quad);
                }
            }
        }
        int staticBlocks = process(level, null, worldCells, representative, mode, created);

        int cutLevels = 0;
        for (ServerSubLevel sub : subLevels) {
            if (sub.isRemoved()) {
                continue;
            }
            Pose3dc pose = sub.logicalPose();
            Map<BlockPos, List<Patch>> cells = new LinkedHashMap<>();
            for (Sweep sweep : sweeps) {
                Vector3d nl = pose.transformNormalInverse(new Vector3d(sweep.n().x, sweep.n().y, sweep.n().z));
                if (nl.lengthSquared() < 1e-12) {
                    continue;
                }
                nl.normalize();
                Vec3 c = sweep.center();
                Plane plane = new Plane(nl, nl.dot(pose.transformPositionInverse(new Vector3d(c.x, c.y, c.z))));
                Vector3d[] quad = new Vector3d[4];
                for (int i = 0; i < 4; i++) {
                    Vec3 q = sweep.quad()[i];
                    quad[i] = pose.transformPositionInverse(new Vector3d(q.x, q.y, q.z));
                }
                Set<BlockPos> seen = new HashSet<>();
                for (Vec3 p : sweep.samples()) {
                    Vector3d local = pose.transformPositionInverse(new Vector3d(p.x, p.y, p.z));
                    BlockPos cell = BlockPos.containing(local.x, local.y, local.z);
                    if (container != null && container.inBounds(cell) && seen.add(cell) && !level.getBlockState(cell).isAir()) {
                        addPatch(cells, cell, plane, quad);
                    }
                }
            }
            if (process(level, sub, cells, representative, mode, created) > 0) {
                cutLevels++;
            }
        }
        return new CutService.Result(staticBlocks, cutLevels, created[0]);
    }

    /** The part of a sweep's surface inside one block, as a patch of that block; merged into what is there already. */
    private static void addPatch(Map<BlockPos, List<Patch>> cells, BlockPos cell, Plane plane, Vector3d[] quad) {
        Vector3d n = plane.n();
        double d = plane.d() - (n.x * cell.getX() + n.y * cell.getY() + n.z * cell.getZ());
        CutPlane keep = new CutPlane(n.x, n.y, n.z, d);
        float[] polygon = new float[12];
        for (int i = 0; i < 4; i++) {
            // the swept outline projected onto the plane, in the block's own coordinates
            double px = quad[i].x - cell.getX(), py = quad[i].y - cell.getY(), pz = quad[i].z - cell.getZ();
            double off = d - (n.x * px + n.y * py + n.z * pz);
            polygon[i * 3] = (float) (px + n.x * off);
            polygon[i * 3 + 1] = (float) (py + n.y * off);
            polygon[i * 3 + 2] = (float) (pz + n.z * off);
        }
        Patch patch = Patch.of(keep, polygon);
        if (patch != null) {
            mergeInto(cells.computeIfAbsent(cell, k -> new ArrayList<>()), patch);
        }
    }

    /** Adds a patch to a block's list, joining it to one it continues. */
    private static void mergeInto(List<Patch> list, Patch patch) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).samePlane(patch)) {
                list.set(i, list.get(i).union(patch));
                return;
            }
        }
        list.add(patch);
        if (list.size() > MAX_PATCHES) {
            list.remove(0);
        }
    }

    @Nullable
    private static Sweep sweepOf(Blade from, Blade to) {
        Vec3 dir = from.direction().add(to.direction());
        Vec3 motion = to.base().subtract(from.base()).add(to.tip().subtract(from.tip())).scale(0.5);
        if (dir.lengthSqr() < 1e-6) {
            return null;
        }
        Vec3 n = dir.normalize().cross(motion);
        if (n.lengthSqr() < 1e-8) {
            return null;
        }
        Vec3 center = from.base().add(from.tip()).add(to.base()).add(to.tip()).scale(0.25);
        Vec3[] quad = {from.base(), from.tip(), to.tip(), to.base()};
        return new Sweep(n.normalize(), center, quad, sweptSamples(from, to));
    }

    /** Points spread over the quad the blade sweeps, dense enough that every block it crosses contains one. */
    private static List<Vec3> sweptSamples(Blade from, Blade to) {
        double lengthU = Math.max(from.direction().length(), to.direction().length());
        double lengthV = Math.max(to.base().subtract(from.base()).length(), to.tip().subtract(from.tip()).length());
        int nu = (int) Math.ceil(lengthU / SAMPLE_STEP) + 1;
        int nv = (int) Math.ceil(lengthV / SAMPLE_STEP) + 1;
        while (nu * nv > MAX_SAMPLES) {
            nu = Math.max(2, nu * 3 / 4);
            nv = Math.max(2, nv * 3 / 4);
        }
        List<Vec3> samples = new ArrayList<>(nu * nv);
        for (int i = 0; i < nu; i++) {
            double u = nu == 1 ? 0 : (double) i / (nu - 1);
            Vec3 a = from.base().lerp(from.tip(), u);
            Vec3 b = to.base().lerp(to.tip(), u);
            for (int j = 0; j < nv; j++) {
                samples.add(a.lerp(b, nv == 1 ? 0 : (double) j / (nv - 1)));
            }
        }
        return samples;
    }

    // ---------------------------------------------------------------- one domain

    /**
     * @param sub the sub-level whose plot the cells are in, or {@code null} for the static world
     * @return the number of blocks the cut passed through
     */
    private static int process(ServerLevel level, @Nullable ServerSubLevel sub, Map<BlockPos, List<Patch>> cells,
                               Vec3 nWorld, CapMode mode, int[] created) {
        Domain dom = new Domain(level, mode, sub == null ? STATIC_CAP : SUBLEVEL_CAP, sub);

        // 1. every block a patch passes through remembers it
        List<Info> hits = new ArrayList<>();
        Vector3d nl = null;
        double dl = 0;
        for (var entry : cells.entrySet()) {
            BlockPos cell = entry.getKey();
            if (!dom.loaded(cell)) {
                continue;
            }
            CutService.Piece piece = CutService.pieceAt(level, cell, mode);
            if (piece == null) {
                continue;
            }
            List<Patch> merged = new ArrayList<>(piece.patches());
            boolean changed = false;
            for (Patch patch : entry.getValue()) {
                if (Polytope.classify(piece.planes(), patch.plane(), MIN_SLICE_VOLUME, MIN_SLICE_EXTENT) != Polytope.Side.SPLIT
                        || (!patch.through() && Patch.area(patch.polygon()) < MIN_PATCH_AREA)) {
                    continue; // grazes the block, or too little of it was swept to matter
                }
                List<Patch> before = new ArrayList<>(merged);
                mergeInto(merged, patch);
                if (!merged.equals(before)) {
                    changed = true;
                    if (nl == null) { // which way the parts are pushed apart is judged by the first cut
                        CutPlane p = patch.plane();
                        nl = new Vector3d(p.nx(), p.ny(), p.nz());
                        dl = p.d() + nl.x * cell.getX() + nl.y * cell.getY() + nl.z * cell.getZ();
                    }
                }
            }
            if (changed) {
                BlockPos fixed = cell.immutable();
                Info info = new Info(fixed, piece, merged);
                dom.infos.put(fixed, info);
                hits.add(info);
            }
        }
        if (hits.isEmpty()) {
            return 0;
        }
        return resolve(level, sub, dom, hits, nl, dl, nWorld, created, true);
    }

    /** Who moves out of the structure, and which of the blocks that were cut are severed. */
    private record Plan(Map<Info, Map<Integer, Set<Integer>>> severed, Map<Integer, List<Node>> nodesByComp,
                        Set<Integer> movers) {}

    /**
     * Decides what the cut blocks do: stay whole with their cuts remembered, or split, with every group that came
     * apart becoming its own sub-level (or staying, if it is the biggest part or anchored).
     *
     * @param nl,dl,nWorld the plane the cut was made along (only used to decide which way the parts are pushed)
     * @param applyUnsevered whether blocks that stay whole are written back with their (possibly new) cuts; off when
     *                       the blocks were merely re-examined
     * @return the number of blocks examined
     */
    private static int resolve(ServerLevel level, @Nullable ServerSubLevel sub, Domain dom, List<Info> hits,
                               Vector3d nl, double dl, Vec3 nWorld, int[] created, boolean applyUnsevered) {
        for (Info hit : hits) {
            if (hit.patches.size() >= SHATTER_CUTS) {
                hit.detachAll();
            }
        }

        // 2. which pieces are connected to which
        Map<Node, Integer> comp = new HashMap<>();
        components(dom, hits, comp);

        // A cut that left a block's pieces connected does not always mean the block holds: if too few neighbours
        // fix it, it comes apart anyway and all its pieces fall.
        boolean anyBreaking = false;
        for (Info hit : hits) {
            if (!isSevered(hit, comp) && solidNeighbours(level, hit.pos) < MIN_FIXING_NEIGHBOURS) {
                hit.detachAll();
                anyBreaking = true;
            }
        }
        if (anyBreaking) {
            comp.clear();
            components(dom, hits, comp);
        }

        // 3.-5. who is severed and who moves; every piece that moves must be convex, or its block stays whole
        Plan attempted = null;
        Map<Info, Map<Integer, Fit>> fits = new HashMap<>();
        for (int attempt = 0; attempt < 8; attempt++) {
            attempted = plan(dom, sub, comp, hits);
            fits.clear();
            Info unfit = fitMovers(attempted, fits);
            if (unfit == null) {
                break;
            }
            unfit.whole = true; // it cannot come apart into convex pieces: it stays one piece
            unfit.detached.clear();
            comp.clear();
            components(dom, hits, comp);
            attempted = null;
        }
        if (attempted == null) {
            return hits.size(); // could not settle on a plan; leave things as they are
        }

        final Plan plan = attempted;

        // Spill the contents of real block entities before they are replaced by mimics.
        Map<BlockPos, CompoundTag> beTags = detachBlockEntities(level, hits);

        if (plan.severed().isEmpty()) {
            // Nothing came apart: the blocks just remember the cut.
            if (applyUnsevered) {
                for (Info hit : hits) {
                    place(level, hit, hit.piece.planes(), hit.piece.excluded(), hit.patches, beTags);
                }
            }
            return hits.size();
        }

        // 5. the pieces each group will hold
        Map<Integer, List<Spec>> specs = new HashMap<>();
        for (var entry : plan.severed().entrySet()) {
            Info info = entry.getKey();
            Map<Integer, Fit> blockFits = fits.getOrDefault(info, Map.of());
            // what the parts that leave were cut out of the block
            List<List<CutPlane>> holes = new ArrayList<>(info.piece.excluded());
            for (Fit fit : blockFits.values()) {
                if (!fit.extra().isEmpty()) {
                    holes.add(fit.extra());
                }
            }
            Set<Integer> stayingGroups = new LinkedHashSet<>();
            Set<Integer> stayingCells = new HashSet<>();
            List<CutPlane> usedByMovers = new ArrayList<>();
            blockFits.values().forEach(f -> usedByMovers.addAll(f.extra()));
            for (var group : entry.getValue().entrySet()) {
                if (plan.movers().contains(group.getKey())) {
                    Fit fit = blockFits.get(group.getKey());
                    List<Patch> kept = PieceFit.remaining(info.grid, group.getValue(), info.patches, fit.extra());
                    specs.computeIfAbsent(group.getKey(), k -> new ArrayList<>())
                            .add(new Spec(info.pos, info, fit.planes(), info.piece.excluded(), kept));
                } else {
                    stayingGroups.add(group.getKey());
                    stayingCells.addAll(group.getValue());
                }
            }
            if (!stayingGroups.isEmpty()) {
                List<Patch> kept = PieceFit.remaining(info.grid, stayingCells, info.patches, usedByMovers);
                specs.computeIfAbsent(stayingGroups.iterator().next(), k -> new ArrayList<>())
                        .add(new Spec(info.pos, info, info.piece.planes(), holes, kept));
            }
        }
        for (Info hit : hits) {
            if (!plan.severed().containsKey(hit) && !hit.nodes().isEmpty()) {
                Integer id = comp.get(new Node(hit.pos, hit.nodes().get(0)));
                if (id != null) {
                    specs.computeIfAbsent(id, k -> new ArrayList<>())
                            .add(new Spec(hit.pos, hit, hit.piece.planes(), hit.piece.excluded(), hit.patches));
                }
            }
        }

        // If nothing would be left in the sub-level while the others move out, Sable would discard it on the spot.
        BlockPos placeholder = null;
        if (sub != null) {
            Set<BlockPos> leaving = new HashSet<>();
            plan.movers().forEach(id -> plan.nodesByComp().get(id).forEach(node -> leaving.add(node.pos())));
            boolean survivor = false;
            for (var entry : comp.entrySet()) {
                BlockPos p = entry.getKey().pos();
                if (!plan.movers().contains(entry.getValue()) && !leaving.contains(p) && !plan.severed().containsKey(dom.infos.get(p))) {
                    survivor = true;
                    break;
                }
            }
            if (!survivor) {
                Set<BlockPos> near = new HashSet<>();
                plan.severed().keySet().forEach(info -> near.add(info.pos));
                placeholder = CutService.findPlaceholderSpot(level, near);
                if (placeholder == null) {
                    return 0; // nothing has been changed yet except spilled contents
                }
                level.setBlock(placeholder, Blocks.STONE.defaultBlockState(), 2);
            }
        }

        // 6. move the groups out, then put the pieces that stay in place
        for (int id : plan.movers()) {
            List<BlockPos> positions = new ArrayList<>();
            Set<BlockPos> seen = new HashSet<>();
            double side = 0;
            for (Node node : plan.nodesByComp().get(id)) {
                if (seen.add(node.pos())) {
                    positions.add(node.pos());
                }
                side += Math.signum(sideOf(dom, node, nl, dl));
            }
            for (Spec spec : specs.getOrDefault(id, List.of())) {
                place(level, spec.info(), spec.planes(), spec.excluded(), spec.patches(), beTags);
            }
            ServerSubLevel moved = CutService.assemble(level, positions);
            if (moved != null) {
                created[0]++;
                CutService.push(moved, nWorld.scale(side >= 0 ? SEPARATION_SPEED : -SEPARATION_SPEED));
            }
        }
        for (var entry : specs.entrySet()) {
            if (!plan.movers().contains(entry.getKey())) {
                for (Spec spec : entry.getValue()) {
                    place(level, spec.info(), spec.planes(), spec.excluded(), spec.patches(), beTags);
                }
            }
        }
        if (placeholder != null) {
            level.setBlock(placeholder, Blocks.AIR.defaultBlockState(), 2);
        }
        if (sub != null && !sub.isRemoved()) {
            refreshMass(level, sub);
        }
        return hits.size();
    }

    /** Which blocks are severed (their pieces are in different groups), and which groups leave the structure. */
    private static Plan plan(Domain dom, @Nullable ServerSubLevel sub, Map<Node, Integer> comp, List<Info> hits) {
        Map<Info, Map<Integer, Set<Integer>>> severed = new LinkedHashMap<>();
        for (Info info : dom.infos.values()) {
            List<Integer> nodes = info.nodes();
            if (nodes.size() < 2) {
                continue;
            }
            Map<Integer, Set<Integer>> byGroup = new LinkedHashMap<>();
            boolean allReached = true;
            for (int node : nodes) {
                Integer id = comp.get(new Node(info.pos, node));
                if (id == null) {
                    allReached = false;
                    break;
                }
                byGroup.computeIfAbsent(id, k -> new HashSet<>()).addAll(info.groupsOf(node));
            }
            if (allReached && byGroup.size() >= 2) {
                severed.put(info, byGroup);
            }
        }

        Map<Integer, List<Node>> nodesByComp = new HashMap<>();
        comp.forEach((node, id) -> nodesByComp.computeIfAbsent(id, k -> new ArrayList<>()).add(node));
        Set<Integer> affected = new LinkedHashSet<>();
        severed.values().forEach(groups -> affected.addAll(groups.keySet()));
        List<Integer> finite = new ArrayList<>();
        for (int id : affected) {
            if (id != HUGE) {
                finite.add(id);
            }
        }
        Set<Integer> movers = new LinkedHashSet<>(finite);
        if (sub != null && !affected.contains(HUGE)) {
            // Inside a sub-level the biggest part that is still joined to the rest of it stays where it is. A group
            // made only of pieces that broke away is joined to nothing, so it moves out as well.
            finite.stream()
                    .filter(id -> nodesByComp.get(id).stream().anyMatch(node -> !dom.infos.get(node.pos()).detached.contains(node.node())))
                    .max(Comparator.comparingInt(id -> nodesByComp.get(id).size()))
                    .ifPresent(movers::remove);
        }
        return new Plan(severed, nodesByComp, movers);
    }

    /**
     * Works out the convex shape of every piece that leaves a severed block.
     *
     * @return a block one of whose leaving pieces is not convex, or {@code null} if all of them are
     */
    @Nullable
    private static Info fitMovers(Plan plan, Map<Info, Map<Integer, Fit>> fits) {
        for (var entry : plan.severed().entrySet()) {
            Info info = entry.getKey();
            for (var group : entry.getValue().entrySet()) {
                if (!plan.movers().contains(group.getKey())) {
                    continue;
                }
                List<CutPlane> planes = PieceFit.fit(info.grid, group.getValue(), info.piece.planes(), info.patches);
                if (planes == null) {
                    return info;
                }
                Fit fit = new Fit(planes, planes.subList(info.piece.planes().size(), planes.size()));
                fits.computeIfAbsent(info, k -> new HashMap<>()).put(group.getKey(), fit);
            }
        }
        return null;
    }

    /**
     * Recomputes a sub-level's mass, centre of mass and inertia from its blocks as they are now, and moves the body
     * so that nothing visibly shifts when the centre of mass moves. Blocks that were taken out and put back have
     * been through many incremental updates; this makes sure the sub-level ends up with the true values.
     */
    private static void refreshMass(ServerLevel level, ServerSubLevel sub) {
        var container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }
        var physics = container.physicsSystem();
        try {
            Vector3dc before = sub.getMassTracker().getCenterOfMass();
            Vector3d oldCenter = before == null ? null : new Vector3d(before);
            physics.updatePose(sub);
            sub.buildMassTracker();
            sub.updateMergedMassData((float) physics.getPartialPhysicsTick());
            Vector3dc after = sub.getMassTracker().getCenterOfMass();
            if (oldCenter != null && after != null) {
                Pose3d pose = sub.logicalPose();
                Vector3d moved = pose.orientation().transform(new Vector3d(after).sub(oldCenter)).add(pose.position());
                physics.getPipeline().teleport(sub, moved, pose.orientation());
                pose.position().set(moved);
            }
            physics.getPipeline().onStatsChanged(sub);
        } catch (RuntimeException e) {
            MeshCut.LOGGER.warn("Couldn't refresh the mass of {}", sub, e);
        }
    }

    // ---------------------------------------------------------------- re-examining

    private record Recheck(ServerLevel level, BlockPos pos) {}

    /** Mimics whose surroundings changed, to be looked at again next tick (server thread only). */
    private static final Set<Recheck> RECHECKS = new LinkedHashSet<>();
    private static final int RECHECKS_PER_TICK = 16;

    /**
     * A cut block that stayed whole was only held together by what was around it. When a neighbour changes (it is
     * broken, say), that may no longer be true, so the block is examined again as if it had just been cut.
     */
    public static void queueRecheck(ServerLevel level, BlockPos pos) {
        RECHECKS.add(new Recheck(level, pos.immutable()));
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        if (RECHECKS.isEmpty()) {
            return;
        }
        List<Recheck> batch = new ArrayList<>();
        for (var it = RECHECKS.iterator(); it.hasNext() && batch.size() < RECHECKS_PER_TICK; ) {
            batch.add(it.next());
            it.remove();
        }
        for (Recheck r : batch) {
            try {
                recheck(r.level(), r.pos());
            } catch (RuntimeException e) {
                MeshCut.LOGGER.error("Re-examining the cut block at {} failed", r.pos(), e);
            }
        }
    }

    private static void recheck(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockState(pos).getBlock() instanceof MimicBlock)
                || !(level.getBlockEntity(pos) instanceof MimicBlockEntity be) || be.getPatches().isEmpty()) {
            return;
        }
        var container = SubLevelContainer.getContainer(level);
        ServerSubLevel sub = container != null && container.inBounds(pos)
                && Sable.HELPER.getContaining(level, pos) instanceof ServerSubLevel s && !s.isRemoved() ? s : null;
        Domain dom = new Domain(level, be.getCapMode(), sub == null ? STATIC_CAP : SUBLEVEL_CAP, sub);
        if (!dom.loaded(pos)) {
            return;
        }
        CutService.Piece piece = CutService.pieceAt(level, pos, be.getCapMode());
        if (piece == null) {
            return;
        }
        Info info = new Info(pos, piece, piece.patches());
        if (info.nodes().size() < 2 && piece.patches().size() < SHATTER_CUTS
                && solidNeighbours(level, pos) >= MIN_FIXING_NEIGHBOURS) {
            return; // nothing divides it and it is held: nothing can have changed for the worse
        }
        dom.infos.put(pos, info);

        // the plane of its newest cut, in the domain's frame, only decides which way the parts are pushed
        CutPlane newest = piece.patches().get(piece.patches().size() - 1).plane();
        Vector3d nl = new Vector3d(newest.nx(), newest.ny(), newest.nz());
        double dl = newest.d() + nl.x * pos.getX() + nl.y * pos.getY() + nl.z * pos.getZ();
        Vector3d world = sub == null ? new Vector3d(nl) : sub.logicalPose().transformNormal(new Vector3d(nl));
        resolve(level, sub, dom, List.of(info), nl, dl, new Vec3(world.x, world.y, world.z), new int[]{0}, false);
    }

    // ---------------------------------------------------------------- graph

    private static int faceIndex(Direction dir) {
        return dir.getAxis().ordinal() * 2 + (dir.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 0 : 1);
    }

    /**
     * Floods outwards from {@code start} over touching pieces and gives them all one group id. A flood that grows
     * past the cap, runs into an unloaded chunk or reaches a group that did is anchored: {@link #HUGE}.
     */
    private static void label(Domain dom, Map<Node, Integer> comp, Node start, int[] nextId) {
        Set<Node> reached = new LinkedHashSet<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        reached.add(start);
        queue.add(start);
        boolean huge = false;
        int expanded = 0;
        while (!queue.isEmpty()) {
            Node a = queue.poll();
            if (++expanded > dom.cap) {
                huge = true;
                break;
            }
            Info infoA = dom.infos.get(a.pos());
            boolean detachedA = infoA.detached.contains(a.node());
            for (Direction dir : Direction.values()) {
                BlockPos q = a.pos().relative(dir);
                if (!dom.inside(q)) {
                    continue;
                }
                if (!dom.loaded(q)) {
                    huge = true;
                    continue;
                }
                Info infoB = dom.info(q);
                if (infoB == null) {
                    continue;
                }
                // join cell to cell across the shared face
                int faceA = faceIndex(dir), faceB = faceA ^ 1;
                Set<Integer> touching = new HashSet<>();
                for (int cell = 0; cell < CellGrid.N * CellGrid.N; cell++) {
                    int ca = infoA.grid.faceComponent(faceA, cell);
                    if (ca < 0 || infoA.node(ca) != a.node()) {
                        continue;
                    }
                    int cb = infoB.grid.faceComponent(faceB, cell);
                    if (cb >= 0) {
                        touching.add(infoB.node(cb));
                    }
                }
                for (int nodeB : touching) {
                    Node b = new Node(q, nodeB);
                    if (reached.contains(b) || detachedA != infoB.detached.contains(nodeB)) {
                        continue; // one of the two is breaking away from the other
                    }
                    Integer existing = comp.get(b);
                    if (existing == null) {
                        reached.add(b);
                        queue.add(b);
                    } else if (existing == HUGE) {
                        huge = true; // connected to something anchored
                    }
                }
            }
            if (huge) {
                break; // everything reached so far, and what is still queued, belongs to the anchored group
            }
        }
        int id = huge ? HUGE : nextId[0]++;
        for (Node node : reached) {
            comp.put(node, id);
        }
    }

    /** Labels every piece reachable from the cut blocks with its group. */
    private static void components(Domain dom, List<Info> hits, Map<Node, Integer> comp) {
        int[] nextId = {0};
        for (Info hit : hits) {
            for (int node : hit.nodes()) {
                Node start = new Node(hit.pos, node);
                if (!comp.containsKey(start)) {
                    label(dom, comp, start, nextId);
                }
            }
        }
    }

    /** Whether the pieces of this block ended up in different groups. */
    private static boolean isSevered(Info info, Map<Node, Integer> comp) {
        Integer first = null;
        for (int node : info.nodes()) {
            Integer id = comp.get(new Node(info.pos, node));
            if (id == null) {
                return false;
            }
            if (first == null) {
                first = id;
            } else if (!first.equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** Neighbouring blocks (of the six) that are solid, i.e. what holds a block in place. */
    private static int solidNeighbours(ServerLevel level, BlockPos pos) {
        int count = 0;
        for (Direction dir : Direction.values()) {
            BlockState state = level.getBlockState(pos.relative(dir));
            if (!state.isAir() && !(state.getBlock() instanceof LiquidBlock)) {
                count++;
            }
        }
        return count;
    }

    /** Which side of the cut plane a piece is on (positive: the normal's side), judged by its centre. */
    private static double sideOf(Domain dom, Node node, Vector3d nl, double dl) {
        Info info = dom.infos.get(node.pos());
        double[] c = info.whole || info.grid == CellGrid.FULL ? new double[]{0.5, 0.5, 0.5} : info.grid.centroid(node.node());
        return nl.x * (node.pos().getX() + c[0]) + nl.y * (node.pos().getY() + c[1]) + nl.z * (node.pos().getZ() + c[2]) - dl;
    }

    // ---------------------------------------------------------------- placing

    private static Map<BlockPos, CompoundTag> detachBlockEntities(ServerLevel level, List<Info> hits) {
        Map<BlockPos, CompoundTag> tags = new HashMap<>();
        for (Info hit : hits) {
            if (hit.piece.undetachedBe()) {
                BlockEntity be = level.getBlockEntity(hit.pos);
                if (be != null) {
                    CutService.spillContents(level, hit.pos, be);
                    tags.put(hit.pos, be.saveWithoutMetadata(level.registryAccess()));
                }
            }
        }
        return tags;
    }

    private static void place(ServerLevel level, Info info, List<CutPlane> planes, List<List<CutPlane>> excluded,
                              List<Patch> patches, Map<BlockPos, CompoundTag> beTags) {
        CutService.Piece piece = info.piece;
        MimicBlock.place(level, info.pos, piece.target(), planes, excluded, patches, piece.mode(),
                beTags.getOrDefault(info.pos, piece.beTag()));
    }

    private SlashService() {}
}
