package ml.mypals.meshcut.client;

import ml.mypals.meshcut.MeshCut;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

@EventBusSubscriber(modid = MeshCut.MODID, value = Dist.CLIENT)
public final class AnvilLaserEvents {
    private static Boolean present;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (present == null) present = ModList.get().isLoaded("anvilcraft");
        if (present) AnvilLaserDraw.render(event);
    }

    private AnvilLaserEvents() {}
}
