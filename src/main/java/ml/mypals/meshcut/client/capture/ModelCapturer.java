package ml.mypals.meshcut.client.capture;

import com.mojang.blaze3d.vertex.PoseStack;
import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.geom.MeshPoly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Captures what a block looks like into {@link MeshPoly}s.
 * <ul>
 *   <li>{@link #captureModel} — only the baked model, grouped by chunk render layer. Pure reads of baked data, safe
 *       from chunk-compile worker threads.</li>
 *   <li>{@link #capture} — the model plus whatever the block entity renderer draws (chests, beds, signs, ...),
 *       grouped by the entity-style render type that draws them. Render thread only.</li>
 * </ul>
 * Results are cached per block state until the resources reload.
 */
public final class ModelCapturer {
    /**
     * @param byChunkType model quads per chunk render layer
     * @param opaque      the non-translucent polygons, used to work out cut sections
     * @param particle    the model's particle sprite; cut faces are textured with it
     */
    public record ModelMesh(Map<RenderType, List<MeshPoly>> byChunkType, List<MeshPoly> opaque, TextureAtlasSprite particle) {
        public float[] particleUv() {
            return new float[]{particle.getU0(), particle.getV0(), particle.getU1(), particle.getV1()};
        }
    }

    /**
     * @param byType everything to draw, per entity-style render type
     * @param opaque the non-translucent polygons, used to work out cut sections
     * @param capUv  {u0, v0, u1, v1} of the block's particle sprite, used to texture cut faces
     */
    public record CapturedMesh(Map<RenderType, List<MeshPoly>> byType, List<MeshPoly> opaque, float[] capUv) {}

    private record Key(BlockState state, @Nullable CompoundTag beTag) {}

    private static final Map<BlockState, ModelMesh> MODEL_CACHE = new ConcurrentHashMap<>();
    private static final Map<BlockState, Boolean> HAS_RENDERER = new ConcurrentHashMap<>();
    private static final Map<Key, CapturedMesh> CACHE = new HashMap<>();
    private static boolean warnedAboutRenderer;

    public static ModelMesh captureModel(BlockState state) {
        return MODEL_CACHE.computeIfAbsent(state, ModelCapturer::doCaptureModel);
    }

    /**
     * Whether the block is drawn (at least partly) by a block entity renderer. Such blocks can't go into the chunk
     * mesh: their geometry uses entity textures that aren't in the block atlas.
     */
    public static boolean hasBlockEntityRenderer(BlockState state) {
        return HAS_RENDERER.computeIfAbsent(state, s -> {
            if (!(s.getBlock() instanceof EntityBlock entityBlock)) {
                return false;
            }
            try {
                BlockEntity be = entityBlock.newBlockEntity(BlockPos.ZERO, s);
                return be != null && Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(be) != null;
            } catch (RuntimeException e) {
                return false;
            }
        });
    }

    /** @param beTag saved data of the original block entity, so e.g. a sign keeps its text; may be null */
    public static CapturedMesh capture(BlockState state, @Nullable CompoundTag beTag) {
        return CACHE.computeIfAbsent(new Key(state, beTag), k -> doCapture(k.state(), k.beTag()));
    }

    public static void clearCache() {
        MODEL_CACHE.clear();
        HAS_RENDERER.clear();
        CACHE.clear();
    }

    private static ModelMesh doCaptureModel(BlockState state) {
        BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
        Map<RenderType, List<MeshPoly>> byType = new LinkedHashMap<>();

        RandomSource rand = RandomSource.create(42L);
        ChunkRenderTypeSet types = model.getRenderTypes(state, rand, ModelData.EMPTY);
        for (RenderType type : types) {
            List<MeshPoly> polys = byType.computeIfAbsent(chunkLayer(type), k -> new ArrayList<>());
            for (int i = 0; i <= Direction.values().length; i++) {
                Direction side = i < Direction.values().length ? Direction.values()[i] : null;
                rand.setSeed(42L);
                for (BakedQuad quad : model.getQuads(state, side, rand, ModelData.EMPTY, type)) {
                    polys.add(toPoly(quad, side == null ? -1 : side.ordinal()));
                }
            }
        }
        List<MeshPoly> opaque = new ArrayList<>();
        byType.forEach((type, polys) -> {
            if (type != RenderType.translucent()) {
                opaque.addAll(polys);
            }
        });
        return new ModelMesh(byType, opaque, model.getParticleIcon(ModelData.EMPTY));
    }

    private static CapturedMesh doCapture(BlockState state, @Nullable CompoundTag beTag) {
        ModelMesh model = captureModel(state);
        Map<RenderType, List<MeshPoly>> byType = new LinkedHashMap<>();
        model.byChunkType().forEach((type, polys) ->
                byType.computeIfAbsent(blockSheet(type), k -> new ArrayList<>()).addAll(polys));
        captureBlockEntityRenderer(state, beTag, byType);

        List<MeshPoly> opaque = new ArrayList<>();
        byType.forEach((type, polys) -> {
            if (!type.toString().contains("translucent")) {
                opaque.addAll(polys);
            }
        });
        return new CapturedMesh(byType, opaque, model.particleUv());
    }

    /** Renders the block entity renderer once into a recording buffer; its animation freezes at this frame. */
    private static void captureBlockEntityRenderer(BlockState state, @Nullable CompoundTag beTag,
                                                   Map<RenderType, List<MeshPoly>> into) {
        if (!(state.getBlock() instanceof EntityBlock entityBlock)) {
            return;
        }
        try {
            BlockEntity be = entityBlock.newBlockEntity(BlockPos.ZERO, state);
            if (be == null) {
                return;
            }
            var level = Minecraft.getInstance().level;
            if (beTag != null && level != null) {
                be.loadWithComponents(beTag, level.registryAccess());
            }
            BlockEntityRenderer<BlockEntity> renderer = Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(be);
            if (renderer == null) {
                return;
            }
            RecordingBufferSource recorder = new RecordingBufferSource();
            renderer.render(be, 0f, new PoseStack(), recorder, 0xF000F0, OverlayTexture.NO_OVERLAY);
            recorder.finish().forEach((type, polys) -> into.computeIfAbsent(type, k -> new ArrayList<>()).addAll(polys));
        } catch (Throwable t) {
            if (!warnedAboutRenderer) {
                warnedAboutRenderer = true;
                MeshCut.LOGGER.warn("Couldn't capture the block entity renderer of {}; it will be drawn without it", state, t);
            }
        }
    }

    /** Chunk render layers are collapsed to the three that matter for baked quads. */
    private static RenderType chunkLayer(RenderType type) {
        if (type == RenderType.translucent()) {
            return RenderType.translucent();
        }
        if (type == RenderType.solid()) {
            return RenderType.solid();
        }
        return RenderType.cutout();
    }

    /** The entity-style render type that draws block-atlas quads inside a block entity renderer. */
    private static RenderType blockSheet(RenderType chunkType) {
        if (chunkType == RenderType.translucent()) {
            return Sheets.translucentCullBlockSheet();
        }
        if (chunkType == RenderType.solid()) {
            return Sheets.solidBlockSheet();
        }
        return Sheets.cutoutBlockSheet();
    }

    /** BLOCK vertex format, 8 ints per vertex: pos(3 floats), color, uv(2 floats), light, normal. */
    private static MeshPoly toPoly(BakedQuad quad, int cullFace) {
        int[] data = quad.getVertices();
        float[] verts = new float[4 * MeshPoly.STRIDE];
        for (int v = 0; v < 4; v++) {
            int o = v * 8;
            verts[v * 5] = Float.intBitsToFloat(data[o]);
            verts[v * 5 + 1] = Float.intBitsToFloat(data[o + 1]);
            verts[v * 5 + 2] = Float.intBitsToFloat(data[o + 2]);
            verts[v * 5 + 3] = Float.intBitsToFloat(data[o + 4]);
            verts[v * 5 + 4] = Float.intBitsToFloat(data[o + 5]);
        }
        // Face normal from the quad diagonals; falls back to the declared direction for degenerate quads.
        float d1x = verts[10] - verts[0], d1y = verts[11] - verts[1], d1z = verts[12] - verts[2];
        float d2x = verts[15] - verts[5], d2y = verts[16] - verts[6], d2z = verts[17] - verts[7];
        float nx = d1y * d2z - d1z * d2y;
        float ny = d1z * d2x - d1x * d2z;
        float nz = d1x * d2y - d1y * d2x;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-6f) {
            var n = quad.getDirection().getNormal();
            nx = n.getX();
            ny = n.getY();
            nz = n.getZ();
        } else {
            nx /= len;
            ny /= len;
            nz /= len;
        }
        return new MeshPoly(verts, nx, ny, nz, quad.getTintIndex(), (data[6] >> 4) & 15, 0xFFFFFFFF,
                quad.getSprite(), cullFace, quad.isShade());
    }

    private ModelCapturer() {}
}
