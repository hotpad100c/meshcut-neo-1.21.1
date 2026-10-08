package ml.mypals.meshcut.client.capture;

import com.mojang.blaze3d.vertex.PoseStack;
import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.geom.MeshPoly;
import com.mojang.authlib.GameProfile;
import ml.mypals.meshcut.block.EntityAppearance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.InventoryMenu;
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
    private static boolean recording;

    /**
     * Whether a block entity renderer is being recorded right now (render thread only). Renderers that leave parts
     * to Flywheel are told it is not there meanwhile, so the recording has everything.
     */
    public static boolean isRecording() {
        return recording;
    }

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
        ENTITY_CACHE.clear();
        ALPHA_CACHE.clear();
    }

    /**
     * A recorded entity: all of it, and the limbs it was taken apart into (see {@link EntityParts}).
     *
     * @param limbs  the faces of each limb, by its name, where the entity stood (not yet in the limb's frame)
     * @param bodies how to build the limbs; empty if the entity could not be drawn
     */
    public record EntityCapture(CapturedMesh whole, Map<String, CapturedMesh> limbs, List<ml.mypals.meshcut.cut.BodySpec> bodies) {}

    /** Recordings of the entities whose bodies were drawn last; every body is a new entity, so the oldest go. */
    private static final int MAX_ENTITIES = 64;
    private static final Map<EntityAppearance.Look, EntityCapture> ENTITY_CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<EntityAppearance.Look, EntityCapture> eldest) {
            return size() > MAX_ENTITIES;
        }
    };
    private static final ResourceLocation FLESH = ResourceLocation.fromNamespaceAndPath(MeshCut.MODID, "block/flesh");
    private static boolean warnedAboutEntity;

    /**
     * What an entity looked like: it is made again on the client from its saved data, turned as it was, and drawn
     * once by its renderer into a recording, standing at the origin. Cut faces are textured with seared flesh.
     * Render thread only; cached per look.
     */
    public static CapturedMesh captureEntity(EntityAppearance.Look look) {
        return captureEntityParts(look).whole();
    }

    /** {@link #captureEntity}, also taken apart into limbs. */
    public static EntityCapture captureEntityParts(EntityAppearance.Look look) {
        return ENTITY_CACHE.computeIfAbsent(look, ModelCapturer::doCaptureEntity);
    }

    private static EntityCapture doCaptureEntity(EntityAppearance.Look look) {
        Minecraft minecraft = Minecraft.getInstance();
        TextureAtlasSprite flesh = minecraft.getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(FLESH);
        float[] uv = {flesh.getU0(), flesh.getV0(), flesh.getU1(), flesh.getV1()};
        Map<RenderType, List<RecordingBufferSource.Tagged>> tagged = new LinkedHashMap<>();
        Entity entity = null;
        List<PartTags.DrawnPart> drawn = List.of();
        if (minecraft.level != null) {
            try {
                entity = createEntity(minecraft.level, look);
                if (entity != null) {
                    settleAnimations(minecraft, entity, look);
                    RecordingBufferSource recorder = new RecordingBufferSource();
                    recording = true;
                    PartTags.begin();
                    try {
                        minecraft.getEntityRenderDispatcher().render(entity, 0, 0, 0, look.bodyYaw(), 0f, new PoseStack(),
                                recorder, 0xF000F0);
                    } finally {
                        recording = false;
                        drawn = PartTags.end();
                    }
                    recorder.finishTagged().forEach((type, faces) -> {
                        String name = layerName(type);
                        // the name tag, the shadow and debug boxes are not part of the body
                        if (!name.startsWith("text") && !name.contains("shadow") && !name.equals("lines")) {
                            Alpha alpha = alphaOf(type);
                            List<RecordingBufferSource.Tagged> seen = new ArrayList<>(faces.size());
                            for (RecordingBufferSource.Tagged face : faces) {
                                if (alpha == null || seen(face.poly(), alpha)) {
                                    seen.add(face);
                                }
                            }
                            if (!seen.isEmpty()) {
                                tagged.computeIfAbsent(type, k -> new ArrayList<>()).addAll(seen);
                            }
                        }
                    });
                }
            } catch (Throwable t) {
                if (!warnedAboutEntity) {
                    warnedAboutEntity = true;
                    MeshCut.LOGGER.warn("Couldn't draw the cut-up {}; its parts will be invisible", look.type(), t);
                }
            }
        }
        Map<RenderType, List<MeshPoly>> byType = new LinkedHashMap<>();
        tagged.forEach((type, faces) -> byType.put(type, faces.stream().map(RecordingBufferSource.Tagged::poly).toList()));
        CapturedMesh whole = new CapturedMesh(byType, opaqueOf(byType), uv);
        EntityCapture capture;
        try {
            capture = entity == null ? new EntityCapture(whole, Map.of(), List.of())
                    : EntityParts.split(entity, look, tagged, drawn, whole);
        } catch (RuntimeException e) {
            MeshCut.LOGGER.warn("Couldn't take the cut-up {} apart into limbs", look.type(), e);
            capture = new EntityCapture(whole, Map.of(), List.of());
        }
        int faces = byType.values().stream().mapToInt(List::size).sum();
        MeshCut.LOGGER.info("Recorded the look of a cut-up {}: {} faces in {} layers, {} limb(s)", look.type(), faces,
                byType.size(), capture.bodies().size());
        return capture;
    }

    /** The faces that are not see-through: what cut faces are worked out from. */
    static List<MeshPoly> opaqueOf(Map<RenderType, List<MeshPoly>> byType) {
        List<MeshPoly> opaque = new ArrayList<>();
        byType.forEach((type, polys) -> {
            if (!type.toString().contains("translucent")) {
                opaque.addAll(polys);
            }
        });
        if (opaque.isEmpty()) {
            byType.values().forEach(opaque::addAll);
        }
        return opaque;
    }

    /** The alpha channel of a texture, row by row. */
    private record Alpha(int width, int height, byte[] alpha) {
        int at(int x, int y) {
            return alpha[Math.floorMod(y, height) * width + Math.floorMod(x, width)] & 0xFF;
        }
    }

    private static final Map<ResourceLocation, java.util.Optional<Alpha>> ALPHA_CACHE = new HashMap<>();
    private static final java.util.regex.Pattern TEXTURE = java.util.regex.Pattern.compile("texture\\[Optional\\[([^\\]]+?)\\]");
    /** Below this alpha a cutout shader throws the pixel away (0.1 of 255). */
    private static final int CUTOUT_ALPHA = 26;

    /**
     * The faces of which something can be seen. Models are drawn with parts whose texture is wholly transparent
     * (the villager's hat brim, which only the farmer's texture fills in; the outer layer of a skin); the game
     * throws those pixels away, but here they would be cut faces and walls with nothing around them.
     */
    @Nullable
    private static Alpha alphaOf(RenderType type) {
        java.util.regex.Matcher m = TEXTURE.matcher(type.toString());
        ResourceLocation location = m.find() ? ResourceLocation.tryParse(m.group(1)) : null;
        // no texture to go by: everything is kept
        return location == null ? null : ALPHA_CACHE.computeIfAbsent(location, l -> java.util.Optional.ofNullable(readAlpha(l))).orElse(null);
    }

    /** Whether any texel under the face is kept by a cutout shader. */
    private static boolean seen(MeshPoly p, Alpha alpha) {
        float[] v = p.verts();
        int n = p.vertexCount();
        double[] us = new double[n], vs = new double[n];
        double minU = Double.MAX_VALUE, minV = Double.MAX_VALUE, maxU = -Double.MAX_VALUE, maxV = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            us[i] = v[i * MeshPoly.STRIDE + 3] * alpha.width();
            vs[i] = v[i * MeshPoly.STRIDE + 4] * alpha.height();
            minU = Math.min(minU, us[i]);
            maxU = Math.max(maxU, us[i]);
            minV = Math.min(minV, vs[i]);
            maxV = Math.max(maxV, vs[i]);
        }
        boolean tested = false;
        for (int y = (int) Math.floor(minV); y < Math.ceil(maxV); y++) {
            for (int x = (int) Math.floor(minU); x < Math.ceil(maxU); x++) {
                if (inside(us, vs, x + 0.5, y + 0.5)) {
                    tested = true;
                    if (alpha.at(x, y) >= CUTOUT_ALPHA) {
                        return true;
                    }
                }
            }
        }
        if (!tested) { // thinner than a texel: go by the texel under its middle
            double cu = 0, cv = 0;
            for (int i = 0; i < n; i++) {
                cu += us[i];
                cv += vs[i];
            }
            return alpha.at((int) Math.floor(cu / n), (int) Math.floor(cv / n)) >= CUTOUT_ALPHA;
        }
        return false;
    }

    /** Whether the point is inside the convex polygon (either winding). */
    private static boolean inside(double[] us, double[] vs, double u, double v) {
        int sign = 0;
        for (int i = 0; i < us.length; i++) {
            int j = (i + 1) % us.length;
            double cross = (us[j] - us[i]) * (v - vs[i]) - (vs[j] - vs[i]) * (u - us[i]);
            if (Math.abs(cross) < 1e-9) {
                continue;
            }
            int s = cross > 0 ? 1 : -1;
            if (sign != 0 && s != sign) {
                return false;
            }
            sign = s;
        }
        return true;
    }

    /**
     * Reads the texture back from the graphics card, which covers downloaded skins as well as resource textures.
     * Render thread only; null if it can't be read.
     */
    @Nullable
    private static Alpha readAlpha(ResourceLocation location) {
        try {
            var texture = Minecraft.getInstance().getTextureManager().getTexture(location);
            com.mojang.blaze3d.systems.RenderSystem.bindTexture(texture.getId());
            int width = com.mojang.blaze3d.platform.GlStateManager._getTexLevelParameter(
                    org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_WIDTH);
            int height = com.mojang.blaze3d.platform.GlStateManager._getTexLevelParameter(
                    org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_HEIGHT);
            if (width <= 0 || height <= 0 || width * height > 4096 * 4096) {
                return null;
            }
            try (com.mojang.blaze3d.platform.NativeImage image = new com.mojang.blaze3d.platform.NativeImage(width, height, false)) {
                image.downloadTexture(0, false);
                byte[] alpha = new byte[width * height];
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        alpha[y * width + x] = (byte) (image.getPixelRGBA(x, y) >>> 24);
                    }
                }
                return new Alpha(width, height, alpha);
            }
        } catch (RuntimeException e) {
            MeshCut.LOGGER.debug("Couldn't read {} back", location, e);
            return null;
        }
    }

    /** The name a render type was registered with ("entity_cutout_no_cull", "text", ...), without its state. */
    private static String layerName(RenderType type) {
        String s = type.toString(); // RenderType[name:state...]
        int open = s.indexOf('['), colon = s.indexOf(':', open + 1);
        return open >= 0 && colon > open ? s.substring(open + 1, colon) : s;
    }

    /** Ticks a new entity is let go on for before it is recorded, longer than any transition between animations. */
    private static final int ANIMATION_SETTLE_TICKS = 40;

    /**
     * GeckoLib animates a model when it is drawn, from the time the entity has been alive: an entity just made has only
     * started its first animation, which is blended in from the model's rest pose (the arms out, a T), and would be
     * recorded as that. Drawn once, and again a while later, it has arrived at the animation it plays (its idling).
     */
    private static void settleAnimations(Minecraft minecraft, Entity entity, EntityAppearance.Look look) {
        if (!usesGeckoLib(minecraft.getEntityRenderDispatcher().getRenderer(entity))) {
            return;
        }
        recording = true;
        try {
            minecraft.getEntityRenderDispatcher().render(entity, 0, 0, 0, look.bodyYaw(), 0f, new PoseStack(),
                    new RecordingBufferSource(), 0xF000F0);
        } finally {
            recording = false;
        }
        entity.tickCount += ANIMATION_SETTLE_TICKS;
    }

    /** Whether the renderer is one of GeckoLib's (without GeckoLib being needed to know). */
    private static boolean usesGeckoLib(Object renderer) {
        for (Class<?> c = renderer.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getName().startsWith("software.bernie.geckolib.renderer.")) {
                return true;
            }
            for (Class<?> i : c.getInterfaces()) {
                if (i.getName().startsWith("software.bernie.geckolib.renderer.")) {
                    return true;
                }
            }
        }
        return false;
    }

    @Nullable
    private static Entity createEntity(ClientLevel level, EntityAppearance.Look look) {
        Entity entity;
        if (look.profileId() != null) {
            entity = new RemotePlayer(level, new GameProfile(look.profileId(), look.profileName() == null ? "" : look.profileName()));
        } else {
            entity = EntityType.byString(look.type()).map(type -> type.create(level)).orElse(null);
        }
        if (entity == null) {
            return null;
        }
        entity.load(look.data());
        entity.setPos(0, 0, 0);
        entity.xo = entity.yo = entity.zo = 0;
        entity.setYRot(look.bodyYaw());
        entity.yRotO = look.bodyYaw();
        entity.setXRot(look.pitch());
        entity.xRotO = look.pitch();
        entity.setRemainingFireTicks(0);
        if (entity instanceof LivingEntity living) {
            living.yBodyRot = living.yBodyRotO = look.bodyYaw();
            living.yHeadRot = living.yHeadRotO = look.headYaw();
            living.hurtTime = 0;
            living.deathTime = 0;
        }
        return entity;
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
            if (level != null) {
                // many renderers read the level (game time for the beacon's beam, neighbours for chests, ...)
                be.setLevel(level);
                if (beTag != null) {
                    be.loadWithComponents(beTag, level.registryAccess());
                }
            }
            BlockEntityRenderer<BlockEntity> renderer = Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(be);
            if (renderer == null) {
                return;
            }
            RecordingBufferSource recorder = new RecordingBufferSource();
            recording = true; // see isRecording
            try {
                renderer.render(be, 0f, new PoseStack(), recorder, 0xF000F0, OverlayTexture.NO_OVERLAY);
            } finally {
                recording = false;
            }
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
