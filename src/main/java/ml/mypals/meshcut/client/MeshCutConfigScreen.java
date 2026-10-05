package ml.mypals.meshcut.client;

import ml.mypals.meshcut.MeshCut;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@EventBusSubscriber(modid = MeshCut.MODID, value = Dist.CLIENT)
public final class MeshCutConfigScreen {
    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        net.neoforged.fml.ModList.get().getModContainerById(MeshCut.MODID).orElseThrow()
                .registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
    private MeshCutConfigScreen() {}
}
