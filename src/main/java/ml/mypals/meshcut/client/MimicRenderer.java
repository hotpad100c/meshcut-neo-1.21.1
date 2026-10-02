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
    private static final double GLOW_TICKS = 160;

    private record CacheEntry(int version, Map<RenderType, List<MeshPoly>> byType) {}

    private final Map<MimicBlockEntity, CacheEntry> cache = new WeakHashMap<>();

    public MimicRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(MimicBlockEntity be, float partialTick, PoseStack poseStack, MultiBufferSource buffer,
                       int packedLight, int packedOverlay) {
        BlockState state = be.getTargetState();
        PoseStack.Pose pose = poseStack.last();

        if (ModelCapturer.hasBlockEntityRenderer(state)) {
            CacheEntry entry = cache.get(be);
            if (entry == null || entry.version() != be.getVersion()) {
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
            for (MeshPoly poly : MimicMesh.glowPolys(be.renderData())) {
                emit(consumer, pose, poly, 0xFFFFFF, packedLight, fade);
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
            return 1f; // a clock that ran backwards just means "fresh"
        }
        if (age >= GLOW_TICKS) {
            return 0f;
        }
        double remaining = 1 - age / GLOW_TICKS;
        return (float) (remaining * remaining);
    }

    private static CacheEntry build(MimicBlockEntity be) {
        var captured = ModelCapturer.capture(be.getTargetState(), be.getTargetBeTag());
        // cut exactly as the chunk mesh cuts other blocks: pieces, slits, cut-out parts and their walls
        Map<RenderType, List<MeshPoly>> out = MimicMesh.cutModel(captured.byType(), captured.opaque(), captured.capUv(), null,
                be.renderData(), Sheets.cutoutBlockSheet());
        return new CacheEntry(be.getVersion(), out);
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
