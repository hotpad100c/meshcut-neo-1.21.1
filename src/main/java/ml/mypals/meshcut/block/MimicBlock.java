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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class MimicBlock extends BaseEntityBlock implements BlockSubLevelAssemblyListener, SimpleWaterloggedBlock {
    public static final MapCodec<MimicBlock> CODEC = simpleCodec(MimicBlock::new);

    public static final BooleanProperty REFRESH = BooleanProperty.create("refresh");
    public static final BooleanProperty WATERLOGGED = net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED;

    /**
     * Flags for replacing a block: tell clients, but don't let neighbours react. A door whose upper half is replaced
     * would otherwise see that the other half is gone and break itself before it could be cut as well.
     */
    private static final int QUIET = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

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

    private static final double EMPTY_BODY_VOLUME = 1e-5;

    public static void place(ServerLevel level, BlockPos pos, BlockState target, List<CutPlane> planes, CapMode mode,
                             @Nullable CompoundTag beTag) {
        place(level, pos, target, planes, List.of(), List.of(), mode, beTag);
    }

    public static void place(ServerLevel level, BlockPos pos, BlockState target, List<CutPlane> planes,
                             List<List<CutPlane>> excluded, List<Patch> patches, CapMode mode, @Nullable CompoundTag beTag) {
        place(level, pos, target, planes, excluded, patches, mode, beTag, null);
    }

    public static void place(ServerLevel level, BlockPos pos, BlockState target, List<CutPlane> planes,
                             List<List<CutPlane>> excluded, List<Patch> patches, CapMode mode, @Nullable CompoundTag beTag,
                             @Nullable EntityAppearance entity) {
        place(level, pos, target, planes, excluded, patches, mode, beTag, entity, null);
    }

    public static void place(ServerLevel level, BlockPos pos, BlockState target, List<CutPlane> planes,
                             List<List<CutPlane>> excluded, List<Patch> patches, CapMode mode, @Nullable CompoundTag beTag,
                             @Nullable EntityAppearance entity, @Nullable MimicBlockEntity.GlowHistory inherited) {
        if (entity != null && ml.mypals.meshcut.geom.VoxelBoxes.remainingVolume(entity.boxes(), planes, excluded) < EMPTY_BODY_VOLUME) {
            if (level.getBlockState(pos).getBlock() instanceof MimicBlock) {
                level.setBlock(pos, level.getFluidState(pos).createLegacyBlock(), UPDATE_ALL);
            }
            return;
        }
        boolean fresh = !(level.getBlockState(pos).getBlock() instanceof MimicBlock);
        if (fresh) {
            boolean wet = level.getFluidState(pos).getType() == Fluids.WATER;
            level.setBlock(pos, MeshCut.MIMIC_BLOCK.get().defaultBlockState().setValue(WATERLOGGED, wet), QUIET);
        }
        if (level.getBlockEntity(pos) instanceof MimicBlockEntity be) {
            boolean slot = level.getBlockState(pos).getValue(REFRESH);
            be.beginMassTransition(fresh, slot);
            be.configure(target, planes, excluded, patches, mode, beTag, entity, inherited);
            refreshPhysics(level, pos);
            be.endMassTransition();
        }
    }

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
        if (level.getBlockEntity(pos) instanceof MimicBlockEntity be && be.passable()) {
            return Shapes.empty();
        }
        return getShape(state, level, pos, context);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, BlockPos neighborPos,
                                   boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (level instanceof ServerLevel server) {
            SlashService.queueLocalRecheck(server, pos);
        }
    }

    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player, boolean willHarvest,
                                       FluidState fluid) {
        if (level.getBlockEntity(pos) instanceof MimicBlockEntity be && be.grid().componentCount() >= 2) {
            if (level instanceof ServerLevel server) {
                if (FragmentBreaking.breakPointed(server, pos, be, player)) {
                    return false;
                }
            } else {
                return false;
            }
        }
        return super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
    }

    @Nullable
    private static BlockState targetAt(BlockGetter level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof MimicBlockEntity be ? be.getTargetState() : null;
    }

    @Override
    public ItemStack getCloneItemStack(BlockState state, HitResult target, LevelReader level, BlockPos pos, Player player) {
        BlockState imitated = targetAt(level, pos);
        return imitated != null ? imitated.getCloneItemStack(target, level, pos, player) : ItemStack.EMPTY;
    }

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


    @Override
    public boolean hidesNeighborFace(BlockGetter level, BlockPos pos, BlockState state, BlockState neighborState, Direction dir) {
        return level.getBlockEntity(pos) instanceof MimicBlockEntity be && be.coversFace(dir);
    }

    @Override
    public boolean hasDynamicLightEmission(@NotNull BlockState state) {
        return true;
    }

    @Override
    public int getLightEmission(@NotNull BlockState state, BlockGetter level, @NotNull BlockPos pos) {
        var light = level.getAuxLightManager(pos);
        return light == null ? 0 : light.getLightAt(pos);
    }


    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return state.getFluidState().isEmpty();
    }

    @Override
    protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
        return 1.0F;
    }

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
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MimicBlockEntity(pos, state);
    }
}
