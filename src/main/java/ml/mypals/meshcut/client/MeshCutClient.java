package ml.mypals.meshcut.client;

import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.client.capture.ModelCapturer;
import ml.mypals.meshcut.client.render.MimicBakedModel;
import ml.mypals.meshcut.client.render.MimicMesh;
import ml.mypals.meshcut.client.render.MimicTint;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

@EventBusSubscriber(modid = MeshCut.MODID, value = Dist.CLIENT)
public final class MeshCutClient {
    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(MeshCut.MIMIC_BE.get(), MimicRenderer::new);
    }

    /** Swaps the (empty) baked model of every mimic block state for one that draws the cut block. */
    @SubscribeEvent
    public static void wrapMimicModels(ModelEvent.ModifyBakingResult event) {
        event.getModels().replaceAll((id, model) ->
                id.id().getNamespace().equals(MeshCut.MODID) && id.id().getPath().equals("mimic")
                        && !(model instanceof MimicBakedModel) ? new MimicBakedModel(model) : model);
    }

    /** Cut grass stays grass-coloured: tints are forwarded to the imitated block's colour handler. */
    @SubscribeEvent
    public static void registerBlockColors(RegisterColorHandlersEvent.Block event) {
        event.register((state, level, pos, tintIndex) -> MimicTint.color(level, pos, tintIndex), MeshCut.MIMIC_BLOCK.get());
    }

    /** Captured meshes hold atlas UVs, so they're stale after any resource reload. */
    @SubscribeEvent
    public static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) rm -> {
            ModelCapturer.clearCache();
            MimicMesh.clearCache();
        });
    }

    private MeshCutClient() {}
}
