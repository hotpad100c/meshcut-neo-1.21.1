package ml.mypals.meshcut.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import ml.mypals.meshcut.client.LightsaberControls;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MouseHandler.class)
public abstract class LightsaberMouseMixin {
    @WrapOperation(method = "turnPlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private void meshcut$freeAim(LocalPlayer player, double yaw, double pitch, Operation<Void> original) {
        var turn = LightsaberControls.routeTurn(player, yaw * 0.15, pitch * 0.15);
        original.call(player, turn.yaw() / 0.15, turn.pitch() / 0.15);
    }
}
