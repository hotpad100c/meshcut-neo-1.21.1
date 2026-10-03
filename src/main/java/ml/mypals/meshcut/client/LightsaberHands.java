package ml.mypals.meshcut.client;

import com.mojang.blaze3d.vertex.PoseStack;
import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.cut.Blade;
import ml.mypals.meshcut.cut.SaberPose;
import ml.mypals.meshcut.cut.SaberGesture;
import ml.mypals.meshcut.item.LightsaberItem;
import ml.mypals.meshcut.item.LightsaberMotion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** First-person skin arms, hilt and beam share the world pass and its existing depth buffer. */
@EventBusSubscriber(modid = MeshCut.MODID, value = Dist.CLIENT)
public final class LightsaberHands {
    private static PlayerModel<AbstractClientPlayer> wideModel;
    private static PlayerModel<AbstractClientPlayer> slimModel;

    static void clearModels() {
        wideModel = slimModel = null;
        LightsaberBurns.clear();
    }

    @SubscribeEvent
    public static void hideVanillaHands(RenderHandEvent event) {
        Player player = Minecraft.getInstance().player;
        if (player != null && LightsaberItem.isActive(player)) event.setCanceled(true);
    }

    public static void poseArms(Player player, float partial, ModelPart right, ModelPart left) {
        var aim = LightsaberMotion.angles(player, partial);
        float relativeYaw = Mth.wrapDegrees(aim.x - Mth.rotLerp(partial, player.yBodyRotO, player.yBodyRot));
        Vector3f translation = LightsaberMotion.handOffset(player, partial)
                .rotateY((float) Math.toRadians(Mth.rotLerp(partial, player.yBodyRotO, player.yBodyRot)))
                .div(SaberPose.PLAYER_MODEL_SCALE);
        pose(right, SaberPose.armPose(relativeYaw, aim.y, false, player.getMainArm() == HumanoidArm.RIGHT,
                player.isCrouching(), translation), false, player.isCrouching());
        pose(left, SaberPose.armPose(relativeYaw, aim.y, true, player.getMainArm() == HumanoidArm.LEFT,
                player.isCrouching(), translation), true, player.isCrouching());
    }

    private static void pose(ModelPart arm, SaberPose.ArmPose pose, boolean left, boolean crouching) {
        arm.x = left ? 5 : -5;
        arm.y = crouching ? 5.2f : 2;
        arm.z = 0;
        arm.xRot = pose.pitch();
        arm.yRot = pose.yaw();
        arm.zRot = 0;
        arm.xScale = arm.zScale = 1;
        arm.yScale = pose.lengthScale();
    }

    public static Matrix4f hiltPose(Blade blade, Vec3 camera, float entityScale) {
        Vec3 origin = blade.base().subtract(camera);
        Vec3 dir = blade.direction().normalize();
        return SaberPose.hiltPose(new Vector3f((float) origin.x, (float) origin.y, (float) origin.z),
                new Vector3f((float) dir.x, (float) dir.y, (float) dir.z), entityScale);
    }

    public static void render(AbstractClientPlayer player, Blade blade, float partial, Vec3 camera,
                              Matrix4f view, MultiBufferSource.BufferSource buffers) {
        Minecraft minecraft = Minecraft.getInstance();
        int light = minecraft.getEntityRenderDispatcher().getPackedLightCoords(player, partial);
        PoseStack hilt = new PoseStack();
        hilt.mulPose(new Matrix4f(view).mul(hiltPose(blade, camera, player.getScale())));
        LightsaberItemRenderer.renderHilt(player.getMainHandItem(), hilt, buffers, light, OverlayTexture.NO_OVERLAY);
        buffers.endBatch();
        if (player.isInvisible()) return;

        boolean slim = player.getSkin().model() == PlayerSkin.Model.SLIM;
        if (slim && slimModel == null)
            slimModel = new PlayerModel<>(minecraft.getEntityModels().bakeLayer(ModelLayers.PLAYER_SLIM), true);
        if (!slim && wideModel == null)
            wideModel = new PlayerModel<>(minecraft.getEntityModels().bakeLayer(ModelLayers.PLAYER), false);
        PlayerModel<AbstractClientPlayer> model = slim ? slimModel : wideModel;
        poseArms(player, partial, model.rightArm, model.leftArm);
        model.rightSleeve.copyFrom(model.rightArm);
        model.leftSleeve.copyFrom(model.leftArm);
        Vec3 position = player.getPosition(partial).subtract(camera);
        float scale = player.getScale();
        PoseStack arms = new PoseStack();
        arms.mulPose(view);
        arms.translate(position.x, position.y - (player.isCrouching() ? scale / 8 : 0), position.z);
        arms.scale(scale, scale, scale);
        arms.mulPose(new Quaternionf().rotationY((float) Math.toRadians(
                180 - Mth.rotLerp(partial, player.yBodyRotO, player.yBodyRot))));
        arms.scale(-SaberPose.PLAYER_MODEL_SCALE, -SaberPose.PLAYER_MODEL_SCALE, SaberPose.PLAYER_MODEL_SCALE);
        arms.translate(0, -1.501, 0);
        float opacity = SaberGesture.handOpacity(Mth.lerp(partial, player.xRotO, player.getXRot()));
        int tint = ((int) (opacity * 255) << 24) | 0xffffff;
        var skin = opacity > 0.999f ? RenderType.entitySolid(player.getSkin().texture())
                : LightsaberRenderTypes.FADED_HANDS.apply(player.getSkin().texture());
        model.rightArm.render(arms, buffers.getBuffer(skin), light, OverlayTexture.NO_OVERLAY, tint);
        model.leftArm.render(arms, buffers.getBuffer(skin), light, OverlayTexture.NO_OVERLAY, tint);
        var sleeves = opacity > 0.999f ? RenderType.entityCutoutNoCull(player.getSkin().texture()) : skin;
        if (player.isModelPartShown(PlayerModelPart.RIGHT_SLEEVE))
            model.rightSleeve.render(arms, buffers.getBuffer(sleeves), light, OverlayTexture.NO_OVERLAY, tint);
        if (player.isModelPartShown(PlayerModelPart.LEFT_SLEEVE))
            model.leftSleeve.render(arms, buffers.getBuffer(sleeves), light, OverlayTexture.NO_OVERLAY, tint);
        buffers.endBatch(skin);
        buffers.endBatch(sleeves);
    }

    private LightsaberHands() {}
}
