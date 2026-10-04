package ml.mypals.meshcut.mixin;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import ml.mypals.meshcut.physics.MassGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SubLevelPhysicsSystem.class, remap = false)
public abstract class SubLevelPhysicsSystemMixin {
    @Inject(method = "tickPipelinePhysics", at = @At("HEAD"))
    private void meshcut$checkMass(ServerSubLevelContainer container, CallbackInfo ci) {
        MassGuard.check((SubLevelPhysicsSystem) (Object) this, container);
    }
}
