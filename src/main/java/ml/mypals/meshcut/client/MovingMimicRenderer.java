package ml.mypals.meshcut.client;

import com.mojang.blaze3d.vertex.PoseStack;
import ml.mypals.meshcut.block.MimicBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.RenderTypeHelper;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.Map;
import java.util.WeakHashMap;

public final class MovingMimicRenderer {
    private static final Map<PistonMovingBlockEntity, MimicBlockEntity> STAND_INS = new WeakHashMap<>();

    public static void render(PistonMovingBlockEntity moving, CompoundTag data, BlockPos pos, BlockState state, PoseStack poseStack,
                              MultiBufferSource buffer, Level level, float partialTick, int packedLight, int packedOverlay) {
        MimicBlockEntity mimic = STAND_INS.get(moving);
        if (mimic == null) {
            mimic = new MimicBlockEntity(pos, state);
            mimic.loadWithComponents(data, level.registryAccess());
            mimic.setLevel(level);
            STAND_INS.put(moving, mimic);
        }
        var dispatcher = Minecraft.getInstance().getBlockRenderer();
        BakedModel model = dispatcher.getBlockModel(state);
        ModelData modelData = mimic.getModelData();
        long seed = state.getSeed(pos);
        for (RenderType type : model.getRenderTypes(state, RandomSource.create(seed), modelData)) {
            dispatcher.getModelRenderer().tesselateBlock(level, model, state, pos, poseStack,
                    buffer.getBuffer(RenderTypeHelper.getMovingBlockRenderType(type)), false, RandomSource.create(), seed,
                    packedOverlay, modelData, type);
        }
        var renderer = Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(mimic);
        if (renderer != null) {
            renderer.render(mimic, partialTick, poseStack, buffer, packedLight, packedOverlay);
        }
    }

    private MovingMimicRenderer() {}
}
