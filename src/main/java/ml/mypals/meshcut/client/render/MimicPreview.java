package ml.mypals.meshcut.client.render;

import ml.mypals.meshcut.block.MimicRenderData;
import ml.mypals.meshcut.geom.MeshClipper;
import ml.mypals.meshcut.geom.MeshPoly;
import ml.mypals.meshcut.geom.VoxelFaces;
import ml.mypals.meshcut.geom.VoxelFragments;
import net.minecraft.client.renderer.RenderType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class MimicPreview {
    private static final int[][] DIRECTIONS = {{1, -1}, {1, 1}, {2, -1}, {2, 1}, {0, -1}, {0, 1}};
    static Map<RenderType, List<MeshPoly>> build(MimicRenderData data, Map<RenderType, List<MeshPoly>> byType,
                                                 float[] uv, Object sprite, RenderType capLayer) {
        Map<RenderType, List<MeshPoly>[]> sides = new LinkedHashMap<>();
        byType.forEach((type, polys) -> {
            @SuppressWarnings("unchecked")
            List<MeshPoly>[] bySide = new List[6];
            for (MeshPoly poly : polys) {
                int side = poly.cullFace();
                if (side >= 0 && side < 6) {
                    if (bySide[side] == null) bySide[side] = new ArrayList<>();
                    bySide[side].add(poly);
                }
            }
            sides.put(type, bySide);
        });
        Map<RenderType, List<MeshPoly>> out = new LinkedHashMap<>();
        for (VoxelFaces.Rect rect : VoxelFaces.greedy(VoxelFaces.filled(data.planes(), data.excluded()))) {
            int ua = (rect.axis() + 1) % 3, va = (rect.axis() + 2) % 3;
            double[] lo = new double[3], hi = new double[3];
            lo[ua] = rect.u0() / 8.0; hi[ua] = rect.u1() / 8.0;
            lo[va] = rect.v0() / 8.0; hi[va] = rect.v1() / 8.0;
            if (rect.outer()) {
                int direction = directionOf(rect);
                lo[rect.axis()] = 0; hi[rect.axis()] = 1;
                var box = VoxelFragments.planes(new double[]{lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]});
                for (var entry : sides.entrySet()) {
                    List<MeshPoly> polys = entry.getValue()[direction];
                    if (polys != null) {
                        out.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).addAll(MeshClipper.clip(polys, box));
                    }
                }
            } else {
                double at = (rect.layer() + (rect.sign() > 0 ? 1 : 0)) / 8.0;
                out.computeIfAbsent(capLayer, k -> new ArrayList<>()).add(wall(rect, ua, va, at, lo, hi, uv, sprite));
            }
        }
        return out;
    }

    private static int directionOf(VoxelFaces.Rect rect) {
        for (int d = 0; d < 6; d++) {
            if (DIRECTIONS[d][0] == rect.axis() && DIRECTIONS[d][1] == rect.sign()) return d;
        }
        throw new IllegalStateException();
    }

    private static MeshPoly wall(VoxelFaces.Rect rect, int ua, int va, double at, double[] lo, double[] hi, float[] uv, Object sprite) {
        double[][] corners = {{lo[ua], lo[va]}, {hi[ua], lo[va]}, {hi[ua], hi[va]}, {lo[ua], hi[va]}};
        if (rect.sign() < 0) {
            double[] swap = corners[1];
            corners[1] = corners[3];
            corners[3] = swap;
        }
        float[] v = new float[4 * MeshPoly.STRIDE];
        for (int i = 0; i < 4; i++) {
            int o = i * MeshPoly.STRIDE;
            v[o + rect.axis()] = (float) at;
            v[o + ua] = (float) corners[i][0];
            v[o + va] = (float) corners[i][1];
            v[o + 3] = uv[0] + (uv[2] - uv[0]) * (float) corners[i][0];
            v[o + 4] = uv[1] + (uv[3] - uv[1]) * (float) corners[i][1];
        }
        float nx = rect.axis() == 0 ? rect.sign() : 0, ny = rect.axis() == 1 ? rect.sign() : 0, nz = rect.axis() == 2 ? rect.sign() : 0;
        return new MeshPoly(v, nx, ny, nz, -1, 0, 0xFFFFFFFF, sprite, -1, true);
    }

    private MimicPreview() {}
}
