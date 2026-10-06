package ml.mypals.meshcut.mixin.compat;

import ml.mypals.meshcut.cut.AnvilLaserBeam;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
public abstract class AnvilLaserBlockEntityMixin {
    @Shadow
    protected BlockPos irradiateBlockPos;
    @Shadow
    protected int laserLevel;
    @Shadow
    protected abstract boolean canPassThrough(Direction direction, BlockPos pos);
    @Shadow
    public abstract BlockPos getLaserOrigin();

    @Inject(require = 0, method = "emitLaserComponents", at = @At("TAIL"))
    private void meshcut$cut(Direction direction, boolean gamma, CallbackInfo ci) {
        BlockEntity blockEntity = (BlockEntity) (Object) this;
        Level level = blockEntity.getLevel();
        if (level == null || level.isClientSide() || irradiateBlockPos == null) return;
        boolean blocked = !canPassThrough(direction, irradiateBlockPos);
        AnvilLaserBeam.emit(level, getLaserOrigin(), irradiateBlockPos, direction, blocked, laserLevel, (int) (level.getGameTime() % Integer.MAX_VALUE));
    }
}
