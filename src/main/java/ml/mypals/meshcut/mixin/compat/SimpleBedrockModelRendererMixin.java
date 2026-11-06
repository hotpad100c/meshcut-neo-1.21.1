package ml.mypals.meshcut.mixin.compat;

import ml.mypals.meshcut.client.capture.ModelCapturer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakedGeometryChunkRenderer", remap = false)
public abstract class SimpleBedrockModelRendererMixin {
    @Inject(method = "shouldCullQuad", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void meshcut$noCullWhenRecording(float[] positions, int offset, Matrix4f pose, float nx, float ny, float nz, CallbackInfoReturnable<Boolean> cir) {
        if (ModelCapturer.isRecording()) {
            cir.setReturnValue(false);
        }
    }
}
