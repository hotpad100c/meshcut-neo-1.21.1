package ml.mypals.meshcut.block;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * A block a piston is moving ({@code PistonMovingBlockEntity}, see {@code PistonMovingBlockEntityMixin}) that is a
 * mimic: it carries the mimic's data (what it imitates, its cuts) while it moves, and gives it back when it lands.
 */
public interface MovedMimic {
    @Nullable
    CompoundTag meshcut$mimic();

    void meshcut$setMimic(@Nullable CompoundTag data);

    /** The mimic just placed where the piston stopped gets its data back. */
    static void land(Level level, BlockPos pos, @Nullable CompoundTag data) {
        if (data == null || !(level.getBlockEntity(pos) instanceof MimicBlockEntity be)) {
            return;
        }
        be.loadWithComponents(data, level.registryAccess());
        be.setChanged();
        if (level instanceof ServerLevel server) {
            BlockState state = server.getBlockState(pos);
            server.sendBlockUpdated(pos, state, state, 3);
            MimicBlock.refreshPhysics(server, pos); // the collider was read for a mimic with no data yet
        }
    }
}
