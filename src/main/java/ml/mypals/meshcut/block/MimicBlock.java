package ml.mypals.meshcut.block;

import com.mojang.serialization.MapCodec;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.block.BlockSubLevelAssemblyListener;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.cut.CapMode;
import ml.mypals.meshcut.cut.CutPlane;
import ml.mypals.meshcut.cut.FragmentBreaking;
import ml.mypals.meshcut.cut.SlashService;
import ml.mypals.meshcut.geom.Patch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.HitResult;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Stands in for any block; its block model draws the imitated block, clipped by the cuts in the block entity. */
public class MimicBlock extends BaseEntityBlock implements BlockSubLevelAssemblyListener, SimpleWaterloggedBlock {
    public static final MapCodec<MimicBlock> CODEC = simpleCodec(MimicBlock::new);

    /**
     * Flipped to make Sable's physics engine re-read this block's collider once the block entity holds its final
     * data (colliders are read on block changes, and a state change is the only reliable trigger).
     */
    public static final BooleanProperty REFRESH = BooleanProperty.create("refresh");
    public static final BooleanProperty WATERLOGGED = net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED;

    /**
     * Flags for replacing a block: tell clients, but don't let neighbours react. A door whose upper half is replaced
     * would otherwise see that the other half is gone and break itself before it could be cut as well.
     */
    private static final int QUIET = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    /** Set while a blade is cutting (server thread): only then do the faces a change opens up glow. */
    private static boolean glowingCuts;

    public static void setGlowingCuts(boolean on) {
        glowingCuts = on;
    }

    public static boolean glowingCuts() {
        return glowingCuts;
    }

    public MimicBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(REFRESH, false).setValue(WATERLOGGED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(REFRESH, WATERLOGGED);
    }

    /** Places a mimic of {@code target} cut by {@code planes} (block-local half-spaces). */
    public static void place(ServerLevel level, BlockPos pos, BlockState target, List<CutPlane> planes, CapMode mode,
                             @Nullable CompoundTag beTag) {
        place(level, pos, target, planes, List.of(), List.of(), mode, beTag);
    }

    /**
     * @param excluded convex parts of the block that were cut out and went elsewhere
     * @param patches  cuts that took nothing out of the block; see {@link MimicBlockEntity#getPatches()}
     */
    public static void place(ServerLevel level, BlockPos pos, BlockState target, List<CutPlane> planes,
                             List<List<CutPlane>> excluded, List<Patch> patches, CapMode mode, @Nullable CompoundTag beTag) {
        place(level, pos, target, planes, excluded, patches, mode, beTag, null);
    }

    /** @param entity for a part of a cut-up entity, what it looked like; null for a block */
    public static void place(ServerLevel level, BlockPos pos, BlockState target, List<CutPlane> planes,
                             List<List<CutPlane>> excluded, List<Patch> patches, CapMode mode, @Nullable CompoundTag beTag,
                             @Nullable EntityAppearance entity) {
        // A block Sable saw being placed had no block entity yet, so it weighed what a default mimic weighs.
        boolean fresh = !(level.getBlockState(pos).getBlock() instanceof MimicBlock);
        if (fresh) {
            // a block standing in water (a waterlogged stair, say) stays in water when it is cut
            boolean wet = level.getFluidState(pos).getType() == Fluids.WATER;
            level.setBlock(pos, MeshCut.MIMIC_BLOCK.get().defaultBlockState().setValue(WATERLOGGED, wet), QUIET);
        }
        if (level.getBlockEntity(pos) instanceof MimicBlockEntity be) {
            boolean slot = level.getBlockState(pos).getValue(REFRESH);
            be.beginMassTransition(fresh, slot);
            be.configure(target, planes, excluded, patches, mode, beTag, entity);
            refreshPhysics(level, pos); // flips REFRESH: Sable re-reads collider and mass, old slot -> new slot
            be.endMassTransition();
        }
    }

    /**
     * Blocks are placed into the new sub-level first and their block entity data is loaded afterwards, so Sable has
     * counted them with default mass. Now that the data is there, announce the difference.
     */
    @Override
    public void afterMove(ServerLevel from, ServerLevel to, BlockState state, BlockPos oldPos, BlockPos newPos) {
        if (!(to.getBlockEntity(newPos) instanceof MimicBlockEntity be)
                || !(Sable.HELPER.getContaining(to, newPos) instanceof ServerSubLevel subLevel)) {
            return;
        }
        var container = SubLevelContainer.getContainer(to);
        BlockState now = to.getBlockState(newPos);
        if (container == null || !(now.getBlock() instanceof MimicBlock)) {
            return;
        }
        boolean slot = now.getValue(REFRESH);
        be.beginMassTransition(true, !slot);
        try {
            container.physicsSystem().updateMassDataFromBlockChange(subLevel, newPos, now.setValue(REFRESH, !slot), now, true);
        } finally {
            be.endMassTransition();
        }
    }

    /** Makes the physics engine pick up the block entity's current shape. */
    public static void refreshPhysics(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof MimicBlock) {
            level.setBlock(pos, state.setValue(REFRESH, !state.getValue(REFRESH)), QUIET);
        }
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return level.getBlockEntity(pos) instanceof MimicBlockEntity be ? be.getShape() : Shapes.block();
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return getShape(state, level, pos, context);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    /**
     * A cut block may be held up only by what is around it. When a neighbour changes, check whether it still holds.
     * (The structure graph also asks for a check when an edit splits a group; this catches neighbours across sections.)
     */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, BlockPos neighborPos,
                                   boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (level instanceof ServerLevel server) {
            SlashService.queueRecheck(server, pos);
        }
    }

    /**
     * A block cut into several pieces is broken one piece at a time: only the piece being pointed at goes. (If it
     * cannot be taken out on its own, the whole block is broken as usual.)
     */
    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player, boolean willHarvest,
                                       FluidState fluid) {
        if (level.getBlockEntity(pos) instanceof MimicBlockEntity be && be.grid().componentCount() >= 2) {
            if (level instanceof ServerLevel server) {
                if (FragmentBreaking.breakPointed(server, pos, be, player)) {
                    return false; // the block stays, with one piece fewer
                }
            } else {
                return false; // the server decides what goes; the block's new shape arrives from it
            }
        }
        return super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
    }

    // ---------------------------------------------------------------- behaving like the imitated block

    /** The block imitated at the position, or {@code null} if it is not known (no block entity yet). */
    @Nullable
    private static BlockState targetAt(BlockGetter level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof MimicBlockEntity be ? be.getTargetState() : null;
    }

    /** Picking the block (middle click) gives the block it imitates. */
    @Override
    public ItemStack getCloneItemStack(BlockState state, HitResult target, LevelReader level, BlockPos pos, Player player) {
        BlockState imitated = targetAt(level, pos);
        return imitated != null ? imitated.getCloneItemStack(target, level, pos, player) : ItemStack.EMPTY;
    }

    /** It is as hard to break as what it imitates, and needs the same tool. */
    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        BlockState imitated = targetAt(level, pos);
        return imitated != null ? imitated.getDestroyProgress(player, level, pos) : super.getDestroyProgress(state, player, level, pos);
    }

    @Override
    public float getExplosionResistance(BlockState state, BlockGetter level, BlockPos pos, Explosion explosion) {
        BlockState imitated = targetAt(level, pos);
        return imitated != null ? imitated.getExplosionResistance(level, pos, explosion) : super.getExplosionResistance(state, level, pos, explosion);
    }

    @Override
    public SoundType getSoundType(BlockState state, LevelReader level, BlockPos pos, @Nullable Entity entity) {
        BlockState imitated = targetAt(level, pos);
        return imitated != null ? imitated.getSoundType(level, pos, entity) : super.getSoundType(state, level, pos, entity);
    }

    @Override
    public float getFriction(BlockState state, LevelReader level, BlockPos pos, @Nullable Entity entity) {
        BlockState imitated = targetAt(level, pos);
        return imitated != null ? imitated.getFriction(level, pos, entity) : super.getFriction(state, level, pos, entity);
    }

    /**
     * It gives off the light of what it imitates. The light level depends on the block entity, which the lighting
     * engine cannot read (it runs off the main thread, and before block entities are loaded), so the block entity
     * stores it in NeoForge's auxiliary light data for the chunk, which is saved and synced with the chunk.
     */
    @Override
    public boolean hasDynamicLightEmission(BlockState state) {
        return true;
    }

    @Override
    public int getLightEmission(BlockState state, BlockGetter level, BlockPos pos) {
        var light = level.getAuxLightManager(pos);
        return light == null ? 0 : light.getLightAt(pos);
    }

    // ---------------------------------------------------------------- water

    @Override
    protected FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor, LevelAccessor level,
                                     BlockPos pos, BlockPos neighborPos) {
        if (state.getValue(WATERLOGGED)) {
            level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
        return super.updateShape(state, direction, neighbor, level, pos, neighborPos);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL; // BaseEntityBlock defaults to INVISIBLE; our model draws the cut block
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MimicBlockEntity(pos, state);
    }
}
