package ml.mypals.meshcut.mixin;

import ml.mypals.meshcut.block.MovedMimic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PistonMovingBlockEntity.class)
public abstract class PistonMovingBlockEntityMixin implements MovedMimic {
    @Unique
    private static final String MESHCUT_KEY = "meshcut:mimic";
    @Unique
    @Nullable
    private CompoundTag meshcut$mimic;

    @Override
    @Nullable
    public CompoundTag meshcut$mimic() {
        return meshcut$mimic;
    }

    @Override
    public void meshcut$setMimic(@Nullable CompoundTag data) {
        meshcut$mimic = data;
    }

    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void meshcut$save(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        if (meshcut$mimic != null) {
            tag.put(MESHCUT_KEY, meshcut$mimic.copy());
        }
    }

    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void meshcut$load(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        meshcut$mimic = tag.contains(MESHCUT_KEY, Tag.TAG_COMPOUND) ? tag.getCompound(MESHCUT_KEY) : null;
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private static void meshcut$landed(Level level, BlockPos pos, BlockState state, PistonMovingBlockEntity entity, CallbackInfo ci) {
        MovedMimic.land(level, pos, ((MovedMimic) entity).meshcut$mimic());
    }

    @Inject(method = "finalTick", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private void meshcut$landedEarly(CallbackInfo ci) {
        BlockEntity self = (BlockEntity) (Object) this;
        if (self.getLevel() != null) {
            MovedMimic.land(self.getLevel(), self.getBlockPos(), meshcut$mimic);
        }
    }
}
