package ml.mypals.meshcut.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import ml.mypals.meshcut.block.MimicBlockEntity;
import ml.mypals.meshcut.geom.MeshPoly;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;

/**
 * What a cut-up body draws, kept in vertex buffers on the graphics card, so that a frame costs a draw call a layer
 * instead of every vertex of every face being worked out and copied again. The geometry is in the block's own
 * coordinates, the pose moves it (a body that tumbles is moved by the matrix at no cost); what is baked in is the light
 * (a new buffer when it changes) and the normals, which light the faces by the way they point, so they are redone once
 * a body has come to rest in another position than the one they were made for.
 */
final class MimicVbos {
    private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("meshcut.vbo"));
    private static final float NORMAL_TOLERANCE = 0.12f;
    private static final int SETTLE_FRAMES = 8;
    private static final int SWEEP_EVERY = 240;
    /** A tumbling piece crosses light levels all the time; its buffer is redone for that at most this often. */
    private static final int RELIGHT_FRAMES = 30;

    private static final class Entry {
        final WeakReference<MimicBlockEntity> be;
        Object source;
        int light;
        final Matrix3f made = new Matrix3f();
        final Matrix3f last = new Matrix3f();
        int stable;
        int builtAt;
        final Map<RenderType, VertexBuffer> buffers = new LinkedHashMap<>();

        Entry(MimicBlockEntity be) {
            this.be = new WeakReference<>(be);
        }

        // the glow of a fresh cut: made once in white, tinted and faded by the shader colour each frame
        Object glowSource;
        int glowLight;
        int glowBuiltAt;
        final Matrix3f glowMade = new Matrix3f();
        final Matrix3f glowLast = new Matrix3f();
        int glowStable;
        VertexBuffer glow;

        void close() {
            buffers.values().forEach(VertexBuffer::close);
            buffers.clear();
            closeGlow();
        }

        void closeGlow() {
            if (glow != null) {
                glow.close();
                glow = null;
            }
        }
    }

    private static final Map<MimicBlockEntity, Entry> ENTRIES = new IdentityHashMap<>();
    private static int frames;

    static boolean enabled() {
        return ENABLED;
    }

    /** Frees every buffer (a resource reload makes what they were made from stale). */
    static void clear() {
        ENTRIES.values().forEach(Entry::close);
        ENTRIES.clear();
    }

    static List<RenderType> draw(MimicBlockEntity be, Object source, Map<RenderType, List<MeshPoly>> layers,
                                 PoseStack.Pose pose, int light, IntUnaryOperator tint) {
        if (++frames % SWEEP_EVERY == 0) {
            sweep();
        }
        Entry entry = ENTRIES.computeIfAbsent(be, Entry::new);
        Matrix3f normal = pose.normal();
        boolean rebuild = entry.source != source || entry.buffers.isEmpty()
                || entry.light != light && frames - entry.builtAt >= RELIGHT_FRAMES;
        if (!rebuild) {
            if (!near(entry.made, normal, NORMAL_TOLERANCE)) {
                entry.stable = near(entry.last, normal, 1e-3f) ? entry.stable + 1 : 0;
                rebuild = entry.stable >= SETTLE_FRAMES;
            } else {
                entry.stable = 0;
            }
            entry.last.set(normal);
        }
        List<RenderType> rest = new ArrayList<>();
        if (rebuild) {
            entry.close();
            entry.builtAt = frames;
            entry.source = source;
            entry.light = light;
            entry.made.set(normal);
            entry.last.set(normal);
            entry.stable = 0;
            for (var layer : layers.entrySet()) {
                VertexBuffer buffer = layer.getKey().sortOnUpload() ? null : build(layer.getKey(), layer.getValue(), normal, light, tint);
                if (buffer != null) {
                    entry.buffers.put(layer.getKey(), buffer);
                }
            }
        }
        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose.pose());
        for (var layer : layers.entrySet()) {
            VertexBuffer buffer = entry.buffers.get(layer.getKey());
            if (buffer == null) {
                rest.add(layer.getKey());
                continue;
            }
            RenderType type = layer.getKey();
            type.setupRenderState();
            ShaderInstance shader = RenderSystem.getShader();
            if (shader != null) {
                buffer.bind();
                buffer.drawWithShader(modelView, RenderSystem.getProjectionMatrix(), shader);
                VertexBuffer.unbind();
            }
            type.clearRenderState();
        }
        return rest;
    }

    /**
     * The glow over the cut faces, drawn from a buffer made once: only the colour changes as it cools, and that is the
     * shader colour. Returns false when it could not be drawn this way (the caller draws it directly).
     */
    static boolean drawGlow(MimicBlockEntity be, List<MeshPoly> polys, RenderType type, PoseStack.Pose pose, int light,
                            float r, float g, float b, float a) {
        if (polys.isEmpty()) {
            return true;
        }
        if (++frames % SWEEP_EVERY == 0) {
            sweep();
        }
        Entry entry = ENTRIES.computeIfAbsent(be, Entry::new);
        Matrix3f normal = pose.normal();
        boolean rebuild = entry.glowSource != polys || entry.glow == null
                || entry.glowLight != light && frames - entry.glowBuiltAt >= RELIGHT_FRAMES;
        if (!rebuild) {
            if (!near(entry.glowMade, normal, NORMAL_TOLERANCE)) {
                entry.glowStable = near(entry.glowLast, normal, 1e-3f) ? entry.glowStable + 1 : 0;
                rebuild = entry.glowStable >= SETTLE_FRAMES;
            } else {
                entry.glowStable = 0;
            }
            entry.glowLast.set(normal);
        }
        if (rebuild) {
            entry.closeGlow();
            entry.glowSource = polys;
            entry.glowLight = light;
            entry.glowBuiltAt = frames;
            entry.glowMade.set(normal);
            entry.glowLast.set(normal);
            entry.glowStable = 0;
            entry.glow = build(type, polys, normal, light, index -> 0xFFFFFF);
        }
        if (entry.glow == null) {
            return false;
        }
        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose.pose());
        type.setupRenderState();
        ShaderInstance shader = RenderSystem.getShader();
        if (shader != null) {
            RenderSystem.setShaderColor(r, g, b, a);
            // the glow lies in the very plane of the cut face under it, and its matrix is worked out another way than
            // the one that placed that face: pulled a little towards the viewer, as vanilla layers decals
            RenderSystem.enablePolygonOffset();
            RenderSystem.polygonOffset(-1f, -10f);
            entry.glow.bind();
            entry.glow.drawWithShader(modelView, RenderSystem.getProjectionMatrix(), shader);
            VertexBuffer.unbind();
            RenderSystem.polygonOffset(0f, 0f);
            RenderSystem.disablePolygonOffset();
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        }
        type.clearRenderState();
        return true;
    }

    private static VertexBuffer build(RenderType type, List<MeshPoly> polys, Matrix3f normal, int light, IntUnaryOperator tint) {
        if (polys.isEmpty()) {
            return null;
        }
        PoseStack frame = new PoseStack();
        frame.last().normal().set(normal);
        boolean triangles = type.mode() == VertexFormat.Mode.TRIANGLES;
        int vertices = 0;
        for (MeshPoly poly : polys) {
            vertices += Math.max(0, poly.vertexCount() - 2) * 4;
        }
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(Math.max(256, vertices * type.format().getVertexSize()))) {
            BufferBuilder builder = new BufferBuilder(bytes, type.mode(), type.format());
            for (MeshPoly poly : polys) {
                MimicRenderer.emit(builder, frame.last(), poly, tint.applyAsInt(poly.tintIndex()), light, 1f, triangles);
            }
            MeshData mesh = builder.build();
            if (mesh == null) {
                return null;
            }
            VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            buffer.bind();
            buffer.upload(mesh);
            VertexBuffer.unbind();
            return buffer;
        }
    }

    private static boolean near(Matrix3f a, Matrix3f b, float tolerance) {
        return Math.abs(a.m00() - b.m00()) < tolerance && Math.abs(a.m01() - b.m01()) < tolerance
                && Math.abs(a.m02() - b.m02()) < tolerance && Math.abs(a.m10() - b.m10()) < tolerance
                && Math.abs(a.m11() - b.m11()) < tolerance && Math.abs(a.m12() - b.m12()) < tolerance
                && Math.abs(a.m20() - b.m20()) < tolerance && Math.abs(a.m21() - b.m21()) < tolerance
                && Math.abs(a.m22() - b.m22()) < tolerance;
    }
    private static void sweep() {
        for (Iterator<Map.Entry<MimicBlockEntity, Entry>> it = ENTRIES.entrySet().iterator(); it.hasNext(); ) {
            Entry entry = it.next().getValue();
            MimicBlockEntity be = entry.be.get();
            if (be == null || be.isRemoved() || be.getLevel() == null) {
                entry.close();
                it.remove();
            }
        }
    }

    private MimicVbos() {}
}
