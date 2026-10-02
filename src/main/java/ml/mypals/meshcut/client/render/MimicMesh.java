package ml.mypals.meshcut.client.render;

import ml.mypals.meshcut.block.MimicRenderData;
import ml.mypals.meshcut.client.capture.ModelCapturer;
import ml.mypals.meshcut.cut.CapMode;
import ml.mypals.meshcut.cut.CutPlane;
import ml.mypals.meshcut.geom.Polytope;
import ml.mypals.meshcut.geom.Arrangement;
import ml.mypals.meshcut.geom.CapBuilder;
import ml.mypals.meshcut.geom.Patch;
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
    /** The charred colour of a scratch's mark on the block face. */
    private static final int SCRATCH_ARGB = 0xFF2A1A12;
    private static final int MAX_CACHED = 4096;
    /** Width, in blocks, of the slit that marks a cut that has not separated anything yet. */
    private static final double SLIT_WIDTH = 0.08;
    /** At most this many pending cuts are drawn (a block cannot hold more). */

    private static final Map<MimicRenderData, MimicMesh> CACHE = new ConcurrentHashMap<>();
    private static final Map<MimicRenderData, List<MeshPoly>> GLOW = new ConcurrentHashMap<>();
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

    /**
     * Everything the mesh of a cut block is made of, as text, for debugging: for each piece (region) the model faces,
     * cut faces and hole walls before baking, with their vertices and normals; then every baked quad.
     */
    public static String describe(MimicRenderData data) {
        StringBuilder sb = new StringBuilder();
        sb.append("target ").append(data.target()).append('\n');
        sb.append("planes ").append(data.planes()).append('\n');
        sb.append("excluded ").append(data.excluded()).append('\n');
        sb.append("patches ").append(data.patches()).append('\n');
        if (ModelCapturer.hasBlockEntityRenderer(data.target())) {
            return sb.append("(drawn by a block entity renderer)\n").toString();
        }
        ModelCapturer.ModelMesh model = ModelCapturer.captureModel(data.target());
        List<MeshPoly> capBase = capBase(model.opaque(), data.target());
        sb.append("cut faces from ").append(capBase == Polytope.cube() ? "the unit cube" : "the model").append('\n');
        List<List<CutPlane>> regions = regionPlanes(data);
        for (int r = 0; r < regions.size(); r++) {
            List<CutPlane> region = regions.get(r);
            sb.append("\n== piece ").append(r).append(" planes ").append(region).append('\n');
            int index = 0;
            for (var entry : model.byChunkType().entrySet()) {
                List<MeshPoly> clipped = MeshClipper.clip(entry.getValue(), region);
                for (List<CutPlane> hole : data.excluded()) {
                    clipped = MeshClipper.subtract(clipped, hole);
                }
                for (MeshPoly p : clipped) {
                    describe(sb, "model " + entry.getKey() + " #" + index++, p);
                }
            }
            index = 0;
            for (MeshPoly p : cutCaps(capBase, region, data.excluded(), model.particleUv(), model.particle())) {
                describe(sb, "cut face #" + index++, p);
            }
            index = 0;
            for (MeshPoly p : holeFaces(capBase, region, data.excluded(), model.particleUv(), model.particle())) {
                describe(sb, "hole wall #" + index++, p);
            }
        }
        sb.append("\n== baked quads\n");
        MimicMesh mesh = of(data);
        mesh.byLayer.forEach((layer, sides) -> {
            for (int side = 0; side < sides.length; side++) {
                for (BakedQuad q : sides[side]) {
                    int[] v = q.getVertices();
                    int stride = v.length / 4;
                    sb.append(layer).append(" side ").append(side == SIDES - 1 ? "none" : Direction.from3DDataValue(side))
                            .append(" dir ").append(q.getDirection()).append(" :");
                    for (int i = 0; i < 4; i++) {
                        sb.append(String.format(" (%.4f %.4f %.4f)", Float.intBitsToFloat(v[i * stride]),
                                Float.intBitsToFloat(v[i * stride + 1]), Float.intBitsToFloat(v[i * stride + 2])));
                    }
                    sb.append('\n');
                }
            }
        });
        return sb.toString();
    }

    private static void describe(StringBuilder sb, String what, MeshPoly p) {
        sb.append(what).append(String.format(" normal (%.3f %.3f %.3f) cull %d :", p.nx(), p.ny(), p.nz(), p.cullFace()));
        float[] v = p.verts();
        for (int i = 0; i < p.vertexCount(); i++) {
            sb.append(String.format(" (%.4f %.4f %.4f)", v[i * MeshPoly.STRIDE], v[i * MeshPoly.STRIDE + 1], v[i * MeshPoly.STRIDE + 2]));
        }
        sb.append('\n');
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
        GLOW.clear();
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

        Map<RenderType, List<MeshPoly>> clipped = cutModel(model.byChunkType(), model.opaque(), model.particleUv(),
                model.particle(), data, RenderType.cutout());

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

    /**
     * The pieces a block is drawn as: just its real cuts, or, if it has pending cuts, one piece per region those
     * divide it into. Each region is pulled back from every pending cut by half a slit, so a gap shows where the
     * block was cut.
     */
    /**
     * The pieces the cuts that go right through divide the block into: exactly the ones the server separates (see
     * {@link Arrangement}), each pulled back from the cuts by half the width of the slit between them.
     */
    private static List<List<CutPlane>> regionPlanes(MimicRenderData data) {
        List<List<CutPlane>> pieces = Arrangement.pieces(data.planes(), data.patches());
        int real = data.planes().size();
        List<List<CutPlane>> out = new ArrayList<>(pieces.size());
        for (List<CutPlane> piece : pieces) {
            List<CutPlane> shrunk = new ArrayList<>(piece.subList(0, real));
            for (CutPlane cut : piece.subList(real, piece.size())) {
                shrunk.add(new CutPlane(cut.nx(), cut.ny(), cut.nz(), cut.d() - SLIT_WIDTH / 2));
            }
            out.add(shrunk);
        }
        return out;
    }

    /**
     * A model cut as the block is: divided into the pieces its cuts make (with the slit between them), without the
     * parts that were cut out, and with the cut faces and the walls of those parts added in {@code capLayer}. Used for
     * the chunk mesh and, for blocks drawn by a block entity renderer, by {@code MimicRenderer}, so both look the same.
     *
     * @param opaque the model's closed surface, to cut faces from (a whole-cube block uses an exact cube instead)
     */
    public static Map<RenderType, List<MeshPoly>> cutModel(Map<RenderType, List<MeshPoly>> byType, List<MeshPoly> opaque,
                                                           float[] uv, Object sprite, MimicRenderData data, RenderType capLayer) {
        Map<RenderType, List<MeshPoly>> out = new LinkedHashMap<>();
        List<MeshPoly> capBase = capBase(opaque, data.target());
        for (List<CutPlane> region : regionPlanes(data)) {
            byType.forEach((type, polys) -> {
                List<MeshPoly> clipped = MeshClipper.clip(polys, region);
                for (List<CutPlane> hole : data.excluded()) {
                    clipped = MeshClipper.subtract(clipped, hole);
                }
                out.computeIfAbsent(type, k -> new ArrayList<>()).addAll(clipped);
            });
            List<MeshPoly> caps = new ArrayList<>(cutCaps(capBase, region, data.excluded(), uv, sprite));
            caps.addAll(holeFaces(capBase, region, data.excluded(), uv, sprite));
            if (!caps.isEmpty()) {
                out.computeIfAbsent(capLayer, k -> new ArrayList<>()).addAll(caps);
            }
        }
        return out;
    }

    /** The cut faces of a piece: those its own cuts leave, minus whatever was cut out of them. */
    private static List<MeshPoly> cutCaps(List<MeshPoly> opaque, List<CutPlane> planes, List<List<CutPlane>> excluded,
                                          float[] uv, Object sprite) {
        List<MeshPoly> caps = new ArrayList<>();
        if (!planes.isEmpty()) {
            caps.addAll(CapBuilder.build(opaque, planes, uv, sprite));
            for (List<CutPlane> region : excluded) {
                caps = MeshClipper.subtract(caps, region);
            }
        }
        return withinBlock(caps);
    }

    /**
     * What the cut faces are made from. A block whose collision shape is a whole cube is cut open as a plain cube:
     * the section of its model can be unreliable (models made of several shells, or with inner faces, give loops that
     * do not close), and a cube's section is exact. Other blocks use their own model.
     */
    private static List<MeshPoly> capBase(List<MeshPoly> opaque, net.minecraft.world.level.block.state.BlockState target) {
        for (double[] b : ml.mypals.meshcut.block.MimicShapes.cutBoxes(target)) {
            if (b[0] <= 1e-6 && b[1] <= 1e-6 && b[2] <= 1e-6 && b[3] >= 1 - 1e-6 && b[4] >= 1 - 1e-6 && b[5] >= 1 - 1e-6) {
                return Polytope.cube();
            }
        }
        return opaque;
    }

    /** The six half-spaces of the unit cube. */
    private static final List<CutPlane> CUBE = List.of(
            new CutPlane(-1, 0, 0, 0), new CutPlane(1, 0, 0, 1), new CutPlane(0, -1, 0, 0),
            new CutPlane(0, 1, 0, 1), new CutPlane(0, 0, -1, 0), new CutPlane(0, 0, 1, 1));

    /** Faces made here (not taken from the model) never leave the block, whatever the geometry they came from. */
    private static List<MeshPoly> withinBlock(List<MeshPoly> faces) {
        return faces.isEmpty() ? faces : MeshClipper.clip(faces, CUBE);
    }

    /**
     * The faces around the parts that were cut out of the block, turned to face into the hole, within one piece:
     * a piece is pulled back from the cuts by the slit, and a wall must stop where its piece stops, or it is left
     * standing alone in the slit.
     */
    private static List<MeshPoly> holeFaces(List<MeshPoly> opaque, List<CutPlane> planes, List<List<CutPlane>> excluded,
                                            float[] uv, Object sprite) {
        List<MeshPoly> faces = new ArrayList<>();
        for (List<CutPlane> region : excluded) {
            for (int j = 0; j < region.size(); j++) {
                List<CutPlane> others = new ArrayList<>(planes);
                for (int k = 0; k < region.size(); k++) {
                    if (k != j) {
                        others.add(region.get(k));
                    }
                }
                List<MeshPoly> wall = CapBuilder.face(opaque, region.get(j).flipped(), others, uv, sprite);
                for (List<CutPlane> other : excluded) { // nothing is behind a wall where another part was cut out too
                    if (other != region && !wall.isEmpty()) {
                        wall = MeshClipper.subtract(wall, other);
                    }
                }
                faces.addAll(wall);
            }
        }
        return withinBlock(faces);
    }

    /**
     * The glowing lava layer over the cut faces, as polygons in block-local coordinates. It is not part of the chunk
     * mesh: {@code MimicRenderer} draws it for a while after the cut and lets it fade. Render thread only.
     */
    public static List<MeshPoly> glowPolys(MimicRenderData data) {
        List<MeshPoly> cached = GLOW.get(data);
        if (cached != null) {
            return cached;
        }
        List<MeshPoly> out = new ArrayList<>();
        if (data.capMode() == CapMode.LAVA) {
            List<MeshPoly> opaque = ModelCapturer.hasBlockEntityRenderer(data.target())
                    ? ModelCapturer.capture(data.target(), data.beTag()).opaque()
                    : ModelCapturer.captureModel(data.target()).opaque();
            opaque = capBase(opaque, data.target());
            TextureAtlasSprite lava = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(LAVA);
            float[] uv = {lava.getU0(), lava.getV0(), lava.getU1(), lava.getV1()};
            for (List<CutPlane> region : regionPlanes(data)) {
                for (MeshPoly cap : cutCaps(opaque, region, data.excluded(), uv, lava)) {
                    out.add(glowOf(cap));
                }
                for (MeshPoly face : holeFaces(opaque, region, data.excluded(), uv, lava)) {
                    out.add(glowOf(face));
                }
            }
        }
        if (GLOW.size() >= MAX_CACHED) {
            GLOW.clear();
        }
        GLOW.put(data, out);
        return out;
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
