package ml.mypals.meshcut.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.physics.floating_block.FloatingClusterContainer;
import ml.mypals.meshcut.physics.MimicFloating;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;


@Mixin(value = FloatingClusterContainer.class, remap = false)
public abstract class FloatingClusterContainerMixin {
    private static final String ADD = "Ldev/ryanhcode/sable/physics/floating_block/FloatingClusterContainer;addFloatingBlock(Lnet/minecraft/world/level/block/state/BlockState;Lorg/joml/Vector3d;)V";
    private static final String REMOVE = "Ldev/ryanhcode/sable/physics/floating_block/FloatingClusterContainer;removeFloatingBlock(Lnet/minecraft/world/level/block/state/BlockState;Lorg/joml/Vector3d;)V";
    private static final String SCALE = "Ldev/ryanhcode/sable/physics/config/block_properties/PhysicsBlockPropertyHelper;getFloatingScale(Lnet/minecraft/world/level/block/state/BlockState;)D";

    @WrapOperation(method = "processBlockChanges", at = @At(value = "INVOKE", target = ADD))
    private void meshcut$takeIn(FloatingClusterContainer self, BlockState state, Vector3d at, Operation<Void> original,
                                @Local(argsOnly = true) Vector3dc offset) {
        MimicFloating.takingIn(self, meshcut$pos(at, offset));
        try {
            original.call(self, state, at);
        } finally {
            MimicFloating.done();
        }
    }

    @WrapOperation(method = "processBlockChanges", at = @At(value = "INVOKE", target = REMOVE))
    private void meshcut$letGo(FloatingClusterContainer self, BlockState state, Vector3d at, Operation<Void> original,
                               @Local(argsOnly = true) Vector3dc offset) {
        MimicFloating.lettingGo(self, meshcut$pos(at, offset));
        try {
            original.call(self, state, at);
        } finally {
            MimicFloating.done();
        }
    }

    @WrapOperation(method = {"addFloatingBlock", "removeFloatingBlock"}, at = @At(value = "INVOKE", target = SCALE))
    private double meshcut$scale(BlockState state, Operation<Double> original) {
        return MimicFloating.scale(original.call(state));
    }

    private static long meshcut$pos(Vector3d at, Vector3dc offset) {
        return BlockPos.containing(at.x + offset.x(), at.y + offset.y(), at.z + offset.z()).asLong();
    }
}
