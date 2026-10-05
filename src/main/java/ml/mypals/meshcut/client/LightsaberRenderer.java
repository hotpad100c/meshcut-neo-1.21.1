package ml.mypals.meshcut.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.cut.Blade;
import ml.mypals.meshcut.item.LightsaberItem;
import ml.mypals.meshcut.item.LightsaberMotion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@EventBusSubscriber(modid = MeshCut.MODID, value = Dist.CLIENT)
public final class LightsaberRenderer {
    private static final float[] CORE = {1f, 1f, 1f, 1f};
    private static final float[] GLOW = {0.25f, 0.8f, 1f, 0.45f};
    private static final float[] HALO = {0.2f, 0.6f, 1f, 0.18f};

    private record Palette(float[] glow, float[] halo) {
        static final Palette DEFAULT = new Palette(GLOW, HALO);

        static Palette of(Player player) {
            ItemStack stack = player.getMainHandItem();
            int rgb = stack.getItem() instanceof LightsaberItem ? LightsaberItem.getBladeColor(stack) : -1;
            if (rgb == -1) return DEFAULT;
            float r = ((rgb >> 16) & 0xFF) / 255f, g = ((rgb >> 8) & 0xFF) / 255f, b = (rgb & 0xFF) / 255f;
            return new Palette(new float[]{r, g, b, GLOW[3]}, new float[]{r * 0.8f, g * 0.8f, b * 0.8f, HALO[3]});
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        double time = minecraft.level.getGameTime() + partialTick;
        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        Matrix4f matrix = poseStack.last().pose();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        Map<UUID, Blade> blades = new HashMap<>();
        Map<UUID, Palette> palettes = new HashMap<>();
        for (Player player : minecraft.level.players()) {
            if (!LightsaberMotion.isVisible(player)) {
                continue;
            }
            blades.put(player.getUUID(), Blade.of(player, partialTick));
            palettes.put(player.getUUID(), Palette.of(player));
        }
        if (!Bloom.defersHands() && minecraft.player != null && minecraft.options.getCameraType().isFirstPerson()
                && !minecraft.options.hideGui && LightsaberMotion.isVisible(minecraft.player)) {
            LightsaberHands.render(minecraft.player, blades.get(minecraft.player.getUUID()), partialTick,
                    camera, matrix, buffers);
        }
        if (MotionBlur.usable()) {
            blades.forEach((id, blade) -> MotionBlur.record(id, blade, time));
        }
        RenderType beamType = LightsaberRenderTypes.BEAM;
        VertexConsumer consumer = buffers.getBuffer(beamType);
        for (var entry : blades.entrySet()) {
            Blade blade = entry.getValue();
            drawBlade(consumer, matrix, blade.base().subtract(camera), blade.tip().subtract(camera),
                    time, entry.getKey(), palettes.get(entry.getKey()), !Bloom.usable());
        }
        buffers.endBatch(beamType);
        if (Bloom.usable() && !blades.isEmpty()) { // the same beam again, into the glow buffer
            VertexConsumer glow = Bloom.consumer(BloomRenderTypes.QUADS);
            for (var entry : blades.entrySet()) {
                Blade blade = entry.getValue();
                drawBlade(glow, matrix, blade.base().subtract(camera), blade.tip().subtract(camera),
                        time, entry.getKey(), palettes.get(entry.getKey()), true);
            }
            Bloom.flush(BloomRenderTypes.QUADS);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void renderHands(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER || !Bloom.defersHands()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || !minecraft.options.getCameraType().isFirstPerson()
                || minecraft.options.hideGui || !LightsaberMotion.isVisible(minecraft.player)) {
            return;
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        LightsaberHands.render(minecraft.player, Blade.of(minecraft.player, partialTick), partialTick,
                event.getCamera().getPosition(), event.getPoseStack().last().pose(), minecraft.renderBuffers().bufferSource());
    }

    private static void drawBlade(VertexConsumer consumer, Matrix4f matrix, Vec3 base, Vec3 tip,
                                  double time, UUID owner, Palette palette, boolean includeHalo) {
        if (base.distanceToSqr(tip) < 1e-4) return;
        double phase = (owner.hashCode() & 0xffff) * (Math.PI * 2 / 65536);
        double pulse = Math.sin(time * 1.7 + phase) * 0.035 + Math.sin(time * 2.9 + phase) * 0.015;
        Vec3 direction = tip.subtract(base).normalize();
        Vec3 helper = Math.abs(direction.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 sideways = direction.cross(helper).normalize();
        Vec3 upwards = direction.cross(sideways);
        Vec3 shimmer = sideways.scale(Math.sin(time * 1.3 + phase) * 0.003)
                .add(upwards.scale(Math.sin(time * 1.9 + phase) * 0.003));
        if (includeHalo) {
            prism(consumer, matrix, base, tip.add(shimmer), 0.040 * (1 + pulse), palette.halo(), (float) (1 + pulse));
            prism(consumer, matrix, base, tip.add(shimmer.scale(0.5)), 0.021 * (1 + pulse), palette.glow(), (float) (1 + pulse));
        }
        prism(consumer, matrix, base, tip, 0.008 * (1 + pulse * 0.4), CORE, 1f);
    }

    static void prism(VertexConsumer c, Matrix4f m, Vec3 a, Vec3 b, double r, float[] color, float intensity) {
        Vec3 dir = b.subtract(a).normalize();
        Vec3 helper = Math.abs(dir.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 u = dir.cross(helper).normalize().scale(r);
        Vec3 v = dir.cross(u).normalize().scale(r);
        Vec3[] ca = {a.add(u).add(v), a.subtract(u).add(v), a.subtract(u).subtract(v), a.add(u).subtract(v)};
        Vec3[] cb = {b.add(u).add(v), b.subtract(u).add(v), b.subtract(u).subtract(v), b.add(u).subtract(v)};
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            quad(c, m, ca[i], ca[j], cb[j], cb[i], color, intensity);
        }
        quad(c, m, cb[0], cb[1], cb[2], cb[3], color, intensity);
    }

    private static void quad(VertexConsumer c, Matrix4f m, Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3,
                             float[] color, float intensity) {
        vertex(c, m, p0, color, intensity);
        vertex(c, m, p1, color, intensity);
        vertex(c, m, p2, color, intensity);
        vertex(c, m, p3, color, intensity);
    }

    private static void vertex(VertexConsumer c, Matrix4f m, Vec3 p, float[] color, float intensity) {
        c.addVertex(m, (float) p.x, (float) p.y, (float) p.z)
                .setColor(color[0], color[1], color[2], color[3] * intensity);
    }

    private LightsaberRenderer() {}
}
