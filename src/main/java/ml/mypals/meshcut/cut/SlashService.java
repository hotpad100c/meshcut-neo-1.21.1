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
import ml.mypals.meshcut.block.MimicShapes;
import ml.mypals.meshcut.cut.graph.BlockNodes;
import ml.mypals.meshcut.cut.graph.StructureGraph;
import ml.mypals.meshcut.cut.graph.WorldGraphs;
import ml.mypals.meshcut.geom.CellGrid;
import ml.mypals.meshcut.geom.Patch;
import ml.mypals.meshcut.geom.VoxelFragments;
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
 * parts ended up in different groups are split, and each group that came apart becomes its own sub-level. Each fragment is a union of grid cells; concave fragments can leave just like convex ones.
 * <p>
 * In the static world a group is only released if it is small enough to be an isolated island: one that reaches the
 * ground (or anything too big to follow, or unloaded) is treated as anchored and stays where it is.
 */
public final class SlashService {
    private static final double SAMPLE_STEP = 0.3;
    private static final int MAX_SAMPLES = 900;
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
        /** A thin layer of the block was burnt away by a cut along its edge, so it may no longer touch its neighbours. */
        boolean burned;
        /** Weakly supported cut blocks release all their local pieces together. */
        boolean detached;

        /** A block that was removed entirely: it holds nothing, but what was beside it may have come loose. */
        Info(BlockPos pos) {
            this.pos = pos;
            this.piece = null;
            this.patches = List.of();
            this.grid = CellGrid.EMPTY;
        }

        /** A block as its own block entity already holds it: its cells are known, not worked out again. */
        Info(BlockPos pos, CutService.Piece piece, CellGrid grid) {
            this.pos = pos;
            this.piece = piece;
            this.patches = piece.patches();
            this.grid = grid;
        }

        Info(BlockPos pos, @Nullable CutService.Piece piece, List<Patch> patches) {
            this.pos = pos;
            this.piece = piece;
            this.patches = patches;
            this.grid = piece == null ? CellGrid.FULL : CellGrid.of(piece.planes(), piece.excluded(), patches, piece.cutBoxes());
        }

        /** The graph node a cell group belongs to. */
        int node(int component) {
            return component;
        }

        /** The graph nodes of this block. */
        List<Integer> nodes() {
            List<Integer> nodes = new ArrayList<>();
            int count = grid.componentCount();
            for (int i = 0; i < count; i++) {
                nodes.add(i);
            }
            return nodes;
        }

        /** The cell groups a node stands for. */
        Set<Integer> groupsOf(int node) {
            Set<Integer> groups = new HashSet<>();
            groups.add(node);
            return groups;
        }
    }

    private record Node(BlockPos pos, int node) {}

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
            Info info = piece == null ? new Info(pos, null, List.of())
                    : level.getBlockEntity(pos) instanceof MimicBlockEntity be && be.getAppearance() == piece.entity()
                    ? new Info(pos, piece, be.grid()) : new Info(pos, piece, piece.patches());
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
        return slashSwing(level, swipes, mode, null);
    }

    /** @param wielder who swung (creatures the blade passes through die and are cut too; the wielder never is) */
    public static CutService.Result slashSwing(ServerLevel level, List<Swipe> swipes, CapMode mode,
                                               @Nullable net.minecraft.world.entity.Entity wielder) {
        return slashSwing(level, swipes, mode, wielder, true);
    }

    /** Whose blocks a cut leaves alone: one sub-level, or the static world (a laser must not cut what it stands on). */
    private static final ThreadLocal<Object> PROTECTED = new ThreadLocal<>();
    private static final Object STATIC_WORLD = new Object();

    /**
     * Like {@link #slashBlocks}, but nothing of {@code emitterBody} (the static world if null) is cut: the surface a
     * beam sweeps starts at its emitter, and a body that moves carries the emitter through it.
     */
    public static CutService.Result slashBlocksOutside(ServerLevel level, List<Swipe> swipes, CapMode mode,
                                                       @Nullable ServerSubLevel emitterBody) {
        PROTECTED.set(emitterBody != null ? emitterBody : STATIC_WORLD);
        try {
            return slashBlocks(level, swipes, mode, null);
        } finally {
            PROTECTED.remove();
        }
    }

    /** Entity contact was already handled while holding the saber; release only applies the recorded world stroke. */
    public static CutService.Result slashBlocks(ServerLevel level, List<Swipe> swipes, CapMode mode,
                                                @Nullable net.minecraft.world.entity.Entity wielder) {
        try (var timing = ml.mypals.meshcut.util.CutTiming.begin(); var batch = WorldGraphs.batch(level)) {
        return slashSwing(level, swipes, mode, wielder, false);
    
        }
    }

    private static CutService.Result slashSwing(ServerLevel level, List<Swipe> swipes, CapMode mode,
                                                @Nullable net.minecraft.world.entity.Entity wielder, boolean entities) {
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

        MimicBlock.setGlowingCuts(true); // what this swing cuts open glows; see MimicBlockEntity#configure
        try {
            return cutWith(level, sweeps, mode, wielder, entities);
        } finally {
            MimicBlock.setGlowingCuts(false);
        }
    }

    private static CutService.Result cutWith(ServerLevel level, List<Sweep> sweeps, CapMode mode,
                                                @Nullable net.minecraft.world.entity.Entity wielder) {
        try (var timing = ml.mypals.meshcut.util.CutTiming.phase("SlashService.cutWith")) {
        return cutWith(level, sweeps, mode, wielder, true);
    
        }
    }

    private static CutService.Result cutWith(ServerLevel level, List<Sweep> sweeps, CapMode mode,
                                             @Nullable net.minecraft.world.entity.Entity wielder, boolean entities) {
        try (var timing = ml.mypals.meshcut.util.CutTiming.phase("SlashService.cutWith")) {
        java.util.Set<ServerSubLevel> alreadyCutBodies = entities ? cutEntities(level, sweeps, mode, wielder)
                : java.util.Set.of();

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
            if (s instanceof ServerSubLevel ss && !ss.isRemoved() && !alreadyCutBodies.contains(ss) && ss != PROTECTED.get()) {
                subLevels.add(ss);
            }
        }

        int[] created = {0};
        var container = SubLevelContainer.getContainer(level);

        Map<BlockPos, List<StrokeFit.Fragment>> worldFragments = new LinkedHashMap<>();
        for (Sweep sweep : sweeps) {
            Vector3d n = new Vector3d(sweep.n().x, sweep.n().y, sweep.n().z);
            Vector3d center = new Vector3d(sweep.center().x, sweep.center().y, sweep.center().z);
            Vector3d[] quad = new Vector3d[4];
            for (int i = 0; i < 4; i++) {
                quad[i] = new Vector3d(sweep.quad()[i].x, sweep.quad()[i].y, sweep.quad()[i].z);
            }
            collect(worldFragments, n, center, quad, sweep.samples(), null,
                    cell -> level.isInWorldBounds(cell) && !(container != null && container.inBounds(cell))
                            && level.isLoaded(cell) && !level.getBlockState(cell).isAir()); // empty space is not worth clipping
        }
        int staticBlocks = PROTECTED.get() == STATIC_WORLD ? 0 : process(level, null, surfacePatches(worldFragments), mode, created);

        int cutLevels = cutSubLevels(level, sweeps, subLevels, mode, created);
        return new CutService.Result(staticBlocks, cutLevels, created[0]);
    
        }
    }

    /** Immediate entity-only contact, including a motionless blade. Never processes world blocks. */
    public static void touchEntities(ServerLevel level, Blade before, Blade now,
                                     net.minecraft.world.entity.Entity wielder) {
        if (now.direction().lengthSqr() < 1e-4) return;
        Sweep sweep = before == null ? null : sweepOf(before, now);
        List<Sweep> sweeps = new ArrayList<>();
        if (sweep != null) sweeps.add(sweep);
        Vec3 direction = now.direction().normalize();
        Vec3 helper = Math.abs(direction.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        // The finite ribbon is as thin as the visible core and also supplies a plane for a stationary contact.
        Vec3 side = direction.cross(helper).normalize().scale(0.008);
        Sweep contact = sweepOf(new Blade(now.base().subtract(side), now.tip().subtract(side)),
                new Blade(now.base().add(side), now.tip().add(side)));
        if (contact != null) sweeps.add(contact);
        if (!sweeps.isEmpty()) cutEntities(level, sweeps, CapMode.LAVA, wielder);
    }

    private static java.util.Set<ServerSubLevel> cutEntities(ServerLevel level, List<Sweep> sweeps, CapMode mode,
                                     @Nullable net.minecraft.world.entity.Entity wielder) {
        // Without a capture client the fallback body is built synchronously. Do not cut it again in cutWith.
        java.util.Set<ServerSubLevel> builtNow = new java.util.HashSet<>();
        // creatures first: their bodies become sub-levels, which the cut below then goes through like any other
        List<EntityCutting.Swept> swept = new ArrayList<>(sweeps.size());
        sweeps.forEach(sweep -> swept.add(new EntityCutting.Swept(sweep.quad())));
        // bodies built later (once a client has said what their limbs are) are cut by the same swing then
        EntityCutting.cut(level, swept, wielder, bodies -> {
            builtNow.addAll(bodies);
            MimicBlock.setGlowingCuts(true);
            try {
                cutSubLevels(level, sweeps, bodies, mode, new int[1]);
            } finally {
                MimicBlock.setGlowingCuts(false);
            }
        });
        return builtNow;
    }

    /** Cuts the sub-levels with the swing. @return how many of them it went through */
    private static int cutSubLevels(ServerLevel level, List<Sweep> sweeps, List<ServerSubLevel> subLevels, CapMode mode,
                                    int[] created) {
        var container = SubLevelContainer.getContainer(level);
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
            if (process(level, sub, surfacePatches(fragments), mode, created) > 0) {
                cutLevels++;
            }
        }
        return cutLevels;
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
        try (var timing = ml.mypals.meshcut.util.CutTiming.phase("SlashService.collect")) {
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
        // Keep each triangle in its own plane, including twisted blade sweeps. (The planes do not depend on the cell.)
        int[][] triangles = {{0,1,2},{0,2,3}};
        Vector3d[] normals = new Vector3d[2];
        for (int t = 0; t < 2; t++) {
            int[] triangle = triangles[t];
            Vector3d tn = new Vector3d(quad[triangle[1]]).sub(quad[triangle[0]])
                    .cross(new Vector3d(quad[triangle[2]]).sub(quad[triangle[0]]));
            if (tn.lengthSquared() >= 1e-12) normals[t] = tn.normalize();
        }
        for (BlockPos cell : candidates) {
            if (!accept.test(cell)) {
                continue;
            }
            for (int t = 0; t < 2; t++) {
                int[] triangle = triangles[t];
                Vector3d tn = normals[t];
                if (tn == null) continue;
                float[] polygon = new float[9];
                for (int i=0;i<3;i++) {
                    Vector3d q=quad[triangle[i]];
                    polygon[i*3]=(float)(q.x-cell.getX());
                    polygon[i*3+1]=(float)(q.y-cell.getY());
                    polygon[i*3+2]=(float)(q.z-cell.getZ());
                }
                float[] clipped=Patch.clipToCube(polygon);
                if (clipped.length>=9 && Patch.area(clipped)>1e-7)
                    out.computeIfAbsent(cell.immutable(),k->new ArrayList<>()).add(new StrokeFit.Fragment(new Vector3d(tn),motion,clipped));
            }
        }
    
        }
    }

    /** Bounded triangles, never upgraded to an infinite cut or averaged across a bend. */
    private static Map<BlockPos,List<Patch>> surfacePatches(Map<BlockPos,List<StrokeFit.Fragment>> fragments) {
        try (var timing = ml.mypals.meshcut.util.CutTiming.phase("SlashService.surfacePatches")) {
        Map<BlockPos,List<Patch>> result=new LinkedHashMap<>();
        fragments.forEach((pos, parts)-> {
            List<Patch> patches=new ArrayList<>();
            for (StrokeFit.Fragment f:parts) {
                float[] v=f.polygon();
                double d=f.n().x*v[0]+f.n().y*v[1]+f.n().z*v[2];
                Patch p=Patch.restore(new CutPlane(f.n().x,f.n().y,f.n().z,d),v,false);
                if (!patches.contains(p)) patches.add(p);
            }
            result.put(pos,patches);
        });
        return result;
    
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
                               CapMode mode, int[] created) {
        try (var timing = ml.mypals.meshcut.util.CutTiming.phase("SlashService.process")) {
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
                if (!straddles(piece.cutBoxes(), patch.plane())) {
                    continue; // passes through where the block has nothing (a torch is only a thin stick)
                }
                List<Patch> before = new ArrayList<>(merged);
                // Never convex-hull distinct surfaces or forget earlier connections.
                if (merged.stream().noneMatch(old -> old.equals(patch) || old.covers(patch))) merged.add(patch);
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
                List<List<CutPlane>> burnedHoles=new ArrayList<>(piece.excluded());
                burnedHoles.addAll(VoxelFragments.edgeBurns(entry.getValue()));
                CellGrid grid=CellGrid.of(planes,burnedHoles,merged,piece.cutBoxes());
                Set<Integer> all=new HashSet<>();
                for(int i=0;i<grid.componentCount();i++) all.add(i);
                List<List<CutPlane>> holes=new ArrayList<>(burnedHoles);
                List<List<CutPlane>> emptyCells=VoxelFragments.excluded(grid,all);
                for(List<CutPlane> hole:emptyCells) if(!holes.contains(hole)) holes.add(hole);
                burned=grid.filledCells()<CellGrid.of(piece.planes(),piece.excluded(),List.of(),piece.cutBoxes()).filledCells();
                CutService.Piece now=new CutService.Piece(piece.target(),planes,VoxelFragments.compact(holes),merged,piece.mode(),piece.beTag(),piece.undetachedBe(),piece.entity(),piece.glow());
                // the cells were just worked out for exactly this: slivers are empty in it as they are in the holes
                Info info = new Info(fixed, now, grid);
                info.burned = burned;
                dom.infos.put(fixed, info);
                hits.add(info);
            }
        }
        if (hits.isEmpty()) {
            return 0;
        }
        // the pieces are pushed apart along the plane that tells which side they are on (with several swipes in one
        // swing, it need not be the first swipe's)
        Vector3d world = sub == null ? new Vector3d(nl) : sub.logicalPose().transformNormal(new Vector3d(nl));
        return resolve(level, sub, dom, hits, nl, dl, new Vec3(world.x, world.y, world.z), created, true);
    
        }
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
        try (var timing = ml.mypals.meshcut.util.CutTiming.phase("SlashService.resolve")) {
        // 2. which pieces are connected to which
        Map<Node, Integer> comp = new HashMap<>();
        Map<Integer, StructureGraph.Closure> closures = components(dom, hits, comp);
        if(sub==null) {
            boolean changed=false;
            for(Info hit:hits) {
                if(hit.grid.componentCount()<2) continue;
                Set<Integer> ids=new HashSet<>();
                for(int node:hit.nodes()) {
                    Integer id=comp.get(new Node(hit.pos,node));
                    if(id!=null) ids.add(id);
                }
                // Analyse the whole structure first: a chain already severed keeps its neighbouring blocks.
                if(ids.size()==1 && supportingNeighbours(dom,hit)<3) {hit.detached=true;changed=true;}
            }
            if(changed) {comp.clear();closures=components(dom,hits,comp);}
        }
        final Plan plan = plan(dom, sub, comp, closures, hits, !applyUnsevered && sub == null);

        // A real block entity (a chest, a furnace) the cut only scratched stays the real block: as a mimic it would
        // lose its contents and stop working. Only one that comes apart, or loses a layer, becomes a mimic.
        List<Info> mimics = new ArrayList<>();
        for (Info hit : hits) mimics.add(hit);
        // Spill the contents of real block entities before they are replaced by mimics.
        Map<BlockPos, CompoundTag> beTags = detachBlockEntities(level, mimics);

        for(Info hit:hits) if(hit.grid.componentCount()==0)
            level.setBlock(hit.pos,Blocks.AIR.defaultBlockState(),net.minecraft.world.level.block.Block.UPDATE_CLIENTS | net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE);
        if (plan.severed().isEmpty() && plan.movers().isEmpty()) {
            // Nothing came apart: the blocks just remember the cut.
            if (applyUnsevered) {
                for (Info hit : mimics) {
                    if(hit.grid.componentCount()>0) place(level, hit, hit.piece.planes(), hit.piece.excluded(), hit.patches, beTags);
                }
            }
            return hits.size();
        }

        // 5. the pieces each group will hold
        Map<Integer, List<Spec>> specs = new HashMap<>();
        for (var entry : plan.severed().entrySet()) {
            Info info = entry.getKey();
            for (var group : entry.getValue().entrySet()) {
                List<List<CutPlane>> excluded = new ArrayList<>(info.piece.excluded());
                excluded.addAll(VoxelFragments.excluded(info.grid, group.getValue(), info.piece.excluded()));
                specs.computeIfAbsent(group.getKey(), k -> new ArrayList<>())
                        .add(new Spec(info.pos, info, info.piece.planes(), VoxelFragments.compact(excluded), info.patches));
            }
        }

        for (Info hit : mimics) {
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
            // Something attached to more than we followed certainly stays. Otherwise a block stays only if it is in a
            // part that stays and is not itself being split: a split block is taken away whole with the part that
            // leaves and only put back (as the piece that stays) after the move, so for that moment it is not there.
            boolean survivor = comp.containsValue(HUGE);
            if (!survivor) {
                Set<BlockPos> leaving = new HashSet<>();
                for (int id : plan.movers()) {
                    for (long p : plan.closures().get(id).positions()) {
                        leaving.add(BlockPos.of(p));
                    }
                }
                Set<BlockPos> split = new HashSet<>();
                plan.severed().keySet().forEach(info -> split.add(info.pos));
                search:
                for (var entry : plan.closures().entrySet()) {
                    if (plan.movers().contains(entry.getKey())) {
                        continue;
                    }
                    for (long p : entry.getValue().positions()) {
                        BlockPos at = BlockPos.of(p);
                        if (!leaving.contains(at) && !split.contains(at)) {
                            survivor = true;
                            break search;
                        }
                    }
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

        try {
            moveOut(level, sub, dom, plan, specs, beTags, nl, dl, nWorld, created);
        } finally {
            if (placeholder != null) {
                level.setBlock(placeholder, Blocks.AIR.defaultBlockState(), 2);
            }
        }
        if (sub != null && !sub.isRemoved()) {
            refreshMass(level, sub);
        }
        return hits.size();
    
        }
    }

    /** 6. Moves the groups out, then puts the pieces that stay in place. */
    private static void moveOut(ServerLevel level, @Nullable ServerSubLevel sub, Domain dom, Plan plan,
                                Map<Integer, List<Spec>> specs, Map<BlockPos, CompoundTag> beTags,
                                Vector3d nl, double dl, Vec3 nWorld, int[] created) {
        try (var timing = ml.mypals.meshcut.util.CutTiming.phase("SlashService.moveOut")) {
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
            if (sub != null && sub.isRemoved()) {
                // what stays would be put into the plot of a sub-level that is gone
                MeshCut.LOGGER.warn("The sub-level being cut was removed while its pieces were moved out; {} blocks could not follow", positions.size());
                return;
            }
            positions.removeIf(p -> level.getBlockState(p).isAir());
            if (positions.isEmpty()) {
                MeshCut.LOGGER.warn("A piece that should come loose has no blocks left; nothing is moved");
                continue;
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
    
        }
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
     * Recomputes a sub-level's mass, centre of mass and inertia from its blocks as they are now, and moves the body
     * so that nothing visibly shifts when the centre of mass moves. Blocks that were taken out and put back have
     * been through many incremental updates; this makes sure the sub-level ends up with the true values.
     */
    private static void refreshMass(ServerLevel level, ServerSubLevel sub) {
        try (var timing = ml.mypals.meshcut.util.CutTiming.phase("SlashService.refreshMass")) {
            refreshMassUntimed(level, sub);
        }
    }

    private static void refreshMassUntimed(ServerLevel level, ServerSubLevel sub) {
        var container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }
        var physics = container.physicsSystem();
        ml.mypals.meshcut.physics.RagdollJoints.noteSplit(level, sub, null); // its joints were made for the old centre of mass
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
    /** Ticks during which a block that came apart on re-examination is not re-examined again. */
    private static final long SPLIT_COOLDOWN = 40;
    /** At most this many pieces come loose from re-examinations in one tick. */
    private static final int MAX_LOOSE_PER_TICK = 4;
    private static final Map<Recheck, Long> LAST_SPLIT = new HashMap<>();
    private static int createdThisTick;

    /**
     * A cut block that stayed whole was only held together by what was around it. When a neighbour changes (it is
     * broken, say), that may no longer be true, so the block is examined again as if it had just been cut.
     */
    public static void queueRecheck(ServerLevel level, BlockPos pos) {
        RECHECKS.add(new Recheck(level, pos.immutable()));
    }

    /** Forgets what was queued: it belongs to a server that has stopped. */
    public static void clear() {
        RECHECKS.clear();
        LAST_SPLIT.clear();
        createdThisTick = 0;
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        createdThisTick = 0;
        LAST_SPLIT.entrySet().removeIf(e -> e.getKey().level().getGameTime() - e.getValue() > SPLIT_COOLDOWN);
        if (RECHECKS.isEmpty()) {
            return;
        }
        List<Recheck> batch = new ArrayList<>();
        for (var it = RECHECKS.iterator(); it.hasNext() && batch.size() < RECHECKS_PER_TICK; ) {
            batch.add(it.next());
            it.remove();
        }
        for (Recheck r : batch) {
            Long last = LAST_SPLIT.get(r);
            if (last != null && r.level().getGameTime() - last < SPLIT_COOLDOWN) {
                continue; // it has just come apart; whatever made it look again is most likely its own doing
            }
            if (createdThisTick >= MAX_LOOSE_PER_TICK) {
                RECHECKS.add(r); // enough for one tick: later
                continue;
            }
            try {
                recheck(r.level(), r.pos());
            } catch (RuntimeException e) {
                MeshCut.LOGGER.error("Re-examining the cut block at {} failed", r.pos(), e);
            }
        }
    }

    /**
     * Settles what a tool that carved blocks out directly (the laser) left behind: the blocks it changed, whether they
     * remain as cut mimics or are gone, and whatever they held up. Everything that no longer hangs together leaves
     * as its own sub-level, the way it would after a sweep of the blade.
     *
     * @param nl,dl a plane through the cut in the frame of {@code sub} (the world if null); it only decides which way
     *              the parts that come loose are pushed
     */
    public static void settleCut(ServerLevel level, @Nullable ServerSubLevel sub, java.util.Collection<BlockPos> changed,
                                 Vector3d nl, double dl) {
        if (changed.isEmpty() || sub != null && sub.isRemoved()) {
            return;
        }
        Domain dom = new Domain(level, CapMode.LAVA, sub);
        List<Info> hits = new ArrayList<>();
        for (BlockPos pos : changed) {
            if (!dom.inside(pos) || !dom.loaded(pos)) {
                continue;
            }
            Info info;
            if (level.getBlockState(pos).getBlock() instanceof MimicBlock) {
                info = dom.info(pos);
                if (info == null) {
                    continue;
                }
            } else {
                info = new Info(pos);
                dom.infos.put(pos, info);
            }
            info.burned = true; // look at what lies beside it as well: the cut may have left it unattached
            hits.add(info);
        }
        if (hits.isEmpty()) {
            return;
        }
        Vector3d world = sub == null ? new Vector3d(nl) : sub.logicalPose().transformNormal(new Vector3d(nl));
        int[] created = {0};
        try {
            resolve(level, sub, dom, hits, nl, dl, new Vec3(world.x, world.y, world.z), created, false);
        } catch (RuntimeException e) {
            MeshCut.LOGGER.error("Settling the cut at {} failed", hits.get(0).pos, e);
        }
        createdThisTick += created[0];
    }

    private static void recheck(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockState(pos).getBlock() instanceof MimicBlock)
                || !(level.getBlockEntity(pos) instanceof MimicBlockEntity be)) {
            return;
        }
        var container = SubLevelContainer.getContainer(level);
        ServerSubLevel sub = container != null && container.inBounds(pos)
                && Sable.HELPER.getContaining(level, pos) instanceof ServerSubLevel s && !s.isRemoved() ? s : null;
        if (sub == null && container != null && container.inBounds(pos)) {
            return; // left behind in the plot of a sub-level that is gone: not part of the world, nothing to do
        }
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
        int[] created = {0};
        resolve(level, sub, dom, List.of(info), nl, dl, new Vec3(world.x, world.y, world.z), created, false);
        if (created[0] > 0) {
            LAST_SPLIT.put(new Recheck(level, pos), level.getGameTime());
            createdThisTick += created[0];
            if (ml.mypals.meshcut.MeshCutSettings.diagnostics()) MeshCut.LOGGER.debug("Re-examined the cut block at {}{}: {} piece(s) came loose", pos,
                    sub == null ? "" : " (in a sub-level)", created[0]);
        }
    }

    // ---------------------------------------------------------------- graph

    /**
     * Finds out which structure each piece of the cut blocks belongs to, using the world's structure graph. The cut
     * blocks are always taken as they are described here (as the cut would leave them, or as they are now when
     * re-examined), not as the graph remembers them: what the graph knows of a block can lag behind (a block entity
     * whose data arrived after the block was placed, for instance).
     *
     * @param comp    receives, for every piece of the cut blocks, its structure's id ({@link #HUGE} for everything
     *                attached to more than the search was willing to follow)
     * @return the structures that are not attached, by id
     */
    private static Map<Integer, StructureGraph.Closure> components(Domain dom, List<Info> hits, Map<Node, Integer> comp) {
        try (var timing = ml.mypals.meshcut.util.CutTiming.phase("SlashService.components")) {
        Map<Long, BlockNodes> over = new HashMap<>();
        List<StructureGraph.NodeRef> seeds = new ArrayList<>();
        for (Info hit : hits) {
            over.put(StructureGraph.pack(hit.pos.getX(), hit.pos.getY(), hit.pos.getZ()), hit.detached
                    ? BlockNodes.mimic(new long[hit.grid.componentCount()][6]) : BlockNodes.of(hit.grid, false));
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
    }

    private static int supportingNeighbours(Domain dom,Info hit) {
        int count=0;
        for(Direction direction:Direction.values()) {
            BlockPos pos=hit.pos.relative(direction);
            if(!dom.inside(pos) || !dom.loaded(pos)) {count++;continue;}
            Info neighbour=dom.info(pos);
            if(neighbour==null) continue;
            int face=direction.getStepX()!=0 ? (direction.getStepX()>0?0:1)
                    : direction.getStepY()!=0 ? (direction.getStepY()>0?2:3) : (direction.getStepZ()>0?4:5);
            for(int i=0;i<CellGrid.N*CellGrid.N;i++) {
                if(hit.grid.faceComponent(face,i)>=0 && neighbour.grid.faceComponent(face^1,i)>=0) {count++;break;}
            }
        }
        return count;
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

    /** Whether the plane has some of the shape on both sides of it. */
    private static boolean straddles(List<double[]> shape, CutPlane plane) {
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (double[] b : shape) {
            for (int i = 0; i < 8; i++) {
                double d = plane.signedDistance(b[(i & 1) == 0 ? 0 : 3], b[(i & 2) == 0 ? 1 : 4], b[(i & 4) == 0 ? 2 : 5]);
                min = Math.min(min, d);
                max = Math.max(max, d);
            }
        }
        return min < -1e-6 && max > 1e-6;
    }

    /** Which side of the cut plane a piece is on (positive: the normal's side), judged by its centre. */
    private static double sideOf(Domain dom, Node node, Vector3d nl, double dl) {
        Info info = dom.infos.get(node.pos());
        double[] c = info.grid == CellGrid.FULL ? new double[]{0.5, 0.5, 0.5} : info.grid.centroid(node.node());
        return nl.x * (node.pos().getX() + c[0]) + nl.y * (node.pos().getY() + c[1]) + nl.z * (node.pos().getZ() + c[2]) - dl;
    }

    // ---------------------------------------------------------------- placing

    private static Map<BlockPos, CompoundTag> detachBlockEntities(ServerLevel level, List<Info> hits) {
        Map<BlockPos, CompoundTag> tags = new HashMap<>();
        for (Info hit : hits) {
            if (hit.piece != null && hit.piece.undetachedBe()) {
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
        try (var timing = ml.mypals.meshcut.util.CutTiming.phase("SlashService.place")) {
            MimicBlock.place(level, info.pos, piece.target(), planes, excluded, patches, piece.mode(),
                    beTags.getOrDefault(info.pos, piece.beTag()), piece.entity(), piece.glow());
        }
    }

    private SlashService() {}
}
