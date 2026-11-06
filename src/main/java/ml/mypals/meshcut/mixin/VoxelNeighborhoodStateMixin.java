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

@Mixin(value = VoxelNeighborhoodState.class, remap = false)
public abstract class VoxelNeighborhoodStateMixin {
    @Inject(method = "isSolid", at = @At("HEAD"), cancellable = true)
    private static void meshcut$mimicSolid(BlockGetter level, BlockPos pos, BlockState state,
                                           CallbackInfoReturnable<Boolean> cir) {
        if (state.getBlock() instanceof MimicBlock) {
            boolean passable = level.getBlockEntity(pos) instanceof ml.mypals.meshcut.block.MimicBlockEntity be && be.passable();
            cir.setReturnValue(!passable || meshcut$inPlot(level, pos));
        }
    }

    private static boolean meshcut$inPlot(BlockGetter level, BlockPos pos) {
        var container = level instanceof net.minecraft.world.level.Level l
                ? dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(l) : null;
        return container == null || container.inBounds(pos);
    }

    @Inject(method = "isFullBlock", at = @At("HEAD"), cancellable = true)
    private static void meshcut$mimicNotFull(BlockGetter level, BlockPos pos, BlockState state,
                                             CallbackInfoReturnable<Boolean> cir) {
        if (state.getBlock() instanceof MimicBlock) {
            cir.setReturnValue(false);
        }
    }
}
