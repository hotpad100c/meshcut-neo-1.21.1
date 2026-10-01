package ml.mypals.meshcut.block;

import ml.mypals.meshcut.cut.CapMode;
import ml.mypals.meshcut.cut.CutPlane;
import ml.mypals.meshcut.geom.Patch;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Immutable snapshot of everything that decides how a mimic looks. It is handed to the block model through
 * {@code ModelData} and doubles as the key of the client's mesh cache, so identical cuts share one mesh.
 *
 * @param planes   cuts that really separated this piece: the block is clipped by them
 * @param excluded convex parts of the block that were cut out and went elsewhere
 * @param patches  cuts that took nothing out: slits where they go right through, scratches where they stop midway
 */
public record MimicRenderData(BlockState target, @Nullable CompoundTag beTag, List<CutPlane> planes,
                              List<List<CutPlane>> excluded, List<Patch> patches, CapMode capMode) {
    public static final ModelProperty<MimicRenderData> PROPERTY = new ModelProperty<>();
}
