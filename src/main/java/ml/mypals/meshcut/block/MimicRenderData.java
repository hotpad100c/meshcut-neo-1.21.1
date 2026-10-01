package ml.mypals.meshcut.block;

import ml.mypals.meshcut.cut.CapMode;
import ml.mypals.meshcut.cut.CutPlane;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Immutable snapshot of everything that decides how a mimic looks. It is handed to the block model through
 * {@code ModelData} and doubles as the key of the client's mesh cache, so identical cuts share one mesh.
 *
 * @param planes  cuts that really separated this piece: the block is clipped by them
 * @param pending cuts that went through without severing anything: drawn as a slit, the block is otherwise whole
 */
public record MimicRenderData(BlockState target, @Nullable CompoundTag beTag, List<CutPlane> planes,
                              List<CutPlane> pending, CapMode capMode) {
    public static final ModelProperty<MimicRenderData> PROPERTY = new ModelProperty<>();
}
