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
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Draws cut blocks whose imitated block is itself drawn by a block entity renderer (chests, beds, signs, ...):
 * their geometry uses entity textures that can't go into the chunk mesh. Every other mimic is part of the chunk
 * mesh via {@code MimicBakedModel} and this renderer does nothing for it.
 */
public class MimicRenderer implements BlockEntityRenderer<MimicBlockEntity> {
    private static final ResourceLocation LAVA = ResourceLocation.withDefaultNamespace("block/lava_still");

    private record CacheEntry(int version, Map<RenderType, List<MeshPoly>> byType) {}

    private final Map<MimicBlockEntity, CacheEntry> cache = new WeakHashMap<>();

    public MimicRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(MimicBlockEntity be, float partialTick, PoseStack poseStack, MultiBufferSource buffer,
                       int packedLight, int packedOverlay) {
        CacheEntry entry = cache.get(be);
        if (entry == null || entry.version() != be.getVersion()) {
            entry = build(be);
            cache.put(be, entry);
        }
        BlockState state = be.getTargetState();
        PoseStack.Pose pose = poseStack.last();
        for (var layer : entry.byType().entrySet()) {
            VertexConsumer consumer = buffer.getBuffer(layer.getKey());
            for (MeshPoly poly : layer.getValue()) {
                emit(consumer, pose, poly, tint(be, state, poly.tintIndex()), packedLight);
            }
        }
    }

    private static CacheEntry build(MimicBlockEntity be) {
        if (!ModelCapturer.hasBlockEntityRenderer(be.getTargetState())) {
            return new CacheEntry(be.getVersion(), Map.of());
        }
        var captured = ModelCapturer.capture(be.getTargetState(), be.getTargetBeTag());
        var planes = be.getPlanes();
        Map<RenderType, List<MeshPoly>> out = new LinkedHashMap<>();
        captured.byType().forEach((type, polys) -> out.put(type, new ArrayList<>(MeshClipper.clip(polys, planes))));

        if (!planes.isEmpty()) {
            List<MeshPoly> caps = CapBuilder.build(captured.opaque(), planes, captured.capUv());
            out.computeIfAbsent(Sheets.cutoutBlockSheet(), k -> new ArrayList<>()).addAll(caps);

            if (be.getCapMode() == CapMode.LAVA) {
                TextureAtlasSprite lava = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(LAVA);
                float[] uv = {lava.getU0(), lava.getV0(), lava.getU1(), lava.getV1()};
                List<MeshPoly> glow = new ArrayList<>();
                for (MeshPoly cap : CapBuilder.build(captured.opaque(), planes, uv)) {
                    glow.add(MimicMesh.glowOf(cap));
                }
                out.computeIfAbsent(Sheets.translucentCullBlockSheet(), k -> new ArrayList<>()).addAll(glow);
            }
        }
        return new CacheEntry(be.getVersion(), out);
    }

    private static int tint(MimicBlockEntity be, BlockState state, int tintIndex) {
        return tintIndex < 0 || be.getLevel() == null ? 0xFFFFFF
                : MimicTint.color(state, be.getLevel(), be.getBlockPos(), tintIndex);
    }

    /** Fan-triangulates the convex polygon; triangles are emitted as degenerate quads. */
    private static void emit(VertexConsumer c, PoseStack.Pose pose, MeshPoly poly, int rgb, int light) {
        int argb = poly.argb();
        float r = (rgb >> 16 & 255) / 255f * (argb >> 16 & 255) / 255f;
        float g = (rgb >> 8 & 255) / 255f * (argb >> 8 & 255) / 255f;
        float b = (rgb & 255) / 255f * (argb & 255) / 255f;
        float a = (argb >>> 24) / 255f;
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
