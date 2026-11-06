package ml.mypals.meshcut.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import ml.mypals.meshcut.block.MimicBlockEntity;
import ml.mypals.meshcut.client.capture.ModelCapturer;
import ml.mypals.meshcut.client.render.MimicMesh;
import ml.mypals.meshcut.client.render.MimicTint;
import ml.mypals.meshcut.cut.CapMode;
import ml.mypals.meshcut.geom.CapBuilder;
import ml.mypals.meshcut.geom.MeshClipper;
import ml.mypals.meshcut.geom.MeshPoly;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public class MimicRenderer implements BlockEntityRenderer<MimicBlockEntity> {
    private static final double GLOW_TICKS = MimicBlockEntity.GLOW_TICKS;

    private static final class CacheEntry {
        final int version;
        final Map<RenderType, List<MeshPoly>> whole;
        volatile Map<RenderType, List<MeshPoly>> cut;

        CacheEntry(int version, Map<RenderType, List<MeshPoly>> whole) {
            this.version = version;
            this.whole = whole;
        }

        Map<RenderType, List<MeshPoly>> byType() {
            Map<RenderType, List<MeshPoly>> done = cut;
            return done != null ? done : whole;
        }
    }

    private final Map<MimicBlockEntity, CacheEntry> cache = new WeakHashMap<>();

    public MimicRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(MimicBlockEntity be, float partialTick, PoseStack poseStack, MultiBufferSource buffer,
                       int packedLight, int packedOverlay) {
        if (be.isPending()) {
            return;
        }
        BlockState state = be.getTargetState();
        PoseStack.Pose pose = poseStack.last();

        if (be.getAppearance() != null || ModelCapturer.hasBlockEntityRenderer(state)) {
            CacheEntry entry = cache.get(be);
            if (entry == null || entry.version != be.getVersion()) {
                entry = build(be);
                cache.put(be, entry);
            }
            Map<RenderType, List<MeshPoly>> layers = entry.byType();
            List<RenderType> immediate = null;
            if (entry.cut != null && MimicVbos.enabled()) {
                immediate = MimicVbos.draw(be, entry.cut, layers, pose, packedLight, index -> tint(be, state, index));
            }
            for (var layer : layers.entrySet()) {
                if (immediate != null && !immediate.contains(layer.getKey())) {
                    continue;
                }
                VertexConsumer consumer = buffer.getBuffer(layer.getKey());
                boolean triangles = layer.getKey().mode() == com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES;
                for (MeshPoly poly : layer.getValue()) {
                    emit(consumer, pose, poly, tint(be, state, poly.tintIndex()), packedLight, 1f, triangles);
                }
            }
        }

        float fade = glowFade(be, partialTick);
        if (fade > 0f) {
            int heat = 0xFF0000 | Math.round(55 + 200 * fade) << 8 | Math.round(25 + 230 * fade * fade);
            boolean bloom = Bloom.usable();
            org.joml.Vector3f at = new org.joml.Vector3f();
            int[] corner = new int[3];
            List<MeshPoly> glow = MimicMesh.glowPolys(be.renderData(), be.getBlockPos().asLong());
            VertexConsumer consumer = buffer.getBuffer(Sheets.translucentCullBlockSheet());
            for (MeshPoly poly : glow) {
                emit(consumer, pose, poly, heat, packedLight, fade, false);
                if (bloom) {
                    float[] v = poly.verts();
                    for (int i = 1; i + 1 < poly.vertexCount(); i++) {
                        corner[0] = 0;
                        corner[1] = i;
                        corner[2] = i + 1;
                        for (int k : corner) {
                            pose.pose().transformPosition(v[k * MeshPoly.STRIDE], v[k * MeshPoly.STRIDE + 1],
                                    v[k * MeshPoly.STRIDE + 2], at);
                            Bloom.queue(at.x, at.y, at.z, 1f, 0.42f * fade, 0.1f * fade, fade * 0.5f);
                        }
                    }
                }
            }
        }
    }

    private static float glowFade(MimicBlockEntity be, float partialTick) {
        long time = net.minecraft.client.Minecraft.getInstance().level != null
                ? net.minecraft.client.Minecraft.getInstance().level.getGameTime()
                : (be.getLevel() != null ? be.getLevel().getGameTime() : 0);
        return be.glowFade(partialTick, time);
    }

    private static CacheEntry build(MimicBlockEntity be) {
        var captured = be.getAppearance() != null ? MimicMesh.entityPart(be.getAppearance())
                : ModelCapturer.capture(be.getTargetState(), be.getTargetBeTag());
        CacheEntry entry = new CacheEntry(be.getVersion(), captured.byType());
        var data = be.renderData();
        MimicMesh.async(() -> {
            entry.cut = MimicMesh.cutModel(captured.byType(), captured.opaque(), captured.capUv(), null, data,
                    Sheets.cutoutBlockSheet());
        });
        return entry;
    }

    private static int tint(MimicBlockEntity be, BlockState state, int tintIndex) {
        return tintIndex < 0 || be.getLevel() == null ? 0xFFFFFF
                : MimicTint.color(state, be.getLevel(), be.getBlockPos(), tintIndex);
    }

    static void emit(VertexConsumer c, PoseStack.Pose pose, MeshPoly poly, int rgb, int light, float alphaScale,
                             boolean triangles) {
        int argb = poly.argb();
        float r = (rgb >> 16 & 255) / 255f * (argb >> 16 & 255) / 255f;
        float g = (rgb >> 8 & 255) / 255f * (argb >> 8 & 255) / 255f;
        float b = (rgb & 255) / 255f * (argb & 255) / 255f;
        float a = (argb >>> 24) / 255f * alphaScale;
        if (poly.lightEmission() > 0) {
            light = LightTexture.pack(Math.max(LightTexture.block(light), poly.lightEmission()),
                    Math.max(LightTexture.sky(light), poly.lightEmission()));
        }
        float[] v = poly.verts();
        int n = poly.vertexCount();
        for (int i = 1; i + 1 < n; i++) {
            vertex(c, pose, poly, v, 0, r, g, b, a, light);
            vertex(c, pose, poly, v, i, r, g, b, a, light);
            vertex(c, pose, poly, v, i + 1, r, g, b, a, light);
            if (!triangles) {
                vertex(c, pose, poly, v, i + 1, r, g, b, a, light);
            } else {
                vertex(c, pose, poly, v, 0, r, g, b, a, light);
                vertex(c, pose, poly, v, i + 1, r, g, b, a, light);
                vertex(c, pose, poly, v, i, r, g, b, a, light);
            }
        }
    }

    private static void vertex(VertexConsumer c, PoseStack.Pose pose, MeshPoly poly, float[] v, int idx,
                               float r, float g, float b, float a, int light) {
        int o = idx * MeshPoly.STRIDE;
        c.addVertex(pose, v[o], v[o + 1], v[o + 2])
                .setColor(r, g, b, a)
                .setUv(v[o + 3], v[o + 4])
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, poly.nx(), poly.ny(), poly.nz());
    }
}
