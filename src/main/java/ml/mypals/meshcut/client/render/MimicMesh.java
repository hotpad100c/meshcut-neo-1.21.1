package ml.mypals.meshcut.client.render;

import ml.mypals.meshcut.block.MimicRenderData;
import ml.mypals.meshcut.client.capture.ModelCapturer;
import ml.mypals.meshcut.cut.CapMode;
import ml.mypals.meshcut.cut.CutPlane;
import ml.mypals.meshcut.geom.Polytope;
import ml.mypals.meshcut.geom.CapBuilder;
import ml.mypals.meshcut.geom.MeshClipper;
import ml.mypals.meshcut.geom.MeshPoly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The cut block as baked quads for the chunk mesh: the imitated model clipped by the cuts, plus the cut faces.
 * Quads are grouped by chunk render layer and by the side whose neighbour may hide them, which is what lets the
 * chunk mesher cull faces. Built from {@link ModelCapturer#captureModel}, so it is safe on chunk-compile threads.
 */
public final class MimicMesh {
    private static final int SIDES = 7; // the six directions, then "null" (never culled)
    private static final ResourceLocation LAVA = ResourceLocation.withDefaultNamespace("block/lava_still");
    /** Lifts the lava layer off the base cap to avoid z-fighting. */
    private static final float GLOW_LIFT = 0.002f;
    private static final int GLOW_ARGB = 0x99FFFFFF;
    private static final int MAX_CACHED = 4096;
    /** Width, in blocks, of the slit that marks a cut that has not separated anything yet. */
    private static final double SLIT_WIDTH = 0.08;
    /** At most this many pending cuts are drawn (each doubles the number of regions). */
    private static final int MAX_SLITS = 3;
    private static final double MIN_REGION_VOLUME = 0.002;

    private static final Map<MimicRenderData, MimicMesh> CACHE = new ConcurrentHashMap<>();
    private static final MimicMesh EMPTY = new MimicMesh(new LinkedHashMap<>(), null);

    private final Map<RenderType, List<BakedQuad>[]> byLayer;
    private final List<BakedQuad>[] merged;
    private final ChunkRenderTypeSet layers;
    @Nullable
    private final TextureAtlasSprite particle;

    @SuppressWarnings("unchecked")
    private MimicMesh(Map<RenderType, List<BakedQuad>[]> byLayer, @Nullable TextureAtlasSprite particle) {
        this.byLayer = byLayer;
        this.particle = particle;
        this.layers = byLayer.isEmpty() ? ChunkRenderTypeSet.none() : ChunkRenderTypeSet.of(byLayer.keySet().toArray(new RenderType[0]));
        this.merged = new List[SIDES];
        Arrays.fill(merged, List.of());
        for (List<BakedQuad>[] sides : byLayer.values()) {
            for (int i = 0; i < SIDES; i++) {
                if (!sides[i].isEmpty()) {
                    List<BakedQuad> all = new ArrayList<>(merged[i]);
                    all.addAll(sides[i]);
                    merged[i] = all;
                }
            }
        }
    }

    public static MimicMesh of(MimicRenderData data) {
        MimicMesh mesh = CACHE.get(data);
        if (mesh == null) {
            mesh = build(data);
            if (CACHE.size() >= MAX_CACHED) {
                CACHE.clear();
            }
            CACHE.put(data, mesh);
        }
        return mesh;
    }

    public static void clearCache() {
        CACHE.clear();
    }

    /**
     * @param layer the chunk render layer being compiled, or null to get the quads of every layer
     * @param side  the face being compiled; null for quads that are never culled
     */
    public List<BakedQuad> quads(@Nullable RenderType layer, @Nullable Direction side) {
        int index = side == null ? SIDES - 1 : side.ordinal();
        if (layer == null) {
            return merged[index];
        }
        List<BakedQuad>[] sides = byLayer.get(layer);
        return sides == null ? List.of() : sides[index];
    }

    public ChunkRenderTypeSet layers() {
        return layers;
    }

    @Nullable
    public TextureAtlasSprite particle() {
        return particle;
    }

    private static MimicMesh build(MimicRenderData data) {
        if (ModelCapturer.hasBlockEntityRenderer(data.target())) {
            return EMPTY; // drawn by the block entity renderer instead
        }
        ModelCapturer.ModelMesh model = ModelCapturer.captureModel(data.target());

        Map<RenderType, List<MeshPoly>> clipped = new LinkedHashMap<>();
        List<CutPlane> pending = data.pending().size() > MAX_SLITS ? data.pending().subList(0, MAX_SLITS) : data.pending();
        if (pending.isEmpty()) {
            addPiece(clipped, model, data.planes(), data.capMode());
        } else {
            // The pending cuts divide the block into regions, one per combination of sides. Each is drawn on its own,
            // pulled back from every pending cut by half a slit, so a glowing gap shows where the block was cut.
            for (int mask = 0; mask < 1 << pending.size(); mask++) {
                List<CutPlane> region = new ArrayList<>(data.planes());
                for (int i = 0; i < pending.size(); i++) {
                    CutPlane side = (mask >> i & 1) == 1 ? pending.get(i).flipped() : pending.get(i);
                    region.add(new CutPlane(side.nx(), side.ny(), side.nz(), side.d() - SLIT_WIDTH / 2));
                }
                if (Polytope.volume(region) >= MIN_REGION_VOLUME) {
                    addPiece(clipped, model, region, data.capMode());
                }
            }
        }

        Map<RenderType, List<BakedQuad>[]> byLayer = new LinkedHashMap<>();
        clipped.forEach((type, polys) -> {
            if (polys.isEmpty()) {
                return;
            }
            @SuppressWarnings("unchecked")
            List<BakedQuad>[] sides = new List[SIDES];
            for (int i = 0; i < SIDES; i++) {
                sides[i] = new ArrayList<>();
            }
            for (MeshPoly poly : polys) {
                int side = poly.cullFace() < 0 ? SIDES - 1 : poly.cullFace();
                addQuads(sides[side], poly, model.particle());
            }
            byLayer.put(type, sides);
        });
        return new MimicMesh(byLayer, model.particle());
    }

    /** The model clipped by {@code planes}, with the cut faces added (and, for lava caps, the glowing layer). */
    private static void addPiece(Map<RenderType, List<MeshPoly>> into, ModelCapturer.ModelMesh model,
                                 List<CutPlane> planes, CapMode mode) {
        model.byChunkType().forEach((type, polys) ->
                into.computeIfAbsent(type, k -> new ArrayList<>()).addAll(MeshClipper.clip(polys, planes)));
        if (planes.isEmpty()) {
            return;
        }
        into.computeIfAbsent(RenderType.cutout(), k -> new ArrayList<>())
                .addAll(CapBuilder.build(model.opaque(), planes, model.particleUv(), model.particle()));
        if (mode == CapMode.LAVA) {
            TextureAtlasSprite lava = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(LAVA);
            float[] uv = {lava.getU0(), lava.getV0(), lava.getU1(), lava.getV1()};
            List<MeshPoly> glow = into.computeIfAbsent(RenderType.translucent(), k -> new ArrayList<>());
            for (MeshPoly cap : CapBuilder.build(model.opaque(), planes, uv, lava)) {
                glow.add(glowOf(cap));
            }
        }
    }

    /** The lava layer: same face, nudged outwards, full-bright and semi-transparent. */
    public static MeshPoly glowOf(MeshPoly cap) {
        float[] v = cap.verts().clone();
        for (int o = 0; o < v.length; o += MeshPoly.STRIDE) {
            v[o] += cap.nx() * GLOW_LIFT;
            v[o + 1] += cap.ny() * GLOW_LIFT;
            v[o + 2] += cap.nz() * GLOW_LIFT;
        }
        return new MeshPoly(v, cap.nx(), cap.ny(), cap.nz(), -1, 15, GLOW_ARGB, cap.sprite(), -1, false);
    }

    /** Fan-triangulates the convex polygon; every triangle becomes a degenerate quad. */
    private static void addQuads(List<BakedQuad> out, MeshPoly poly, TextureAtlasSprite fallback) {
        TextureAtlasSprite sprite = poly.sprite() instanceof TextureAtlasSprite s ? s : fallback;
        Direction direction = Direction.getNearest(poly.nx(), poly.ny(), poly.nz());
        int n = poly.vertexCount();
        if (n == 4) {
            out.add(quad(poly, new int[]{0, 1, 2, 3}, sprite, direction));
            return;
        }
        for (int i = 1; i + 1 < n; i++) {
            out.add(quad(poly, new int[]{0, i, i + 1, i + 1}, sprite, direction));
        }
    }

    /** BLOCK vertex format, 8 ints per vertex: pos(3 floats), color, uv(2 floats), light, normal. */
    private static BakedQuad quad(MeshPoly poly, int[] corners, TextureAtlasSprite sprite, Direction direction) {
        int[] data = new int[32];
        float[] v = poly.verts();
        int argb = poly.argb();
        int abgr = (argb & 0xFF00FF00) | (argb >> 16 & 0xFF) | (argb & 0xFF) << 16; // block colours are stored as ABGR
        int e = poly.lightEmission();
        int light = e > 0 ? (e << 4) | (e << 4) << 16 : 0;
        int normal = (pack(poly.nx())) | (pack(poly.ny()) << 8) | (pack(poly.nz()) << 16);
        for (int k = 0; k < 4; k++) {
            int o = k * 8;
            int s = corners[k] * MeshPoly.STRIDE;
            data[o] = Float.floatToRawIntBits(v[s]);
            data[o + 1] = Float.floatToRawIntBits(v[s + 1]);
            data[o + 2] = Float.floatToRawIntBits(v[s + 2]);
            data[o + 3] = abgr;
            data[o + 4] = Float.floatToRawIntBits(v[s + 3]);
            data[o + 5] = Float.floatToRawIntBits(v[s + 4]);
            data[o + 6] = light;
            data[o + 7] = normal;
        }
        return new BakedQuad(data, poly.tintIndex(), direction, sprite, poly.shade());
    }

    private static int pack(float component) {
        return ((byte) Math.round(Math.max(-1f, Math.min(1f, component)) * 127f)) & 255;
    }
}
