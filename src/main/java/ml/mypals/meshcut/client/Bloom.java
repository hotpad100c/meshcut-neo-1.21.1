package ml.mypals.meshcut.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import ml.mypals.meshcut.MeshCut;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import ml.mypals.meshcut.client.compat.IrisCompat;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;


@EventBusSubscriber(modid = MeshCut.MODID, value = Dist.CLIENT)
public final class Bloom {

    private static final int LEVELS = 5;
    private static final float STRENGTH = 1.4f;

    private static final MultiBufferSource.BufferSource BUFFERS = MultiBufferSource.immediate(new ByteBufferBuilder(1 << 18));

    record Frame(float partial, Matrix4f toClip, Vec3 camera, Matrix4f projection, Matrix4f modelView) {}

    private static Frame deferred;
    private static int previousFramebuffer;
    private static int sceneDepthTexture;
    private static ShaderInstance down, up, depthCopy;
    private static RenderTarget glow;
    private static RenderTarget[] mips;
    private static boolean active;
    private static boolean haveDepth;
    private static int recent;
    private static final int RECENT_FRAMES = 10;
    private static float[] queue = new float[7 * 256];
    private static int queued;

    @SubscribeEvent
    public static void registerShaders(RegisterShadersEvent event) throws IOException {
        event.registerShader(new ShaderInstance(event.getResourceProvider(),
                ResourceLocation.fromNamespaceAndPath(MeshCut.MODID, "bloom_down"), DefaultVertexFormat.POSITION),
                shader -> down = shader);
        event.registerShader(new ShaderInstance(event.getResourceProvider(),
                ResourceLocation.fromNamespaceAndPath(MeshCut.MODID, "bloom_up"), DefaultVertexFormat.POSITION),
                shader -> up = shader);
        event.registerShader(new ShaderInstance(event.getResourceProvider(),
                ResourceLocation.fromNamespaceAndPath(MeshCut.MODID, "depth_copy"), DefaultVertexFormat.POSITION),
                shader -> depthCopy = shader);
    }

    static boolean defersHands() {
        return usable() && !IrisCompat.shadersInUse();
    }

    static void wantDepth() {
        recent = RECENT_FRAMES;
    }

    static int sceneDepth() {
        return haveDepth && glow != null ? glow.getDepthTextureId() : Minecraft.getInstance().getMainRenderTarget().getDepthTextureId();
    }

    public static boolean usable() {
        return down != null && up != null && depthCopy != null && Minecraft.getInstance().level != null;
    }

    public static VertexConsumer consumer(RenderType type) {
        begin();
        return BUFFERS.getBuffer(type);
    }

    public static void flush(RenderType type) {
        if (active) {
            BUFFERS.endBatch(type);
        }
    }

    public static void queue(float x, float y, float z, float r, float g, float b, float a) {
        if (queued + 7 > queue.length) {
            queue = java.util.Arrays.copyOf(queue, queue.length * 2);
        }
        queue[queued++] = x;
        queue[queued++] = y;
        queue[queued++] = z;
        queue[queued++] = r;
        queue[queued++] = g;
        queue[queued++] = b;
        queue[queued++] = a;
        recent = RECENT_FRAMES;
    }

    static void bindGlow() {
        if (glow != null) {
            previousFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            glow.bindWrite(false);
        }
    }

    static void bindMain() {
        if (previousFramebuffer > 0) {
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
        } else {
            Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
        }
    }

    private static void copySceneDepth() {
        int fb = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int source = Minecraft.getInstance().getMainRenderTarget().getDepthTextureId();
        if (fb != 0) {
            for (int attachment : new int[]{GL30.GL_DEPTH_ATTACHMENT, GL30.GL_DEPTH_STENCIL_ATTACHMENT}) {
                int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, attachment,
                        GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
                int name = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, attachment,
                        GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
                if (type == GL11.GL_TEXTURE && name > 0) {
                    source = name;
                    break;
                }
            }
        }
        sceneDepthTexture = source;
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        previousFramebuffer = fb;
        glow.bindWrite(true);
        RenderSystem.disableBlend();
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.depthFunc(GL11.GL_ALWAYS);
        RenderSystem.colorMask(false, false, false, false);
        RenderSystem.setShader(() -> depthCopy);
        RenderSystem.setShaderTexture(0, source);
        BufferBuilder quad = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        quad.addVertex(-1, -1, 0);
        quad.addVertex(1, -1, 0);
        quad.addVertex(1, 1, 0);
        quad.addVertex(-1, 1, 0);
        BufferUploader.drawWithShader(quad.buildOrThrow());
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.enableCull();
        bindMain();
        RenderSystem.viewport(0, 0, main.width, main.height);
    }

    /**
     * Before the translucent layers: what the glow hides behind is the opaque scene only. Water and glass in front of
     * the beam must let it show through, which they would not if their depth (written as they are drawn) counted.
     */
    @SubscribeEvent
    public static void snapshotDepth(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            return;
        }
        haveDepth = false;
        if (usable() && recent > 0) {
            // what block entities were given to draw (the bodies of cut-up entities, in sub-levels too) is drawn
            // when its buffers are flushed, which may be after this stage: it must be in the depth
            Minecraft.getInstance().renderBuffers().bufferSource().endBatch();
            ensureTargets();
            copySceneDepth();
            haveDepth = true;
        }
    }

    private static void begin() {
        if (active) {
            return;
        }
        recent = RECENT_FRAMES;
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        ensureTargets();
        if (!haveDepth) {
            copySceneDepth();
            haveDepth = true;
        }
        previousFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        glow.bindWrite(true);
        RenderSystem.clearColor(0, 0, 0, 0);
        RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);
        bindMain();
        RenderSystem.viewport(0, 0, main.width, main.height);
        active = true;
    }

    private static void ensureTargets() {
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        if (glow == null) {
            glow = target(main.width, main.height, true);
            mips = new RenderTarget[LEVELS];
        } else if (glow.width != main.width || glow.height != main.height) {
            glow.resize(main.width, main.height, Minecraft.ON_OSX);
            glow.setFilterMode(GL11.GL_LINEAR);
        }
        for (int i = 0; i < LEVELS; i++) {
            int w = Math.max(1, main.width >> (i + 1)), h = Math.max(1, main.height >> (i + 1));
            if (mips[i] == null) {
                mips[i] = target(w, h, false);
            } else if (mips[i].width != w || mips[i].height != h) {
                mips[i].resize(w, h, Minecraft.ON_OSX);
                mips[i].setFilterMode(GL11.GL_LINEAR);
            }
        }
    }

    private static RenderTarget target(int width, int height, boolean depth) {
        RenderTarget target = new TextureTarget(width, height, depth, Minecraft.ON_OSX);
        target.setClearColor(0, 0, 0, 0);
        target.setFilterMode(GL11.GL_LINEAR);
        return target;
    }

    @SubscribeEvent
    public static void compose(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            return;
        }
        Frame frame = new Frame(event.getPartialTick().getGameTimeDeltaPartialTick(true),
                new Matrix4f(RenderSystem.getProjectionMatrix()).mul(RenderSystem.getModelViewMatrix())
                        .mul(event.getPoseStack().last().pose()),
                event.getCamera().getPosition(), new Matrix4f(RenderSystem.getProjectionMatrix()),
                new Matrix4f(RenderSystem.getModelViewMatrix()));
        if (IrisCompat.shadersInUse()) {
            deferred = frame;
            return;
        }
        finish(frame);
    }

    public static void composeDeferred() {
        Frame frame = deferred;
        deferred = null;
        if (frame != null) {
            finish(frame);
        }
    }

    private static void finish(Frame frame) {
        int depth = sceneDepth();
        if (!usable() || (!active && queued == 0)) {
            queued = 0;
            active = false;
            haveDepth = false;
            if (recent > 0) {
                recent--;
            }
            return;
        }
        begin();
        MultiBufferSource.BufferSource buffers = BUFFERS;
        Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        var sorting = RenderSystem.getVertexSorting();
        RenderSystem.setProjectionMatrix(frame.projection(), com.mojang.blaze3d.vertex.VertexSorting.DISTANCE_TO_ORIGIN);
        var modelViews = RenderSystem.getModelViewStack();
        modelViews.pushMatrix();
        modelViews.set(frame.modelView());
        RenderSystem.applyModelViewMatrix();
        if (queued > 0) {
            VertexConsumer consumer = buffers.getBuffer(BloomRenderTypes.CUT_TRIANGLES);
            for (int i = 0; i < queued; i += 7) {
                consumer.addVertex(queue[i], queue[i + 1], queue[i + 2])
                        .setColor(queue[i + 3], queue[i + 4], queue[i + 5], queue[i + 6]);
            }
            queued = 0;
        }
        buffers.endBatch(BloomRenderTypes.CUT_TRIANGLES);
        buffers.endBatch(BloomRenderTypes.TRIANGLES);
        buffers.endBatch(BloomRenderTypes.QUADS);
        modelViews.popMatrix();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(projection, sorting);
        active = false;
        haveDepth = false;

        previousFramebuffer = 0;
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderTarget from = glow;
        for (RenderTarget to : mips) {
            pass(down, from, to, 1);
            from = to;
        }
        for (int i = LEVELS - 1; i > 0; i--) {
            pass(up, mips[i], mips[i - 1], 1);
        }
        MotionBlur.render(frame, depth, glow.getColorTextureId(), mips[0].getColorTextureId(), STRENGTH);
        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        pass(up, mips[0], main, STRENGTH);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        main.bindWrite(true);
    }

    private static void pass(ShaderInstance shader, RenderTarget source, RenderTarget target, float strength) {
        target.bindWrite(true);
        RenderSystem.enableBlend();
        if (shader == up) {
            RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE,
                    GlStateManager.DestFactor.ONE);
        } else {
            RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE,
                    GlStateManager.DestFactor.ZERO);
        }
        RenderSystem.setShader(() -> shader);
        RenderSystem.setShaderTexture(0, source.getColorTextureId());
        shader.safeGetUniform("TexelSize").set(1f / source.width, 1f / source.height);
        if (shader == up) {
            shader.safeGetUniform("Strength").set(strength);
        }
        BufferBuilder quad = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        quad.addVertex(-1, -1, 0);
        quad.addVertex(1, -1, 0);
        quad.addVertex(1, 1, 0);
        quad.addVertex(-1, 1, 0);
        BufferUploader.drawWithShader(quad.buildOrThrow());
    }

    private Bloom() {}
}
