package ml.mypals.meshcut.mixin;

import dev.ryanhcode.sable.physics.floating_block.FloatingBlockController;
import dev.ryanhcode.sable.physics.floating_block.FloatingClusterContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = FloatingBlockController.class, remap = false)
public interface FloatingBlockControllerAccessor {
    @Accessor("sublevelContainer")
    FloatingClusterContainer meshcut$container();
}
