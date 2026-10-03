package ml.mypals.meshcut.mixin;

import ml.mypals.meshcut.cut.EntityCutting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** An entity held still while it is being cut is saved as it was before (see {@link EntityCutting#saved}). */
@Mixin(Entity.class)
public abstract class EntitySaveMixin {
    @Inject(method = "saveWithoutId", at = @At("RETURN"))
    private void meshcut$saved(CompoundTag tag, CallbackInfoReturnable<CompoundTag> cir) {
        EntityCutting.saved((Entity) (Object) this, cir.getReturnValue());
    }
}
