package ml.mypals.meshcut.physics;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelObserver;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.platform.SableEventPlatform;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.MeshCutConfig;
import ml.mypals.meshcut.block.MimicBlock;
import ml.mypals.meshcut.block.MimicBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3dc;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class Debris {
    private static final int CHECK_EVERY = 20;
    private static final long MAX_PLOT_BLOCKS = 4096;

    public static void register() {
        SableEventPlatform.INSTANCE.onSubLevelContainerReady((level, container) -> {
            if (level instanceof ServerLevel server) {
                container.addObserver(new Observer(server));
            }
        });
    }

    private static final class Observer implements SubLevelObserver {
        private final ServerLevel level;
        private final Map<UUID, Long> since = new HashMap<>();
        private int ticks;

        Observer(ServerLevel level) {
            this.level = level;
        }

        @Override
        public void onSubLevelRemoved(SubLevel sub, SubLevelRemovalReason reason) {
            since.remove(sub.getUniqueId());
        }

        @Override
        public void tick(SubLevelContainer container) {
            if (++ticks % CHECK_EVERY != 0) {
                return;
            }
            long now = level.getGameTime();
            expire(container, now);
            double maxVolume = MeshCutConfig.DEBRIS_VOLUME.get();
            if (maxVolume <= 0) {
                since.clear();
                return;
            }
            long lifetime = MeshCutConfig.DEBRIS_SECONDS.get() * 20L;
            try {
                java.util.Set<UUID> seen = new java.util.HashSet<>();
                for (ServerSubLevel sub : java.util.List.copyOf(((dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer) container).getAllSubLevels())) {
                    if (sub.isRemoved() || CutMarks.isKept(sub) || !isDebris(sub, maxVolume)) {
                        continue;
                    }
                    UUID id = sub.getUniqueId();
                    seen.add(id);
                    if (now - since.computeIfAbsent(id, k -> now) >= lifetime) {
                        Vector3dc at = sub.logicalPose().position();
                        level.sendParticles(ParticleTypes.POOF, at.x(), at.y(), at.z(), 4, 0.15, 0.15, 0.15, 0.01);
                        container.removeSubLevel(sub, SubLevelRemovalReason.REMOVED);
                        since.remove(id);
                    }
                }
                since.keySet().retainAll(seen);
            } catch (RuntimeException e) {
                MeshCut.LOGGER.error("Clearing away the small pieces of cuts failed", e);
            }
        }

        /**
         * Every piece a cut made goes after a while, unless somebody has held or clicked it. Sable draws a sub-level at
         * the size it was made at (the scale of a pose is not sent to clients), so it cannot be seen to shrink: it poofs.
         */
        private void expire(SubLevelContainer container, long now) {
            long lifetime = MeshCutConfig.PIECE_EXPIRE_MINUTES.get() * 1200L;
            if (lifetime <= 0) {
                return;
            }
            try {
                for (ServerSubLevel sub : java.util.List.copyOf(((dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer) container).getAllSubLevels())) {
                    long born = CutMarks.bornAt(sub);
                    if (sub.isRemoved() || born < 0 || CutMarks.isKept(sub) || now - born < lifetime) {
                        continue;
                    }
                    Vector3dc at = sub.logicalPose().position();
                    level.sendParticles(ParticleTypes.POOF, at.x(), at.y(), at.z(), 6, 0.25, 0.25, 0.25, 0.01);
                    container.removeSubLevel(sub, SubLevelRemovalReason.REMOVED);
                    since.remove(sub.getUniqueId());
                }
            } catch (RuntimeException e) {
                MeshCut.LOGGER.error("Clearing away the old pieces of cuts failed", e);
            }
        }

        private boolean isDebris(ServerSubLevel sub, double maxVolume) {
            BoundingBox3ic box = sub.getPlot().getBoundingBox();
            long blocks = (long) (box.maxX() - box.minX() + 1) * (box.maxY() - box.minY() + 1) * (box.maxZ() - box.minZ() + 1);
            if (blocks > MAX_PLOT_BLOCKS) {
                return false;
            }
            double volume = 0;
            boolean cut = false;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int x = box.minX(); x <= box.maxX(); x++) {
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        pos.set(x, y, z);
                        if (!level.hasChunkAt(pos)) {
                            return false;
                        }
                        BlockState state = level.getBlockState(pos);
                        if (state.isAir()) {
                            continue;
                        }
                        if (state.getBlock() instanceof MimicBlock && level.getBlockEntity(pos) instanceof MimicBlockEntity be) {
                            cut = true;
                            volume += be.getVolumeFraction();
                        } else {
                            volume += 1;
                        }
                        if (volume >= maxVolume) {
                            return false;
                        }
                    }
                }
            }
            return cut; // a few plain blocks someone assembled are not debris
        }
    }

    private Debris() {}
}
