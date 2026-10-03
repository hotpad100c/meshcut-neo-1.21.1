package ml.mypals.meshcut.client;

import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.cut.SaberControl;
import ml.mypals.meshcut.cut.SaberTranslation;
import ml.mypals.meshcut.cut.SaberGesture;
import net.neoforged.neoforge.client.event.InputEvent;
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
    private static float handX, handY;

    public static SaberControl.Turn routeTurn(LocalPlayer player, double yawDelta, double pitchDelta) {
        if (!LightsaberItem.isActive(player)) {
            clear();
            return new SaberControl.Turn(yawDelta, pitchDelta);
        }
        ensureOwner(player);
        if (Minecraft.getInstance().options.keyAttack.isDown()) {
            handX = (float) Math.clamp(handX + yawDelta * 0.012, -SaberTranslation.LIMIT_X, SaberTranslation.LIMIT_X);
            handY = (float) Math.clamp(handY - pitchDelta * 0.012, -SaberTranslation.LIMIT_Y, SaberTranslation.LIMIT_Y);
            LightsaberMotion.setHandTarget(player, handX, handY);
            return new SaberControl.Turn(0, 0);
        }
        SaberControl.Turn turn = control.move(yawDelta * 1.5, pitchDelta * 1.5, player.getXRot());
        LightsaberMotion.setOffsets(player, control.yaw(), control.pitch());
        return new SaberControl.Turn(turn.yaw() / 1.5, turn.pitch() / 1.5);
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Pre event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !LightsaberItem.isActive(player)) {
            clear();
            return;
        }
        ensureOwner(player);
        if (Minecraft.getInstance().screen != null) {
            while (LightsaberKeys.RANDOM_SWING.consumeClick()) {}
            return;
        }
        while (LightsaberKeys.RANDOM_SWING.consumeClick()) {
            int preset = player.getRandom().nextInt(SaberGesture.COUNT), seed = player.getRandom().nextInt();
            if (LightsaberMotion.startGesture(player, preset, seed))
                PacketDistributor.sendToServer(new LightsaberNet.Gesture(preset, seed));
        }
        // At most one packet per client tick, including while turning the camera at the free-range boundary.
        PacketDistributor.sendToServer(new LightsaberNet.Input(control.yaw(), control.pitch(), handX, handY));
    }

    private static void ensureOwner(LocalPlayer player) {
        if (owner != player || control == null || session != LightsaberMotion.session(player)) {
            owner = player;
            control = new SaberControl();
            handX = handY = 0;
            LightsaberMotion.setOffsets(player, 0, 0);
            session = LightsaberMotion.session(player);
        }
    }

    private static void clear() {
        owner = null;
        control = null;
        session = null;
        handX = handY = 0;
        while (LightsaberKeys.RANDOM_SWING.consumeClick()) {}
    }

    @SubscribeEvent
    public static void suppressAttack(InputEvent.InteractionKeyMappingTriggered event) {
        var player = Minecraft.getInstance().player;
        if (event.isAttack() && player != null && LightsaberItem.isActive(player)) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }

    private LightsaberControls() {}
}
