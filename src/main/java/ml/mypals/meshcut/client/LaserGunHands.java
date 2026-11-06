package ml.mypals.meshcut.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.item.LaserGunItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import java.util.HashMap;
import java.util.Map;

@EventBusSubscriber(modid = MeshCut.MODID, value = Dist.CLIENT)
public final class LaserGunHands {
    private record Kick(double time, float initial, int slot) {}
    private static final Map<Integer, Kick> kicks = new HashMap<>();
    private static Object world;
    private static Matrix4f firstFrame;
    private static Vec3 firstMuzzle;
    private static PlayerModel<AbstractClientPlayer> wideModel, slimModel;

    public static void clearModels() { wideModel = slimModel = null; }

    private static void syncWorld() {
        var level = Minecraft.getInstance().level;
        if (world != level) { kicks.clear(); firstFrame = null; firstMuzzle = null; world = level; }
    }

    public static void shoot(Player player, ItemStack stack) {
        syncWorld();
        float initial = recoil(player);
        if (kicks.size() >= 128 && !kicks.containsKey(player.getId())) kicks.clear();
        kicks.put(player.getId(), new Kick(time(), initial, player.getInventory().selected));
    }

    public static void acceptShot(int shooter) {
        var mc = Minecraft.getInstance();
        syncWorld();
        if (mc.level == null || mc.player != null && shooter == mc.player.getId()) return;
        if (mc.level.getEntity(shooter) instanceof Player player) shoot(player, player.getMainHandItem());
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        syncWorld();
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        kicks.entrySet().removeIf(e -> !(level.getEntity(e.getKey()) instanceof Player p)
                || !(p.getMainHandItem().getItem() instanceof LaserGunItem)
                || p == Minecraft.getInstance().player && p.getInventory().selected != e.getValue().slot
                || time() - e.getValue().time > 24);
    }

    private static double time() {
        var mc = Minecraft.getInstance();
        return mc.level == null ? 0 : mc.level.getGameTime() + mc.getTimer().getGameTimeDeltaPartialTick(true);
    }

    private static float recoil(Player player) {
        Kick kick = kicks.get(player.getId());
        return kick == null ? 0 : GunRecoil.amount(time() - kick.time, kick.initial);
    }

    public static void poseArm(Player player, float partial, ModelPart arm) {
        var pose = GunPose.arm(player.getMainArm() == HumanoidArm.RIGHT, player.isCrouching(),
                Mth.wrapDegrees(Mth.rotLerp(partial, player.yRotO, player.getYRot())
                        - Mth.rotLerp(partial, player.yBodyRotO, player.yBodyRot)),
                Mth.lerp(partial, player.xRotO, player.getXRot()), recoil(player));
        arm.x = pose.x(); arm.y = pose.y(); arm.z = pose.z();
        arm.xRot = pose.pitch(); arm.yRot = pose.yaw(); arm.zRot = 0;
        arm.xScale = arm.yScale = arm.zScale = 1;
    }

    public static Vec3 muzzle(int shooter, float partial, Vec3 fallback) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.level.getEntity(shooter) instanceof Player p)
                || !(p.getMainHandItem().getItem() instanceof LaserGunItem)) return fallback;
        if (p == mc.player && mc.options.getCameraType().isFirstPerson() && firstMuzzle != null) return firstMuzzle;
        Vector3f point = GunPose.thirdPerson(p.getMainArm() == HumanoidArm.RIGHT, p.isCrouching(),
                Mth.rotLerp(partial, p.yBodyRotO, p.yBodyRot), Mth.rotLerp(partial, p.yRotO, p.getYRot()),
                Mth.lerp(partial, p.xRotO, p.getXRot()), p.getScale(), recoil(p)).transformPosition(GunPose.muzzle());
        return p.getPosition(partial).add(point.x, point.y, point.z);
    }

    @SubscribeEvent
    public static void hideVanillaHand(RenderHandEvent event) {
        if (event.getHand() == InteractionHand.MAIN_HAND && event.getItemStack().getItem() instanceof LaserGunItem)
            event.setCanceled(true);
    }

    @SubscribeEvent
    public static void captureFrame(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        syncWorld();
        var mc = Minecraft.getInstance();
        firstFrame = null; firstMuzzle = null;
        if (mc.player == null || mc.player.isSpectator() || mc.getCameraEntity() != mc.player
                || !mc.options.getCameraType().isFirstPerson() || mc.options.hideGui
                || !(mc.player.getMainHandItem().getItem() instanceof LaserGunItem)) return;
        firstFrame = new Matrix4f().rotate(event.getCamera().rotation()).mul(GunPose.firstPerson(
                mc.player.getMainArm() == HumanoidArm.RIGHT, recoil(mc.player), 0, event.getProjectionMatrix().m11()));
        Vector3f point = firstFrame.transformPosition(GunPose.muzzle());
        firstMuzzle = event.getCamera().getPosition().add(point.x, point.y, point.z);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER || firstFrame == null) return;
        var mc = Minecraft.getInstance();
        var player = mc.player;
        if (player == null || !(player.getMainHandItem().getItem() instanceof LaserGunItem)) return;
        mc.renderBuffers().bufferSource().endBatch();
        boolean previousDepthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int previousTarget = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        try {
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, mc.getMainRenderTarget().frameBufferId);
            RenderSystem.depthMask(true);
            renderHeldGun(event);
        } finally {
            RenderSystem.depthMask(previousDepthWrite);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousTarget);
        }
    }

    private static void renderHeldGun(RenderLevelStageEvent event) {
        var mc = Minecraft.getInstance();
        var player = mc.player;
        boolean right = player.getMainArm() == HumanoidArm.RIGHT;
        boolean slim = player.getSkin().model() == PlayerSkin.Model.SLIM;
        int light = mc.getEntityRenderDispatcher().getPackedLightCoords(player, event.getPartialTick().getGameTimeDeltaPartialTick(true));
        var buffers = mc.renderBuffers().bufferSource();
        PoseStack pose = new PoseStack();
        pose.mulPose(new Matrix4f(event.getPoseStack().last().pose()).mul(firstFrame));
        if (!player.isInvisible()) {
            if (slim && slimModel == null) slimModel = new PlayerModel<>(mc.getEntityModels().bakeLayer(ModelLayers.PLAYER_SLIM), true);
            if (!slim && wideModel == null) wideModel = new PlayerModel<>(mc.getEntityModels().bakeLayer(ModelLayers.PLAYER), false);
            var model = slim ? slimModel : wideModel;
            ModelPart arm = right ? model.rightArm : model.leftArm;
            ModelPart sleeve = right ? model.rightSleeve : model.leftSleeve;
            arm.resetPose();
            arm.xRot = arm.yRot = arm.zRot = 0;
            arm.xScale = arm.yScale = arm.zScale = 1;
            sleeve.copyFrom(arm);
            pose.pushPose();
            Vector3f grip = GunRecoil.grip();
            pose.translate(grip.x, grip.y, grip.z);
            pose.mulPose(Axis.XP.rotationDegrees(-55));
            pose.scale(1.05f, 1.05f, 1.05f);
            pose.translate((right ? 1 : -1) * (slim ? 5.5f : 6f) / 16, -10.5f / 16, 0);
            arm.render(pose, buffers.getBuffer(RenderType.entitySolid(player.getSkin().texture())), light, OverlayTexture.NO_OVERLAY);
            if (player.isModelPartShown(right ? PlayerModelPart.RIGHT_SLEEVE : PlayerModelPart.LEFT_SLEEVE))
                sleeve.render(pose, buffers.getBuffer(RenderType.entityCutoutNoCull(player.getSkin().texture())), light, OverlayTexture.NO_OVERLAY);
            pose.popPose();
            buffers.endBatch(RenderType.entitySolid(player.getSkin().texture()));
            buffers.endBatch(RenderType.entityCutoutNoCull(player.getSkin().texture()));
        }
        mc.getEntityRenderDispatcher().getItemInHandRenderer().renderItem(player, player.getMainHandItem(),
                ItemDisplayContext.NONE, !right, pose, buffers, light);
        buffers.endBatch();
    }

    private LaserGunHands() {}
}
