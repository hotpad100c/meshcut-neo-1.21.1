package ml.mypals.meshcut.mixin;

import ml.mypals.meshcut.item.LightsaberItem;
import ml.mypals.meshcut.client.LightsaberHands;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HumanoidModel.class)
public abstract class LightsaberArmPoseMixin {
    @Shadow public ModelPart rightArm;
    @Shadow public ModelPart leftArm;
    @Shadow public ModelPart body;

    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("HEAD"))
    private void meshcut$resetArmLength(LivingEntity entity, float limbSwing, float limbSwingAmount,
                                       float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo ci) {
        rightArm.yScale = leftArm.yScale = 1;
    }

    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("RETURN"))
    private void meshcut$aimSaberArm(LivingEntity entity, float limbSwing, float limbSwingAmount,
                                    float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo ci) {
        if (!(entity instanceof Player player)) return;
        float partialTick = Mth.clamp(ageInTicks - player.tickCount, 0f, 1f);
        if (player.getMainHandItem().getItem() instanceof ml.mypals.meshcut.item.LaserGunItem) {
            ml.mypals.meshcut.client.LaserGunHands.poseArm(player, partialTick,
                    player.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT ? rightArm : leftArm);
            body.yRot = 0;
            return;
        }
        if (!LightsaberItem.isActive(player)) return;
        LightsaberHands.poseArms(player, partialTick, rightArm, leftArm);
        body.yRot = 0;
    }
}
