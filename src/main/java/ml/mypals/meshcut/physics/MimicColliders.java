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
    /**
     * The coarse shape of each exact shape that was snapped (a block is asked for its collider by itself and by each of
     * its six neighbours whenever it changes, so the same exact shape comes back seven times, and a laser makes a new
     * one every tick).
     */
    private static final Map<MimicShapes.Key, MimicShapes.Key> COARSE_OF = new java.util.LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<MimicShapes.Key, MimicShapes.Key> eldest) {
            return size() > 8192;
        }
    };

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
        List<double[]> boxes = null;
        if (ENTRIES.size() >= ml.mypals.meshcut.MeshCutSettings.maxEntries()) {
            coarsened++;
            MimicShapes.Key coarse = COARSE_OF.get(key);
            if (coarse == null) {
                coarse = coarse(key, MimicShapes.boxes(key));
                COARSE_OF.put(key, coarse);
            }
            key = coarse;
            entry = ENTRIES.get(key);
            if (entry != null) {
                return entry;
            }
            if (ENTRIES.size() >= ml.mypals.meshcut.MeshCutSettings.maxEntries() + MAX_COARSE) {
                overflowFallbacks++;
                return fallback.get();
            }
            boxes = key.baseBoxes();
        } else {
            boxes = MimicShapes.boxes(key);
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
        // Snapped to quarters, a shape is a subset of 4x4x4 cells whatever it was made of: mark them, take the boxes of
        // those (a handful), and only then join those. (Joining the exact boxes pair by pair costs the cube of their
        // number, and a cut block has hundreds.)
        boolean[] cells = new boolean[64];
        for (double[] b : boxes) {
            int[] lo = new int[3], hi = new int[3];
            for (int a = 0; a < 3; a++) {
                lo[a] = Math.max(0, (int) Math.floor(b[a] * 4 + 1e-6));
                hi[a] = Math.min(4, (int) Math.ceil(b[a + 3] * 4 - 1e-6));
            }
            for (int x = lo[0]; x < hi[0]; x++) for (int y = lo[1]; y < hi[1]; y++) for (int z = lo[2]; z < hi[2]; z++) {
                cells[(x * 4 + y) * 4 + z] = true;
            }
        }
        List<double[]> snapped = new ArrayList<>();
        for (int x = 0; x < 4; x++) for (int y = 0; y < 4; y++) for (int z = 0; z < 4; z++) {
            if (!cells[(x * 4 + y) * 4 + z]) continue;
            int ze = z + 1, ye = y + 1, xe = x + 1;
            while (ze < 4 && cells[(x * 4 + y) * 4 + ze]) ze++;
            while (ye < 4 && filled(cells, x, x + 1, ye, ye + 1, z, ze)) ye++;
            while (xe < 4 && filled(cells, xe, xe + 1, y, ye, z, ze)) xe++;
            for (int a = x; a < xe; a++) for (int c = y; c < ye; c++) for (int d = z; d < ze; d++) cells[(a * 4 + c) * 4 + d] = false;
            snapped.add(new double[]{x / 4.0, y / 4.0, z / 4.0, xe / 4.0, ye / 4.0, ze / 4.0});
        }
        List<double[]> joined = VoxelBoxes.join(snapped, 4);
        joined.sort(java.util.Comparator.comparingDouble((double[] b) -> b[0]).thenComparingDouble(b -> b[1])
                .thenComparingDouble(b -> b[2]).thenComparingDouble(b -> b[3]).thenComparingDouble(b -> b[4]));
        return MimicShapes.Key.of(key.target(), List.of(), List.of(), joined);
    }

    private static boolean filled(boolean[] cells, int x, int xe, int y, int ye, int z, int ze) {
        for (int a = x; a < xe; a++) for (int b = y; b < ye; b++) for (int c = z; c < ze; c++) {
            if (!cells[(a * 4 + b) * 4 + c]) return false;
        }
        return true;
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
        COARSE_OF.clear();
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
