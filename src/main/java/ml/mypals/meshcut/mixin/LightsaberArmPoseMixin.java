package ml.mypals.meshcut.mixin;

import ml.mypals.meshcut.item.LightsaberItem;
import ml.mypals.meshcut.item.LightsaberMotion;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.joml.Vector2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The holding arm follows the shared blade aim instead of the vanilla walking/attack arm swing. */
@Mixin(HumanoidModel.class)
public abstract class LightsaberArmPoseMixin {
    @Shadow public ModelPart rightArm;
    @Shadow public ModelPart leftArm;
    @Shadow public ModelPart body;

    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("RETURN"))
    private void meshcut$aimSaberArm(LivingEntity entity, float limbSwing, float limbSwingAmount,
                                    float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo ci) {
        if (!(entity instanceof Player player) || !LightsaberItem.isActive(player)) return;
        float partialTick = Mth.clamp(ageInTicks - player.tickCount, 0f, 1f);
        Vector2f aim = LightsaberMotion.angles(player, partialTick);
        float bodyYaw = Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot);
        boolean left = player.getMainArm() == HumanoidArm.LEFT;
        ModelPart arm = left ? leftArm : rightArm;
        arm.x = left ? 5 : -5;
        arm.y = player.isCrouching() ? 5.2f : 2;
        arm.z = 0;
        arm.xRot = (float) Math.toRadians(aim.y - 90);
        arm.yRot = (float) Math.toRadians(Mth.wrapDegrees(aim.x - bodyYaw));
        arm.zRot = 0;
        body.yRot = 0;
    }
}
