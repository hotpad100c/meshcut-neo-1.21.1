package ml.mypals.meshcut.geom;

import java.util.ArrayList;
import java.util.List;

public final class VoxelFaces {
    private static final int N = CellGrid.N;

    public record Rect(int axis, int sign, int layer, int u0, int u1, int v0, int v1, boolean outer) {
        public int area() { return (u1 - u0) * (v1 - v0); }
    }

    public static boolean[] filled(List<ml.mypals.meshcut.cut.CutPlane> real, List<List<ml.mypals.meshcut.cut.CutPlane>> excluded) {
        long[] occupied = CellMasks.occupied(real, excluded);
        boolean[] filled = new boolean[CellGrid.CELLS];
        for (int i = 0; i < filled.length; i++) {
            filled[i] = (occupied[i / 64] & (1L << (i % 64))) != 0;
        }
        return filled;
    }

    public static List<Rect> greedy(boolean[] filled) {
        List<Rect> out = new ArrayList<>();
        int[] c = new int[3];
        for (int axis = 0; axis < 3; axis++) {
            int ua = (axis + 1) % 3, va = (axis + 2) % 3;
            for (int sign = -1; sign <= 1; sign += 2) {
                for (int layer = 0; layer < N; layer++) {
                    boolean[] exposed = new boolean[N * N];
                    boolean any = false;
                    int beyond = layer + sign;
                    boolean outer = beyond < 0 || beyond >= N;
                    for (int u = 0; u < N; u++) {
                        for (int v = 0; v < N; v++) {
                            c[axis] = layer; c[ua] = u; c[va] = v;
                            if (!filled[(c[0] * N + c[1]) * N + c[2]]) continue;
                            if (!outer) {
                                c[axis] = beyond;
                                if (filled[(c[0] * N + c[1]) * N + c[2]]) continue;
                            }
                            exposed[u * N + v] = true;
                            any = true;
                        }
                    }
                    if (!any) continue;
                    for (int u = 0; u < N; u++) {
                        for (int v = 0; v < N; v++) {
                            if (!exposed[u * N + v]) continue;
                            int ve = v + 1;
                            while (ve < N && exposed[u * N + ve]) ve++;
                            int ue = u + 1;
                            while (ue < N && rowExposed(exposed, ue, v, ve)) ue++;
                            for (int a = u; a < ue; a++) for (int b = v; b < ve; b++) exposed[a * N + b] = false;
                            out.add(new Rect(axis, sign, layer, u, ue, v, ve, outer));
                        }
                    }
                }
            }
        }
        return out;
    }

    private static boolean rowExposed(boolean[] exposed, int u, int v0, int v1) {
        for (int v = v0; v < v1; v++) if (!exposed[u * N + v]) return false;
        return true;
    }

    private VoxelFaces() {}
}
