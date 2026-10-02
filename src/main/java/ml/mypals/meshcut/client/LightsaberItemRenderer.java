package ml.mypals.meshcut.client;

import com.mojang.blaze3d.systems.RenderSystem;
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
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Draws only the hilt and exports its actual emitter pose to the world beam renderer. */
@EventBusSubscriber(modid = MeshCut.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class LightsaberItemRenderer extends BlockEntityWithoutLevelRenderer {
    private static final ModelResourceLocation HILT = ModelResourceLocation.standalone(
            ResourceLocation.fromNamespaceAndPath(MeshCut.MODID, "item/lightsaber_hilt"));
    // Coordinates are normalized from the hilt model's (8, 15.35, 8) emitter cap.
    private static final Vector3f EMITTER = new Vector3f(0.5f, 15.35f / 16f, 0.5f);
    private static final Vector3f GRIP = new Vector3f(0.5f, 6.4f / 16f, 0.5f);

    private LightsaberItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
    }

    @SubscribeEvent
    public static void registerModels(ModelEvent.RegisterAdditional event) {
        event.register(HILT);
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
            Matrix4f toWorld = worldPose(context, poseStack.last().pose());
            if (toWorld != null) {
                Vec3 direction = Blade.of(owner, minecraft.getTimer().getGameTimeDeltaPartialTick(true)).direction().normalize();
                Vector3f localDirection = LightsaberProjection.localDirection(toWorld, GRIP,
                        new Vector3f((float) direction.x, (float) direction.y, (float) direction.z));
                // Keep the grip in the hand while aiming the emitter along the cutting direction.
                poseStack.pushPose();
                poseStack.translate(GRIP.x, GRIP.y, GRIP.z);
                poseStack.mulPose(new Quaternionf().rotationTo(0, 1, 0,
                        localDirection.x, localDirection.y, localDirection.z));
                poseStack.translate(-GRIP.x, -GRIP.y, -GRIP.z);
                Matrix4f emitterPose = worldPose(context, poseStack.last().pose());
                if (emitterPose != null) {
                    Vector3f emitter = emitterPose.transformProject(new Vector3f(EMITTER));
                    LightsaberRenderer.captureEmitter(owner, context.firstPerson(),
                            new Vec3(emitter.x, emitter.y, emitter.z));
                }
                renderHilt(stack, poseStack, buffers, light, overlay);
                poseStack.popPose();
                return;
            }
        }
        renderHilt(stack, poseStack, buffers, light, overlay);
    }

    @Nullable
    private static Matrix4f worldPose(ItemDisplayContext context, Matrix4f itemPose) {
        return context.firstPerson()
                ? LightsaberRenderer.firstPersonWorldPose(RenderSystem.getProjectionMatrix(),
                        RenderSystem.getModelViewMatrix(), itemPose)
                : new Matrix4f(itemPose);
    }

    @Nullable
    private static Player findOwner(Minecraft minecraft, ItemStack stack, ItemDisplayContext context) {
        if (minecraft.level == null) return null;
        if (context.firstPerson()) {
            Player player = minecraft.player;
            if (player == null) return null;
            ItemDisplayContext mainHand = player.getMainArm() == HumanoidArm.RIGHT
                    ? ItemDisplayContext.FIRST_PERSON_RIGHT_HAND : ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
            return context == mainHand && player.getMainHandItem().getItem() instanceof LightsaberItem ? player : null;
        }
        if (context != ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
                && context != ItemDisplayContext.THIRD_PERSON_LEFT_HAND) return null;
        for (Player player : minecraft.level.players()) {
            if (player.getMainHandItem() == stack) return player;
        }
        return null;
    }

    private static void renderHilt(ItemStack stack, PoseStack poseStack, MultiBufferSource buffers, int light, int overlay) {
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
