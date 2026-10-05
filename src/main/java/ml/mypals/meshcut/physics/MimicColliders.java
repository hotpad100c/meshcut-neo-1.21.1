package ml.mypals.meshcut.physics;

import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import dev.ryanhcode.sable.physics.impl.rapier.collider.RapierVoxelColliderData;
import dev.ryanhcode.sable.util.LevelAccelerator;
import ml.mypals.meshcut.block.MimicBlock;
import ml.mypals.meshcut.block.MimicBlockEntity;
import ml.mypals.meshcut.block.MimicShapes;
import ml.mypals.meshcut.geom.VoxelBoxes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;


public final class MimicColliders {
    /**
     * How many exact collider shapes are made before new ones are snapped to a coarser grid and shared. Every box of a
     * cut block costs Rapier a cuboid test against every box it touches, so the shape count drives the step time
     * (measured: 40 to 70 ms at 600 exact shapes, 5 to 10 ms at 64). {@code -Dmeshcut.maxEntries=N} overrides it.
     */

    private static final int MAX_COARSE = 4096;
    private static final Map<MimicShapes.Key, RapierVoxelColliderData> ENTRIES = new HashMap<>();

    // diagnostics, read by PhysicsDiag
    static long createdEntries;
    static long createdBoxes;
    static long overflowFallbacks;
    static long coarsened;

    private record Pending(ServerLevel level, BlockPos pos) {}

    private static final Set<Pending> PENDING = ConcurrentHashMap.newKeySet();

    public static RapierVoxelColliderData resolve(LevelAccelerator accelerator, ServerLevel level, BlockPos pos,
                                                  BlockState state, Supplier<RapierVoxelColliderData> fallback) {
        if (!(state.getBlock() instanceof MimicBlock)) {
            return fallback.get();
        }
        LevelChunk chunk = accelerator.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
        BlockEntity be = chunk == null ? null : chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
        if (!(be instanceof MimicBlockEntity mimic)) {
            PENDING.add(new Pending(level, pos.immutable()));
            return fallback.get();
        }
        if (mimic.passable() && !inPlot(level, pos)) {
            // a piece of a torch in the world is passed through, as the torch was; one in a sub-level collides with
            // its outline, or the body it is part of would fall through everything
            return RapierVoxelColliderData.EMPTY;
        }
        return entryFor(mimic.shapeKey(), fallback);
    }

    private static boolean inPlot(ServerLevel level, BlockPos pos) {
        var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        return container != null && container.inBounds(pos);
    }

    private static synchronized RapierVoxelColliderData entryFor(MimicShapes.Key key, Supplier<RapierVoxelColliderData> fallback) {
        RapierVoxelColliderData entry = ENTRIES.get(key);
        if (entry != null) {
            return entry;
        }
        List<double[]> boxes = MimicShapes.boxes(key);
        if (ENTRIES.size() >= ml.mypals.meshcut.MeshCutSettings.maxEntries()) {
            coarsened++;
            key = coarse(key, boxes);
            entry = ENTRIES.get(key);
            if (entry != null) {
                return entry;
            }
            if (ENTRIES.size() >= ml.mypals.meshcut.MeshCutSettings.maxEntries() + MAX_COARSE) {
                overflowFallbacks++;
                return fallback.get();
            }
            boxes = key.baseBoxes();
        }
        if (boxes.isEmpty()) {
            entry = RapierVoxelColliderData.EMPTY;
        } else {
            BlockState target = key.target();
            entry = Rapier3D.createVoxelColliderEntry(
                    PhysicsBlockPropertyHelper.getFriction(target),
                    PhysicsBlockPropertyHelper.getVolume(target) * MimicShapes.volumeFraction(key),
                    PhysicsBlockPropertyHelper.getRestitution(target),
                     false, dev.ryanhcode.sable.api.block.BlockWithSubLevelCollisionCallback.sable$getCallback(target));
            createdEntries++;
            createdBoxes += boxes.size();
            for (double[] b : boxes) {
                entry.addBox(new Vector3d(b[0], b[1], b[2]), new Vector3d(b[3], b[4], b[5]));
            }
        }
        ENTRIES.put(key, entry);
        return entry;
    }


    private static MimicShapes.Key coarse(MimicShapes.Key key, List<double[]> boxes) {
        List<double[]> snapped = new ArrayList<>(boxes.size());
        for (double[] b : boxes) {
            double[] s = new double[6];
            for (int a = 0; a < 3; a++) {
                s[a] = Math.floor(b[a] * 4 + 1e-6) / 4;
                s[a + 3] = Math.ceil(b[a + 3] * 4 - 1e-6) / 4;
            }
            snapped.add(s);
        }
        List<double[]> joined = VoxelBoxes.join(snapped, 4);
        joined.sort(java.util.Comparator.comparingDouble((double[] b) -> b[0]).thenComparingDouble(b -> b[1])
                .thenComparingDouble(b -> b[2]).thenComparingDouble(b -> b[3]).thenComparingDouble(b -> b[4]));
        return MimicShapes.Key.of(key.target(), List.of(), List.of(), joined);
    }

    /** Re-reads colliders of mimics that were first seen without their block entity. */
    public static void onServerTick(ServerTickEvent.Post event) {
        if (PENDING.isEmpty()) {
            return;
        }
        List<Pending> batch = new ArrayList<>(PENDING);
        PENDING.removeAll(batch);
        for (Pending p : batch) {
            if (p.level().isLoaded(p.pos()) && p.level().getBlockEntity(p.pos()) instanceof MimicBlockEntity) {
                MimicBlock.refreshPhysics(p.level(), p.pos());
            }
        }
    }

    /** Forgets the blocks waiting to be refreshed: they belong to a server that has stopped. */
    public static void clear() {
        PENDING.clear();
    }

    static synchronized int entryCount() {
        return ENTRIES.size();
    }

    /** Total volume of the boxes, for diagnostics. */
    public static double volumeOf(MimicShapes.Key key) {
        return VoxelBoxes.volume(MimicShapes.boxes(key));
    }

    private MimicColliders() {}
}
