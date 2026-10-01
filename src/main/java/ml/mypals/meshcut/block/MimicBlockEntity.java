package ml.mypals.meshcut.block;

import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.cut.CapMode;
import ml.mypals.meshcut.cut.CutPlane;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
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

/** Holds the block this mimic imitates and the half-spaces it has been cut by. */
public class MimicBlockEntity extends BlockEntity {
    private BlockState targetState = Blocks.STONE.defaultBlockState();
    /** Cuts that have really separated this piece from the rest of its block: they shape its collision and mass. */
    private final List<CutPlane> planes = new ArrayList<>();
    /**
     * Cuts that went through this block without severing the structure it belongs to. The block is still whole, but
     * remembers the cut (drawn as a glowing slit); a later cut that does sever the structure splits it along them.
     */
    private final List<CutPlane> pending = new ArrayList<>();
    private CapMode capMode = CapMode.LAVA;
    /** Saved data of the original block entity (sign text, banner patterns, ...), used to look like it. */
    @Nullable
    private CompoundTag targetBeTag;
    /** Bumped on every change so client-side mesh caches know when to rebuild. */
    private int version;

    /** Snapshot for the client's block model; rebuilt lazily after every change. */
    private volatile MimicRenderData renderData;

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

    public List<CutPlane> getPending() {
        return Collections.unmodifiableList(pending);
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
            var key = MimicShapes.Key.of(targetState, planes);
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
            var key = MimicShapes.Key.of(targetState, planes);
            shape = MimicShapes.shape(MimicShapes.boxes(key));
            volumeFraction = MimicShapes.volumeFraction(key);
            shapeVersion = version;
        }
    }

    /** Replaces the whole configuration with a single sync to clients. */
    public void configure(BlockState target, List<CutPlane> newPlanes, List<CutPlane> newPending, CapMode mode,
                          @Nullable CompoundTag beTag) {
        this.targetState = target;
        this.targetBeTag = beTag;
        List<CutPlane> planesCopy = List.copyOf(newPlanes); // the arguments may be views of our own lists
        List<CutPlane> pendingCopy = List.copyOf(newPending);
        this.planes.clear();
        this.planes.addAll(planesCopy);
        this.pending.clear();
        this.pending.addAll(pendingCopy);
        this.capMode = mode;
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
            data = new MimicRenderData(targetState, targetBeTag, List.copyOf(planes), List.copyOf(pending), capMode);
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
        version++;
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("target", NbtUtils.writeBlockState(targetState));
        tag.put("planes", writePlanes(planes));
        tag.put("pending", writePlanes(pending));
        tag.putByte("cap", (byte) capMode.ordinal());
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
        readPlanes(tag.getList("pending", Tag.TAG_DOUBLE), pending);
        targetBeTag = tag.contains("targetBe") ? tag.getCompound("targetBe") : null;
        capMode = CapMode.byOrdinal(tag.contains("cap") ? tag.getByte("cap") : CapMode.LAVA.ordinal());
        renderData = null;
        version++;
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
