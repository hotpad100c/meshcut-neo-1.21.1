package ml.mypals.meshcut.mixin.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.dubhe.anvilcraft.block.entity.BaseLaserBlockEntity;
import ml.mypals.meshcut.client.AnvilLaserDraw;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A laser on a sub-level is drawn by {@link AnvilLaserDraw} (with the pose of the body, whatever renderer pipeline is in
 * use), so AnvilCraft's own block entity renderer leaves it alone: it would draw it a second time, or not at all.
 */
@Pseudo
@Mixin(targets = "dev.dubhe.anvilcraft.client.renderer.blockentity.LaserBlockRenderer", remap = false)
public abstract class AnvilLaserRendererMixin {
    @Inject(require = 0, method = "render", at = @At("HEAD"), cancellable = true)
    private void meshcut$leaveSubLevelLasers(BaseLaserBlockEntity be, float partialTick, PoseStack poseStack,
                                             MultiBufferSource buffer, int packedLight, int packedOverlay, CallbackInfo ci) {
        if (AnvilLaserDraw.onSubLevel(be)) ci.cancel();
    }
}
