package ml.mypals.meshcut.client;

import ml.mypals.meshcut.MeshCut;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = MeshCut.MODID, value = Dist.CLIENT)
public final class LightsaberKeys {
    public static final KeyMapping RANDOM_SWING = new KeyMapping("key.meshcut.saber_swing",
            GLFW.GLFW_KEY_G, "key.categories.meshcut");
    public static final KeyMapping REPOSITION = new KeyMapping("key.meshcut.saber_reposition",
            GLFW.GLFW_KEY_V, "key.categories.meshcut");
    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(RANDOM_SWING);
        event.register(REPOSITION);
    }
    private LightsaberKeys() {}
}
