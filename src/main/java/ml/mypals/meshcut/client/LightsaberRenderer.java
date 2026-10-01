package ml.mypals.meshcut.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.cut.Blade;
import ml.mypals.meshcut.item.LightsaberItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/** Draws the blade of every active lightsaber held in a main hand: a bright core inside two softer glows. */
@EventBusSubscriber(modid = MeshCut.MODID, value = Dist.CLIENT)
public final class LightsaberRenderer {
    private static final float[] CORE = {1f, 1f, 1f, 1f};
    private static final float[] GLOW = {0.25f, 0.8f, 1f, 0.45f};
    private static final float[] HALO = {0.2f, 0.6f, 1f, 0.18f};

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
        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer consumer = buffers.getBuffer(RenderType.lightning());
        boolean drew = false;
        for (Player player : minecraft.level.players()) {
            var stack = player.getMainHandItem();
            if (!(stack.getItem() instanceof LightsaberItem) || !LightsaberItem.isActive(stack)) {
                continue;
            }
            Blade blade = Blade.of(player, partialTick);
            Matrix4f matrix = poseStack.last().pose();
            Vec3 base = blade.base().subtract(camera);
            Vec3 tip = blade.tip().subtract(camera);
            prism(consumer, matrix, base, tip, 0.16, HALO);
            prism(consumer, matrix, base, tip, 0.09, GLOW);
            prism(consumer, matrix, base, tip, 0.03, CORE);
            drew = true;
        }
        if (drew) {
            buffers.endBatch(RenderType.lightning());
        }
    }

    /** A square prism of half-width {@code r} around the segment, drawn double-sided. */
    private static void prism(VertexConsumer c, Matrix4f m, Vec3 a, Vec3 b, double r, float[] color) {
        Vec3 dir = b.subtract(a).normalize();
        Vec3 helper = Math.abs(dir.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 u = dir.cross(helper).normalize().scale(r);
        Vec3 v = dir.cross(u).normalize().scale(r);
        Vec3[] ca = {a.add(u).add(v), a.subtract(u).add(v), a.subtract(u).subtract(v), a.add(u).subtract(v)};
        Vec3[] cb = {b.add(u).add(v), b.subtract(u).add(v), b.subtract(u).subtract(v), b.add(u).subtract(v)};
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            quad(c, m, ca[i], ca[j], cb[j], cb[i], color);
        }
        quad(c, m, cb[0], cb[1], cb[2], cb[3], color);
    }

    private static void quad(VertexConsumer c, Matrix4f m, Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, float[] color) {
        vertex(c, m, p0, color);
        vertex(c, m, p1, color);
        vertex(c, m, p2, color);
        vertex(c, m, p3, color);
        vertex(c, m, p3, color);
        vertex(c, m, p2, color);
        vertex(c, m, p1, color);
        vertex(c, m, p0, color);
    }

    private static void vertex(VertexConsumer c, Matrix4f m, Vec3 p, float[] color) {
        c.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setColor(color[0], color[1], color[2], color[3]);
    }

    private LightsaberRenderer() {}
}
