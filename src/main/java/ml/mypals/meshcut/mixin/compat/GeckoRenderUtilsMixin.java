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

/**
 * GeckoLib draws a bone by pushing a level of the pose stack, moving to where the bone is, to its pivot, turning and
 * scaling about it, and moving back before it draws the cubes. At the moment of moving back the pose is the bone's frame
 * (its pivot, its rotation), which is where its limb hinges. The class is {@code RenderUtil} from 4.8 on and
 * {@code RenderUtils} before it; it also has a version of the method for cubes, which must not be taken for a bone.
 */
@Pseudo
@Mixin(targets = {"software.bernie.geckolib.util.RenderUtil", "software.bernie.geckolib.util.RenderUtils"}, remap = false)
public abstract class GeckoRenderUtilsMixin {
    @Unique
    private static Method meshcut$getName;
    @Unique
    private static boolean meshcut$looked;

    @Inject(require = 0, method = "translateAwayFromPivotPoint(Lcom/mojang/blaze3d/vertex/PoseStack;Lsoftware/bernie/geckolib/cache/object/GeoBone;)V",
            at = @At("HEAD"))
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
