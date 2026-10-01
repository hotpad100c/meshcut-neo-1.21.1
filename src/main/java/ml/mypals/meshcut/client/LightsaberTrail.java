package ml.mypals.meshcut.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.cut.Blade;
import ml.mypals.meshcut.item.LightsaberItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The swish of a swinging lightsaber: a ribbon through the blade's positions of the last moments, fading out.
 * It shows the surface that is being swept, and so what is about to be cut.
 */
@EventBusSubscriber(modid = MeshCut.MODID, value = Dist.CLIENT)
public final class LightsaberTrail {
    /** Ticks a part of the trail stays visible. */
    private static final double LIFETIME = 40;
    /** Tip movement per tick below which the blade counts as standing still and adds nothing to the trail. */
    private static final double MIN_TIP_MOVEMENT = 0.12;
    /** Samples further apart in time than this are separate strokes and are not joined. */
    private static final long MAX_GAP_TICKS = 3;
    /** Colours (rgb) at the blade's base and at its tip. */
    private static final float[] BASE_COLOR = {0.15f, 0.55f, 1f};
    private static final float[] TIP_COLOR = {0.8f, 0.95f, 1f};
    private static final float MAX_ALPHA = 0.55f;

    private record Sample(long time, Blade blade) {}

    private static final Map<UUID, ArrayDeque<Sample>> TRAILS = new HashMap<>();

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            TRAILS.clear();
            return;
        }
        long now = level.getGameTime();
        for (Player player : level.players()) {
            ArrayDeque<Sample> trail = TRAILS.computeIfAbsent(player.getUUID(), id -> new ArrayDeque<>());
            while (!trail.isEmpty() && now - trail.peekFirst().time() > LIFETIME) {
                trail.pollFirst();
            }
            if (LightsaberItem.isActive(player)) {
                Blade blade = Blade.of(player, 1f);
                Sample last = trail.peekLast();
                if (last == null || last.blade().tip().distanceTo(blade.tip()) >= MIN_TIP_MOVEMENT) {
                    trail.addLast(new Sample(now, blade));
                }
            }
        }
        TRAILS.entrySet().removeIf(e -> e.getValue().isEmpty() && level.getPlayerByUUID(e.getKey()) == null);
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || TRAILS.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        double time = level.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(true);
        Vec3 camera = event.getCamera().getPosition();
        Matrix4f matrix = event.getPoseStack().last().pose();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer consumer = null;

        for (ArrayDeque<Sample> trail : TRAILS.values()) {
            Sample previous = null;
            for (Sample sample : trail) {
                if (previous != null && sample.time() - previous.time() <= MAX_GAP_TICKS) {
                    float alpha = (float) (1 - (time - sample.time()) / LIFETIME);
                    if (alpha > 0) {
                        if (consumer == null) {
                            consumer = buffers.getBuffer(RenderType.lightning());
                        }
                        ribbon(consumer, matrix, camera, previous.blade(), sample.blade(), alpha * alpha * MAX_ALPHA);
                    }
                }
                previous = sample;
            }
        }
        if (consumer != null) {
            buffers.endBatch(RenderType.lightning());
        }
    }

    /** The surface between two blade positions, brighter towards the tip. Drawn double-sided. */
    private static void ribbon(VertexConsumer c, Matrix4f m, Vec3 camera, Blade a, Blade b, float alpha) {
        Vec3 a0 = a.base().subtract(camera), a1 = a.tip().subtract(camera);
        Vec3 b0 = b.base().subtract(camera), b1 = b.tip().subtract(camera);
        vertex(c, m, a0, BASE_COLOR, alpha * 0.4f);
        vertex(c, m, a1, TIP_COLOR, alpha);
        vertex(c, m, b1, TIP_COLOR, alpha);
        vertex(c, m, b0, BASE_COLOR, alpha * 0.4f);
        vertex(c, m, b0, BASE_COLOR, alpha * 0.4f);
        vertex(c, m, b1, TIP_COLOR, alpha);
        vertex(c, m, a1, TIP_COLOR, alpha);
        vertex(c, m, a0, BASE_COLOR, alpha * 0.4f);
    }

    private static void vertex(VertexConsumer c, Matrix4f m, Vec3 p, float[] rgb, float alpha) {
        c.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setColor(rgb[0], rgb[1], rgb[2], alpha);
    }

    private LightsaberTrail() {}
}
