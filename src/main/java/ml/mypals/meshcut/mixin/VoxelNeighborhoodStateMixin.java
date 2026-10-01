package ml.mypals.meshcut.mixin;

import dev.ryanhcode.sable.physics.chunk.VoxelNeighborhoodState;
import ml.mypals.meshcut.block.MimicBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sable caches "is this block solid / a full cube" per state, but for a mimic that depends on the cut. Reporting
 * "solid, not full" keeps its mass counted and stops neighbours from hiding the faces that touch a cut-away void.
 */
@Mixin(value = VoxelNeighborhoodState.class, remap = false)
public abstract class VoxelNeighborhoodStateMixin {
    @Inject(method = "isSolid", at = @At("HEAD"), cancellable = true)
    private static void meshcut$mimicSolid(BlockGetter level, BlockPos pos, BlockState state,
                                           CallbackInfoReturnable<Boolean> cir) {
        if (state.getBlock() instanceof MimicBlock) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "isFullBlock", at = @At("HEAD"), cancellable = true)
    private static void meshcut$mimicNotFull(BlockGetter level, BlockPos pos, BlockState state,
                                             CallbackInfoReturnable<Boolean> cir) {
        if (state.getBlock() instanceof MimicBlock) {
            cir.setReturnValue(false);
        }
    }
}
