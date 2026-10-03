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

/**
 * Two jobs, both for cut blocks only:
 * <ul>
 *   <li>Draws blocks whose imitated block is itself drawn by a block entity renderer (chests, beds, signs, ...):
 *       their geometry uses entity textures that can't go into the chunk mesh. Every other mimic is part of the
 *       chunk mesh via {@code MimicBakedModel}.</li>
 *   <li>Draws the glowing lava over the cut faces of every mimic. That layer is temporary: it fades out over
 *       {@link #GLOW_TICKS} ticks after the cut, after which this renderer draws nothing for the block.</li>
 * </ul>
 */
public class MimicRenderer implements BlockEntityRenderer<MimicBlockEntity> {
    /** How long the glow on a fresh cut lasts. */
    private static final double GLOW_TICKS = 600;

    /** What a block draws. Until a worker has cut it up, it draws its model whole. */
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
        BlockState state = be.getTargetState();
        PoseStack.Pose pose = poseStack.last();

        if (be.getAppearance() != null || ModelCapturer.hasBlockEntityRenderer(state)) {
            CacheEntry entry = cache.get(be);
            if (entry == null || entry.version != be.getVersion()) {
                entry = build(be);
                cache.put(be, entry);
            }
            for (var layer : entry.byType().entrySet()) {
                VertexConsumer consumer = buffer.getBuffer(layer.getKey());
                for (MeshPoly poly : layer.getValue()) {
                    emit(consumer, pose, poly, tint(be, state, poly.tintIndex()), packedLight, 1f);
                }
            }
        }

        float fade = glowFade(be, partialTick);
        if (fade > 0f) {
            VertexConsumer consumer = buffer.getBuffer(Sheets.translucentCullBlockSheet());
            // cooling: white-hot at first, then orange, then a dull red as the lava dies
            int heat = 0xFF0000 | Math.round(55 + 200 * fade) << 8 | Math.round(25 + 230 * fade * fade);
            boolean bloom = Bloom.usable();
            org.joml.Vector3f at = new org.joml.Vector3f();
            for (MeshPoly poly : MimicMesh.glowPolys(be.renderData())) {
                emit(consumer, pose, poly, heat, packedLight, fade);
                if (bloom) { // the lava also glows: queued, to be drawn into the glow buffer later in the frame
                    float[] v = poly.verts();
                    for (int i = 1; i + 1 < poly.vertexCount(); i++) {
                        for (int k : new int[]{0, i, i + 1}) {
                            pose.pose().transformPosition(v[k * MeshPoly.STRIDE], v[k * MeshPoly.STRIDE + 1],
                                    v[k * MeshPoly.STRIDE + 2], at);
                            Bloom.queue(at.x, at.y, at.z, 1f, 0.42f * fade, 0.1f * fade, fade * 0.5f);
                        }
                    }
                }
            }
        }
    }

    /** 1 right after the cut, easing down to 0 over {@link #GLOW_TICKS}; 0 once it is over or if there is no glow. */
    private static float glowFade(MimicBlockEntity be, float partialTick) {
        if (be.getCapMode() != CapMode.LAVA || be.getGlowStart() == MimicBlockEntity.NEVER || be.getLevel() == null) {
            return 0f;
        }
        double age = be.getLevel().getGameTime() + partialTick - be.getGlowStart();
        if (age < 0) {
            return 1f;
        }
        if (age >= GLOW_TICKS) {
            return 0f;
        }
        double remaining = 1 - age / GLOW_TICKS;
        return (float) (remaining * remaining);
    }

    private static CacheEntry build(MimicBlockEntity be) {
        // recording the model needs the render thread; cutting it up does not
        var captured = be.getAppearance() != null ? MimicMesh.entityPart(be.getAppearance())
                : ModelCapturer.capture(be.getTargetState(), be.getTargetBeTag());
        CacheEntry entry = new CacheEntry(be.getVersion(), captured.byType());
        var data = be.renderData();
        MimicMesh.async(() -> {
            // cut exactly as the chunk mesh cuts other blocks: pieces, slits, cut-out parts and their walls
            entry.cut = MimicMesh.cutModel(captured.byType(), captured.opaque(), captured.capUv(), null, data,
                    Sheets.cutoutBlockSheet());
        });
        return entry;
    }

    private static int tint(MimicBlockEntity be, BlockState state, int tintIndex) {
        return tintIndex < 0 || be.getLevel() == null ? 0xFFFFFF
                : MimicTint.color(state, be.getLevel(), be.getBlockPos(), tintIndex);
    }

    /** Fan-triangulates the convex polygon; triangles are emitted as degenerate quads. */
    private static void emit(VertexConsumer c, PoseStack.Pose pose, MeshPoly poly, int rgb, int light, float alphaScale) {
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
            vertex(c, pose, poly, v, i + 1, r, g, b, a, light);
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
