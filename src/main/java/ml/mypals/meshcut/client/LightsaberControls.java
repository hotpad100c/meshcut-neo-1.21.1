package ml.mypals.meshcut.client;

import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.cut.SaberControl;
import ml.mypals.meshcut.item.LightsaberItem;
import ml.mypals.meshcut.item.LightsaberMotion;
import ml.mypals.meshcut.network.LightsaberNet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = MeshCut.MODID, value = Dist.CLIENT)
public final class LightsaberControls {
    private static LocalPlayer owner;
    private static SaberControl control;
    private static Object session;

    public static SaberControl.Turn routeTurn(LocalPlayer player, double yawDelta, double pitchDelta) {
        if (!LightsaberItem.isActive(player)) {
            clear();
            return new SaberControl.Turn(yawDelta, pitchDelta);
        }
        ensureOwner(player);
        SaberControl.Turn turn = control.move(yawDelta, pitchDelta, player.getXRot());
        LightsaberMotion.setOffsets(player, control.yaw(), control.pitch());
        return turn;
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Pre event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !LightsaberItem.isActive(player)) {
            clear();
            return;
        }
        ensureOwner(player);
        // At most one packet per client tick, including while turning the camera at the free-range boundary.
        PacketDistributor.sendToServer(new LightsaberNet.Input(control.yaw(), control.pitch()));
    }

    private static void ensureOwner(LocalPlayer player) {
        if (owner != player || control == null || session != LightsaberMotion.session(player)) {
            owner = player;
            control = new SaberControl();
            LightsaberMotion.setOffsets(player, 0, 0);
            session = LightsaberMotion.session(player);
        }
    }

    private static void clear() {
        owner = null;
        control = null;
        session = null;
    }

    private LightsaberControls() {}
}
