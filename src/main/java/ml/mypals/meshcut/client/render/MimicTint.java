package ml.mypals.meshcut.client.render;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.block.MimicBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The tint of what a mimic imitates (grass, leaves, water), taken where the block really is: a block in a sub-level
 * lies in a plot far out, whose biome says nothing, so it is tinted by the place in the world the sub-level is over.
 * <p>
 * Tints are asked for while chunk sections are meshed, on worker threads, which must not read Sable's poses (the
 * main thread changes them meanwhile). The poses are copied once a tick on the main thread instead, and the workers
 * read the copies.
 */
@EventBusSubscriber(modid = MeshCut.MODID, value = Dist.CLIENT)
public final class MimicTint {
    /** Where each sub-level was at the last tick, by id; replaced as a whole, never changed. */
    private static volatile Map<UUID, Pose3dc> poses = Map.of();

    public static int color(@Nullable BlockAndTintGetter level, @Nullable BlockPos pos, int tintIndex) {
        if (level == null || pos == null || !(level.getBlockEntity(pos) instanceof MimicBlockEntity mimic)) {
            return -1;
        }
        return color(mimic.getTargetState(), level, pos, tintIndex);
    }

    public static int color(BlockState target, BlockAndTintGetter level, BlockPos pos, int tintIndex) {
        Minecraft client = Minecraft.getInstance();
        return client.getBlockColors().getColor(target, level, inWorld(client.level, pos), tintIndex);
    }

    /** Where the block is in the world, as of the last tick; the block's own position if it is in no sub-level. */
    private static BlockPos inWorld(@Nullable ClientLevel level, BlockPos pos) {
        if (level == null) {
            return pos;
        }
        try {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null || !container.inBounds(pos)) {
                return pos;
            }
            SubLevel sub = Sable.HELPER.getContaining(level, pos); // which plot: a lookup, no pose read
            Pose3dc pose = sub == null ? null : poses.get(sub.getUniqueId());
            return pose == null ? pos : BlockPos.containing(pose.transformPosition(Vec3.atCenterOf(pos)));
        } catch (RuntimeException e) {
            return pos; // a tint a little off rather than no chunk at all
        }
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        ClientLevel level = Minecraft.getInstance().level;
        SubLevelContainer container = level == null ? null : SubLevelContainer.getContainer(level);
        if (container == null) {
            poses = Map.of();
            return;
        }
        Map<UUID, Pose3dc> now = new HashMap<>();
        for (SubLevel sub : container.getAllSubLevels()) {
            if (sub instanceof ClientSubLevel client && !client.isRemoved()) {
                Pose3dc pose = client.renderPose(1f);
                Pose3d copy = new Pose3d();
                copy.position().set(pose.position());
                copy.orientation().set(pose.orientation());
                copy.rotationPoint().set(pose.rotationPoint());
                now.put(client.getUniqueId(), copy);
            }
        }
        poses = now;
    }

    private MimicTint() {}
}
