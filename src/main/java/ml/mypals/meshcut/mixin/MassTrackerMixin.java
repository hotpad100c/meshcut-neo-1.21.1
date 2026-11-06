package ml.mypals.meshcut.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.api.physics.mass.MassTracker;
import ml.mypals.meshcut.block.MimicBlock;
import ml.mypals.meshcut.block.MimicBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.BiFunction;


@Mixin(value = MassTracker.class, remap = false)
public abstract class MassTrackerMixin {
    private static final String APPLY = "Ljava/util/function/BiFunction;apply(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

    @WrapOperation(method = "build", at = @At(value = "INVOKE", target = APPLY, ordinal = 0), remap = false)
    private static Object meshcut$comSum(BiFunction<Object, Object, Object> function, Object level, Object state,
                                         Operation<Object> original, @Local BlockPos.MutableBlockPos pos) {
        return meshcut$center(function, level, state, original, pos);
    }

    @WrapOperation(method = "build", at = @At(value = "INVOKE", target = APPLY, ordinal = 1), remap = false)
    private static Object meshcut$comInertia(BiFunction<Object, Object, Object> function, Object level, Object state,
                                             Operation<Object> original, @Local BlockPos.MutableBlockPos pos) {
        return meshcut$center(function, level, state, original, pos);
    }

    @WrapOperation(method = "addBlockMass", at = @At(value = "INVOKE", target = APPLY), remap = false)
    private Object meshcut$comIncremental(BiFunction<Object, Object, Object> function, Object level, Object state,
                                          Operation<Object> original, @Local(argsOnly = true) BlockPos pos) {
        return meshcut$center(function, level, state, original, pos);
    }

    private static Object meshcut$center(BiFunction<Object, Object, Object> function, Object level, Object state,
                                         Operation<Object> original, BlockPos pos) {
        if (state instanceof BlockState blockState && blockState.getBlock() instanceof MimicBlock
                && level instanceof BlockGetter getter && getter.getBlockEntity(pos) instanceof MimicBlockEntity be) {
            MimicBlockEntity.MassSnapshot mass = be.massFor(blockState);
            if (mass != null) {
                return mass.centerOfMass();
            }
        }
        return original.call(function, level, state);
    }
}
