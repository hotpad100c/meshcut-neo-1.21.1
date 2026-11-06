package ml.mypals.meshcut.client;

import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.item.LaserGunItem;
import ml.mypals.meshcut.network.LaserNet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayDeque;

@EventBusSubscriber(modid = MeshCut.MODID, value = Dist.CLIENT)
public final class LaserVisuals {
    private record Pulse(LaserNet.Shot shot, double time) {}
    private static final ArrayDeque<Pulse> pulses = new ArrayDeque<>();
    private static Object world;

    @SubscribeEvent
    public static void scroll(InputEvent.MouseScrollingEvent event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null || !Screen.hasControlDown()
                || !(mc.player.getMainHandItem().getItem() instanceof LaserGunItem) || event.getScrollDeltaY() == 0) return;
        var stack = mc.player.getMainHandItem();
        int radius = Math.clamp(LaserGunItem.radius(stack) + (event.getScrollDeltaY() > 0 ? 1 : -1), 1, 4);
        LaserGunItem.setRadius(stack, radius);
        PacketDistributor.sendToServer(new LaserNet.Radius(radius));
        mc.player.displayClientMessage(Component.translatable("message.meshcut.laser_radius", radius), true);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var level = Minecraft.getInstance().level;
        if (level != world) { pulses.clear(); world = level; }
        if (level != null) pulses.removeIf(p -> level.getGameTime() - p.time > 4);
    }

    public static void accept(LaserNet.Shot shot) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || !finite(shot.start()) || !finite(shot.end())
                || shot.start().distanceToSqr(shot.end()) > 66 * 66) return;
        if (world != mc.level) { pulses.clear(); world = mc.level; }
        if (pulses.size() >= 128) pulses.removeFirst();
        pulses.addLast(new Pulse(shot, mc.level.getGameTime() + mc.getTimer().getGameTimeDeltaPartialTick(true)));
        LaserGunHands.acceptShot(shot.shooter());
    }

    private static boolean finite(Vec3 v) { return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z); }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null || world != mc.level || pulses.isEmpty()) return;
        double now = mc.level.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(true);
        var buffers = mc.renderBuffers().bufferSource();
        var core = buffers.getBuffer(LightsaberRenderTypes.BEAM);
        var glow = Bloom.usable() ? Bloom.consumer(BloomRenderTypes.QUADS) : core;
        var matrix = event.getPoseStack().last().pose();
        var camera = event.getCamera().getPosition();
        for (Pulse pulse : pulses) {
            double age = Math.max(0, now - pulse.time);
            if (age >= 3) continue;
            var shot = pulse.shot;
            Vec3 start = LaserGunHands.muzzle(shot.shooter(), event.getPartialTick().getGameTimeDeltaPartialTick(true), shot.start());
            Vec3 delta = shot.end().subtract(start);
            double head = Math.min(1, 0.15 + age * 1.5);
            double tail = Math.max(0, Math.min(0.98, (age - 0.65) * 1.5));
            Vec3 a = start.add(delta.scale(tail)).subtract(camera);
            Vec3 b = start.add(delta.scale(head)).subtract(camera);
            float alpha = (float) Math.clamp(3 - age, 0, 1);
            float r = ((shot.color() >> 16) & 255) / 255f, g = ((shot.color() >> 8) & 255) / 255f, bl = (shot.color() & 255) / 255f;
            double width = 0.009 * Math.sqrt(Math.clamp(shot.radius(), 1, 4));
            if (a.distanceToSqr(b) < 1e-8) continue;
            LightsaberRenderer.prism(core, matrix, a, b, width, new float[]{1, 1, 1, alpha}, 1);
            LightsaberRenderer.prism(glow, matrix, a, b, width * 3, new float[]{r, g, bl, 0.55f * alpha}, 1);
            LightsaberRenderer.prism(glow, matrix, a, b, width * 6, new float[]{r, g, bl, 0.16f * alpha}, 1);
        }
        buffers.endBatch(LightsaberRenderTypes.BEAM);
        if (Bloom.usable()) Bloom.flush(BloomRenderTypes.QUADS);
    }

    private LaserVisuals() {}
}
