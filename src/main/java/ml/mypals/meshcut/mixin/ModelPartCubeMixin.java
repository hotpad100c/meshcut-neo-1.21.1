package ml.mypals.meshcut.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import ml.mypals.meshcut.client.capture.ModelCapturer;
import ml.mypals.meshcut.client.capture.PartTags;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells a recording of an entity which cube of a model part is drawing (see {@link PartTags}). */
@Mixin(ModelPart.Cube.class)
public abstract class ModelPartCubeMixin {
    @Inject(require = 0, method = "compile", at = @At("HEAD"))
    private void meshcut$enter(PoseStack.Pose pose, VertexConsumer buffer, int light, int overlay, int color, CallbackInfo ci) {
        if (ModelCapturer.isRecording()) {
            PartTags.cube((ModelPart.Cube) (Object) this);
        }
    }

    @Inject(require = 0, method = "compile", at = @At("RETURN"))
    private void meshcut$leave(PoseStack.Pose pose, VertexConsumer buffer, int light, int overlay, int color, CallbackInfo ci) {
        if (ModelCapturer.isRecording()) {
            PartTags.cube(null);
        }
    }
}
