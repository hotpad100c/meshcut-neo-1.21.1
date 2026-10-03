package ml.mypals.meshcut.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import ml.mypals.meshcut.block.MimicBlock;
import ml.mypals.meshcut.block.MimicBlockEntity;
import ml.mypals.meshcut.block.MovedMimic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pistons move a mimic if they would move what it imitates. Vanilla never moves a block with a block entity, so the
 * mimic's data is taken along by the moving block ({@link MovedMimic}) and given back where it lands.
 */
@Mixin(PistonBaseBlock.class)
public abstract class PistonBaseBlockMixin {
    /** The data of the mimics a piston is about to move, by where they stand (per thread: client and server both move). */
    @Unique
    private static final ThreadLocal<Map<BlockPos, CompoundTag>> meshcut$moving = ThreadLocal.withInitial(HashMap::new);

    @Inject(method = "isPushable", at = @At("HEAD"), cancellable = true)
    private static void meshcut$likeWhatItImitates(BlockState state, Level level, BlockPos pos, Direction movement,
                                                   boolean allowDestroy, Direction pistonFacing, CallbackInfoReturnable<Boolean> cir) {
        if (state.getBlock() instanceof MimicBlock && level.getBlockEntity(pos) instanceof MimicBlockEntity be) {
            BlockState target = be.getTargetState();
            // a piece of a chest (or anything with a block entity of its own) stays, as the chest would
            cir.setReturnValue(!(target.getBlock() instanceof MimicBlock)
                    && PistonBaseBlock.isPushable(target, level, pos, movement, allowDestroy, pistonFacing));
        }
    }

    /** Before anything moves (blocks may land where others stood), the data of every mimic that will move is kept. */
    @WrapOperation(method = "moveBlocks", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/piston/PistonStructureResolver;getToPush()Ljava/util/List;"))
    private List<BlockPos> meshcut$keepData(PistonStructureResolver resolver, Operation<List<BlockPos>> original,
                                           @Local(argsOnly = true) Level level) {
        List<BlockPos> toPush = original.call(resolver);
        Map<BlockPos, CompoundTag> moving = meshcut$moving.get();
        moving.clear();
        for (BlockPos pos : toPush) {
            if (level.getBlockEntity(pos) instanceof MimicBlockEntity be) {
                moving.put(pos.immutable(), be.saveWithoutMetadata(level.registryAccess()));
            }
        }
        return toPush;
    }

    /** The moving block made for a mimic carries its data. */
    @WrapOperation(method = "moveBlocks", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/piston/MovingPistonBlock;newMovingBlockEntity(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;ZZ)Lnet/minecraft/world/level/block/entity/BlockEntity;"))
    private BlockEntity meshcut$carryData(BlockPos pos, BlockState state, BlockState moved, Direction facing, boolean extending,
                                          boolean source, Operation<BlockEntity> original) {
        BlockEntity entity = original.call(pos, state, moved, facing, extending, source);
        if (moved.getBlock() instanceof MimicBlock && entity instanceof MovedMimic carrier) {
            // it moves the way the piston faces when pushing, the other way when pulling
            BlockPos from = pos.relative(extending ? facing.getOpposite() : facing);
            carrier.meshcut$setMimic(meshcut$moving.get().get(from));
        }
        return entity;
    }
}
