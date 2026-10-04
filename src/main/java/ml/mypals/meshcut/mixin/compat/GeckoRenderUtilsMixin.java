package ml.mypals.meshcut.mixin.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import ml.mypals.meshcut.client.capture.ModelCapturer;
import ml.mypals.meshcut.client.capture.PartTags;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.Coerce;

import java.lang.reflect.Method;

//Hey gekolib have bones yo help us, yay!
@Pseudo
@Mixin(targets = "software.bernie.geckolib.util.RenderUtils", remap = false)
public abstract class GeckoRenderUtilsMixin {
    @Unique
    private static Method meshcut$getName;
    @Unique
    private static boolean meshcut$looked;

    @Inject(require = 0, method = "translateAwayFromPivotPoint", at = @At("HEAD"))
    private static void meshcut$hinge(PoseStack poseStack, @Coerce Object bone, CallbackInfo ci) {
        if (ModelCapturer.isRecording()) {
            PartTags.hinge(bone, meshcut$nameOf(bone));
        }
    }

    @Unique
    private static String meshcut$nameOf(Object bone) {
        try {
            if (!meshcut$looked) {
                meshcut$looked = true;
                meshcut$getName = bone.getClass().getMethod("getName");
            }
            Object name = meshcut$getName == null ? null : meshcut$getName.invoke(bone);
            return name instanceof String s ? s : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
