package ml.mypals.meshcut.mixin;

import ml.mypals.meshcut.client.Bloom;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Under a shader pack the scene is finished by the pack's final pass, at the very end of the level's rendering and
 * after every stage event; the bloom is added to the picture then, so that it is part of what is seen.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererBloomMixin {
    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V"))
    private void meshcut$afterLevel(CallbackInfo ci) {
        Bloom.composeDeferred();
    }
}
