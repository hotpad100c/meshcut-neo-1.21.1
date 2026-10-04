package ml.mypals.meshcut.mixin.compat;

import ml.mypals.meshcut.client.capture.ModelCapturer;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;


@Pseudo
@Mixin(targets = "dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl", remap = false)
public abstract class FlywheelVisualizationMixin {
    @Inject(method = "supportsVisualization", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void meshcut$notWhileRecording(LevelAccessor level, CallbackInfoReturnable<Boolean> cir) {
        if (ModelCapturer.isRecording()) {
            cir.setReturnValue(false);
        }
    }
}
