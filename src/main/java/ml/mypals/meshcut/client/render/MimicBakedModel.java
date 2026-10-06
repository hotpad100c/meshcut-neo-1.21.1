package ml.mypals.meshcut.client.render;

import ml.mypals.meshcut.block.MimicRenderData;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.IDynamicBakedModel;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Model of the mimic block: instead of fixed quads it produces the imitated block's quads, clipped by the cuts, so
 * the cut block is part of the ordinary chunk mesh (with face culling, lighting and batching) rather than being
 * drawn by a block entity renderer every frame.
 */
public final class MimicBakedModel implements IDynamicBakedModel {
    private final BakedModel fallback;

    public MimicBakedModel(BakedModel fallback) {
        this.fallback = fallback;
    }

    @Override
    @NotNull
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, @NotNull RandomSource rand,
                                    @NotNull ModelData data, @Nullable RenderType renderType) {
        MimicMesh mesh = meshFor(data);
        return mesh == null ? List.of() : mesh.quads(renderType, side);
    }

    @Override
    @NotNull
    public ChunkRenderTypeSet getRenderTypes(@NotNull BlockState state, @NotNull RandomSource rand, @NotNull ModelData data) {
        // Sable draws a sub-level that is a single block through a wrapper whose getModelData() is always empty, so
        // here the layers must be answered without data: claim all of them. Layers with no quads cost nothing, and
        // the quads themselves are requested later with the real data.
        MimicMesh mesh = meshFor(data);
        return mesh == null ? ChunkRenderTypeSet.all() : mesh.layers();
    }

    @Override
    @NotNull
    public TextureAtlasSprite getParticleIcon(@NotNull ModelData data) {
        MimicMesh mesh = meshFor(data);
        TextureAtlasSprite sprite = mesh == null ? null : mesh.particle();
        return sprite != null ? sprite : fallback.getParticleIcon();
    }

    /** From the block entity's model data; null if the caller couldn't supply it. */
    @Nullable
    private static MimicMesh meshFor(ModelData data) {
        MimicRenderData render = data.get(MimicRenderData.PROPERTY);
        return render == null ? null : MimicMesh.of(render, data.get(MimicRenderData.POS));
    }

    @Override
    public boolean useAmbientOcclusion() {
        return true;
    }

    @Override
    public boolean isGui3d() {
        return false;
    }

    @Override
    public boolean usesBlockLight() {
        return true;
    }

    @Override
    public boolean isCustomRenderer() {
        return false;
    }

    @Override
    @NotNull
    public TextureAtlasSprite getParticleIcon() {
        return fallback.getParticleIcon();
    }

    @Override
    @NotNull
    public ItemTransforms getTransforms() {
        return fallback.getTransforms();
    }

    @Override
    @NotNull
    public ItemOverrides getOverrides() {
        return fallback.getOverrides();
    }
}
