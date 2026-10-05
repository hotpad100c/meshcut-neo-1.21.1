package ml.mypals.meshcut.physics;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.MeshCutConfig;
import ml.mypals.meshcut.block.MimicBlock;
import ml.mypals.meshcut.block.MimicBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Vector3dc;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.WeakHashMap;

/**
 * Keeps a burst of cuts from drowning the physics (and the renderer) in pieces. Every piece a cut makes is entered here
 * with its volume, worked out when it was made, in a set that is kept in order of volume. When more than
 * {@code maxPieces} were made within the last {@code windowSeconds}, the smallest of them are burnt up (at the end of
 * the tick, not in the middle of the cut): a poof and a splash of lava. A piece with at least {@code maxVolume} is
 * never burnt.
 */
public final class FragmentBudget {
    private static final class Entry {
        final ServerSubLevel sub;
        final double volume;
        final long seq;
        final long madeAt;

        Entry(ServerSubLevel sub, double volume, long seq, long madeAt) {
            this.sub = sub;
            this.volume = volume;
            this.seq = seq;
            this.madeAt = madeAt;
        }
    }

    private static final class LevelState {
        /** In order of volume, smallest first; ties go to the older one. */
        final TreeSet<Entry> byVolume = new TreeSet<>(
                Comparator.<Entry>comparingDouble(e -> e.volume).thenComparingLong(e -> e.seq));
        /** In order of age, to let go of those past the window. */
        final ArrayDeque<Entry> byAge = new ArrayDeque<>();
        long seq;
    }

    private static final Map<ServerLevel, LevelState> STATES = new WeakHashMap<>();

    /** The material in the blocks, in whole blocks; to be asked before they are moved into a sub-level. */
    public static double volumeOf(ServerLevel level, List<BlockPos> blocks) {
        double volume = 0;
        for (BlockPos pos : blocks) {
            var state = level.getBlockState(pos);
            if (state.getBlock() instanceof MimicBlock && level.getBlockEntity(pos) instanceof MimicBlockEntity be) {
                volume += be.getVolumeFraction();
            } else if (!state.isAir()) {
                volume += 1;
            }
        }
        return volume;
    }

    /** Enters a piece that has just been made. */
    public static void made(ServerLevel level, ServerSubLevel sub, double volume) {
        if (MeshCutConfig.SURGE_PIECES.get() <= 0) {
            return;
        }
        LevelState state = STATES.computeIfAbsent(level, k -> new LevelState());
        Entry entry = new Entry(sub, volume, state.seq++, level.getGameTime());
        state.byVolume.add(entry);
        state.byAge.addLast(entry);
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        if (STATES.isEmpty()) {
            return;
        }
        int max = MeshCutConfig.SURGE_PIECES.get();
        long window = MeshCutConfig.SURGE_SECONDS.get() * 20L;
        double maxVolume = MeshCutConfig.SURGE_MAX_VOLUME.get();
        for (var it = STATES.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            ServerLevel level = e.getKey();
            LevelState state = e.getValue();
            long now = level.getGameTime();
            // the ones that are gone, or too old to count
            while (!state.byAge.isEmpty()) {
                Entry oldest = state.byAge.peekFirst();
                if (!oldest.sub.isRemoved() && now - oldest.madeAt <= window) {
                    break;
                }
                state.byAge.pollFirst();
                state.byVolume.remove(oldest);
            }
            state.byVolume.removeIf(entry -> entry.sub.isRemoved());
            if (max <= 0 || state.byVolume.size() <= max) {
                if (state.byVolume.isEmpty()) {
                    it.remove();
                }
                continue;
            }
            if (!(SubLevelContainer.getContainer(level) instanceof ServerSubLevelContainer container)) {
                continue;
            }
            int burnt = 0;
            try {
                while (state.byVolume.size() > max) {
                    Entry smallest = state.byVolume.first();
                    if (smallest.volume >= maxVolume) {
                        break; // everything left is too big to be let go of
                    }
                    state.byVolume.pollFirst();
                    state.byAge.remove(smallest);
                    if (smallest.sub.isRemoved()) {
                        continue;
                    }
                    burn(level, container, smallest.sub);
                    burnt++;
                }
            } catch (RuntimeException ex) {
                MeshCut.LOGGER.error("Burning up the surplus pieces of a cut failed", ex);
            }
            if (burnt > 0) {
                MeshCut.LOGGER.debug("Burnt up {} small pieces of a burst of cuts", burnt);
            }
        }
    }

    private static void burn(ServerLevel level, ServerSubLevelContainer container, ServerSubLevel sub) {
        Vector3dc at = sub.logicalPose().position();
        level.sendParticles(ParticleTypes.POOF, at.x(), at.y(), at.z(), 3, 0.1, 0.1, 0.1, 0.01);
        level.sendParticles(ParticleTypes.LAVA, at.x(), at.y(), at.z(), 6, 0.2, 0.2, 0.2, 0.0);
        level.sendParticles(ParticleTypes.FLAME, at.x(), at.y(), at.z(), 4, 0.15, 0.15, 0.15, 0.04);
        container.removeSubLevel(sub, SubLevelRemovalReason.REMOVED);
    }

    public static void clear() {
        STATES.clear();
    }

    private FragmentBudget() {}
}
