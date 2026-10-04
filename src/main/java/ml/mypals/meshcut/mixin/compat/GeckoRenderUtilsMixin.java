package ml.mypals.meshcut.mixin.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import ml.mypals.meshcut.client.capture.ModelCapturer;
import ml.mypals.meshcut.client.capture.PartTags;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.Coerce;

import java.lang.reflect.Method;

/**
 * GeckoLib draws a bone by moving to its pivot, turning and scaling, moving back away from the pivot, and then drawing
 * its cubes in the model's own coordinates. The pose the cubes are drawn at has the move away folded into it, so its
 * origin is not where the bone hinges. A recording of an entity is told the moment before the move away: that pose is
 * the bone's frame, with its pivot as origin, and the bone's name goes with it.
 */
@Pseudo
@Mixin(targets = "software.bernie.geckolib.util.RenderUtils", remap = false)
public abstract class GeckoRenderUtilsMixin {
    private static Method meshcut$getName;
    private static boolean meshcut$looked;

    @Inject(require = 0, method = "translateAwayFromPivotPoint", at = @At("HEAD"))
    private static void meshcut$hinge(PoseStack poseStack, @Coerce Object bone, CallbackInfo ci) {
        if (ModelCapturer.isRecording()) {
            PartTags.hinge(bone, meshcut$nameOf(bone));
        }
    }

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
