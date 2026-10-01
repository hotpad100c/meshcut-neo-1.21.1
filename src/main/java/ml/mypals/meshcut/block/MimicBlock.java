package ml.mypals.meshcut.block;

import com.mojang.serialization.MapCodec;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.block.BlockSubLevelAssemblyListener;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.cut.CapMode;
import ml.mypals.meshcut.cut.CutPlane;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Stands in for any block; its block model draws the imitated block, clipped by the cuts in the block entity. */
public class MimicBlock extends BaseEntityBlock implements BlockSubLevelAssemblyListener {
    public static final MapCodec<MimicBlock> CODEC = simpleCodec(MimicBlock::new);

    /**
     * Flipped to make Sable's physics engine re-read this block's collider once the block entity holds its final
     * data (colliders are read on block changes, and a state change is the only reliable trigger).
     */
    public static final BooleanProperty REFRESH = BooleanProperty.create("refresh");

    public MimicBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(REFRESH, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(REFRESH);
    }

    /** Places a mimic of {@code target} cut by {@code planes} (block-local half-spaces). */
    public static void place(ServerLevel level, BlockPos pos, BlockState target, List<CutPlane> planes, CapMode mode,
                             @Nullable CompoundTag beTag) {
        place(level, pos, target, planes, List.of(), mode, beTag);
    }

    /**
     * @param pending cuts that went through the block without severing anything; see
     *                {@link MimicBlockEntity#getPending()}
     */
    public static void place(ServerLevel level, BlockPos pos, BlockState target, List<CutPlane> planes,
                             List<CutPlane> pending, CapMode mode, @Nullable CompoundTag beTag) {
        // A block Sable saw being placed had no block entity yet, so it weighed what a default mimic weighs.
        boolean fresh = !(level.getBlockState(pos).getBlock() instanceof MimicBlock);
        if (fresh) {
            level.setBlock(pos, MeshCut.MIMIC_BLOCK.get().defaultBlockState(), 3);
        }
        if (level.getBlockEntity(pos) instanceof MimicBlockEntity be) {
            boolean slot = level.getBlockState(pos).getValue(REFRESH);
            be.beginMassTransition(fresh, slot);
            be.configure(target, planes, pending, mode, beTag);
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
            level.setBlock(pos, state.setValue(REFRESH, !state.getValue(REFRESH)), 3);
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

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL; // BaseEntityBlock defaults to INVISIBLE; our model draws the cut block
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MimicBlockEntity(pos, state);
    }
}
