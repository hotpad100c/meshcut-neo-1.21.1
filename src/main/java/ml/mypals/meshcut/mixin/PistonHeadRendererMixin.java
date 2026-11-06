package ml.mypals.meshcut.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import ml.mypals.meshcut.block.MimicBlock;
import ml.mypals.meshcut.block.MovedMimic;
import ml.mypals.meshcut.client.MovingMimicRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.PistonHeadRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PistonHeadRenderer.class)
public abstract class PistonHeadRendererMixin {
    @WrapOperation(method = "render(Lnet/minecraft/world/level/block/piston/PistonMovingBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/blockentity/PistonHeadRenderer;renderBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/level/Level;ZI)V"))
    private void meshcut$drawMimic(PistonHeadRenderer self, BlockPos pos, BlockState state, PoseStack poseStack, MultiBufferSource buffer,
                                   Level level, boolean checkSides, int packedOverlay, Operation<Void> original,
                                   @Local(argsOnly = true) PistonMovingBlockEntity moving, @Local(argsOnly = true) float partialTick,
                                   @Local(argsOnly = true, ordinal = 0) int packedLight) {
        CompoundTag data = ((MovedMimic) moving).meshcut$mimic();
        if (data != null && state.getBlock() instanceof MimicBlock) {
            MovingMimicRenderer.render(moving, data, pos, state, poseStack, buffer, level, partialTick, packedLight, packedOverlay);
        } else {
            original.call(self, pos, state, poseStack, buffer, level, checkSides, packedOverlay);
        }
    }
}
