package ml.mypals.meshcut.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.cut.Blade;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * The sweep of the blade, as a motion blur: a layer of its own, drawn over the finished scene by a shader that adds
 * up, for each pixel, how long the blade was over it during the last moments. It replaces the ribbon the blade used to
 * leave, which was a surface and looked like one; this is light, soft at its edges, longer where the blade moves
 * faster, and hidden behind what is in front of it by the depth of the scene.
 */
@EventBusSubscriber(modid = MeshCut.MODID, value = Dist.CLIENT)
public final class MotionBlur {
    /** How far back the blur reaches, in ticks. */
    private static final double SHUTTER_TICKS = 2.8;
    /** Positions of the blade the shader sweeps through (see motion_blur.fsh). */
    private static final int POSES = 6;
    /** Half-width of the blade's light in the blur, in fractions of the screen height; and its gain. */
    private static final float WIDTH = 0.012f;
    private static final float GAIN = 0.8f;

    private record Pose(double time, Vec3 base, Vec3 tip) {}

    private static final Map<UUID, ArrayDeque<Pose>> HISTORY = new HashMap<>();
    private static ShaderInstance shader;

    @SubscribeEvent
    public static void registerShaders(RegisterShadersEvent event) throws IOException {
        event.registerShader(new ShaderInstance(event.getResourceProvider(),
                ResourceLocation.fromNamespaceAndPath(MeshCut.MODID, "motion_blur"), DefaultVertexFormat.POSITION),
                s -> shader = s);
    }

    static boolean usable() {
        return shader != null;
    }

    /** Where a blade is this frame: kept for a moment, for the blur to sweep through. */
    static void record(UUID id, Blade blade, double time) {
        ArrayDeque<Pose> history = HISTORY.computeIfAbsent(id, k -> new ArrayDeque<>());
        Pose last = history.peekLast();
        if (last != null && time - last.time() < 1e-3) {
            history.pollLast(); // drawn twice in a frame (hands and world): the later is the one
        }
        history.addLast(new Pose(time, blade.base(), blade.tip()));
        while (history.size() > 2 && time - history.peekFirst().time() > SHUTTER_TICKS * 1.5) {
            history.pollFirst();
        }
        Bloom.wantDepth();
    }

    /** Called by the bloom's composition, in the stage after the scene: its depth is still in its buffer. */
    static void render(RenderLevelStageEvent event) {
        if (shader == null || HISTORY.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            HISTORY.clear();
            return;
        }
        double now = minecraft.level.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(true);
        Matrix4f toClip = new Matrix4f(RenderSystem.getProjectionMatrix()).mul(RenderSystem.getModelViewMatrix())
                .mul(event.getPoseStack().last().pose());
        Vec3 camera = event.getCamera().getPosition();
        RenderTarget main = minecraft.getMainRenderTarget();
        boolean prepared = false;
        for (Iterator<Map.Entry<UUID, ArrayDeque<Pose>>> it = HISTORY.entrySet().iterator(); it.hasNext(); ) {
            ArrayDeque<Pose> history = it.next().getValue();
            Pose newest = history.peekLast();
            if (newest == null || now - newest.time() > 1.0) { // the blade is out
                it.remove();
                continue;
            }
            float[][] a = new float[POSES][4], b = new float[POSES][4];
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            int valid = 0;
            for (int k = 0; k < POSES; k++) {
                double age = SHUTTER_TICKS * k / (POSES - 1);
                Pose pose = at(history, now - age);
                Vector4f base = clip(toClip, pose.base(), camera), tip = clip(toClip, pose.tip(), camera);
                if (base == null || tip == null) {
                    continue; // behind the camera
                }
                a[k] = new float[]{base.x, base.y, tip.x, tip.y};
                b[k] = new float[]{base.z * 0.5f + 0.5f, tip.z * 0.5f + 0.5f, (float) k / (POSES - 1), 1};
                minX = Math.min(minX, Math.min(base.x, tip.x));
                maxX = Math.max(maxX, Math.max(base.x, tip.x));
                minY = Math.min(minY, Math.min(base.y, tip.y));
                maxY = Math.max(maxY, Math.max(base.y, tip.y));
                valid++;
            }
            if (valid < 2) {
                continue;
            }
            if (!prepared) {
                prepare(main);
                prepared = true;
            }
            float aspect = (float) main.width / Math.max(1, main.height);
            shader.safeGetUniform("Params").set(aspect, WIDTH, GAIN, 0f);
            for (int k = 0; k < POSES; k++) {
                shader.safeGetUniform("PoseA" + k).set(a[k][0], a[k][1], a[k][2], a[k][3]);
                shader.safeGetUniform("PoseB" + k).set(b[k][0], b[k][1], b[k][2], b[k][3]);
            }
            // only the part of the screen the blade has been over, and the light around it
            float marginY = WIDTH * 4, marginX = marginY / aspect;
            float x0 = Math.max(-1, minX - marginX), x1 = Math.min(1, maxX + marginX);
            float y0 = Math.max(-1, minY - marginY), y1 = Math.min(1, maxY + marginY);
            if (x0 >= x1 || y0 >= y1) {
                continue;
            }
            BufferBuilder quad = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
            quad.addVertex(x0, y0, 0);
            quad.addVertex(x1, y0, 0);
            quad.addVertex(x1, y1, 0);
            quad.addVertex(x0, y1, 0);
            BufferUploader.drawWithShader(quad.buildOrThrow());
        }
        if (prepared) {
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.enableCull();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
            main.bindWrite(true);
        }
    }

    private static void prepare(RenderTarget main) {
        main.bindWrite(true);
        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
        RenderSystem.setShader(() -> shader);
        RenderSystem.setShaderTexture(0, Bloom.sceneDepth());
    }

    /** The blade at the time, between the two positions it was seen in on either side of it. */
    private static Pose at(ArrayDeque<Pose> history, double time) {
        Pose before = null, after = null;
        for (Pose pose : history) {
            if (pose.time() <= time) {
                before = pose;
            } else {
                after = pose;
                break;
            }
        }
        if (before == null) {
            return history.peekFirst();
        }
        if (after == null) {
            return before;
        }
        double t = (time - before.time()) / Math.max(1e-6, after.time() - before.time());
        return new Pose(time, before.base().lerp(after.base(), t), before.tip().lerp(after.tip(), t));
    }

    /** The point in clip space (xy and depth in normalised device coordinates), or null if it is behind the camera. */
    private static Vector4f clip(Matrix4f toClip, Vec3 point, Vec3 camera) {
        Vector4f v = new Vector4f((float) (point.x - camera.x), (float) (point.y - camera.y), (float) (point.z - camera.z), 1);
        toClip.transform(v);
        if (v.w < 0.05f) {
            return null;
        }
        return new Vector4f(v.x / v.w, v.y / v.w, v.z / v.w, v.w);
    }

    private MotionBlur() {}
}
