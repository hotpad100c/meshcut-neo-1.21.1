package ml.mypals.meshcut.client.capture;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import ml.mypals.meshcut.geom.MeshPoly;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link MultiBufferSource} that records what a block entity renderer draws instead of drawing it,
 * so the geometry can be clipped and replayed later. Only quads and triangles are kept.
 */
public final class RecordingBufferSource implements MultiBufferSource {
    private final Map<RenderType, Recorder> recorders = new LinkedHashMap<>();

    @Override
    public VertexConsumer getBuffer(RenderType type) {
        return recorders.computeIfAbsent(type, Recorder::new);
    }

    public Map<RenderType, List<MeshPoly>> finish() {
        Map<RenderType, List<MeshPoly>> out = new LinkedHashMap<>();
        recorders.forEach((type, rec) -> {
            List<MeshPoly> polys = rec.toPolys();
            if (!polys.isEmpty()) {
                out.put(type, polys);
            }
        });
        return out;
    }

    private static final class Recorder implements VertexConsumer {
        private final int primitive;
        /** x, y, z, u, v, argb(as float bits), nx, ny, nz per vertex */
        private final List<float[]> vertices = new ArrayList<>();
        private float[] cur;

        Recorder(RenderType type) {
            VertexFormat.Mode mode = type.mode();
            this.primitive = mode == VertexFormat.Mode.QUADS ? 4 : mode == VertexFormat.Mode.TRIANGLES ? 3 : 0;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            cur = new float[9];
            cur[0] = x;
            cur[1] = y;
            cur[2] = z;
            cur[5] = Float.intBitsToFloat(0xFFFFFFFF);
            vertices.add(cur);
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            cur[5] = Float.intBitsToFloat((a & 255) << 24 | (r & 255) << 16 | (g & 255) << 8 | (b & 255));
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            cur[3] = u;
            cur[4] = v;
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            cur[6] = x;
            cur[7] = y;
            cur[8] = z;
            return this;
        }

        List<MeshPoly> toPolys() {
            List<MeshPoly> polys = new ArrayList<>();
            if (primitive == 0) {
                return polys;
            }
            for (int i = 0; i + primitive <= vertices.size(); i += primitive) {
                float[] verts = new float[primitive * MeshPoly.STRIDE];
                for (int k = 0; k < primitive; k++) {
                    float[] v = vertices.get(i + k);
                    System.arraycopy(v, 0, verts, k * MeshPoly.STRIDE, MeshPoly.STRIDE);
                }
                float[] n = faceNormal(verts);
                polys.add(new MeshPoly(verts, n[0], n[1], n[2], -1, 0, Float.floatToRawIntBits(vertices.get(i)[5])));
            }
            return polys;
        }

        private static float[] faceNormal(float[] v) {
            int n = v.length / MeshPoly.STRIDE;
            int s = MeshPoly.STRIDE;
            // diagonal cross product works for triangles and quads alike
            int last = n - 1, mid = n / 2;
            float d1x = v[mid * s] - v[0], d1y = v[mid * s + 1] - v[1], d1z = v[mid * s + 2] - v[2];
            float d2x = v[last * s] - v[s], d2y = v[last * s + 1] - v[s + 1], d2z = v[last * s + 2] - v[s + 2];
            if (n == 3) {
                d1x = v[s] - v[0];
                d1y = v[s + 1] - v[1];
                d1z = v[s + 2] - v[2];
                d2x = v[2 * s] - v[0];
                d2y = v[2 * s + 1] - v[1];
                d2z = v[2 * s + 2] - v[2];
            }
            float nx = d1y * d2z - d1z * d2y, ny = d1z * d2x - d1x * d2z, nz = d1x * d2y - d1y * d2x;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            return len < 1e-9f ? new float[]{0, 1, 0} : new float[]{nx / len, ny / len, nz / len};
        }
    }
}
