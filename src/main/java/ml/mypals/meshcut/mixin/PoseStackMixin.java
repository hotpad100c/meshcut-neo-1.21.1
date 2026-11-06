package ml.mypals.meshcut.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import ml.mypals.meshcut.client.capture.ModelCapturer;
import ml.mypals.meshcut.client.capture.PartTags;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PoseStack.class)
public abstract class PoseStackMixin {
    @Inject(method = "pushPose", at = @At("TAIL"))
    private void meshcut$pushed(CallbackInfo ci) {
        if (ModelCapturer.isRecording()) {
            PartTags.pushed(((PoseStack) (Object) this).last());
        }
    }

    @Inject(method = "popPose", at = @At("HEAD"))
    private void meshcut$popped(CallbackInfo ci) {
        if (ModelCapturer.isRecording()) {
            PartTags.popped();
        }
    }
}
