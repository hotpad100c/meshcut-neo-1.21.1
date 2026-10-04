package ml.mypals.meshcut.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.physics.impl.rapier.collider.RapierVoxelColliderBakery;
import dev.ryanhcode.sable.physics.impl.rapier.collider.RapierVoxelColliderData;
import dev.ryanhcode.sable.util.LevelAccelerator;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import ml.mypals.meshcut.physics.MimicColliders;
import ml.mypals.meshcut.physics.PhysicsDiag;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Sable asks its collider bakery for a collider per block <em>state</em>. For mimic blocks the collider depends on
 * the block entity at that position, so those lookups are rerouted through {@link MimicColliders}.
 */
@Mixin(targets = "dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline", remap = false)
public abstract class RapierPhysicsPipelineMixin {
    private static final String BAKE = "Ldev/ryanhcode/sable/physics/impl/rapier/collider/RapierVoxelColliderBakery;"
            + "getPhysicsDataForBlock(Lnet/minecraft/world/level/block/state/BlockState;)"
            + "Ldev/ryanhcode/sable/physics/impl/rapier/collider/RapierVoxelColliderData;";

    @Shadow @Final private LevelAccelerator accelerator;
    @Shadow @Final private ServerLevel level;
    @Shadow @Final private Int2ObjectMap<?> activeSubLevels;

    /** Times the native step (see {@link PhysicsDiag}). */
    @WrapOperation(method = "physicsTick", at = @At(value = "INVOKE",
            target = "Ldev/ryanhcode/sable/physics/impl/rapier/Rapier3D;step(JD)V"), remap = false)
    private void meshcut$timeStep(long scene, double dt, Operation<Void> original) {
        if (!PhysicsDiag.enabled()) {
            original.call(scene, dt);
            return;
        }
        long start = System.nanoTime();
        original.call(scene, dt);
        PhysicsDiag.step(System.nanoTime() - start, activeSubLevels.size());
    }

    /** Whole chunk sections being uploaded. */
    @WrapOperation(method = "handleChunkSectionAddition", at = @At(value = "INVOKE", target = BAKE), remap = false)
    private RapierVoxelColliderData meshcut$onSection(RapierVoxelColliderBakery bakery, BlockState state,
                                                      Operation<RapierVoxelColliderData> original,
                                                      @Local(ordinal = 0) BlockPos pos) {
        return MimicColliders.resolve(accelerator, level, pos, state, () -> original.call(bakery, state));
    }

    /** Neighbours of a changed block get their collider re-evaluated too. */
    @WrapOperation(method = "handleBlockChange", at = @At(value = "INVOKE", target = BAKE, ordinal = 0), remap = false)
    private RapierVoxelColliderData meshcut$onNeighbour(RapierVoxelColliderBakery bakery, BlockState state,
                                                        Operation<RapierVoxelColliderData> original,
                                                        @Local(ordinal = 1) BlockPos pos) {
        return MimicColliders.resolve(accelerator, level, pos, state, () -> original.call(bakery, state));
    }

    /** The changed block itself. */
    @WrapOperation(method = "handleBlockChange", at = @At(value = "INVOKE", target = BAKE, ordinal = 1), remap = false)
    private RapierVoxelColliderData meshcut$onBlock(RapierVoxelColliderBakery bakery, BlockState state,
                                                    Operation<RapierVoxelColliderData> original,
                                                    @Local(ordinal = 0) BlockPos pos) {
        return MimicColliders.resolve(accelerator, level, pos, state, () -> original.call(bakery, state));
    }
}
