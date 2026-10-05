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

    /**
     * What the frame's composition needs from the stage it was set up in. With a shader pack in use the scene is not
     * finished until the pack has made its final pass, which is after every stage: the glow is composed then (see
     * {@link #composeDeferred}), onto what the pack has made of the frame, the same as it is onto the plain one.
     */
    record Frame(float partial, Matrix4f toClip, Vec3 camera, Matrix4f projection, Matrix4f modelView) {}

    private static Frame deferred;
    /** The framebuffer that was bound before the glow's was, to bind again after. */
    private static int previousFramebuffer;
    /** Texture of the depth of the opaque scene as the frame's own pipeline has it (a shader pack's, if one runs). */
    private static int sceneDepthTexture;
    private static ShaderInstance down, up, depthCopy;
    private static RenderTarget glow;
    private static RenderTarget[] mips;
    private static boolean active;
    /** The depth of the opaque scene has been copied into the glow buffer this frame. */
    private static boolean haveDepth;
    /** Frames left in which the glow is expected again, so that its depth is kept ready. */
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

    /** Whether the first-person hands are drawn after the bloom, so that it does not lie over them (not with shaders). */
    static boolean defersHands() {
        return usable() && !IrisCompat.shadersInUse();
    }

    /** Asks for the depth of the opaque scene to be kept this frame and the next few (the motion blur reads it). */
    static void wantDepth() {
        recent = RECENT_FRAMES;
    }

    /** The texture of the opaque scene's depth: the snapshot taken before the translucent layers, if there is one. */
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

    /** A vertex of a triangle to glow, in camera-relative space; drawn when the glow of the frame is composed. */
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

    /** Back to what was bound before the glow's buffer: the scene's own, a shader pack's or the main target. */
    static void bindMain() {
        if (previousFramebuffer > 0) {
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
        } else {
            Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
        }
    }

    /**
     * Copies the depth of what is being drawn into (whatever the frame's pipeline has bound: the main target, or a
     * shader pack's buffers) into the glow buffer, by a pass that reads it as a texture: a shader pack's depth is not
     * of the format of ours, which a plain copy of the buffers needs.
     */
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
        if (!haveDepth) { // the glow is new this frame: the translucent layers are in the depth already
            copySceneDepth();
            haveDepth = true;
        }
        previousFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        glow.bindWrite(true);
        RenderSystem.clearColor(0, 0, 0, 0);
        RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX); // not the depth: that is the opaque scene's
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

    /** After everything else of the level is drawn: the glow is blurred and added to the scene. */
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
            deferred = frame; // composed once the shader pack has made its final pass
            return;
        }
        finish(frame);
    }

    /** After the whole of the level is drawn (by {@code GameRendererBloomMixin}): the glow of a frame under a shader pack. */
    public static void composeDeferred() {
        Frame frame = deferred;
        deferred = null;
        if (frame != null) {
            finish(frame);
        }
    }

    private static void finish(Frame frame) {
        // the motion blur reads the opaque scene's depth: taken now, before the snapshot of it is let go of below
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
        // what blocks queued (the lava on cut faces) is drawn with the matrices of the stage it was queued in: by the
        // time a shader pack's frame is composed, those are no longer the ones set
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

        previousFramebuffer = 0; // from here on, the main target
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
        // the blade's trail is made of this frame's glow and bloom (see MotionBlur): drawn while both are at hand
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

    /** Draws {@code source} over the whole of {@code target} with the shader. */
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
