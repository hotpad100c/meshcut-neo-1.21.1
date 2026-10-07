package ml.mypals.meshcut.block;

import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.cut.CapMode;
import ml.mypals.meshcut.cut.CutPlane;
import ml.mypals.meshcut.cut.graph.BlockNodes;
import ml.mypals.meshcut.cut.graph.WorldGraphs;
import ml.mypals.meshcut.geom.CellGrid;
import ml.mypals.meshcut.geom.HoleReducer;
import ml.mypals.meshcut.geom.Patch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Holds the block this mimic imitates and the cuts that have been made in it. */
public class MimicBlockEntity extends BlockEntity {
    public static final long NEVER = Long.MIN_VALUE;

    private BlockState targetState = Blocks.AIR.defaultBlockState();
    private boolean configured;
    private static final java.util.Map<Long, BlockState> REPLACED = new java.util.concurrent.ConcurrentHashMap<>();

    public static void rememberReplaced(BlockPos pos, BlockState old) {
        if (REPLACED.size() > 4096) REPLACED.clear();
        REPLACED.put(pos.asLong(), old);
    }

    @Nullable
    private BlockState replaced() {
        return configured ? null : REPLACED.get(worldPosition.asLong());
    }

    /** Nothing is known of what to draw yet (a client waiting for data): draw nothing rather than the default. */
    public boolean isPending() {
        return !configured && replaced() == null;
    }
    /** Cuts that have really separated this piece from the rest of its block: they shape its collision and mass. */
    /** Guards the three lists below: meshing threads read them while the main thread rewrites them. */
    private final Object cutLock = new Object();
    private final List<CutPlane> planes = new ArrayList<>();
    /** Convex parts of the block that were cut out and went elsewhere; this piece is what is left around them. */
    private final List<List<CutPlane>> excluded = new ArrayList<>();
    private final List<Patch> patches = new ArrayList<>();
    private CapMode capMode = CapMode.LAVA;
    private long glowStart = NEVER;
    private final List<CutPlane> glowCuts = new ArrayList<>();
    @Nullable
    private CompoundTag targetBeTag;
    @Nullable
    private EntityAppearance appearance;
    private int version;

    private volatile MimicRenderData renderData;
    private CellGrid grid;

    public record MassSnapshot(BlockState target, double volumeFraction, Vector3dc centerOfMass) {}

    private int massVersion = -1;
    private MassSnapshot currentMass;

    private boolean transitionActive;
    private boolean transitionSlot;
    @Nullable
    private MassSnapshot transitionMass;

    private record ShapeData(int version, MimicShapes.Key key, VoxelShape shape, double volumeFraction, int wholeFaces) {}

    @Nullable
    private volatile ShapeData shapeData;

    public MimicBlockEntity(BlockPos pos, BlockState state) {
        super(MeshCut.MIMIC_BE.get(), pos, state);
    }

    public BlockState getTargetState() {
        BlockState replaced = replaced();
        return replaced != null ? replaced : targetState;
    }

    public List<CutPlane> getPlanes() {
        return Collections.unmodifiableList(planes);
    }

    public List<List<CutPlane>> getExcluded() {
        return Collections.unmodifiableList(excluded);
    }

    public List<Patch> getPatches() {
        return Collections.unmodifiableList(patches);
    }

    public long getGlowStart() {
        return glowStart;
    }

    public record GlowHistory(long start, List<CutPlane> glowing, List<CutPlane> knownCuts) {
        public GlowHistory { glowing = List.copyOf(glowing); knownCuts = List.copyOf(knownCuts); }
    }

    public GlowHistory glowHistory() {
        return new GlowHistory(glowStart, glowCuts, allCuts(planes, excluded, patches.stream().map(Patch::plane).toList()));
    }

    public int getVersion() {
        return version;
    }

    public CapMode getCapMode() {
        return capMode;
    }

    @Nullable
    public CompoundTag getTargetBeTag() {
        return targetBeTag;
    }

    @Nullable
    public EntityAppearance getAppearance() {
        return appearance;
    }

    /** What there is of this block to cut: the part of an entity it holds, or the imitated block's shape. */
    public List<double[]> cutBoxes() {
        return appearance != null ? appearance.boxes() : MimicShapes.cutBoxes(targetState);
    }

    /** The key of this block's collision geometry, cached per version. */
    public MimicShapes.Key shapeKey() {
        return shapeData().key();
    }

    /** Collision / selection shape of what is left of the block, cached per version. */
    public VoxelShape getShape() {
        return shapeData().shape();
    }

    /**
     * Whether its face on that side is still whole and opaque, as the imitated block's: it then hides the face of
     * what lies against it (see {@link MimicBlock#hidesNeighborFace}). Cached per version; asked from meshing threads.
     */
    public boolean coversFace(net.minecraft.core.Direction dir) {
        return (shapeData().wholeFaces() & 1 << dir.get3DDataValue()) != 0;
    }

    /**
     * Whether one walks through it, as through what it imitates (a torch, a flower): it then collides with nothing in
     * the world and with no entity, and only a body it is part of collides with its outline (see MimicColliders).
     */
    public boolean passable() {
        return appearance == null && !MimicShapes.hasCollision(targetState);
    }

    /** Fraction of the imitated block that survives the cuts; scales the physics mass. */
    public double getVolumeFraction() {
        return shapeData().volumeFraction();
    }

    /**
     * Mass data for the given mimic block state.
     * <p>
     * Sable updates a sub-level's mass incrementally on block changes: add the new state's mass, subtract the old
     * one's, both looked up through the state. Mimics change without changing state, so to make that arithmetic work
     * the two values of {@code refresh} act as two slots: during a transition the old slot answers with what was
     * accounted before the change, the other with the current data.
     *
     * @return {@code null} to let Sable use its default for the state
     */
    @Nullable
    public MassSnapshot massFor(BlockState mimicState) {
        if (transitionActive && mimicState.getValue(MimicBlock.REFRESH) == transitionSlot) {
            return transitionMass;
        }
        if (currentMass == null || massVersion != version) {
            var key = shapeKey();
            currentMass = new MassSnapshot(targetState, MimicShapes.volumeFraction(key), MimicShapes.centerOfMass(key));
            massVersion = version;
        }
        return currentMass;
    }

    /**
     * Call before changing the data (or, for a block that Sable has just accounted with defaults, before
     * announcing it): the state with {@code refresh == oldSlot} will keep answering with the previous data.
     *
     * @param previousIsDefault the previous value was never computed from this block entity (the block entity did
     *                          not exist yet when Sable looked), so Sable's default is what it holds
     */
    public void beginMassTransition(boolean previousIsDefault, boolean oldSlot) {
        transitionMass = previousIsDefault ? null : massFor(getBlockState());
        transitionSlot = oldSlot;
        transitionActive = true;
    }

    public void endMassTransition() {
        transitionActive = false;
        transitionMass = null;
    }

    private ShapeData shapeData() {
        int v = version; // read first: a change made meanwhile bumps it, and the next call works it out again
        ShapeData data = shapeData;
        if (data == null || data.version() != v) {
            synchronized (cutLock) {
                var key = MimicShapes.Key.of(targetState, planes, excluded, appearance == null ? null : appearance.boxes());
                data = new ShapeData(v, key, MimicShapes.shape(key), MimicShapes.volumeFraction(key),
                        MimicFaces.wholeFaces(targetState, planes, excluded, patches, appearance != null));
            }
            shapeData = data;
        }
        return data;
    }

    /** Replaces the whole configuration with a single sync to clients. */
    public void configure(BlockState target, List<CutPlane> newPlanes, List<List<CutPlane>> newExcluded,
                          List<Patch> newPatches, CapMode mode, @Nullable CompoundTag beTag) {
        configure(target, newPlanes, newExcluded, newPatches, mode, beTag, null);
    }

    /** @param entity for a part of a cut-up entity, what it looked like; null for a block */
    public void configure(BlockState target, List<CutPlane> newPlanes, List<List<CutPlane>> newExcluded,
                          List<Patch> newPatches, CapMode mode, @Nullable CompoundTag beTag, @Nullable EntityAppearance entity) {
        configure(target, newPlanes, newExcluded, newPatches, mode, beTag, entity, null);
    }

    public void configure(BlockState target, List<CutPlane> newPlanes, List<List<CutPlane>> newExcluded,
                          List<Patch> newPatches, CapMode mode, @Nullable CompoundTag beTag,
                          @Nullable EntityAppearance entity, @Nullable GlowHistory inherited) {
        // the cut planes it has now that it did not have before are what this change opened
        List<CutPlane> before = inherited == null
                ? allCuts(planes, excluded, patches.stream().map(Patch::plane).toList()) : inherited.knownCuts();
        if (inherited != null) {
            glowStart = inherited.start();
            glowCuts.clear();
            glowCuts.addAll(inherited.glowing());
        }
        List<CutPlane> after = allCuts(newPlanes, newExcluded, newPatches.stream().map(Patch::plane).toList());
        if (MimicBlock.glowingCuts()) {
            List<CutPlane> fresh = new ArrayList<>();
            PlaneSet known = new PlaneSet(before);
            for (CutPlane p : after) {
                if (!known.has(p)) {
                    fresh.add(p);
                }
            }
            if (!fresh.isEmpty()) {
                long now = level == null ? NEVER : level.getGameTime();
                if (glowStart != NEVER && now != NEVER && now - glowStart < GLOW_TICKS) {
                    PlaneSet glowing = new PlaneSet(glowCuts);
                    for (CutPlane p : fresh) {
                        if (!glowing.has(p)) {
                            glowCuts.add(p);
                            glowing.add(p);
                        }
                    }
                } else {
                    glowCuts.clear();
                    glowCuts.addAll(fresh);
                }
                glowStart = now;
                // a laser adds planes every tick: only the newest ones are still hot
                while (glowCuts.size() > MAX_GLOW_PLANES) glowCuts.remove(0);
            }
        }
        this.appearance = entity;
        this.targetState = target;
        this.configured = true;
        this.targetBeTag = beTag;
        // the arguments may be views of our own lists
        List<CutPlane> planesCopy = List.copyOf(newPlanes);
        List<List<CutPlane>> excludedCopy = reducedRegions(newExcluded);
        List<Patch> patchesCopy = List.copyOf(newPatches);
        synchronized (cutLock) {
            this.planes.clear();
            this.planes.addAll(planesCopy);
            this.excluded.clear();
            this.excluded.addAll(excludedCopy);
            this.patches.clear();
            this.patches.addAll(patchesCopy);
        }
        this.capMode = mode;
        changed();
    }

    /** Saves from before regions were cut down hold the capsule's 62 planes. */
    private static final int LEGACY_REGION_PLANES = 40;

    /** Planes found again in about constant time (what {@code noneMatch(p::sameAs)} over a list does in a loop). */
    private static final class PlaneSet {
        private final java.util.Map<Long, List<CutPlane>> buckets = new java.util.HashMap<>();

        PlaneSet(List<CutPlane> planes) {
            planes.forEach(this::add);
        }

        private static long key(CutPlane p) {
            // facing either way is the same plane: take the normal whose first non-zero part is positive
            double s = p.nx() > 1e-6 || p.nx() > -1e-6 && (p.ny() > 1e-6 || p.ny() > -1e-6 && p.nz() >= 0) ? 1 : -1;
            long x = Math.round(s * p.nx() * 100), y = Math.round(s * p.ny() * 100), z = Math.round(s * p.nz() * 100);
            return (x + 128) | (y + 128) << 8 | (z + 128) << 16;
        }

        void add(CutPlane p) {
            buckets.computeIfAbsent(key(p), k -> new ArrayList<>(2)).add(p);
        }

        boolean has(CutPlane p) {
            List<CutPlane> bucket = buckets.get(key(p));
            if (bucket != null) {
                for (CutPlane q : bucket) {
                    if (p.sameAs(q)) return true;
                }
            }
            return false;
        }
    }

    /** Copies the regions, each cut down to the planes that matter inside this block (see {@link HoleReducer}). */
    private static List<List<CutPlane>> reducedRegions(List<List<CutPlane>> regions) {
        List<List<CutPlane>> out = new ArrayList<>(regions.size());
        for (List<CutPlane> region : regions) {
            if (region.size() <= 12) {
                out.add(List.copyOf(region)); // a lattice box or a burn prism: already as small as it gets
                continue;
            }
            var reduced = HoleReducer.reduce(region);
            if (reduced != null) {
                out.add(List.copyOf(reduced.planes()));
            }
        }
        return out;
    }

    private static List<CutPlane> allCuts(List<CutPlane> planes, List<List<CutPlane>> excluded, List<CutPlane> patches) {
        List<CutPlane> all = new ArrayList<>(planes);
        all.addAll(patches);
        for (List<CutPlane> hole : ml.mypals.meshcut.geom.VoxelFragments.smoothHoles(excluded)) {
            for (CutPlane p : hole) {
                all.add(p.flipped());
            }
        }
        return all;
    }

    public void setTargetState(BlockState targetState) {
        this.targetState = targetState;
        this.configured = true;
        changed();
    }

    public void addPlane(CutPlane plane) {
        synchronized (cutLock) {
            planes.add(plane);
        }
        changed();
    }

    /** What the block model needs to draw this block; safe to read from chunk-compile threads. */
    public MimicRenderData renderData() {
        MimicRenderData data = renderData;
        if (data == null) {
            synchronized (cutLock) {
                List<List<CutPlane>> excludedCopy = new ArrayList<>();
                excluded.forEach(region -> excludedCopy.add(List.copyOf(region)));
                BlockState replaced = replaced();
                data = new MimicRenderData(replaced != null ? replaced : targetState, replaced != null ? null : targetBeTag, List.copyOf(planes), List.copyOf(excludedCopy),
                        List.copyOf(patches), capMode, appearance, List.copyOf(glowCuts));
            }
            renderData = data;
        }
        return data;
    }

    @Override
    public ModelData getModelData() {
        return isPending() ? ModelData.EMPTY : ModelData.builder().with(MimicRenderData.PROPERTY, renderData()).with(MimicRenderData.POS, worldPosition.immutable()).build();
    }

    @Override
    public void setRemoved() {
        ml.mypals.meshcut.physics.MimicFloating.update(level, worldPosition, null, 0);
        super.setRemoved();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        updateLight(); // e.g. moved into a sub-level: the light is set for the new place
        requestModelDataUpdate();
    }

    /** The server sent new data: refresh the model data and re-mesh the chunk section this block is in. */
    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt, HolderLookup.Provider registries) {
        super.onDataPacket(net, pkt, registries);
        requestModelDataUpdate();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 0);
        }
    }

    /** A grid the caller has just worked out for exactly what it is about to configure: not worked out twice. */
    private static final ThreadLocal<CellGrid> PRESET_GRID = new ThreadLocal<>();

    public static void presetGrid(@Nullable CellGrid grid) {
        if (grid == null) PRESET_GRID.remove();
        else PRESET_GRID.set(grid);
    }

    private void changed() {
        try (var timing = ml.mypals.meshcut.util.CutTiming.phase("MimicBlockEntity.changed")) {
            changedUntimed();
        }
    }

    private void changedUntimed() {
        renderData = null;
        grid = PRESET_GRID.get();
        PRESET_GRID.remove();
        version++;
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            if (level instanceof ServerLevel server) {
                WorldGraphs.onNodesChanged(server, worldPosition);
            }
            updateLight();
        }
    }

    /** Gives off the light the imitated block does (see {@link MimicBlock#getLightEmission}). */
    void updateLight() {
        // On both sides: the client works out its own lighting, and the synced light data may come later than this
        // block entity's data, so it sets the value itself and relights rather than wait.
        if (level == null) {
            return;
        }
        // a piece of something that floats still floats (server only; see MimicFloating)
        if (level instanceof ServerLevel) {
            ml.mypals.meshcut.physics.MimicFloating.update(level, worldPosition, targetState, MimicShapes.volumeFraction(shapeKey()));
        }
        var light = level.getAuxLightManager(worldPosition);
        int emission = lightLevel();
        if (light != null && (light.getLightAt(worldPosition) != emission || (level.isClientSide && emission > 0))) {
            light.setLightAt(worldPosition, emission);
            level.getLightEngine().checkBlock(worldPosition);
        }
        if (glowLight() > 0) {
            GlowLight.track(this);
        }
    }

    public static int getLight(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof MimicBlockEntity be ? be.lightLevel() : 0;
    }

    public int lightLevel() {
        return Math.max(targetState.getLightEmission(), glowLight());
    }

    public static final double GLOW_TICKS = 600;
    /** Of the faces a cut opened, this many glow at most. */
    private static final int MAX_GLOW_PLANES = 192;

    public int glowLight() {
        return glowCuts.isEmpty() ? 0 : (int) Math.ceil(15 * glowFade(0));
    }

    public float glowFade(float partialTick) {
        return glowFade(partialTick, level == null ? 0 : level.getGameTime());
    }

    public float glowFade(float partialTick, long gameTime) {
        if (capMode != CapMode.LAVA || glowStart == NEVER) {
            return 0f;
        }
        double age = gameTime + partialTick - glowStart;
        if (age < 0) return 0f; // future/mismatched timestamps must never revive a cooled cut
        if (age >= GLOW_TICKS) {
            return 0f;
        }
        double remaining = 1 - age / GLOW_TICKS;
        return (float) (remaining * remaining);
    }

    public CellGrid grid() {
        CellGrid g = grid;
        if (g == null) {
            g = CellGrid.of(planes, excluded, patches, cutBoxes());
            grid = g;
        }
        return g;
    }

    public BlockNodes nodes() {
        return BlockNodes.of(grid(), false);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("target", NbtUtils.writeBlockState(targetState));
        tag.put("planes", writePlanes(planes));
        ListTag excludedTag = new ListTag();
        excluded.forEach(region -> excludedTag.add(writePlanes(region)));
        tag.put("excluded", excludedTag);
        ListTag patchesTag = new ListTag();
        for (Patch patch : patches) {
            CompoundTag p = new CompoundTag();
            p.put("plane", writePlanes(List.of(patch.plane())));
            ListTag poly = new ListTag();
            for (float v : patch.polygon()) {
                poly.add(FloatTag.valueOf(v));
            }
            p.put("poly", poly);
            p.putBoolean("through", patch.through());
            patchesTag.add(p);
        }
        tag.put("patches", patchesTag);
        tag.putByte("cap", (byte) capMode.ordinal());
        tag.putLong("glowStart", glowStart);
        tag.put("glowCuts", writePlanes(glowCuts));
        if (targetBeTag != null) {
            tag.put("targetBe", targetBeTag);
        }
        if (appearance != null) {
            tag.put("entity", appearance.save());
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("target")) {
            targetState = NbtUtils.readBlockState(registries.lookupOrThrow(Registries.BLOCK), tag.getCompound("target"));
            configured = true;
            REPLACED.remove(worldPosition.asLong());
        }
        synchronized (cutLock) {
            readPlanes(tag.getList("planes", Tag.TAG_DOUBLE), planes);
            excluded.clear();
            for (Tag region : tag.getList("excluded", Tag.TAG_LIST)) {
                List<CutPlane> regionPlanes = new ArrayList<>();
                readPlanes((ListTag) region, regionPlanes);
                excluded.add(regionPlanes.size() > LEGACY_REGION_PLANES ? reducedRegions(List.of(regionPlanes)).stream().findFirst().orElse(null) : regionPlanes);
                if (excluded.get(excluded.size() - 1) == null) excluded.remove(excluded.size() - 1);
            }
            patches.clear();
            for (Tag t : tag.getList("patches", Tag.TAG_COMPOUND)) {
                CompoundTag p = (CompoundTag) t;
                List<CutPlane> plane = new ArrayList<>();
                readPlanes(p.getList("plane", Tag.TAG_DOUBLE), plane);
                ListTag poly = p.getList("poly", Tag.TAG_FLOAT);
                float[] polygon = new float[poly.size()];
                for (int i = 0; i < polygon.length; i++) {
                    polygon[i] = poly.getFloat(i);
                }
                if (plane.size() == 1 && polygon.length >= 9 && polygon.length % 3 == 0) {
                    patches.add(Patch.restore(plane.get(0), polygon, p.getBoolean("through")));
                }
            }
            if (tag.contains("pending")) {
                List<CutPlane> old = new ArrayList<>();
                readPlanes(tag.getList("pending", Tag.TAG_DOUBLE), old);
                old.forEach(plane -> patches.add(Patch.full(plane)));
            }
        }
        targetBeTag = tag.contains("targetBe") ? tag.getCompound("targetBe") : null;
        appearance = tag.contains("entity") ? EntityAppearance.load(tag.getCompound("entity")) : null;
        glowStart = tag.contains("glowStart") ? tag.getLong("glowStart") : NEVER;
        readPlanes(tag.getList("glowCuts", Tag.TAG_DOUBLE), glowCuts);
        capMode = CapMode.byOrdinal(tag.contains("cap") ? tag.getByte("cap") : CapMode.LAVA.ordinal());
        renderData = null;
        grid = null;
        version++;
        updateLight();
        if (level instanceof ServerLevel server) {
            WorldGraphs.onNodesChanged(server, worldPosition);
        }
        if (level != null && level.isClientSide) {
            requestModelDataUpdate();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 0);
        }
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (level.isClientSide) {
            requestModelDataUpdate();
        }
    }

    private static ListTag writePlanes(List<CutPlane> planes) {
        ListTag list = new ListTag();
        for (CutPlane p : planes) {
            list.add(DoubleTag.valueOf(p.nx()));
            list.add(DoubleTag.valueOf(p.ny()));
            list.add(DoubleTag.valueOf(p.nz()));
            list.add(DoubleTag.valueOf(p.d()));
        }
        return list;
    }

    private static void readPlanes(ListTag list, List<CutPlane> into) {
        into.clear();
        for (int i = 0; i + 3 < list.size(); i += 4) {
            into.add(new CutPlane(list.getDouble(i), list.getDouble(i + 1), list.getDouble(i + 2), list.getDouble(i + 3)));
        }
    }

    @Override
    public @NotNull CompoundTag getUpdateTag(HolderLookup.@NotNull Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
