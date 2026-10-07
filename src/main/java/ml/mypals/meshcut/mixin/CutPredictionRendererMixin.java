package ml.mypals.meshcut.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import ml.mypals.meshcut.block.MimicRenderData;
import ml.mypals.meshcut.client.CutPrediction;
import ml.mypals.meshcut.client.render.MimicBakedModel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Covers ordinary blocks before the server has replaced them with a mimic BE. */
@Mixin(BlockRenderDispatcher.class)
public abstract class CutPredictionRendererMixin {
    @Inject(method="renderBatched(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/BlockAndTintGetter;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;ZLnet/minecraft/util/RandomSource;Lnet/neoforged/neoforge/client/model/data/ModelData;Lnet/minecraft/client/renderer/RenderType;)V",at=@At("HEAD"),cancellable=true)
    private void meshcut$predict(BlockState state,BlockPos pos,BlockAndTintGetter level,PoseStack pose,VertexConsumer consumer,
                                 boolean checkSides,RandomSource random,ModelData originalData,RenderType layer,CallbackInfo ci) {
        var view=CutPrediction.view(pos);
        if(view==null||view.source()!=state)return;
        ci.cancel();if(view.empty())return;
        var dispatcher=(BlockRenderDispatcher)(Object)this;
        var model=new MimicBakedModel(dispatcher.getBlockModel(view.data().target()));
        ModelData data=ModelData.builder().with(MimicRenderData.PROPERTY,view.data()).with(MimicRenderData.POS,pos.immutable()).build();
        dispatcher.getModelRenderer().tesselateBlock(level,model,view.data().target(),pos,pose,consumer,false,random,
                state.getSeed(pos),OverlayTexture.NO_OVERLAY,data,layer);
    }
}
