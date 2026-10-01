package ml.mypals.meshcut.mixin;

import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper;
import ml.mypals.meshcut.block.MimicBlock;
import ml.mypals.meshcut.block.MimicBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A cut block weighs what the imitated block weighs, scaled by how much of it is left. */
@Mixin(value = PhysicsBlockPropertyHelper.class, remap = false)
public abstract class PhysicsBlockPropertyHelperMixin {
    @Inject(method = "getMass", at = @At("HEAD"), cancellable = true)
    private static void meshcut$mimicMass(BlockGetter level, BlockPos pos, BlockState state,
                                          CallbackInfoReturnable<Double> cir) {
        if (state.getBlock() instanceof MimicBlock && level.getBlockEntity(pos) instanceof MimicBlockEntity be) {
            MimicBlockEntity.MassSnapshot mass = be.massFor(state);
            if (mass != null) {
                cir.setReturnValue(PhysicsBlockPropertyHelper.getMass(level, pos, mass.target()) * mass.volumeFraction());
            }
        }
    }
}
