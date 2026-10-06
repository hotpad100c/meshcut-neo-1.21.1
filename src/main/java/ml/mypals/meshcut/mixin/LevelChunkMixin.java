package ml.mypals.meshcut.mixin;

import ml.mypals.meshcut.cut.graph.WorldGraphs;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Tells the structure graph about every block that changes on the server, whoever changed it. */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    @Shadow
    @Final
    private Level level;

    /** A client is told of a mimic before it is told what the mimic holds: remember what it replaces meanwhile. */
    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void meshcut$replaced(BlockPos pos, BlockState state, boolean isMoving, CallbackInfoReturnable<BlockState> cir) {
        if (level.isClientSide && state.getBlock() instanceof ml.mypals.meshcut.block.MimicBlock) {
            BlockState old = ((LevelChunk) (Object) this).getBlockState(pos);
            if (!old.isAir() && !(old.getBlock() instanceof ml.mypals.meshcut.block.MimicBlock)) {
                ml.mypals.meshcut.block.MimicBlockEntity.rememberReplaced(pos, old);
            }
        }
    }

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void meshcut$blockChanged(BlockPos pos, BlockState state, boolean isMoving, CallbackInfoReturnable<BlockState> cir) {
        BlockState old = cir.getReturnValue();
        if (old != null && level instanceof ServerLevel server) {
            WorldGraphs.onBlockChanged(server, pos, old, state);
        }
    }
}
