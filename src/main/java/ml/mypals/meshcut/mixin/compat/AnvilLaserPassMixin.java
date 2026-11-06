package ml.mypals.meshcut.mixin.compat;

import ml.mypals.meshcut.cut.MimicBeamPass;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "dev.dubhe.anvilcraft.api.laser.LaserTypeComponent", remap = false)
public abstract class AnvilLaserPassMixin {
    @Inject(require = 0, method = "canPassThrough", at = @At("HEAD"), cancellable = true)
    private void meshcut$bore(Level level, Direction direction, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (MimicBeamPass.passes(level, pos, direction)) cir.setReturnValue(true);
    }
}
