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
 * @param patches  cuts that took nothing out: slits where they go right through
 * @param entity   for a part of a cut-up entity, what it looked like and where it is; null for a block
 * @param glowCuts the planes of the faces the last cut opened, which glow for a while
 */
public record MimicRenderData(BlockState target, @Nullable CompoundTag beTag, List<CutPlane> planes,
                              List<List<CutPlane>> excluded, List<Patch> patches, CapMode capMode,
                              @Nullable EntityAppearance entity, List<CutPlane> glowCuts) {
    /**
     * What decides the chunk mesh: not the glow history, the cap mode or the saved data of the block entity, which
     * change without the shape changing (and must not make every block's mesh be cut again).
     */
    public record Geometry(BlockState target, List<CutPlane> planes, List<List<CutPlane>> excluded, List<Patch> patches,
                           @Nullable EntityAppearance entity) {}

    public Geometry geometry() {
        return new Geometry(target, planes, excluded, patches, entity);
    }

    public static final ModelProperty<MimicRenderData> PROPERTY = new ModelProperty<>();
    /** Where the block is, so its chunk can be told when a mesh it waited for is ready. */
    public static final ModelProperty<net.minecraft.core.BlockPos> POS = new ModelProperty<>();
}
