package ml.mypals.meshcut.mixin;

import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper;
import ml.mypals.meshcut.block.MimicBlock;
import ml.mypals.meshcut.block.MimicBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = PhysicsBlockPropertyHelper.class, remap = false)
public abstract class PhysicsBlockPropertyHelperMixin {
    @Inject(method = "getInertia", at = @At("HEAD"), cancellable = true)
    private static void meshcut$mimicInertia(BlockGetter level, BlockPos pos, BlockState state,
                                             CallbackInfoReturnable<net.minecraft.world.phys.Vec3> cir) {
        if (state.getBlock() instanceof MimicBlock && level.getBlockEntity(pos) instanceof MimicBlockEntity be) {
            MimicBlockEntity.MassSnapshot mass = be.massFor(state);
            if (mass != null) {
                net.minecraft.world.phys.Vec3 inertia = PhysicsBlockPropertyHelper.getInertia(level, pos, mass.target());
                cir.setReturnValue(inertia == null ? null : inertia.scale(mass.volumeFraction()));
            }
        }
    }

    @Inject(method = "getMass", at = @At("HEAD"), cancellable = true)
    private static void meshcut$mimicMass(BlockGetter level, BlockPos pos, BlockState state,
                                          CallbackInfoReturnable<Double> cir) {
        if (state.getBlock() instanceof MimicBlock && level.getBlockEntity(pos) instanceof MimicBlockEntity be) {
            MimicBlockEntity.MassSnapshot mass = be.massFor(state);
            if (mass != null) {
                double m;
                if (ml.mypals.meshcut.block.MimicShapes.hasCollision(mass.target())) {
                    m = PhysicsBlockPropertyHelper.getMass(level, pos, mass.target()) * mass.volumeFraction();
                } else {
                    // Sable gives no mass at all to a block one walks through (a torch, a flower, a sign). A piece of
                    // one collides with its outline instead (see MimicShapes#cutBoxes), and weighs what the block's
                    // own mass would make that much of a block weigh: a body made only of such pieces must weigh
                    // something, or it has no centre of mass and brings the physics engine down.
                    double whole = meshcut$ownMass(mass.target());
                    double outline = ml.mypals.meshcut.geom.VoxelBoxes.volume(ml.mypals.meshcut.block.MimicShapes.cutBoxes(mass.target()));
                    m = whole * outline * mass.volumeFraction();
                }
                cir.setReturnValue(Double.isFinite(m) && m > MESHCUT_MIN_MASS ? m : MESHCUT_MIN_MASS);
            }
        }
    }

    private static final double MESHCUT_MIN_MASS = 1e-3;
    private static final double MESHCUT_DEFAULT_MASS = 1.0;
    private static Object meshcut$massType;
    private static boolean meshcut$looked;

    @SuppressWarnings("unchecked")
    private static double meshcut$ownMass(BlockState target) {
        try {
            if (!meshcut$looked) {
                meshcut$looked = true;
                Object holder = dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyTypes.class
                        .getField("MASS").get(null);
                meshcut$massType = holder.getClass().getMethod("get").invoke(holder);
            }
            if (meshcut$massType != null) {
                Object value = ((dev.ryanhcode.sable.mixinterface.block_properties.BlockStateExtension) target).sable$getProperty(
                        (dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyTypes.PhysicsBlockPropertyType<Object>) meshcut$massType);
                if (value instanceof Double d && d > 0) {
                    return d;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            meshcut$massType = null;
        }
        return MESHCUT_DEFAULT_MASS;
    }
}
