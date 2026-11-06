package ml.mypals.meshcut.mixin.compat;

import ml.mypals.meshcut.cut.AnvilGunBeam;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.UUID;

@Pseudo
@Mixin(targets = "dev.dubhe.anvilcraft.item.weapon.LaserGunItem", remap = false)
public abstract class AnvilLaserGunMixin {
    @Shadow
    private static Map<UUID, ?> STATES;

    @Inject(require = 0, method = "onUseTick", at = @At("TAIL"))
    private void meshcut$cut(Level level, LivingEntity user, ItemStack stack, int remaining, CallbackInfo ci) {
        if (user instanceof ServerPlayer player && level instanceof ServerLevel && STATES.containsKey(player.getUUID())) {
            AnvilGunBeam.fire(player);
        }
    }
}
