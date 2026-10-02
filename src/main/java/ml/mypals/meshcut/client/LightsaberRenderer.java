package ml.mypals.meshcut.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.cut.Blade;
import ml.mypals.meshcut.item.LightsaberItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Quaternionf;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** World-space blades and trails use the cutting segment; the hilt is aligned to its origin. */
@EventBusSubscriber(modid = MeshCut.MODID, value = Dist.CLIENT)
public final class LightsaberRenderer {
    private static final float[] CORE = {1f, 1f, 1f, 1f};
    private static final float[] GLOW = {0.25f, 0.8f, 1f, 0.45f};
    private static final float[] HALO = {0.2f, 0.6f, 1f, 0.18f};
    private static final Matrix4f WORLD_PROJECTION = new Matrix4f();
    private static final Matrix4f WORLD_VIEW = new Matrix4f();
    @Nullable private static RenderTarget sceneDepth;
    @Nullable private static Matrix4f firstPersonHilt;
    @Nullable private static Blade firstPersonBlade;
    private static boolean firstPersonPrepared;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        Matrix4f matrix = poseStack.last().pose();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        Map<UUID, Blade> blades = new HashMap<>();
        for (Player player : minecraft.level.players()) {
            // This blade is drawn later in the same frame, after its hand pose has been captured.
            if (player == minecraft.player && minecraft.options.getCameraType().isFirstPerson()) continue;
            if (!LightsaberItem.isActive(player)) {
                continue;
            }
            blades.put(player.getUUID(), Blade.of(player, partialTick));
        }
        LightsaberTrail.renderWorld(event, blades);
        RenderType beamType = LightsaberRenderTypes.BEAM;
        VertexConsumer consumer = buffers.getBuffer(beamType);
        for (Blade blade : blades.values()) {
            drawBlade(consumer, matrix, blade.base().subtract(camera), blade.tip().subtract(camera));
        }
        buffers.endBatch(beamType);
    }

    /** Called immediately before vanilla clears scene depth for the first-person hand pass. */
    public static void prepareFirstPersonFrame() {
        firstPersonPrepared = false;
        firstPersonHilt = null;
        firstPersonBlade = null;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null
                || !minecraft.options.getCameraType().isFirstPerson() || minecraft.options.hideGui
                || !LightsaberItem.isActive(minecraft.player)) return;

        RenderTarget main = minecraft.getMainRenderTarget();
        if (sceneDepth == null || sceneDepth.isStencilEnabled() != main.isStencilEnabled()) {
            releaseDepthTarget();
            sceneDepth = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
            if (main.isStencilEnabled()) sceneDepth.enableStencil();
        } else if (sceneDepth.width != main.width || sceneDepth.height != main.height) {
            sceneDepth.resize(main.width, main.height, Minecraft.ON_OSX);
        }
        sceneDepth.copyDepthFrom(main);
        main.bindWrite(false);
        WORLD_PROJECTION.set(RenderSystem.getProjectionMatrix());
        WORLD_VIEW.rotation(minecraft.gameRenderer.getMainCamera().rotation().conjugate(new Quaternionf()));
        firstPersonPrepared = true;
    }

    @Nullable
    static Matrix4f firstPersonWorldPose(Matrix4f handProjection, Matrix4f handView, Matrix4f itemPose) {
        return firstPersonPrepared
                ? LightsaberProjection.itemToWorld(WORLD_PROJECTION, WORLD_VIEW, handProjection, handView, itemPose)
                : null;
    }

    static void captureFirstPersonHilt(Matrix4f pose, Blade blade) {
        if (firstPersonPrepared) {
            firstPersonHilt = new Matrix4f(pose);
            firstPersonBlade = blade;
        }
    }

    /** After hands have flushed, combine world and hilt depth before drawing the world blade. */
    public static void renderFirstPersonBeam(float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!firstPersonPrepared || firstPersonHilt == null || firstPersonBlade == null || sceneDepth == null
                || minecraft.player == null || !LightsaberItem.isActive(minecraft.player)) return;

        RenderTarget main = minecraft.getMainRenderTarget();
        main.copyDepthFrom(sceneDepth);
        main.bindWrite(false);
        Matrix4f handProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix().identity();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(WORLD_PROJECTION, VertexSorting.DISTANCE_TO_ORIGIN);
        try {
            MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
            LightsaberItemRenderer.renderHiltDepth(minecraft.player.getMainHandItem(), firstPersonHilt,
                    WORLD_VIEW, buffers);
            Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
            LightsaberTrail.renderFirstPerson(minecraft.player, firstPersonBlade, partialTick,
                    WORLD_VIEW, camera, buffers);
            drawBlade(buffers.getBuffer(LightsaberRenderTypes.BEAM), WORLD_VIEW,
                    firstPersonBlade.base().subtract(camera), firstPersonBlade.tip().subtract(camera));
            buffers.endBatch(LightsaberRenderTypes.BEAM);
        } finally {
            RenderSystem.setProjectionMatrix(handProjection, VertexSorting.DISTANCE_TO_ORIGIN);
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            firstPersonPrepared = false;
            firstPersonHilt = null;
            firstPersonBlade = null;
        }
    }

    public static void releaseDepthTarget() {
        if (sceneDepth != null) {
            sceneDepth.destroyBuffers();
            sceneDepth = null;
        }
        firstPersonPrepared = false;
        firstPersonHilt = null;
        firstPersonBlade = null;
    }

    private static void drawBlade(VertexConsumer consumer, Matrix4f matrix, Vec3 base, Vec3 tip) {
        prism(consumer, matrix, base, tip, 0.065, HALO);
        prism(consumer, matrix, base, tip, 0.035, GLOW);
        prism(consumer, matrix, base, tip, 0.014, CORE);
    }

    /** A square prism of half-width {@code r}; the beam render type disables face culling. */
    private static void prism(VertexConsumer c, Matrix4f m, Vec3 a, Vec3 b, double r, float[] color) {
        Vec3 dir = b.subtract(a).normalize();
        Vec3 helper = Math.abs(dir.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 u = dir.cross(helper).normalize().scale(r);
        Vec3 v = dir.cross(u).normalize().scale(r);
        Vec3[] ca = {a.add(u).add(v), a.subtract(u).add(v), a.subtract(u).subtract(v), a.add(u).subtract(v)};
        Vec3[] cb = {b.add(u).add(v), b.subtract(u).add(v), b.subtract(u).subtract(v), b.add(u).subtract(v)};
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            quad(c, m, ca[i], ca[j], cb[j], cb[i], color);
        }
        quad(c, m, cb[0], cb[1], cb[2], cb[3], color);
    }

    private static void quad(VertexConsumer c, Matrix4f m, Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, float[] color) {
        vertex(c, m, p0, color);
        vertex(c, m, p1, color);
        vertex(c, m, p2, color);
        vertex(c, m, p3, color);
    }

    private static void vertex(VertexConsumer c, Matrix4f m, Vec3 p, float[] color) {
        c.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setColor(color[0], color[1], color[2], color[3]);
    }

    private LightsaberRenderer() {}
}
