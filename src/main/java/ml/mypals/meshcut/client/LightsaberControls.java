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
    @SubscribeEvent
    public static void scrollLength(InputEvent.MouseScrollingEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (minecraft.screen != null || player == null || !net.minecraft.client.gui.screens.Screen.hasControlDown()
                || !(player.getMainHandItem().getItem() instanceof LightsaberItem) || event.getScrollDeltaY() == 0) return;
        float length = Math.clamp(LightsaberItem.lengthScale(player.getMainHandItem())
                + (float) event.getScrollDeltaY() * 0.1f, 0.25f, 2f);
        LightsaberItem.setLengthScale(player.getMainHandItem(), length);
        PacketDistributor.sendToServer(new LightsaberNet.Length(length));
        player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.meshcut.length",
                Math.round(length * 100)), true);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void toggleBlade(InputEvent.MouseButton.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getButton() != org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT || mc.screen != null
                || mc.player == null || !(mc.player.getMainHandItem().getItem() instanceof LightsaberItem item)) return;
        // Hold mode uses vanilla use-key state and its release packet.
        if (!ml.mypals.meshcut.MeshCutClientConfig.toggleSaber()) return;
        event.setCanceled(true);
        if (event.getAction() == org.lwjgl.glfw.GLFW.GLFW_PRESS) {
            item.use(mc.level, mc.player, net.minecraft.world.InteractionHand.MAIN_HAND);
            PacketDistributor.sendToServer(new LightsaberNet.Toggle());
        }
    }
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
        if (LightsaberKeys.REPOSITION.isDown()) {
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
        boolean swingRequested = false;
        while (LightsaberKeys.RANDOM_SWING.consumeClick()) swingRequested = true;
        if (swingRequested || LightsaberKeys.RANDOM_SWING.isDown()) {
            int preset = player.getRandom().nextInt(SaberGesture.COUNT), seed = player.getRandom().nextInt();
            if (LightsaberMotion.startGesture(player, preset, seed))
                PacketDistributor.sendToServer(new LightsaberNet.Gesture(preset, seed));
        }
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

    /** After the tick's aim is worked out: the blade as drawn, which is what the server is to cut along. */
    @SubscribeEvent
    public static void sendBlade(ClientTickEvent.Post event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !LightsaberItem.isActive(player)) return;
        var blade = ml.mypals.meshcut.cut.Blade.of(player, 1f);
        var at = player.position();
        var tip = blade.tip().subtract(blade.base());
        PacketDistributor.sendToServer(new LightsaberNet.Swing((float) (blade.base().x - at.x), (float) (blade.base().y - at.y),
                (float) (blade.base().z - at.z), (float) tip.x, (float) tip.y, (float) tip.z));
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
