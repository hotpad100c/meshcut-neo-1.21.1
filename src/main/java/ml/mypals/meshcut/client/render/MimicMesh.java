package ml.mypals.meshcut.client.render;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import ml.mypals.meshcut.block.MimicRenderData;
import ml.mypals.meshcut.client.capture.ModelCapturer;
import ml.mypals.meshcut.cut.CapMode;
import ml.mypals.meshcut.cut.CutPlane;
import ml.mypals.meshcut.geom.*;
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

public final class MimicMesh {
    private static final int SIDES = 7; // the six directions, then "null" (never culled)
    private static final ResourceLocation LAVA = ResourceLocation.withDefaultNamespace("block/lava_still");
    private static final float GLOW_LIFT = 0.002f;
    private static final int GLOW_ARGB = 0x99FFFFFF;
    private static final int SCRATCH_ARGB = 0xFF2A1A12;
    private static final int MAX_CACHED = 4096;
    private static final double SLIT_WIDTH = 0.08;

    private static final Map<MimicRenderData, MimicMesh> CACHE = new ConcurrentHashMap<>();
    private static final Map<MimicRenderData, List<MeshPoly>> GLOW = new ConcurrentHashMap<>();

    private static final Cache<MimicRenderData, MimicMesh> BY_IDENTITY = CacheBuilder.newBuilder()
            .weakKeys().maximumSize(MAX_CACHED).build();
    private static final Cache<MimicRenderData, List<MeshPoly>> GLOW_BY_IDENTITY = CacheBuilder.newBuilder()
            .weakKeys().maximumSize(MAX_CACHED).build();
    private static final Cache<MimicRenderData, SmoothFragments> FRAGMENTS = CacheBuilder.newBuilder()
            .weakKeys().maximumSize(512).build();
    private static final java.util.concurrent.ExecutorService WORKERS = java.util.concurrent.Executors.newFixedThreadPool(
            Math.clamp(Runtime.getRuntime().availableProcessors() / 4, 1, 3), task -> {
                Thread thread = new Thread(task, "meshcut-mesh");
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY + 1);
                return thread;
            });
    private static final Map<MimicRenderData, Boolean> BUILDING = java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());
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


    public static String describe(MimicRenderData data) {
        StringBuilder sb = new StringBuilder();
        sb.append("target ").append(data.target()).append('\n');
        sb.append("planes ").append(data.planes()).append('\n');
        sb.append("excluded ").append(data.excluded()).append('\n');
        sb.append("patches ").append(data.patches()).append('\n');
        if (data.entity() != null) {
            var e = data.entity();
            sb.append("entity ").append(e.look().type()).append(" limb ").append(e.part())
                    .append(" rotation ").append(java.util.Arrays.toString(e.rotation()))
                    .append(" origin ").append(java.util.Arrays.toString(e.origin())).append('\n');
            for (double[] b : e.boxes()) {
                sb.append("  box ").append(java.util.Arrays.toString(b)).append('\n');
            }
            ModelCapturer.CapturedMesh whole = ModelCapturer.captureEntity(e.look());
            whole.byType().forEach((type, polys) -> sb.append("  recorded ").append(polys.size()).append(" faces in ").append(type).append('\n'));
            ModelCapturer.CapturedMesh part = entityPart(e);
            part.byType().forEach((type, polys) -> sb.append("  in this block ").append(polys.size()).append(" faces in ").append(type).append('\n'));
            int i = 0;
            for (var entry : part.byType().entrySet()) {
                for (MeshPoly p : entry.getValue()) {
                    if (i++ < 40) {
                        describe(sb, "entity face", p);
                    }
                }
            }
            return sb.toString();
        }
        if (ModelCapturer.hasBlockEntityRenderer(data.target())) {
            return sb.append("(drawn by a block entity renderer)\n").toString();
        }
        ModelCapturer.ModelMesh model = ModelCapturer.captureModel(data.target());
        List<MeshPoly> capBase = capBase(model.opaque(), data);
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

    /** Runs the task on a mesh worker. */
    public static void async(Runnable task) {
        WORKERS.execute(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                ml.mypals.meshcut.MeshCut.LOGGER.error("Cutting a mesh failed", t);
            }
        });
    }

    private static boolean onRenderThread() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft != null && minecraft.isSameThread();
    }

    public static MimicMesh of(MimicRenderData data) {
        MimicMesh mesh = BY_IDENTITY.getIfPresent(data);
        if (mesh != null) {
            return mesh;
        }
        mesh = CACHE.get(data);
        if (mesh == null && onRenderThread()) {
            if (BUILDING.putIfAbsent(data, Boolean.TRUE) == null) {
                async(() -> {
                    try {
                        MimicMesh built = build(data);
                        if (CACHE.size() >= MAX_CACHED) {
                            CACHE.clear();
                        }
                        CACHE.put(data, built);
                    } finally {
                        BUILDING.remove(data);
                    }
                });
            }
            return EMPTY; // not cached: the next frame asks again
        }
        if (mesh == null) {
            mesh = build(data);
            if (CACHE.size() >= MAX_CACHED) {
                CACHE.clear();
            }
            CACHE.put(data, mesh);
        }
        BY_IDENTITY.put(data, mesh);
        return mesh;
    }

    public static void clearCache() {
        CACHE.clear();
        GLOW.clear();
        BY_IDENTITY.invalidateAll();
        GLOW_BY_IDENTITY.invalidateAll();
        FRAGMENTS.invalidateAll();
    }

    /** The fragments of a block cut into cells, or null if its cut-out parts are not a lattice (legacy geometry). */
    @Nullable
    public static SmoothFragments fragments(MimicRenderData data) {
        boolean[] mask = VoxelFragments.latticeMask(data.excluded());
        if (mask == null) {
            return null;
        }
        try {
            return FRAGMENTS.get(data, () -> new SmoothFragments(mask, data.patches(), data.planes()));
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    /** The edges of a cut block for its selection outline, following the real shape of its fragments. */
    public static List<double[]> outline(MimicRenderData data, List<double[]> shape) {
        SmoothFragments fragments = fragments(data);
        if (fragments != null) {
            return fragments.outline(shape);
        }
        return Outline.edges(regionPlanes(data), data.excluded(), shape);
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

    /**
     * The part of a cut-up entity one block holds, as a model to cut: the recorded entity moved to where it stands
     * relative to this block, its faces kept to the block, and its whole closed body (not kept to the block) to cut
     * faces from. Render thread only.
     */
    public static ModelCapturer.CapturedMesh entityPart(ml.mypals.meshcut.block.EntityAppearance entity) {
        ModelCapturer.EntityCapture capture = ModelCapturer.captureEntityParts(entity.look());
        ModelCapturer.CapturedMesh source = entity.part() == null ? capture.whole() : capture.limbs().get(entity.part());
        if (source == null) {
            // this client took the entity apart differently from the one that built the body (other mods, say)
            return new ModelCapturer.CapturedMesh(Map.of(), List.of(), capture.whole().capUv());
        }
        double[] r = entity.rotation();
        org.joml.Quaternionf inverse = new org.joml.Quaternionf((float) r[0], (float) r[1], (float) r[2], (float) r[3]).conjugate();
        double[] o = entity.origin();
        Map<RenderType, List<MeshPoly>> byType = new LinkedHashMap<>();
        source.byType().forEach((type, polys) -> byType.put(type, MeshClipper.clip(place(polys, inverse, o), CUBE)));
        return new ModelCapturer.CapturedMesh(byType, place(source.opaque(), inverse, o), source.capUv());
    }

    /** The faces turned by {@code rotation} and then moved by {@code by}. */
    private static List<MeshPoly> place(List<MeshPoly> polys, org.joml.Quaternionf rotation, double[] by) {
        List<MeshPoly> out = new ArrayList<>(polys.size());
        org.joml.Vector3f p = new org.joml.Vector3f();
        for (MeshPoly poly : polys) {
            float[] v = poly.verts().clone();
            for (int i = 0; i < v.length; i += MeshPoly.STRIDE) {
                rotation.transform(p.set(v[i], v[i + 1], v[i + 2]));
                v[i] = p.x + (float) by[0];
                v[i + 1] = p.y + (float) by[1];
                v[i + 2] = p.z + (float) by[2];
            }
            rotation.transform(p.set(poly.nx(), poly.ny(), poly.nz()));
            out.add(new MeshPoly(v, p.x, p.y, p.z, poly.tintIndex(), poly.lightEmission(), poly.argb(), poly.sprite(),
                    poly.cullFace(), poly.shade()));
        }
        return out;
    }

    private static MimicMesh build(MimicRenderData data) {
        if (data.entity() != null) {
            return EMPTY; // drawn by the block entity renderer: entity textures are not in the block atlas
        }
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
    public static List<List<CutPlane>> regionPlanes(MimicRenderData data) {
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
        List<MeshPoly> capBase = capBase(opaque, data);
        Map<RenderType,List<MeshPoly>> surfaces=new LinkedHashMap<>();
        byType.forEach((type,polys)-> {
            List<MeshPoly> withScars=new ArrayList<>(polys);
            withScars.addAll(FragmentMesh.scorches(polys,data.patches()));
            surfaces.put(type,withScars);
        });
        SmoothFragments fragment = fragments(data);
        if (fragment != null) {
            List<List<CutPlane>> holes=VoxelFragments.smoothHoles(data.excluded());
            surfaces.forEach((type, polys) -> {
                List<MeshPoly> clipped=fragment.clip(polys,data.entity()==null);
                for(List<CutPlane> hole:holes) clipped=MeshClipper.subtract(clipped,hole);
                out.put(type,FragmentMesh.liftScars(clipped));
            });
            List<MeshPoly> caps=fragment.caps(capBase,uv,sprite,data.entity()==null);
            for(List<CutPlane> hole:holes) caps=MeshClipper.subtract(caps,hole);
            caps.addAll(fragment.clip(holeFaces(capBase,data.planes(),holes,uv,sprite)));
            out.computeIfAbsent(capLayer,k->new ArrayList<>()).addAll(caps);
            return out;
        }
        for (List<CutPlane> region : regionPlanes(data)) {
            surfaces.forEach((type, polys) -> {
                List<MeshPoly> clipped = MeshClipper.clip(polys, region);
                for (List<CutPlane> hole : data.excluded()) {
                    clipped = MeshClipper.subtract(clipped, hole);
                }
                out.computeIfAbsent(type, k -> new ArrayList<>()).addAll(FragmentMesh.liftScars(clipped));
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
    private static List<MeshPoly> capBase(List<MeshPoly> opaque, MimicRenderData data) {
        if (data.entity() != null) {
            return opaque; // the body itself: its box says nothing about where its flesh is
        }
        for (double[] b : ml.mypals.meshcut.block.MimicShapes.cutBoxes(data.target())) {
            if (b[0] <= 1e-6 && b[1] <= 1e-6 && b[2] <= 1e-6 && b[3] >= 1 - 1e-6 && b[4] >= 1 - 1e-6 && b[5] >= 1 - 1e-6) {
                return Polytope.cube();
            }
        }
        return opaque;
    }

    /**
     * Whether there is something behind a wall: just behind its middle (away from the part that was cut out) the
     * block must be solid, within its piece and not in another cut-out part. Two parts cut out side by side share a
     * plane, and a wall there would stand alone between two holes.
     */
    private static boolean backed(MeshPoly face, List<CutPlane> hole, List<CutPlane> piece, List<List<CutPlane>> excluded) {
        float[] v = face.verts();
        int n = face.vertexCount();
        double x = 0, y = 0, z = 0;
        for (int i = 0; i < n; i++) {
            x += v[i * MeshPoly.STRIDE];
            y += v[i * MeshPoly.STRIDE + 1];
            z += v[i * MeshPoly.STRIDE + 2];
        }
        // the face looks into the hole; behind it is the other way
        x = x / n - face.nx() * 0.01;
        y = y / n - face.ny() * 0.01;
        z = z / n - face.nz() * 0.01;
        for (CutPlane p : piece) {
            if (p.signedDistance(x, y, z) < 0) {
                return false;
            }
        }
        for (List<CutPlane> other : excluded) {
            boolean inside = true;
            for (CutPlane p : other) {
                if (p.signedDistance(x, y, z) <= 0) {
                    inside = false;
                    break;
                }
            }
            if (inside) {
                return false;
            }
        }
        return true;
    }

    /** Whether the face lies in one of the planes (facing either way). */
    private static boolean onAny(MeshPoly face, List<CutPlane> planes) {
        float[] v = face.verts();
        for (CutPlane p : planes) {
            double dot = p.nx() * face.nx() + p.ny() * face.ny() + p.nz() * face.nz();
            // a piece is pulled back from a cut by half the slit, so its face is that far from the plane
            if (Math.abs(dot) > 0.999 && Math.abs(p.signedDistance(v[0], v[1], v[2])) < SLIT_WIDTH) {
                return true;
            }
        }
        return false;
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
        for (int r = 0; r < excluded.size(); r++) {
            List<CutPlane> region = excluded.get(r);
            boolean repeated = false;
            for (int q = 0; q < r && !repeated; q++) {
                repeated = CutPlane.sameRegion(excluded.get(q), region);
            }
            if (repeated) {
                continue; // the same part cut out twice has one set of walls
            }
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
                for (MeshPoly face : wall) {
                    if (backed(face, region, planes, excluded)) {
                        faces.add(face);
                    }
                }
            }
        }
        return withinBlock(faces);
    }

    /**
     * The glowing lava layer over the cut faces, as polygons in block-local coordinates. It is not part of the chunk
     * mesh: {@code MimicRenderer} draws it for a while after the cut and lets it fade. Render thread only.
     */
    public static List<MeshPoly> glowPolys(MimicRenderData data) {
        List<MeshPoly> cached = GLOW_BY_IDENTITY.getIfPresent(data);
        if (cached != null) {
            return cached;
        }
        cached = GLOW.get(data);
        if (cached != null) {
            GLOW_BY_IDENTITY.put(data, cached);
            return cached;
        }
        if (data.capMode() == CapMode.LAVA) {
            // what needs the render thread is taken here, the cutting is done by a worker
            List<MeshPoly> opaque = data.entity() != null ? entityPart(data.entity()).opaque()
                    : ModelCapturer.hasBlockEntityRenderer(data.target())
                    ? ModelCapturer.capture(data.target(), data.beTag()).opaque()
                    : ModelCapturer.captureModel(data.target()).opaque();
            List<MeshPoly> base = capBase(opaque, data);
            TextureAtlasSprite lava = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(LAVA);
            if (BUILDING.putIfAbsent(data, Boolean.TRUE) != null) {
                return List.of();
            }
            async(() -> {
                try {
                    List<MeshPoly> done = glowFrom(data, base, lava);
                    if (GLOW.size() >= MAX_CACHED) {
                        GLOW.clear();
                    }
                    GLOW.put(data, done);
                } finally {
                    BUILDING.remove(data);
                }
            });
            return List.of();
        }
        GLOW.put(data, List.of());
        GLOW_BY_IDENTITY.put(data, List.of());
        return List.of();
    }

    private static List<MeshPoly> glowFrom(MimicRenderData data, List<MeshPoly> opaque, TextureAtlasSprite lava) {
        List<MeshPoly> out = new ArrayList<>();
        {
            float[] uv = {lava.getU0(), lava.getV0(), lava.getU1(), lava.getV1()};
            SmoothFragments fragment=fragments(data);
            if(fragment!=null) {
                List<List<CutPlane>> holes=VoxelFragments.smoothHoles(data.excluded());
                List<MeshPoly> caps=fragment.caps(opaque,uv,lava,data.entity()==null);
                for(List<CutPlane> hole:holes) caps=MeshClipper.subtract(caps,hole);
                caps.addAll(fragment.clip(holeFaces(opaque,data.planes(),holes,uv,lava)));
                for(MeshPoly cap:caps) out.add(glowOf(cap));
            } else {
                for (List<CutPlane> region : regionPlanes(data)) {
                    for (MeshPoly cap : cutCaps(opaque, region, data.excluded(), uv, lava)) {
                        if (onAny(cap, data.glowCuts())) { // only what the last cut opened
                            out.add(glowOf(cap));
                        }
                    }
                    for (MeshPoly face : holeFaces(opaque, region, data.excluded(), uv, lava)) {
                        if (onAny(face, data.glowCuts())) {
                            out.add(glowOf(face));
                        }
                    }
                }
            }
        }
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
