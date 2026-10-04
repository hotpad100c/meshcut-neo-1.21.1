package ml.mypals.meshcut.block;

import ml.mypals.meshcut.cut.CutPlane;
import ml.mypals.meshcut.geom.Patch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;


final class MimicFaces {
    private static final double EPS = 1e-6;

    static int wholeFaces(BlockState target, List<CutPlane> planes, List<List<CutPlane>> excluded, List<Patch> patches,
                          boolean partOfEntity) {
        if (partOfEntity || !target.canOcclude()) {
            return 0;
        }
        int mask = 0;
        for (Direction dir : Direction.values()) {
            if (opaqueFace(target, dir) && untouched(dir, planes, excluded, patches)) {
                mask |= 1 << dir.get3DDataValue();
            }
        }
        return mask;
    }

    private static boolean opaqueFace(BlockState target, Direction dir) {
        try {
            return Block.isFaceFull(target.getFaceOcclusionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, dir), dir);
        } catch (RuntimeException e) {
            return false;// how
        }
    }

    private static boolean untouched(Direction dir, List<CutPlane> planes, List<List<CutPlane>> excluded, List<Patch> patches) {
        double[][] corners = corners(dir);
        for (CutPlane p : planes) {
            for (double[] c : corners) {
                if (p.signedDistance(c[0], c[1], c[2]) < -EPS) {
                    return false;
                }
            }
        }
        for (List<CutPlane> region : excluded) {
            if (!separated(region, corners)) {
                return false;
            }
        }
        int axis = dir.getAxis().ordinal();
        double at = dir.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0;
        for (Patch patch : patches) {
            float[] v = patch.polygon();
            for (int i = axis; i < v.length; i += 3) {
                if (Math.abs(v[i] - at) < 1e-4) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean separated(List<CutPlane> region, double[][] corners) {
        for (CutPlane p : region) {
            boolean outside = true;
            for (double[] c : corners) {
                if (p.signedDistance(c[0], c[1], c[2]) > EPS) {
                    outside = false;
                    break;
                }
            }
            if (outside) {
                return true;
            }
        }
        return false;
    }

    private static double[][] corners(Direction dir) {
        int axis = dir.getAxis().ordinal();
        double at = dir.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0;
        double[][] out = new double[4][3];
        int u = (axis + 1) % 3, v = (axis + 2) % 3;
        for (int i = 0; i < 4; i++) {
            out[i][axis] = at;
            out[i][u] = (i & 1);
            out[i][v] = (i >> 1);
        }
        return out;
    }

    private MimicFaces() {}
}
