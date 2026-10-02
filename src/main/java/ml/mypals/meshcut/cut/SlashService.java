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
import ml.mypals.meshcut.cut.graph.BlockNodes;
import ml.mypals.meshcut.cut.graph.StructureGraph;
import ml.mypals.meshcut.cut.graph.WorldGraphs;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

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
    /**
     * Each side of a cut must have at least this much volume (in blocks), so the blade merely grazing a corner
     * of a block doesn't count as cutting it.
     */
    private static final double MIN_SLICE_VOLUME = 0.05;
    /** ...and each side must reach at least this far (in blocks) from the plane, so a thin layer is not a cut. */
    private static final double MIN_SLICE_EXTENT = 0.15;
    /** A patch smaller than this (in square blocks) is not worth remembering. */
    private static final double MIN_PATCH_AREA = 0.03;
    /** A layer burnt away must be at least this much of a block, or there is nothing to burn. */
    private static final double MIN_BURN_VOLUME = 1e-3;
    private static final double SEPARATION_SPEED = 1.5;
    /** Structure id for everything attached to more than the search was willing to follow; all of it counts as one. */
    private static final int HUGE = -2;

    /** The blade between two instants. */
    public record Swipe(Blade from, Blade to) {}

    /** One swipe's swept surface: the plane through the blade and its motion, its outline, and sample points on it. */
    private record Sweep(Vec3 n, Vec3 center, Vec3[] quad, List<Vec3> samples) {}

    /** One block position: what it holds, its cuts, and the cell groups (pieces) those cuts divide it into. */
    private static final class Info {
        final BlockPos pos;
        @Nullable
        final CutService.Piece piece;
        final List<Patch> patches;
        final CellGrid grid;
        /** The block cannot be split into convex pieces, so it counts as one piece whatever its cuts say. */
        boolean whole;
        /** A thin layer of the block was burnt away by a cut along its edge, so it may no longer touch its neighbours. */
        boolean burned;

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
        final boolean isStatic;
        @Nullable
        final ServerSubLevel sub;
        final Map<BlockPos, Info> infos = new HashMap<>();
        final Set<BlockPos> empty = new HashSet<>();

        Domain(ServerLevel level, CapMode mode, @Nullable ServerSubLevel sub) {
            this.level = level;
            this.mode = mode;
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
            for (Vec3 p : sweep.quad()) {
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

        Map<BlockPos, List<StrokeFit.Fragment>> worldFragments = new LinkedHashMap<>();
        for (Sweep sweep : sweeps) {
            Vector3d n = new Vector3d(sweep.n().x, sweep.n().y, sweep.n().z);
            Vector3d center = new Vector3d(sweep.center().x, sweep.center().y, sweep.center().z);
            Vector3d[] quad = new Vector3d[4];
            for (int i = 0; i < 4; i++) {
                quad[i] = new Vector3d(sweep.quad()[i].x, sweep.quad()[i].y, sweep.quad()[i].z);
            }
            collect(worldFragments, n, center, quad, sweep.samples(), null,
                    cell -> level.isInWorldBounds(cell) && !(container != null && container.inBounds(cell)));
        }
        int staticBlocks = process(level, null, StrokeFit.fitPatches(worldFragments), representative, mode, created);

        int cutLevels = 0;
        for (ServerSubLevel sub : subLevels) {
            if (sub.isRemoved()) {
                continue;
            }
            Pose3dc pose = sub.logicalPose();
            Map<BlockPos, List<StrokeFit.Fragment>> fragments = new LinkedHashMap<>();
            for (Sweep sweep : sweeps) {
                Vector3d nl = pose.transformNormalInverse(new Vector3d(sweep.n().x, sweep.n().y, sweep.n().z));
                if (nl.lengthSquared() < 1e-12) {
                    continue;
                }
                nl.normalize();
                Vec3 c = sweep.center();
                Vector3d center = pose.transformPositionInverse(new Vector3d(c.x, c.y, c.z));
                Vector3d[] quad = new Vector3d[4];
                for (int i = 0; i < 4; i++) {
                    Vec3 q = sweep.quad()[i];
                    quad[i] = pose.transformPositionInverse(new Vector3d(q.x, q.y, q.z));
                }
                collect(fragments, nl, center, quad, sweep.samples(), pose,
                        cell -> container != null && container.inBounds(cell) && !level.getBlockState(cell).isAir());
            }
            if (process(level, sub, StrokeFit.fitPatches(fragments), representative, mode, created) > 0) {
                cutLevels++;
            }
        }
        return new CutService.Result(staticBlocks, cutLevels, created[0]);
    }

    /** More candidate blocks than this and the swept quad is not worth walking cell by cell: its samples are used. */
    private static final int MAX_CANDIDATE_CELLS = 600;
    /** Smallest piece of a sweep inside a block (in square blocks) that counts. */
    private static final double MIN_FRAGMENT_AREA = 1e-3;


    /**
     * Cuts the swept quad up by the blocks it crosses.
     *
     * @param pose   the sub-level's pose if the quad was brought into its plot space (for the samples), else null
     * @param accept which positions may be cut in this frame
     */
    private static void collect(Map<BlockPos, List<StrokeFit.Fragment>> out, Vector3d n, Vector3d center, Vector3d[] quad,
                                List<Vec3> samples, @Nullable Pose3dc pose, Predicate<BlockPos> accept) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (Vector3d q : quad) {
            minX = Math.min(minX, q.x);
            minY = Math.min(minY, q.y);
            minZ = Math.min(minZ, q.z);
            maxX = Math.max(maxX, q.x);
            maxY = Math.max(maxY, q.y);
            maxZ = Math.max(maxZ, q.z);
        }
        int x0 = (int) Math.floor(minX), y0 = (int) Math.floor(minY), z0 = (int) Math.floor(minZ);
        int x1 = (int) Math.floor(maxX), y1 = (int) Math.floor(maxY), z1 = (int) Math.floor(maxZ);
        long count = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
        Set<BlockPos> candidates = new LinkedHashSet<>();
        if (count <= MAX_CANDIDATE_CELLS) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        candidates.add(new BlockPos(x, y, z));
                    }
                }
            }
        } else {
            for (Vec3 p : samples) {
                Vector3d local = pose == null ? new Vector3d(p.x, p.y, p.z) : pose.transformPositionInverse(new Vector3d(p.x, p.y, p.z));
                candidates.add(BlockPos.containing(local.x, local.y, local.z));
            }
        }
        // which way the blade travelled across the quad
        Vector3d motion = new Vector3d(quad[3]).sub(quad[0]).add(new Vector3d(quad[2]).sub(quad[1])).mul(0.5);
        double d = n.dot(center);
        for (BlockPos cell : candidates) {
            if (!accept.test(cell)) {
                continue;
            }
            // the swept outline projected onto the plane, in the block's own coordinates
            float[] polygon = new float[12];
            for (int i = 0; i < 4; i++) {
                double off = d - n.dot(quad[i]);
                polygon[i * 3] = (float) (quad[i].x + n.x * off - cell.getX());
                polygon[i * 3 + 1] = (float) (quad[i].y + n.y * off - cell.getY());
                polygon[i * 3 + 2] = (float) (quad[i].z + n.z * off - cell.getZ());
            }
            float[] clipped = Patch.clipToCube(polygon);
            if (clipped.length >= 9 && Patch.area(clipped) > MIN_FRAGMENT_AREA) {
                out.computeIfAbsent(cell.immutable(), k -> new ArrayList<>()).add(new StrokeFit.Fragment(n, motion, clipped));
            }
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
        Domain dom = new Domain(level, mode, sub);

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
            List<CutPlane> planes = new ArrayList<>(piece.planes());
            boolean changed = false, burned = false;
            for (Patch patch : entry.getValue()) {
                Polytope.Side side = Polytope.classify(planes, patch.plane(), MIN_SLICE_VOLUME, MIN_SLICE_EXTENT);
                if (side != Polytope.Side.SPLIT) {
                    // A cut right through that leaves only a thin layer on one side (or just clips a corner) does
                    // not divide the block: the layer is burnt away.
                    CutPlane burn = patch.through() ? (side == Polytope.Side.ALL_KEPT ? patch.plane() : patch.plane().flipped()) : null;
                    if (burn != null && Polytope.volume(with(planes, burn.flipped())) > MIN_BURN_VOLUME
                            && Polytope.volume(with(planes, burn)) >= Polytope.MIN_VOLUME) {
                        planes.add(burn);
                        changed = burned = true;
                        if (nl == null) {
                            nl = new Vector3d(burn.nx(), burn.ny(), burn.nz());
                            dl = burn.d() + nl.x * cell.getX() + nl.y * cell.getY() + nl.z * cell.getZ();
                        }
                    }
                    continue; // otherwise it grazes the block, or too little of it was swept to matter
                }
                if (!patch.through() && Patch.area(patch.polygon()) < MIN_PATCH_AREA) {
                    continue;
                }
                List<Patch> before = new ArrayList<>(merged);
                StrokeFit.mergeInto(merged, patch);
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
                CutService.Piece now = burned
                        ? new CutService.Piece(piece.target(), planes, piece.excluded(), merged, piece.mode(), piece.beTag(), piece.undetachedBe())
                        : piece;
                Info info = new Info(fixed, now, merged);
                info.burned = burned;
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
    private record Plan(Map<Info, Map<Integer, Set<Integer>>> severed, Map<Integer, StructureGraph.Closure> closures,
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
        // 2. which pieces are connected to which
        Map<Node, Integer> comp = new HashMap<>();
        Map<Integer, StructureGraph.Closure> closures = components(dom, hits, comp, applyUnsevered);

        MeshCut.LOGGER.debug("Cut of {} blocks: {} structures not attached, {} pieces in total", hits.size(), closures.size(), comp.size());

        // 3.-5. who is severed and who moves; every piece that moves must be convex, or its block stays whole
        Plan attempted = null;
        Map<Info, Map<Integer, Fit>> fits = new HashMap<>();
        for (int attempt = 0; attempt < 8; attempt++) {
            attempted = plan(dom, sub, comp, closures, hits, !applyUnsevered && sub == null);
            fits.clear();
            Info unfit = fitMovers(attempted, fits);
            if (unfit == null) {
                break;
            }
            unfit.whole = true; // it cannot come apart into convex pieces: it stays one piece
            comp.clear();
            closures = components(dom, hits, comp, applyUnsevered);
            attempted = null;
        }
        if (attempted == null) {
            return hits.size(); // could not settle on a plan; leave things as they are
        }

        final Plan plan = attempted;

        // Spill the contents of real block entities before they are replaced by mimics.
        Map<BlockPos, CompoundTag> beTags = detachBlockEntities(level, hits);

        if (plan.severed().isEmpty() && plan.movers().isEmpty()) {
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
            // the biggest part of the sub-level stays, or something attached to more than we followed does
            boolean survivor = false;
            for (int id : comp.values()) {
                if (!plan.movers().contains(id)) {
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
            StructureGraph.Closure closure = plan.closures().get(id);
            long[] packed = closure.positions();
            List<BlockPos> positions = new ArrayList<>(packed.length);
            for (long p : packed) {
                positions.add(BlockPos.of(p));
            }
            double side = 0;
            for (StructureGraph.NodeRef seed : closure.seeds()) {
                side += Math.signum(sideOf(dom, new Node(new BlockPos(seed.x(), seed.y(), seed.z()), seed.fragment()), nl, dl));
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
    private static Plan plan(Domain dom, @Nullable ServerSubLevel sub, Map<Node, Integer> comp,
                             Map<Integer, StructureGraph.Closure> closures, List<Info> hits, boolean everything) {
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

        Set<Integer> affected = new LinkedHashSet<>();
        severed.values().forEach(groups -> affected.addAll(groups.keySet()));
        // a burnt edge may have cut a block off from its neighbours without splitting any single block
        for (Info hit : hits) {
            if (hit.burned) {
                Set<Integer> ids = new LinkedHashSet<>();
                for (Node seed : seedsOf(dom, hit)) {
                    Integer id = comp.get(seed);
                    if (id != null) {
                        ids.add(id);
                    }
                }
                if (ids.size() >= 2) {
                    affected.addAll(ids);
                }
            }
        }
        if (everything) {
            // a block that is being re-examined may have lost what held it: whatever of it is no longer attached falls
            affected.addAll(comp.values());
        }
        List<Integer> finite = new ArrayList<>();
        for (int id : affected) {
            if (id != HUGE) {
                finite.add(id);
            }
        }
        Set<Integer> movers = new LinkedHashSet<>(finite);
        if (sub != null && !affected.contains(HUGE)) {
            // Inside a sub-level the biggest part stays where it is.
            finite.stream()
                    .max(Comparator.comparingInt(id -> closures.get(id).blocks()))
                    .ifPresent(movers::remove);
        }
        return new Plan(severed, closures, movers);
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
                    MeshCut.LOGGER.info("Cut block at {} has {} pieces but piece {} cannot be split off as a convex shape; it stays whole ({} cuts)",
                            info.pos, info.grid.componentCount(), group.getValue(), info.patches.size());
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
                || !(level.getBlockEntity(pos) instanceof MimicBlockEntity be)) {
            return;
        }
        var container = SubLevelContainer.getContainer(level);
        ServerSubLevel sub = container != null && container.inBounds(pos)
                && Sable.HELPER.getContaining(level, pos) instanceof ServerSubLevel s && !s.isRemoved() ? s : null;
        Domain dom = new Domain(level, be.getCapMode(), sub);
        if (!dom.loaded(pos)) {
            return;
        }
        CutService.Piece piece = CutService.pieceAt(level, pos, be.getCapMode());
        if (piece == null) {
            return;
        }
        Info info = new Info(pos, piece, piece.patches());
        dom.infos.put(pos, info);

        // the plane of its newest cut, in the domain's frame, only decides which way the parts are pushed
        CutPlane newest = !piece.patches().isEmpty() ? piece.patches().get(piece.patches().size() - 1).plane()
                : !piece.planes().isEmpty() ? piece.planes().get(piece.planes().size() - 1) : new CutPlane(0, 1, 0, 0.5);
        Vector3d nl = new Vector3d(newest.nx(), newest.ny(), newest.nz());
        double dl = newest.d() + nl.x * pos.getX() + nl.y * pos.getY() + nl.z * pos.getZ();
        Vector3d world = sub == null ? new Vector3d(nl) : sub.logicalPose().transformNormal(new Vector3d(nl));
        resolve(level, sub, dom, List.of(info), nl, dl, new Vec3(world.x, world.y, world.z), new int[]{0}, false);
    }

    // ---------------------------------------------------------------- graph

    /**
     * Finds out which structure each piece of the cut blocks belongs to, using the world's structure graph. The cut
     * blocks are looked at as they would be after the cut (the world still holds them as they were).
     *
     * @param overlay whether the blocks are to be taken as the cut would leave them; if not they are read from the
     *                world, which lets the graph answer from what it already knows
     * @param comp    receives, for every piece of the cut blocks, its structure's id ({@link #HUGE} for everything
     *                attached to more than the search was willing to follow)
     * @return the structures that are not attached, by id
     */
    private static Map<Integer, StructureGraph.Closure> components(Domain dom, List<Info> hits, Map<Node, Integer> comp,
                                                                  boolean overlay) {
        Map<Long, BlockNodes> over = new HashMap<>();
        List<StructureGraph.NodeRef> seeds = new ArrayList<>();
        for (Info hit : hits) {
            if (overlay || hit.whole) {
                over.put(StructureGraph.pack(hit.pos.getX(), hit.pos.getY(), hit.pos.getZ()), BlockNodes.of(hit.grid, hit.whole));
            }
            for (Node seed : seedsOf(dom, hit)) {
                seeds.add(new StructureGraph.NodeRef(seed.pos().getX(), seed.pos().getY(), seed.pos().getZ(), seed.node()));
            }
        }
        Map<Integer, StructureGraph.Closure> byId = new HashMap<>();
        int next = 0;
        for (StructureGraph.Closure closure : WorldGraphs.of(dom.level).closures(seeds, over)) {
            int id = closure.attached() ? HUGE : next++;
            if (!closure.attached()) {
                byId.put(id, closure);
            }
            for (StructureGraph.NodeRef seed : closure.seeds()) {
                comp.put(new Node(new BlockPos(seed.x(), seed.y(), seed.z()), seed.fragment()), id);
            }
        }
        return byId;
    }

    /**
     * The pieces to start from for a cut block: its own, and if a layer of it was burnt away, the pieces of the
     * blocks next to it too (the burn may have left them no longer touching it).
     */
    private static List<Node> seedsOf(Domain dom, Info hit) {
        List<Node> seeds = new ArrayList<>();
        for (int node : hit.nodes()) {
            seeds.add(new Node(hit.pos, node));
        }
        if (hit.burned) {
            for (Direction dir : Direction.values()) {
                BlockPos q = hit.pos.relative(dir);
                if (dom.inside(q) && dom.loaded(q)) {
                    Info neighbour = dom.info(q);
                    if (neighbour != null) {
                        for (int node : neighbour.nodes()) {
                            seeds.add(new Node(q, node));
                        }
                    }
                }
            }
        }
        return seeds;
    }

    private static List<CutPlane> with(List<CutPlane> planes, CutPlane extra) {
        return CutService.with(planes, extra);
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
