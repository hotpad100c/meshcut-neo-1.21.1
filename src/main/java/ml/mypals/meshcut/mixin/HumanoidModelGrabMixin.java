package ml.mypals.meshcut.mixin;

import ml.mypals.meshcut.client.FragmentGrabClient;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HumanoidModel.class)
public abstract class HumanoidModelGrabMixin<T extends LivingEntity> {
    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("RETURN"))
    private void meshcut$holding(T entity, float limbSwing, float limbSwingAmount, float ageInTicks, float headYaw,
                                 float headPitch, CallbackInfo ci) {
        FragmentGrabClient.pose(entity, (HumanoidModel<?>) (Object) this);
    }
}
