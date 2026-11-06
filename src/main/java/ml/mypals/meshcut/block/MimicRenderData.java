package ml.mypals.meshcut.block;

import ml.mypals.meshcut.cut.CapMode;
import ml.mypals.meshcut.cut.CutPlane;
import ml.mypals.meshcut.geom.Patch;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public record MimicRenderData(BlockState target, @Nullable CompoundTag beTag, List<CutPlane> planes,
                              List<List<CutPlane>> excluded, List<Patch> patches, CapMode capMode,
                              @Nullable EntityAppearance entity, List<CutPlane> glowCuts) {
    public record Geometry(BlockState target, List<CutPlane> planes, List<List<CutPlane>> excluded, List<Patch> patches,
                           @Nullable EntityAppearance entity) {}

    public Geometry geometry() {
        return new Geometry(target, planes, excluded, patches, entity);
    }

    public static final ModelProperty<MimicRenderData> PROPERTY = new ModelProperty<>();
    public static final ModelProperty<net.minecraft.core.BlockPos> POS = new ModelProperty<>();
}
