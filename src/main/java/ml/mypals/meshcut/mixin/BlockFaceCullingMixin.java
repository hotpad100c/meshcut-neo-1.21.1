package ml.mypals.meshcut.mixin;

import ml.mypals.meshcut.block.MimicBlock;
import ml.mypals.meshcut.block.MimicBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Whether a face of a mimic is drawn is worked out for that very block. Vanilla remembers the answer per pair of
 * block states, but all mimics share a handful of states while their shapes differ (they are in their block
 * entities): the first one asked about would decide for all, and faces buried against other blocks were drawn.
 */
@Mixin(Block.class)
public abstract class BlockFaceCullingMixin {
    @Inject(method = "shouldRenderFace", at = @At("HEAD"), cancellable = true)
    private static void meshcut$mimicFace(BlockState state, BlockGetter level, BlockPos offset, Direction face, BlockPos pos,
                                          CallbackInfoReturnable<Boolean> cir) {
        if (!(state.getBlock() instanceof MimicBlock)) {
            // a face against a whole side of a mimic is hidden as against what it imitates. NeoForge only lets a
            // neighbour hide faces of blocks that agree to it (by default, none in the solid layer: dirt, stone, ...),
            // and otherwise sees the mimic as a block that hides nothing
            BlockState neighbour = level.getBlockState(pos);
            if (neighbour.getBlock() instanceof MimicBlock && !state.skipRendering(neighbour, face)
                    && neighbour.hidesNeighborFace(level, pos, state, face.getOpposite())) {
                cir.setReturnValue(false);
            }
            return;
        }
        BlockState neighbour = level.getBlockState(pos);
        if (state.skipRendering(neighbour, face)) {
            cir.setReturnValue(false);
            return;
        }
        if (neighbour.hidesNeighborFace(level, pos, state, face.getOpposite())) {
            cir.setReturnValue(false);
            return;
        }
        if (!neighbour.canOcclude()) {
            cir.setReturnValue(true);
            return;
        }
        VoxelShape mine = Shapes.getFaceShape(level.getBlockEntity(offset) instanceof MimicBlockEntity be ? be.getShape() : Shapes.block(), face);
        if (mine.isEmpty()) {
            cir.setReturnValue(true);
            return;
        }
        VoxelShape theirs = neighbour.getFaceOcclusionShape(level, pos, face.getOpposite());
        cir.setReturnValue(Shapes.joinIsNotEmpty(mine, theirs, BooleanOp.ONLY_FIRST));
    }
}
