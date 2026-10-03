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

/**
 * Supplies Sable's Rapier backend with a per-position collider for mimic blocks.
 * <p>
 * Sable caches colliders per {@link BlockState}, which can't express "this stone, cut at this angle", so
 * {@code RapierPhysicsPipelineMixin} routes mimic blocks here instead. Entries are cached per
 * (imitated block, quantised cuts) and live for the lifetime of the JVM.
 */
public final class MimicColliders {
    private static final int MAX_ENTRIES = 4096;
    private static final Map<MimicShapes.Key, RapierVoxelColliderData> ENTRIES = new HashMap<>();

    private record Pending(ServerLevel level, BlockPos pos) {}

    /** Mimic blocks seen before their block entity existed (chunk loading); refreshed once it does. */
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
        return entryFor(mimic.shapeKey(), fallback);
    }

    private static synchronized RapierVoxelColliderData entryFor(MimicShapes.Key key, Supplier<RapierVoxelColliderData> fallback) {
        RapierVoxelColliderData entry = ENTRIES.get(key);
        if (entry != null) {
            return entry;
        }
        if (ENTRIES.size() >= MAX_ENTRIES) {
            return fallback.get(); // native entries can't be freed; stop growing
        }
        List<double[]> boxes = MimicShapes.boxes(key);
        if (boxes.isEmpty()) {
            entry = RapierVoxelColliderData.EMPTY;
        } else {
            BlockState target = key.target();
            entry = Rapier3D.createVoxelColliderEntry(
                    PhysicsBlockPropertyHelper.getFriction(target),
                    PhysicsBlockPropertyHelper.getVolume(target) * MimicShapes.volumeFraction(key),
                    PhysicsBlockPropertyHelper.getRestitution(target),
                    false, null);
            for (double[] b : boxes) {
                entry.addBox(new Vector3d(b[0], b[1], b[2]), new Vector3d(b[3], b[4], b[5]));
            }
        }
        ENTRIES.put(key, entry);
        return entry;
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

    /** Total volume of the boxes, for diagnostics. */
    public static double volumeOf(MimicShapes.Key key) {
        return VoxelBoxes.volume(MimicShapes.boxes(key));
    }

    private MimicColliders() {}
}
