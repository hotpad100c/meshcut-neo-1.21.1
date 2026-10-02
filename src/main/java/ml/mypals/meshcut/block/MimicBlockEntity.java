package ml.mypals.meshcut.block;

import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.cut.CapMode;
import ml.mypals.meshcut.cut.CutPlane;
import ml.mypals.meshcut.cut.graph.BlockNodes;
import ml.mypals.meshcut.cut.graph.WorldGraphs;
import ml.mypals.meshcut.geom.CellGrid;
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
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Holds the block this mimic imitates and the cuts that have been made in it. */
public class MimicBlockEntity extends BlockEntity {
    public static final long NEVER = Long.MIN_VALUE;

    private BlockState targetState = Blocks.STONE.defaultBlockState();
    /** Cuts that have really separated this piece from the rest of its block: they shape its collision and mass. */
    private final List<CutPlane> planes = new ArrayList<>();
    /** Convex parts of the block that were cut out and went elsewhere; this piece is what is left around them. */
    private final List<List<CutPlane>> excluded = new ArrayList<>();
    /**
     * Cuts that have been made in this block without taking anything out of it. Where one goes right through, the
     * block is still whole but shows a glowing slit; one that stops midway is a scratch. A later cut that finishes
     * the job (a loop closing, a structure being severed) separates the block along them.
     */
    private final List<Patch> patches = new ArrayList<>();
    private CapMode capMode = CapMode.LAVA;
    /** Game time at which the block was last cut; the glow on its cut faces fades from then on. */
    private long glowStart = NEVER;
    /** Saved data of the original block entity (sign text, banner patterns, ...), used to look like it. */
    @Nullable
    private CompoundTag targetBeTag;
    /** Bumped on every change so client-side mesh caches know when to rebuild. */
    private int version;

    /** Snapshot for the client's block model; rebuilt lazily after every change. */
    private volatile MimicRenderData renderData;
    private CellGrid grid;

    /** What Sable's mass tracker needs to know about this block: the block it weighs like, how much of it is left, where. */
    public record MassSnapshot(BlockState target, double volumeFraction, Vector3dc centerOfMass) {}

    private int massVersion = -1;
    private MassSnapshot currentMass;

    // While a change is being announced to Sable's mass tracker, the "old" state must still weigh what it was
    // accounted as. See massFor().
    private boolean transitionActive;
    private boolean transitionSlot;
    @Nullable
    private MassSnapshot transitionMass;

    private int shapeVersion = -1;
    private VoxelShape shape;
    private double volumeFraction = 1;

    public MimicBlockEntity(BlockPos pos, BlockState state) {
        super(MeshCut.MIMIC_BE.get(), pos, state);
    }

    public BlockState getTargetState() {
        return targetState;
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

    /** Game time of the last cut, or {@link #NEVER}. */
    public long getGlowStart() {
        return glowStart;
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

    /** Collision / selection shape of what is left of the block, cached per version. */
    public VoxelShape getShape() {
        refreshShape();
        return shape;
    }

    /** Fraction of the imitated block that survives the cuts; scales the physics mass. */
    public double getVolumeFraction() {
        refreshShape();
        return volumeFraction;
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
            var key = MimicShapes.Key.of(targetState, planes, excluded);
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

    private void refreshShape() {
        if (shapeVersion != version || shape == null) {
            var key = MimicShapes.Key.of(targetState, planes, excluded);
            shape = MimicShapes.shape(MimicShapes.boxes(key));
            volumeFraction = MimicShapes.volumeFraction(key);
            shapeVersion = version;
        }
    }

    /** Replaces the whole configuration with a single sync to clients. */
    public void configure(BlockState target, List<CutPlane> newPlanes, List<List<CutPlane>> newExcluded,
                          List<Patch> newPatches, CapMode mode, @Nullable CompoundTag beTag) {
        this.targetState = target;
        this.targetBeTag = beTag;
        // the arguments may be views of our own lists
        List<CutPlane> planesCopy = List.copyOf(newPlanes);
        List<List<CutPlane>> excludedCopy = new ArrayList<>();
        newExcluded.forEach(region -> excludedCopy.add(List.copyOf(region)));
        List<Patch> patchesCopy = List.copyOf(newPatches);
        this.planes.clear();
        this.planes.addAll(planesCopy);
        this.excluded.clear();
        this.excluded.addAll(excludedCopy);
        this.patches.clear();
        this.patches.addAll(patchesCopy);
        this.capMode = mode;
        this.glowStart = level == null ? NEVER : level.getGameTime();
        changed();
    }

    public void setTargetState(BlockState targetState) {
        this.targetState = targetState;
        changed();
    }

    public void addPlane(CutPlane plane) {
        planes.add(plane);
        changed();
    }

    /** What the block model needs to draw this block; safe to read from chunk-compile threads. */
    public MimicRenderData renderData() {
        MimicRenderData data = renderData;
        if (data == null) {
            List<List<CutPlane>> excludedCopy = new ArrayList<>();
            excluded.forEach(region -> excludedCopy.add(List.copyOf(region)));
            data = new MimicRenderData(targetState, targetBeTag, List.copyOf(planes), List.copyOf(excludedCopy),
                    List.copyOf(patches), capMode);
            renderData = data;
        }
        return data;
    }

    @Override
    public ModelData getModelData() {
        return ModelData.builder().with(MimicRenderData.PROPERTY, renderData()).build();
    }

    @Override
    public void onLoad() {
        super.onLoad();
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

    private void changed() {
        renderData = null;
        grid = null;
        version++;
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            if (level instanceof ServerLevel server) {
                WorldGraphs.onNodesChanged(server, worldPosition);
            }
        }
    }

    /** The pieces this block's cuts have divided it into (cached until the cuts change). */
    public CellGrid grid() {
        CellGrid g = grid;
        if (g == null) {
            g = CellGrid.of(planes, excluded, patches, MimicShapes.targetBoxes(targetState));
            grid = g;
        }
        return g;
    }

    /** What this block contributes to the connectivity of the structure it is in. */
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
        if (targetBeTag != null) {
            tag.put("targetBe", targetBeTag);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("target")) {
            targetState = NbtUtils.readBlockState(registries.lookupOrThrow(Registries.BLOCK), tag.getCompound("target"));
        }
        readPlanes(tag.getList("planes", Tag.TAG_DOUBLE), planes);
        excluded.clear();
        for (Tag region : tag.getList("excluded", Tag.TAG_LIST)) {
            List<CutPlane> regionPlanes = new ArrayList<>();
            readPlanes((ListTag) region, regionPlanes);
            excluded.add(regionPlanes);
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
            if (plane.size() == 1) {
                patches.add(Patch.restore(plane.get(0), polygon, p.getBoolean("through")));
            }
        }
        if (tag.contains("pending")) { // saves from before cuts could stop midway: each was a cut right through
            List<CutPlane> old = new ArrayList<>();
            readPlanes(tag.getList("pending", Tag.TAG_DOUBLE), old);
            old.forEach(plane -> patches.add(Patch.full(plane)));
        }
        targetBeTag = tag.contains("targetBe") ? tag.getCompound("targetBe") : null;
        glowStart = tag.contains("glowStart") ? tag.getLong("glowStart") : NEVER;
        capMode = CapMode.byOrdinal(tag.contains("cap") ? tag.getByte("cap") : CapMode.LAVA.ordinal());
        renderData = null;
        grid = null;
        version++;
        // When a chunk arrives, the block entity is created first and filled from the chunk data afterwards, and no
        // hook tells the model data manager about it. Without this the block's model data stays empty and it is
        // not drawn until something else (a block update) requests a refresh.
        if (level != null && level.isClientSide) {
            requestModelDataUpdate();
            // and if the section was already meshed without the data, mesh it again
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
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
