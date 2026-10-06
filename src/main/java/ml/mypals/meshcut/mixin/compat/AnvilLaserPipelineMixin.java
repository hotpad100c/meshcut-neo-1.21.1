package ml.mypals.meshcut.mixin.compat;

import dev.ryanhcode.sable.Sable;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Pseudo
@Mixin(targets = "dev.dubhe.anvilcraft.api.rendering.CacheableBERenderingPipeline", remap = false)
public abstract class AnvilLaserPipelineMixin {
    @Inject(require = 0, method = "update", at = @At("HEAD"), cancellable = true)
    private void meshcut$skipSubLevel(BlockEntity be, CallbackInfo ci) {
        if (be.getLevel() != null && Sable.HELPER.getContaining(be.getLevel(), be.getBlockPos()) != null) ci.cancel();
    }
}
