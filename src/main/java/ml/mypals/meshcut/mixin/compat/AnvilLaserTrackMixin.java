package ml.mypals.meshcut.mixin.compat;

import dev.dubhe.anvilcraft.block.entity.BaseLaserBlockEntity;
import ml.mypals.meshcut.client.AnvilLaserDraw;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.dubhe.anvilcraft.block.entity.BaseLaserBlockEntity", remap = false)
public abstract class AnvilLaserTrackMixin {
    @Inject(require = 0, method = "clearRemoved", at = @At("TAIL"))
    private void meshcut$track(CallbackInfo ci) {
        BaseLaserBlockEntity self = (BaseLaserBlockEntity) (Object) this;
        if (self.getLevel() != null && self.getLevel().isClientSide()) AnvilLaserDraw.track(self);
    }
}
