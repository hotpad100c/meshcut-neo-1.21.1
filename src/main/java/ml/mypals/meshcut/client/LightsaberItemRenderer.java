package ml.mypals.meshcut.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.cut.Blade;
import ml.mypals.meshcut.item.LightsaberItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

/** Uses the shared world hilt pose in third person, and the baked model in inventory/inactive views. */
@EventBusSubscriber(modid = MeshCut.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class LightsaberItemRenderer extends BlockEntityWithoutLevelRenderer {
    private static final ModelResourceLocation HILT = ModelResourceLocation.standalone(
            ResourceLocation.fromNamespaceAndPath(MeshCut.MODID, "item/lightsaber_hilt"));
    private LightsaberItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
    }

    @SubscribeEvent
    public static void registerModels(ModelEvent.RegisterAdditional event) {
        event.register(HILT);
        LightsaberHands.clearModels();
    }

    @SubscribeEvent
    public static void registerExtensions(RegisterClientExtensionsEvent event) {
        event.registerItem(new IClientItemExtensions() {
            private LightsaberItemRenderer renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (renderer == null) renderer = new LightsaberItemRenderer();
                return renderer;
            }
        }, MeshCut.LIGHTSABER.get());
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack poseStack,
                             MultiBufferSource buffers, int light, int overlay) {
        Minecraft minecraft = Minecraft.getInstance();
        Player owner = findOwner(minecraft, stack, context);
        if (owner != null && LightsaberItem.isActive(owner)) {
            Matrix4f toWorld = context.firstPerson() ? null : new Matrix4f(poseStack.last().pose());
            if (toWorld != null) {
                Blade blade = Blade.of(owner, minecraft.getTimer().getGameTimeDeltaPartialTick(true));
                Matrix4f desired = LightsaberHands.hiltPose(blade,
                        minecraft.gameRenderer.getMainCamera().getPosition(), owner.getScale());
                Matrix4f adjustment = new Matrix4f(toWorld).invert().mul(desired);
                poseStack.pushPose();
                try {
                    poseStack.mulPose(adjustment);
                    renderHilt(stack, poseStack, buffers, light, overlay);
                } finally {
                    poseStack.popPose();
                }
                return;
            }
        }
        renderHilt(stack, poseStack, buffers, light, overlay);
    }

    @Nullable
    private static Player findOwner(Minecraft minecraft, ItemStack stack, ItemDisplayContext context) {
        if (minecraft.level == null) return null;
        if (context != ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
                && context != ItemDisplayContext.THIRD_PERSON_LEFT_HAND) return null;
        for (Player player : minecraft.level.players()) {
            if (player.getMainHandItem() == stack) return player;
        }
        return null;
    }

    static void renderHilt(ItemStack stack, PoseStack poseStack, MultiBufferSource buffers, int light, int overlay) {
        Minecraft minecraft = Minecraft.getInstance();
        ItemRenderer renderer = minecraft.getItemRenderer();
        BakedModel model = minecraft.getModelManager().getModel(HILT);
        for (BakedModel pass : model.getRenderPasses(stack, true)) {
            for (RenderType type : pass.getRenderTypes(stack, true)) {
                VertexConsumer vertices = ItemRenderer.getFoilBufferDirect(buffers, type, true, stack.hasFoil());
                renderer.renderModelLists(pass, stack, light, overlay, poseStack, vertices);
            }
        }
    }

}
