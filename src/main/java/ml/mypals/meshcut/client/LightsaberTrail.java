package ml.mypals.meshcut.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.cut.Blade;
import ml.mypals.meshcut.item.LightsaberItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@EventBusSubscriber(modid = MeshCut.MODID, value = Dist.CLIENT)
public final class LightsaberTrail {
    private static final double LIFETIME = 3;
    private static final double MIN_SAMPLE_MOVEMENT = 0.12;
    private static final long MAX_GAP_TICKS = 3;
    private static final float[] BASE_COLOR = {0.15f, 0.55f, 1f};
    private static final float[] TIP_COLOR = {0.8f, 0.95f, 1f};
    private static final float MAX_ALPHA = 0.55f;
    private static final float GLOW_INTENSITY = 0.1f;

    private record Sample(double time, Blade blade) {}

    private static final Map<UUID, ArrayDeque<Sample>> TRAILS = new HashMap<>();
    @Nullable private static ClientLevel trailLevel;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != trailLevel) {
            TRAILS.clear();
            trailLevel = level;
        }
        if (level == null) {
            TRAILS.clear();
            return;
        }
        long now = level.getGameTime();
        for (ArrayDeque<Sample> trail : TRAILS.values()) {
            while (!trail.isEmpty() && now - trail.peekFirst().time() > LIFETIME) {
                trail.pollFirst();
            }
        }
        TRAILS.entrySet().removeIf(e -> e.getValue().isEmpty() && level.getPlayerByUUID(e.getKey()) == null);
    }

    /** Called by the blade renderer so transparent ribbons are flushed before the bright blade. */
    static void renderWorld(RenderLevelStageEvent event, Map<UUID, Blade> blades) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        double time = level.getGameTime() + partialTick;
        blades.forEach((id, blade) -> recordBlade(id, blade, time));
        Vec3 camera = event.getCamera().getPosition();
        Matrix4f matrix = event.getPoseStack().last().pose();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        drawTrails(buffers.getBuffer(LightsaberRenderTypes.TRAIL), minecraft, blades, matrix, camera, time, 1f);
        buffers.endBatch(LightsaberRenderTypes.TRAIL);
        if (Bloom.usable()) {
            drawTrails(Bloom.consumer(BloomRenderTypes.QUADS), minecraft, blades, matrix, camera, time, GLOW_INTENSITY);
            Bloom.flush(BloomRenderTypes.QUADS);
        }
    }

    private static void drawTrails(VertexConsumer consumer, Minecraft minecraft, Map<UUID, Blade> blades,
                                   Matrix4f matrix, Vec3 camera, double time, float intensity) {
        for (Map.Entry<UUID, ArrayDeque<Sample>> entry : TRAILS.entrySet()) {
            if (minecraft.player != null && minecraft.options.getCameraType().isFirstPerson()
                    && !minecraft.options.hideGui && LightsaberItem.isActive(minecraft.player)
                    && entry.getKey().equals(minecraft.player.getUUID())) continue;
            renderTrail(consumer, matrix, camera, entry.getValue(), blades.get(entry.getKey()), time, intensity);
        }
    }

    /** The local trail is drawn with the same world projection and hilt depth as its blade. */
    static void renderFirstPerson(Player player, Blade blade, float partialTick, Matrix4f matrix, Vec3 camera,
                                  MultiBufferSource.BufferSource buffers) {
        double time = player.level().getGameTime() + partialTick;
        recordBlade(player.getUUID(), blade, time);
        renderTrail(buffers.getBuffer(LightsaberRenderTypes.TRAIL), matrix, camera,
                TRAILS.get(player.getUUID()), blade, time);
        buffers.endBatch(LightsaberRenderTypes.TRAIL);
        if (Bloom.usable()) {
            renderTrail(Bloom.consumer(BloomRenderTypes.QUADS), matrix, camera, TRAILS.get(player.getUUID()), blade, time,
                    GLOW_INTENSITY);
            Bloom.flush(BloomRenderTypes.QUADS);
        }
    }

    private static void recordBlade(UUID id, Blade blade, double time) {
        ArrayDeque<Sample> trail = TRAILS.computeIfAbsent(id, ignored -> new ArrayDeque<>());
        Sample last = trail.peekLast();
        if (last == null || time - last.time() > MAX_GAP_TICKS
                || last.blade().tip().distanceTo(blade.tip()) >= MIN_SAMPLE_MOVEMENT
                || last.blade().base().distanceTo(blade.base()) >= MIN_SAMPLE_MOVEMENT) {
            trail.addLast(new Sample(time, blade));
        }
    }

    private static void renderTrail(VertexConsumer consumer, Matrix4f matrix, Vec3 camera,
                                    ArrayDeque<Sample> trail, @Nullable Blade current, double time) {
        renderTrail(consumer, matrix, camera, trail, current, time, 1f);
    }

    /** @param intensity scales the opacity: the glow of the trail is faint, the trail itself translucent */
    private static void renderTrail(VertexConsumer consumer, Matrix4f matrix, Vec3 camera,
                                    ArrayDeque<Sample> trail, @Nullable Blade current, double time, float intensity) {
        Sample previous = null;
        for (Sample sample : trail) {
            if (previous != null && sample.time() - previous.time() <= MAX_GAP_TICKS) {
                float alpha = (float) (1 - (time - sample.time()) / LIFETIME);
                if (alpha > 0) {
                    ribbon(consumer, matrix, camera, previous.blade(), sample.blade(), alpha * alpha * MAX_ALPHA * intensity);
                }
            }
            previous = sample;
        }
        if (previous != null && current != null && time - previous.time() <= MAX_GAP_TICKS
                && (current.tip().distanceToSqr(previous.blade().tip()) > 1e-8
                || current.base().distanceToSqr(previous.blade().base()) > 1e-8)) {
            ribbon(consumer, matrix, camera, previous.blade(), current, MAX_ALPHA * intensity);
        }
    }

    private static void ribbon(VertexConsumer c, Matrix4f m, Vec3 camera, Blade a, Blade b, float alpha) {
        Vec3 a0 = a.base().subtract(camera), a1 = a.tip().subtract(camera);
        Vec3 b0 = b.base().subtract(camera), b1 = b.tip().subtract(camera);
        vertex(c, m, a0, BASE_COLOR, alpha * 0.4f);
        vertex(c, m, a1, TIP_COLOR, alpha);
        vertex(c, m, b1, TIP_COLOR, alpha);
        vertex(c, m, b0, BASE_COLOR, alpha * 0.4f);
    }

    private static void vertex(VertexConsumer c, Matrix4f m, Vec3 p, float[] rgb, float alpha) {
        c.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setColor(rgb[0], rgb[1], rgb[2], alpha);
    }

    private LightsaberTrail() {}
}
