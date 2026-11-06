package ml.mypals.meshcut.physics;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import ml.mypals.meshcut.block.MimicBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.ArrayList;
import java.util.List;

public final class WoodDebris {
    private record Burning(ServerLevel level, ServerSubLevel sub, long born) {}
    private static final List<Burning> BURNING = new ArrayList<>();

    public static boolean eligible(ServerLevel level, List<BlockPos> blocks, double volume) {
        if (volume > ml.mypals.meshcut.MeshCutConfig.WOOD_VOLUME.get()) return false;
        boolean cut = false;
        for (BlockPos pos : blocks) {
            var state = level.getBlockState(pos);
            if (state.isAir()) continue;
            if (level.getBlockEntity(pos) instanceof MimicBlockEntity be) {
                if (be.getAppearance() != null) return false;
                state = be.getTargetState();
                cut = true;
            }
            if (!(state.is(BlockTags.LOGS) || state.is(BlockTags.PLANKS)
                    || state.is(BlockTags.WOODEN_STAIRS) || state.is(BlockTags.WOODEN_SLABS)
                    || state.is(BlockTags.WOODEN_FENCES))) return false;
        }
        return cut && ml.mypals.meshcut.MeshCutConfig.WOOD_VOLUME.get() > 0;
    }
    public static void made(ServerLevel level, ServerSubLevel sub) {
        BURNING.add(new Burning(level, sub, level.getGameTime()));
    }
    public static void tick(ServerTickEvent.Post event) {
        for (var it = BURNING.iterator(); it.hasNext();) {
            Burning entry = it.next();
            if (entry.sub.isRemoved() || CutMarks.isKept(entry.sub)) { it.remove(); continue; }
            long age = entry.level.getGameTime() - entry.born;
            var at = entry.sub.logicalPose().position();
            if (age % 5 == 0) {
                entry.level.sendParticles(ParticleTypes.FLAME, at.x(), at.y(), at.z(), 3, .2, .2, .2, .02);
                entry.level.sendParticles(ParticleTypes.SMOKE, at.x(), at.y(), at.z(), 2, .15, .2, .15, .01);
                entry.level.sendParticles(ParticleTypes.SMALL_FLAME, at.x(), at.y(), at.z(), 3, .2, .2, .2, .04);
            }
            if (age >= ml.mypals.meshcut.MeshCutConfig.WOOD_SECONDS.get() * 20L) {
                var container = SubLevelContainer.getContainer(entry.level);
                if (container != null) {
                    container.removeSubLevel(entry.sub, SubLevelRemovalReason.REMOVED);
                    entry.level.sendParticles(ParticleTypes.POOF, at.x(), at.y(), at.z(), 4, .2, .2, .2, .01);
                }
                it.remove();
            }
        }
    }
    public static void clear() { BURNING.clear(); }
    private WoodDebris() {}
}
