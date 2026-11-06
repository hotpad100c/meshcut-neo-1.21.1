package ml.mypals.meshcut.cut;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.block.EntityAppearance;
import ml.mypals.meshcut.block.MimicBlock;
import ml.mypals.meshcut.block.MimicShapes;
import ml.mypals.meshcut.block.MimicBlockEntity;
import ml.mypals.meshcut.geom.Patch;
import ml.mypals.meshcut.geom.Polytope;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CutService {
    public static final int MAX_RADIUS = 16;
    private static final double SEPARATION_SPEED = 1.5;
    private static final int MAX_SPILLED = 4096;

    public record Result(int staticBlocks, int subLevelsCut, int subLevelsCreated) {}

    public static final net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block> UNCUTTABLE = net.minecraft.tags.TagKey.create(
            net.minecraft.core.registries.Registries.BLOCK, net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MeshCut.MODID, "uncuttable"));

    record Piece(BlockState target, List<CutPlane> planes, List<List<CutPlane>> excluded, List<Patch> patches,
                 CapMode mode, @Nullable CompoundTag beTag, boolean undetachedBe, @Nullable EntityAppearance entity,
                 @Nullable MimicBlockEntity.GlowHistory glow) {
        List<double[]> cutBoxes() {
            return entity != null ? entity.boxes() : MimicShapes.cutBoxes(target);
        }
    }

    record Split(Piece piece, CutPlane keep) {}

    public static Result cut(ServerLevel level, Vec3 point, Vec3 normal, double radius, CapMode mode) {
        if (normal.lengthSqr() < 1e-12) {
            return null;
        }
        Vec3 n = normal.normalize();
        radius = Math.clamp(radius, 0.5, MAX_RADIUS);

        List<ServerSubLevel> targets = new ArrayList<>();
        for (SubLevel s : Sable.HELPER.getAllIntersecting(level, new BoundingBox3d(
                point.x - radius, point.y - radius, point.z - radius,
                point.x + radius, point.y + radius, point.z + radius))) {
            if (s instanceof ServerSubLevel ss && !ss.isRemoved()) {
                targets.add(ss);
            }
        }

        int[] created = {0};
        int staticBlocks = cutStatic(level, point, n, radius, mode, created);
        int cutLevels = 0;
        for (ServerSubLevel s : targets) {
            if (cutSubLevel(level, s, point, n, mode, created)) {
                cutLevels++;
            }
        }
        return new Result(staticBlocks, cutLevels, created[0]);
    }

    private static int cutStatic(ServerLevel level, Vec3 point, Vec3 n, double radius, CapMode mode, int[] created) {
        var container = SubLevelContainer.getContainer(level);
        List<BlockPos> candidates = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = (int) Math.floor(point.x - radius); x <= Math.floor(point.x + radius); x++) {
            for (int y = (int) Math.floor(point.y - radius); y <= Math.floor(point.y + radius); y++) {
                for (int z = (int) Math.floor(point.z - radius); z <= Math.floor(point.z + radius); z++) {
                    pos.set(x, y, z);
                    if (level.isInWorldBounds(pos) && !(container != null && container.inBounds(pos))) {
                        candidates.add(pos.immutable());
                    }
                }
            }
        }
        return cutStaticCells(level, candidates, n, n.dot(point), mode, created);
    }

    static int cutStaticCells(ServerLevel level, Iterable<BlockPos> candidates, Vec3 n, double d, CapMode mode, int[] created) {
        Map<BlockPos, Split> splits = new LinkedHashMap<>();
        for (BlockPos cell : candidates) {
            Piece piece = pieceAt(level, cell, mode);
            if (piece == null) {
                continue;
            }
            CutPlane keep = new CutPlane(n.x, n.y, n.z, d - n.dot(Vec3.atLowerCornerOf(cell)));
            if (Polytope.classify(piece.planes(), keep) == Polytope.Side.SPLIT) {
                splits.put(cell, new Split(piece, keep));
            }
        }

        List<List<BlockPos>> comps = components(splits.keySet());
        List<boolean[]> floating = new ArrayList<>();
        for (List<BlockPos> comp : comps) {
            floating.add(new boolean[]{!isSupported(level, comp, splits, true), !isSupported(level, comp, splits, false)});
        }
        detachBlockEntities(level, splits);

        for (int c = 0; c < comps.size(); c++) {
            List<BlockPos> comp = comps.get(c);
            boolean plusFloats = floating.get(c)[0];
            boolean minusFloats = floating.get(c)[1];
            boolean plusFree = plusFloats || !minusFloats;
            boolean minusFree = minusFloats;

            for (boolean plusSide : new boolean[]{true, false}) {
                if (plusSide ? plusFree : minusFree) {
                    placeHalf(level, comp, splits, plusSide);
                    ServerSubLevel sub = assemble(level, comp);
                    if (sub != null) {
                        created[0]++;
                        push(sub, n.scale(plusSide ? SEPARATION_SPEED : -SEPARATION_SPEED));
                    }
                }
            }
            if (!plusFree) {
                placeHalf(level, comp, splits, true);
            }
            if (!minusFree) {
                placeHalf(level, comp, splits, false);
            }
        }
        return splits.size();
    }

    static void placeHalf(ServerLevel level, List<BlockPos> comp, Map<BlockPos, Split> splits, boolean plusSide) {
        for (BlockPos p : comp) {
            Split s = splits.get(p);
            Piece piece = s.piece();
            CutPlane half = plusSide ? s.keep().flipped() : s.keep();
            MimicBlock.place(level, p, piece.target(), with(piece.planes(), half), piece.excluded(), piece.patches(), piece.mode(), piece.beTag(), piece.entity(), piece.glow());
        }
    }

    private static boolean isSupported(ServerLevel level, List<BlockPos> comp, Map<BlockPos, Split> splits, boolean plusSide) {
        for (BlockPos p : comp) {
            Split s = splits.get(p);
            List<CutPlane> planes = with(s.piece().planes(), plusSide ? s.keep().flipped() : s.keep());
            for (Direction dir : Direction.values()) {
                BlockPos neighbour = p.relative(dir);
                if (splits.containsKey(neighbour)
                        || !Polytope.touchesFace(planes, dir.getAxis().ordinal(), dir.getAxisDirection() == Direction.AxisDirection.POSITIVE)) {
                    continue;
                }
                BlockState state = level.getBlockState(neighbour);
                if (!state.isAir() && !(state.getBlock() instanceof LiquidBlock)
                        && !state.getCollisionShape(level, neighbour).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean cutSubLevel(ServerLevel level, ServerSubLevel sub, Vec3 point, Vec3 n, CapMode mode, int[] created) {
        Pose3dc pose = sub.logicalPose();
        Vector3d pl = pose.transformPositionInverse(new Vector3d(point.x, point.y, point.z));
        Vector3d nl = pose.transformNormalInverse(new Vector3d(n.x, n.y, n.z));
        if (nl.lengthSquared() < 1e-12) {
            return false;
        }
        nl.normalize();
        double dl = nl.dot(pl);

        Set<BlockPos> plus = new HashSet<>();
        Set<BlockPos> minus = new HashSet<>();
        Map<BlockPos, Split> splits = new LinkedHashMap<>();

        BoundingBox3ic bb = sub.getPlot().getBoundingBox();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = bb.minX(); x <= bb.maxX(); x++) {
            for (int y = bb.minY(); y <= bb.maxY(); y++) {
                for (int z = bb.minZ(); z <= bb.maxZ(); z++) {
                    pos.set(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    double dBlock = dl - (nl.x * x + nl.y * y + nl.z * z);
                    CutPlane keep = new CutPlane(nl.x, nl.y, nl.z, dBlock);
                    Piece piece = pieceAt(level, pos, mode);
                    Polytope.Side side;
                    if (piece == null) {
                        side = keep.signedDistance(0.5, 0.5, 0.5) >= 0 ? Polytope.Side.ALL_KEPT : Polytope.Side.ALL_REMOVED;
                    } else {
                        side = Polytope.classify(piece.planes(), keep);
                    }
                    BlockPos fixed = pos.immutable();
                    switch (side) {
                        case ALL_KEPT -> minus.add(fixed);
                        case ALL_REMOVED -> plus.add(fixed);
                        case SPLIT -> {
                            splits.put(fixed, new Split(piece, keep));
                            plus.add(fixed);
                            minus.add(fixed);
                        }
                    }
                }
            }
        }
        if (plus.isEmpty() || minus.isEmpty()) {
            return false;
        }

        // Every split position is in both sets, so this is the number of blocks that stay put untouched. If it is
        // zero, moving the normal side out would leave the sub-level empty; Sable then discards it (and its physics
        // body) on the spot, and the kept halves we put back afterwards would end up in a dead sub-level.
        BlockPos placeholder = null;
        if (minus.size() == splits.size()) {
            placeholder = findPlaceholderSpot(level, splits.keySet());
            if (placeholder == null) {
                return false;
            }
            level.setBlock(placeholder, Blocks.STONE.defaultBlockState(), 2);
        }

        Vec3 push = n.scale(SEPARATION_SPEED);
        try {
            detachBlockEntities(level, splits);
            for (var e : splits.entrySet()) {
                Split s = e.getValue();
                MimicBlock.place(level, e.getKey(), s.piece().target(), with(s.piece().planes(), s.keep().flipped()),
                        s.piece().excluded(), s.piece().patches(), s.piece().mode(), s.piece().beTag(), s.piece().entity(), s.piece().glow());
            }
            for (List<BlockPos> comp : components(plus)) {
                ServerSubLevel created1 = assemble(level, comp);
                if (created1 != null) {
                    created[0]++;
                    push(created1, push);
                }
            }
            if (sub.isRemoved()) {
                return true;
            }
            for (var e : splits.entrySet()) {
                Split s = e.getValue();
                MimicBlock.place(level, e.getKey(), s.piece().target(), with(s.piece().planes(), s.keep()),
                        s.piece().excluded(), s.piece().patches(), s.piece().mode(), s.piece().beTag(), s.piece().entity(), s.piece().glow());
            }
        } finally {
            if (placeholder != null) {
                level.setBlock(placeholder, Blocks.AIR.defaultBlockState(), 2);
            }
        }
        List<List<BlockPos>> rest = new ArrayList<>(components(minus));
        rest.sort(Comparator.comparingInt((List<BlockPos> c) -> c.size()).reversed());
        for (int i = 1; i < rest.size(); i++) {
            ServerSubLevel extra = assemble(level, rest.get(i));
            if (extra != null) {
                created[0]++;
                push(extra, push.scale(-1));
            }
        }
        if (!sub.isRemoved()) {
            push(sub, push.scale(-1));
        }
        return true;
    }

    @Nullable
    static BlockPos findPlaceholderSpot(ServerLevel level, Set<BlockPos> near) {
        for (BlockPos p : near) {
            for (Direction dir : Direction.values()) {
                BlockPos q = p.relative(dir);
                if (!near.contains(q) && (q.getX() >> 4) == (p.getX() >> 4) && (q.getY() >> 4) == (p.getY() >> 4)
                        && (q.getZ() >> 4) == (p.getZ() >> 4) && level.getBlockState(q).isAir()) {
                    return q;
                }
            }
        }
        return null;
    }

    static Piece pieceAt(ServerLevel level, BlockPos pos, CapMode mode) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return null;
        }
        if (state.getBlock() instanceof MimicBlock) {
            return level.getBlockEntity(pos) instanceof MimicBlockEntity be
                    ? new Piece(be.getTargetState(), new ArrayList<>(be.getPlanes()), new ArrayList<>(be.getExcluded()),
                    new ArrayList<>(be.getPatches()), be.getCapMode(), be.getTargetBeTag(), false, be.getAppearance(), be.glowHistory())
                    : null;
        }
        if (state.getBlock() instanceof LiquidBlock || state.getRenderShape() == RenderShape.INVISIBLE
                || state.is(UNCUTTABLE)) {
            return null;
        }
        if (state.hasBlockEntity() && !canSpill(level, pos)) {
            return null;
        }
        return new Piece(state, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), mode, null, state.hasBlockEntity(), null, null);
    }

    private static boolean canSpill(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return true;
        }
        long items = 0;
        if (be instanceof Container container) {
            for (int i = 0; i < container.getContainerSize() && items <= MAX_SPILLED; i++) {
                items += container.getItem(i).getCount();
            }
            return items <= MAX_SPILLED;
        }
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler == null) {
            return true;
        }
        if (handler.getSlots() > MAX_SPILLED) {
            return false;
        }
        for (int slot = 0; slot < handler.getSlots() && items <= MAX_SPILLED; slot++) {
            items += handler.getStackInSlot(slot).getCount();
        }
        return items <= MAX_SPILLED;
    }

    static void detachBlockEntities(ServerLevel level, Map<BlockPos, Split> splits) {
        splits.replaceAll((pos, split) -> new Split(detachBlockEntity(level, pos, split.piece()), split.keep()));
    }

    static Piece detachBlockEntity(ServerLevel level, BlockPos pos, Piece piece) {
        if (!piece.undetachedBe()) return piece;
        CompoundTag tag = null;
        BlockEntity be = level.getBlockEntity(pos);
        if (be != null) {
            spillContents(level, pos, be);
            tag = be.saveWithoutMetadata(level.registryAccess());
        }
        return new Piece(piece.target(), piece.planes(), piece.excluded(), piece.patches(), piece.mode(), tag, false, piece.entity(), piece.glow());
    }

    static void spillContents(ServerLevel level, BlockPos pos, BlockEntity be) {
        List<ItemStack> stacks = new ArrayList<>();
        if (be instanceof Container container) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (!stack.isEmpty()) {
                    stacks.add(stack.copy());
                }
            }
            container.clearContent();
        } else {
            IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
            if (handler != null) {
                // a bounded number of tries: a handler that never runs out (a creative crate) would never stop
                int spilled = 0;
                for (int slot = 0; slot < handler.getSlots() && spilled < MAX_SPILLED; slot++) {
                    for (int tries = 0; tries < MAX_SPILLED / 64 && spilled < MAX_SPILLED; tries++) {
                        ItemStack taken = handler.extractItem(slot, Math.min(64, MAX_SPILLED - spilled), false);
                        if (taken.isEmpty()) {
                            break;
                        }
                        spilled += taken.getCount();
                        stacks.add(taken);
                    }
                }
            }
        }
        Vec3 at = Sable.HELPER.projectOutOfSubLevel(level, Vec3.atCenterOf(pos));
        for (ItemStack stack : stacks) {
            Containers.dropItemStack(level, at.x, at.y, at.z, stack);
        }
    }

    static List<CutPlane> with(List<CutPlane> planes, CutPlane extra) {
        List<CutPlane> out = new ArrayList<>(planes);
        out.add(extra);
        return out;
    }

    static List<List<BlockPos>> components(Set<BlockPos> positions) {
        List<List<BlockPos>> result = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        for (BlockPos start : positions) {
            if (!seen.add(start)) {
                continue;
            }
            List<BlockPos> comp = new ArrayList<>();
            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                BlockPos p = queue.poll();
                comp.add(p);
                for (Direction dir : Direction.values()) {
                    BlockPos q = p.relative(dir);
                    if (positions.contains(q) && seen.add(q)) {
                        queue.add(q);
                    }
                }
            }
            result.add(comp);
        }
        return result;
    }

    static ServerSubLevel assemble(ServerLevel level, List<BlockPos> blocks) {
        try (var timing = ml.mypals.meshcut.util.CutTiming.phase("CutService.assemble")) {
        BlockPos anchor=blocks.getFirst();
        Map<BlockPos,Piece> snapshots=new LinkedHashMap<>();
        for(BlockPos pos:blocks) {
            if(level.getBlockEntity(pos) instanceof MimicBlockEntity) snapshots.put(pos,pieceAt(level,pos,CapMode.LAVA));
        }
        ServerSubLevel source = Sable.HELPER.getContaining(level, blocks.getFirst()) instanceof ServerSubLevel s ? s : null;
        if (source != null) {
            ml.mypals.meshcut.physics.RagdollJoints.noteSplit(level, source, null);
        }
        double volume = ml.mypals.meshcut.physics.FragmentBudget.volumeOf(level, blocks);
        boolean wooden = ml.mypals.meshcut.physics.WoodDebris.eligible(level, blocks, volume);
        try {
            ServerSubLevel made = SubLevelAssemblyHelper.assembleBlocks(level, blocks.getFirst(), blocks, BoundingBox3i.from(blocks));
            if(made!=null) {
                ml.mypals.meshcut.physics.CutMarks.made(made, level.getGameTime());
                ml.mypals.meshcut.physics.FragmentBudget.made(level, made, volume);
                if (wooden) ml.mypals.meshcut.physics.WoodDebris.made(level, made);
                BlockPos plotAnchor=made.getPlot().getCenterBlock();
                for(var entry:snapshots.entrySet()) {
                    BlockPos old=entry.getKey();Piece expected=entry.getValue();
                    BlockPos moved=plotAnchor.offset(old.getX()-anchor.getX(),old.getY()-anchor.getY(),old.getZ()-anchor.getZ());
                    // Assembly creates the destination BE before loading its data. Never let its default stone
                    // material or an incomplete cut payload become the moved fragment's permanent state.
                    if(level.getBlockEntity(moved) instanceof MimicBlockEntity be
                            && (!be.getTargetState().equals(expected.target()) || !be.getPlanes().equals(expected.planes())
                            || !be.getPatches().equals(expected.patches()) || !be.getExcluded().equals(expected.excluded()))) {
                        MimicBlock.place(level,moved,expected.target(),expected.planes(),expected.excluded(),expected.patches(),
                                expected.mode(),expected.beTag(),expected.entity(),expected.glow());
                    }
                }
            }
            if (source != null && made != null) {
                ml.mypals.meshcut.physics.RagdollJoints.noteSplit(level, source, made);
            }
            return made;
        } catch (RuntimeException e) {
            MeshCut.LOGGER.error("Failed to assemble {} cut blocks into a sub-level", blocks.size(), e);
            return null;
        }
    
        }
    }

    static void push(ServerSubLevel sub, Vec3 velocity) {
        try {
            RigidBodyHandle handle = RigidBodyHandle.of(sub);
            if (handle != null && handle.isValid()) {
                handle.addLinearAndAngularVelocity(new Vector3d(velocity.x, velocity.y, velocity.z), new Vector3d());
            }
        } catch (RuntimeException e) {
            MeshCut.LOGGER.warn("Couldn't push cut piece apart", e);
        }
    }

    private CutService() {}
}
