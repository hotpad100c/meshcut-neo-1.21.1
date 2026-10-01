package ml.mypals.meshcut.cut;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ml.mypals.meshcut.block.MimicBlock;
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
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

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
 * Cuts with the surface a blade sweeps between two instants.
 * <p>
 * The cut plane contains the blade and its direction of motion; only blocks the swept area passes through are cut.
 * A cut does not always separate anything, so it is recorded first: every block it passes through remembers it as a
 * <em>pending</em> cut and stays whole (drawn with a glowing slit). Then the structure is analysed as a graph of
 * pieces — a whole block, or each region of a block that has pending cuts, connected to touching pieces whose
 * shared faces overlap. If the cut severs the structure, every block with pending cuts whose regions ended up in
 * different groups is split along them, and each group becomes its own sub-level. A block whose regions are all
 * still connected is left whole, so a later cut can finish the job.
 * <p>
 * In the static world a group is only released if it is small enough to be an isolated island: one that reaches
 * the ground (or anything too big to follow, or unloaded) is treated as anchored and stays where it is.
 */
public final class SlashService {
    private static final double SAMPLE_STEP = 0.3;
    private static final int MAX_SAMPLES = 900;
    /** Groups larger than this are treated as anchored in the static world. */
    private static final int STATIC_CAP = 4096;
    private static final int SUBLEVEL_CAP = 20_000;
    /** Per block; further cuts through the same block are ignored. */
    private static final int MAX_PENDING = 3;
    private static final double SEPARATION_SPEED = 1.5;
    /** Group id for everything anchored or too big to follow; all such groups count as one. */
    private static final int HUGE = -2;

    private record Region(int mask, List<CutPlane> planes) {}

    /** One block position: what it holds, its cuts, and the pieces (regions) those cuts divide it into. */
    private record Info(BlockPos pos, @Nullable CutService.Piece piece, List<CutPlane> pending, boolean hit,
                        List<Region> regions) {}

    private record Node(BlockPos pos, int mask) {}

    private record Spec(BlockPos pos, Info info, List<CutPlane> planes, List<CutPlane> pending) {}

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
            Info info = piece == null
                    ? new Info(pos, null, List.of(), false, List.of(new Region(-1, List.of())))
                    : new Info(pos, piece, piece.pending(), false, regionsOf(piece, piece.pending()));
            infos.put(pos, info);
            return info;
        }
    }

    /** @return {@code null} if the blade barely moved or moved along itself, so there is no swept surface */
    public static CutService.Result slash(ServerLevel level, Blade from, Blade to, CapMode mode) {
        Vec3 dir = from.direction().add(to.direction());
        Vec3 motion = to.base().subtract(from.base()).add(to.tip().subtract(from.tip())).scale(0.5);
        if (dir.lengthSqr() < 1e-6) {
            return null;
        }
        Vec3 n = dir.normalize().cross(motion);
        if (n.lengthSqr() < 1e-8) {
            return null;
        }
        n = n.normalize();
        Vec3 center = from.base().add(from.tip()).add(to.base()).add(to.tip()).scale(0.25);

        List<Vec3> samples = sweptSamples(from, to);
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (Vec3 p : samples) {
            minX = Math.min(minX, p.x);
            minY = Math.min(minY, p.y);
            minZ = Math.min(minZ, p.z);
            maxX = Math.max(maxX, p.x);
            maxY = Math.max(maxY, p.y);
            maxZ = Math.max(maxZ, p.z);
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

        Set<BlockPos> worldCells = new LinkedHashSet<>();
        for (Vec3 p : samples) {
            BlockPos cell = BlockPos.containing(p);
            if (level.isInWorldBounds(cell) && !(container != null && container.inBounds(cell))) {
                worldCells.add(cell);
            }
        }
        Vector3d nWorld = new Vector3d(n.x, n.y, n.z);
        int staticBlocks = process(level, null, worldCells, nWorld, nWorld.dot(center.x, center.y, center.z), n, mode, created);

        int cutLevels = 0;
        for (ServerSubLevel sub : subLevels) {
            if (sub.isRemoved()) {
                continue;
            }
            Pose3dc pose = sub.logicalPose();
            Vector3d nl = pose.transformNormalInverse(new Vector3d(n.x, n.y, n.z));
            if (nl.lengthSquared() < 1e-12) {
                continue;
            }
            nl.normalize();
            double dl = nl.dot(pose.transformPositionInverse(new Vector3d(center.x, center.y, center.z)));
            Set<BlockPos> cells = new LinkedHashSet<>();
            for (Vec3 p : samples) {
                Vector3d local = pose.transformPositionInverse(new Vector3d(p.x, p.y, p.z));
                BlockPos cell = BlockPos.containing(local.x, local.y, local.z);
                if (container != null && container.inBounds(cell) && !level.getBlockState(cell).isAir()) {
                    cells.add(cell);
                }
            }
            if (process(level, sub, cells, nl, dl, n, mode, created) > 0) {
                cutLevels++;
            }
        }
        return new CutService.Result(staticBlocks, cutLevels, created[0]);
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
     * @param sub  the sub-level whose plot the cells are in, or {@code null} for the static world
     * @param nl   plane normal in the domain's own frame (plot space for a sub-level)
     * @return the number of blocks the cut passed through
     */
    private static int process(ServerLevel level, @Nullable ServerSubLevel sub, Set<BlockPos> cells, Vector3d nl, double dl,
                               Vec3 nWorld, CapMode mode, int[] created) {
        Domain dom = new Domain(level, mode, sub == null ? STATIC_CAP : SUBLEVEL_CAP, sub);

        // 1. every block the plane passes through gets the cut recorded as pending
        List<Info> hits = new ArrayList<>();
        for (BlockPos cell : cells) {
            if (!dom.loaded(cell)) {
                continue;
            }
            CutService.Piece piece = CutService.pieceAt(level, cell, mode);
            if (piece == null) {
                continue;
            }
            CutPlane keep = new CutPlane(nl.x, nl.y, nl.z, dl - (nl.x * cell.getX() + nl.y * cell.getY() + nl.z * cell.getZ()));
            if (Polytope.classify(piece.planes(), keep) != Polytope.Side.SPLIT
                    || piece.pending().size() >= MAX_PENDING || isDuplicate(piece.pending(), keep)) {
                continue;
            }
            List<CutPlane> pending = CutService.with(piece.pending(), keep);
            List<Region> regions = regionsOf(piece, pending);
            if (regions.size() < 2) {
                continue;
            }
            BlockPos fixed = cell.immutable();
            Info info = new Info(fixed, piece, pending, true, regions);
            dom.infos.put(fixed, info);
            hits.add(info);
        }
        if (hits.isEmpty()) {
            return 0;
        }

        // 2. which pieces are connected to which
        Map<Node, Integer> comp = new HashMap<>();
        int[] nextId = {0};
        for (Info hit : hits) {
            for (Region region : hit.regions()) {
                Node start = new Node(hit.pos(), region.mask());
                if (!comp.containsKey(start)) {
                    label(dom, comp, start, nextId);
                }
            }
        }

        // 3. blocks whose regions ended up in different groups have been severed
        Map<Info, Map<Integer, List<Region>>> severed = new LinkedHashMap<>();
        for (Info info : dom.infos.values()) {
            if (info.regions().size() < 2) {
                continue;
            }
            Map<Integer, List<Region>> byComp = new LinkedHashMap<>();
            boolean allReached = true;
            for (Region region : info.regions()) {
                Integer id = comp.get(new Node(info.pos(), region.mask()));
                if (id == null) {
                    allReached = false;
                    break;
                }
                byComp.computeIfAbsent(id, k -> new ArrayList<>()).add(region);
            }
            if (allReached && byComp.size() >= 2) {
                severed.put(info, byComp);
            }
        }

        // Spill the contents of real block entities before they are replaced by mimics.
        Map<BlockPos, CompoundTag> beTags = detachBlockEntities(level, hits);

        if (severed.isEmpty()) {
            // Nothing came apart: the blocks just remember the cut.
            for (Info hit : hits) {
                place(level, hit, hit.piece().planes(), hit.pending(), beTags);
            }
            return hits.size();
        }

        // 4. who moves out
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
        if (sub != null && !affected.contains(HUGE) && !finite.isEmpty()) {
            // inside a sub-level the biggest part stays where it is
            int biggest = finite.stream().max(Comparator.comparingInt(id -> nodesByComp.get(id).size())).orElseThrow();
            movers.remove(biggest);
        }

        // 5. the pieces each group will hold
        Map<Integer, List<Spec>> specs = new HashMap<>();
        for (var entry : severed.entrySet()) {
            Info info = entry.getKey();
            for (var group : entry.getValue().entrySet()) {
                specs.computeIfAbsent(group.getKey(), k -> new ArrayList<>())
                        .add(new Spec(info.pos(), info, piecePlanes(info, group.getValue()), List.of()));
            }
        }
        for (Info hit : hits) {
            if (!severed.containsKey(hit)) {
                int id = comp.get(new Node(hit.pos(), hit.regions().get(0).mask()));
                specs.computeIfAbsent(id, k -> new ArrayList<>())
                        .add(new Spec(hit.pos(), hit, hit.piece().planes(), hit.pending()));
            }
        }

        // If nothing would be left in the sub-level while the others move out, Sable would discard it on the spot.
        BlockPos placeholder = null;
        if (sub != null) {
            Set<BlockPos> leaving = new HashSet<>();
            movers.forEach(id -> nodesByComp.get(id).forEach(node -> leaving.add(node.pos())));
            boolean survivor = false;
            for (var entry : comp.entrySet()) {
                BlockPos p = entry.getKey().pos();
                if (!movers.contains(entry.getValue()) && !leaving.contains(p) && !severed.containsKey(dom.infos.get(p))) {
                    survivor = true;
                    break;
                }
            }
            if (!survivor) {
                Set<BlockPos> near = new HashSet<>();
                severed.keySet().forEach(info -> near.add(info.pos()));
                placeholder = CutService.findPlaceholderSpot(level, near);
                if (placeholder == null) {
                    return 0; // nothing has been changed yet except spilled contents
                }
                level.setBlock(placeholder, Blocks.STONE.defaultBlockState(), 2);
            }
        }

        // 6. move the groups out, then put the pieces that stay in place
        for (int id : movers) {
            List<BlockPos> positions = new ArrayList<>();
            Set<BlockPos> seen = new HashSet<>();
            double side = 0;
            for (Node node : nodesByComp.get(id)) {
                if (seen.add(node.pos())) {
                    positions.add(node.pos());
                }
                side += Math.signum(sideOf(dom, node, nl, dl));
            }
            for (Spec spec : specs.getOrDefault(id, List.of())) {
                place(level, spec.info(), spec.planes(), spec.pending(), beTags);
            }
            ServerSubLevel moved = CutService.assemble(level, positions);
            if (moved != null) {
                created[0]++;
                CutService.push(moved, nWorld.scale(side >= 0 ? SEPARATION_SPEED : -SEPARATION_SPEED));
            }
        }
        for (var entry : specs.entrySet()) {
            if (!movers.contains(entry.getKey())) {
                for (Spec spec : entry.getValue()) {
                    place(level, spec.info(), spec.planes(), spec.pending(), beTags);
                }
            }
        }
        if (placeholder != null) {
            level.setBlock(placeholder, Blocks.AIR.defaultBlockState(), 2);
        }
        return hits.size();
    }

    // ---------------------------------------------------------------- graph

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
            List<CutPlane> planesA = planesOf(dom, a);
            for (Direction dir : Direction.values()) {
                BlockPos q = a.pos().relative(dir);
                if (!dom.inside(q)) {
                    continue;
                }
                if (!dom.loaded(q)) {
                    huge = true;
                    continue;
                }
                Info info = dom.info(q);
                if (info == null) {
                    continue;
                }
                int axis = dir.getAxis().ordinal();
                boolean positive = dir.getAxisDirection() == Direction.AxisDirection.POSITIVE;
                for (Region region : info.regions()) {
                    Node b = new Node(q, region.mask());
                    Integer existing = comp.get(b);
                    if (existing != null) {
                        if (existing == HUGE) {
                            huge = true;
                        }
                    } else if (!reached.contains(b) && Polytope.facesOverlap(planesA, axis, positive, region.planes())) {
                        reached.add(b);
                        queue.add(b);
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

    private static List<CutPlane> planesOf(Domain dom, Node node) {
        for (Region region : dom.infos.get(node.pos()).regions()) {
            if (region.mask() == node.mask()) {
                return region.planes();
            }
        }
        return List.of();
    }

    /** The regions a block is divided into by its pending cuts: one per combination of sides, ignoring slivers. */
    private static List<Region> regionsOf(CutService.Piece piece, List<CutPlane> pending) {
        List<Region> whole = List.of(new Region(-1, piece.planes()));
        if (pending.isEmpty()) {
            return whole;
        }
        List<Region> regions = new ArrayList<>();
        for (int mask = 0; mask < 1 << pending.size(); mask++) {
            List<CutPlane> planes = new ArrayList<>(piece.planes());
            for (int i = 0; i < pending.size(); i++) {
                planes.add((mask >> i & 1) == 1 ? pending.get(i).flipped() : pending.get(i));
            }
            if (Polytope.volume(planes) >= Polytope.MIN_VOLUME) {
                regions.add(new Region(mask, planes));
            }
        }
        return regions.size() < 2 ? whole : regions;
    }

    /**
     * The shape one group of regions of a severed block is left with: the block's real cuts plus every pending cut
     * all the group's regions are on the same side of. Cuts the group straddles don't separate it from anything.
     */
    private static List<CutPlane> piecePlanes(Info info, List<Region> group) {
        List<CutPlane> planes = new ArrayList<>(info.piece().planes());
        for (int i = 0; i < info.pending().size(); i++) {
            boolean first = (group.get(0).mask() >> i & 1) == 1;
            boolean agree = true;
            for (Region region : group) {
                agree &= ((region.mask() >> i & 1) == 1) == first;
            }
            if (agree) {
                planes.add(first ? info.pending().get(i).flipped() : info.pending().get(i));
            }
        }
        return planes;
    }

    /** Which side of the cut plane a piece is on (positive: the normal's side), judged by its centre. */
    private static double sideOf(Domain dom, Node node, Vector3d nl, double dl) {
        double[] c = node.mask() < 0 ? new double[]{0.5, 0.5, 0.5} : Polytope.centroid(planesOf(dom, node));
        return nl.x * (node.pos().getX() + c[0]) + nl.y * (node.pos().getY() + c[1]) + nl.z * (node.pos().getZ() + c[2]) - dl;
    }

    private static boolean isDuplicate(List<CutPlane> pending, CutPlane plane) {
        for (CutPlane other : pending) {
            double dot = other.nx() * plane.nx() + other.ny() * plane.ny() + other.nz() * plane.nz();
            if ((dot > 0.99 && Math.abs(other.d() - plane.d()) < 0.04) || (dot < -0.99 && Math.abs(other.d() + plane.d()) < 0.04)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- placing

    private static Map<BlockPos, CompoundTag> detachBlockEntities(ServerLevel level, List<Info> hits) {
        Map<BlockPos, CompoundTag> tags = new HashMap<>();
        for (Info hit : hits) {
            if (hit.piece().undetachedBe()) {
                BlockEntity be = level.getBlockEntity(hit.pos());
                if (be != null) {
                    CutService.spillContents(level, hit.pos(), be);
                    tags.put(hit.pos(), be.saveWithoutMetadata(level.registryAccess()));
                }
            }
        }
        return tags;
    }

    private static void place(ServerLevel level, Info info, List<CutPlane> planes, List<CutPlane> pending,
                              Map<BlockPos, CompoundTag> beTags) {
        CutService.Piece piece = info.piece();
        MimicBlock.place(level, info.pos(), piece.target(), planes, pending, piece.mode(),
                beTags.getOrDefault(info.pos(), piece.beTag()));
    }

    private SlashService() {}
}
