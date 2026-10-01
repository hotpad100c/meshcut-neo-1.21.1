package ml.mypals.meshcut.client.render;

import dev.ryanhcode.sable.Sable;
import ml.mypals.meshcut.block.MimicBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Tints (grass, leaves, water, ...) of a cut block come from the block it imitates. */
public final class MimicTint {
    /** {@code BlockColor} of the mimic block itself. */
    public static int color(@Nullable BlockAndTintGetter level, @Nullable BlockPos pos, int tintIndex) {
        if (level == null || pos == null || !(level.getBlockEntity(pos) instanceof MimicBlockEntity mimic)) {
            return -1;
        }
        return color(mimic.getTargetState(), level, pos, tintIndex);
    }

    /**
     * Biome-dependent tints are looked up where the block really is. A block inside a sub-level sits in plot space
     * far from the world, whose biome has nothing to do with the terrain the sub-level is over.
     */
    public static int color(BlockState target, BlockAndTintGetter level, BlockPos pos, int tintIndex) {
        Minecraft client = Minecraft.getInstance();
        BlockPos at = pos;
        if (client.level != null) {
            at = BlockPos.containing(Sable.HELPER.projectOutOfSubLevel(client.level, Vec3.atCenterOf(pos)));
        }
        return client.getBlockColors().getColor(target, level, at, tintIndex);
    }

    private MimicTint() {}
}
