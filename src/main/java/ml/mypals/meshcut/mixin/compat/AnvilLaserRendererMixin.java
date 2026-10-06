package ml.mypals.meshcut.mixin.compat;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.Sable;
import ml.mypals.meshcut.cut.AnvilLaserSync;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "dev.dubhe.anvilcraft.client.renderer.blockentity.LaserBlockRenderer", remap = false)
public abstract class AnvilLaserRendererMixin {
    @ModifyExpressionValue(require = 0, method = "render*", at = @At(value = "INVOKE",
            target = "Ldev/dubhe/anvilcraft/client/renderer/RenderState;isEnhancedRenderingAvailable()Z"))
    private boolean meshcut$drawOnSubLevel(boolean enhanced, @Local(argsOnly = true) BlockEntity be) {
        boolean onSub = be.getLevel() != null && Sable.HELPER.getContaining(be.getLevel(), be.getBlockPos()) != null;
        if (onSub) AnvilLaserSync.debugRender(be, enhanced);
        return enhanced && !onSub;
    }
}
