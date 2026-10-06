package ml.mypals.meshcut.mixin.compat;

import ml.mypals.meshcut.cut.AnvilLaserSync;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Pseudo
@Mixin(targets = "dev.dubhe.anvilcraft.block.entity.BaseLaserBlockEntity", remap = false)
public abstract class AnvilLaserSyncMixin {
    @Shadow
    protected boolean changed;
    @Shadow
    protected int tickCount;

    @Inject(require = 0, method = "tick", at = @At("HEAD"))
    private void meshcut$syncSubLevel(Level level, CallbackInfo ci) {
        if (changed || tickCount % 20 == 0) AnvilLaserSync.send((BlockEntity) (Object) this);
    }
}
