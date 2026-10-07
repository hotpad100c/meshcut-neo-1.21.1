package ml.mypals.meshcut.mixin.compat;

import ml.mypals.meshcut.client.CutPrediction;
import ml.mypals.meshcut.client.render.MimicBakedModel;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sodium bypasses BlockRenderDispatcher.renderBatched when compiling ordinary blocks. */
@Pseudo
@Mixin(targets="net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer",remap=false)
public abstract class SodiumPredictionMixin {
    @Unique private boolean meshcut$predicting;
    @Shadow public abstract void renderModel(BakedModel model,BlockState state,BlockPos pos,BlockPos origin);

    @Inject(method="renderModel(Lnet/minecraft/client/resources/model/BakedModel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)V",
            at=@At("HEAD"),cancellable=true,require=0)
    private void meshcut$predict(BakedModel model,BlockState state,BlockPos pos,BlockPos origin,CallbackInfo ci) {
        if(meshcut$predicting)return;
        var view=CutPrediction.view(pos);
        if(view==null||view.source()!=state)return;
        ci.cancel();if(view.empty())return;
        meshcut$predicting=true;
        try { renderModel(MimicBakedModel.predicted(model,view,pos),state,pos,origin); }
        finally {meshcut$predicting=false;}
    }
}
