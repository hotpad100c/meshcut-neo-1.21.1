package ml.mypals.meshcut.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import ml.mypals.meshcut.client.capture.ModelCapturer;
import ml.mypals.meshcut.client.capture.PartTags;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

/**
 * Citadel's model parts (Alex's Mobs and others) are not vanilla model parts, but they have names: a recording of an
 * entity gets told which part pushed the pose it is drawing at, and what it is called.
 */
@Pseudo
@Mixin(targets = "com.github.alexthe666.citadel.client.model.AdvancedModelBox", remap = false)
public abstract class CitadelModelBoxMixin {
    private static Field meshcut$boxName;
    private static boolean meshcut$looked;

    @Inject(require = 0, method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V",
            at = @At(value = "INVOKE", shift = At.Shift.AFTER,
                    target = "Lcom/github/alexthe666/citadel/client/model/AdvancedModelBox;translateAndRotate(Lcom/mojang/blaze3d/vertex/PoseStack;)V"))
    private void meshcut$named(PoseStack poseStack, VertexConsumer buffer, int light, int overlay, int color, CallbackInfo ci) {
        if (ModelCapturer.isRecording()) {
            PartTags.own(this, meshcut$nameOf(this));
        }
    }

    private static String meshcut$nameOf(Object part) {
        try {
            if (!meshcut$looked) {
                meshcut$looked = true;
                meshcut$boxName = part.getClass().getField("boxName");
            }
            Object name = meshcut$boxName == null ? null : meshcut$boxName.get(part);
            return name instanceof String s ? s : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
