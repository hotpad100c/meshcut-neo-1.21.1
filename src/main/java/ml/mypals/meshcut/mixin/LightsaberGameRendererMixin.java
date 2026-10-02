package ml.mypals.meshcut.mixin;

import ml.mypals.meshcut.client.LightsaberRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Bridges the vanilla hand pass and scene depth without duplicating hand animation transforms. */
@Mixin(GameRenderer.class)
public abstract class LightsaberGameRendererMixin {
    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V"))
    private void meshcut$preserveWorldDepth(DeltaTracker deltaTracker, CallbackInfo ci) {
        LightsaberRenderer.prepareFirstPersonFrame();
    }

    @Inject(method = "renderItemInHand", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/player/LocalPlayer;I)V"))
    private void meshcut$renderWorldBlade(Camera camera, float partialTick, Matrix4f view, CallbackInfo ci) {
        LightsaberRenderer.renderFirstPersonBeam(partialTick);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void meshcut$releaseDepth(CallbackInfo ci) {
        LightsaberRenderer.releaseDepthTarget();
    }
}
