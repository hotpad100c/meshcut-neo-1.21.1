package ml.mypals.meshcut.cut;

import ml.mypals.meshcut.block.MimicBlock;
import ml.mypals.meshcut.block.MimicBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/**
 * Whether a beam goes through a cut block. AnvilCraft decides that from the block's collision shape, which is only an
 * approximation of what is left of a cut block (and keeps it closed round a narrow bore); here the beam is followed
 * through the actual material, so a laser that has burnt through a block goes on to the next one.
 */
public final class MimicBeamPass {
    /** The beam is as thick as AnvilCraft's own test says: two sixteenths. */
    private static final double HALF_WIDTH = 1.0 / 16;
    private static final int STEPS = 16;

    public static boolean passes(Level level, BlockPos pos, Direction direction) {
        if (!(level.getBlockState(pos).getBlock() instanceof MimicBlock) || !(level.getBlockEntity(pos) instanceof MimicBlockEntity be)) {
            return false;
        }
        var planes = be.getPlanes();
        var excluded = be.getExcluded();
        var boxes = be.cutBoxes();
        int axis = direction.getAxis().ordinal();
        int a = (axis + 1) % 3, b = (axis + 2) % 3;
        double[][] offsets = {{0, 0}, {HALF_WIDTH, 0}, {-HALF_WIDTH, 0}, {0, HALF_WIDTH}, {0, -HALF_WIDTH}};
        double[] p = new double[3];
        for (int step = 0; step <= STEPS; step++) {
            for (double[] offset : offsets) {
                p[axis] = step / (double) STEPS;
                p[a] = 0.5 + offset[0];
                p[b] = 0.5 + offset[1];
                if (material(p, planes, excluded, boxes)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean material(double[] p, java.util.List<CutPlane> planes, java.util.List<java.util.List<CutPlane>> excluded,
                                    java.util.List<double[]> boxes) {
        for (CutPlane plane : planes) {
            if (plane.signedDistance(p[0], p[1], p[2]) < -1e-9) return false;
        }
        boolean inside = boxes.isEmpty();
        for (double[] box : boxes) {
            if (p[0] >= box[0] && p[1] >= box[1] && p[2] >= box[2] && p[0] <= box[3] && p[1] <= box[4] && p[2] <= box[5]) {
                inside = true;
                break;
            }
        }
        if (!inside) return false;
        for (var region : excluded) {
            boolean gone = true;
            for (CutPlane plane : region) {
                if (plane.signedDistance(p[0], p[1], p[2]) <= 1e-9) {
                    gone = false;
                    break;
                }
            }
            if (gone) return false;
        }
        return true;
    }

    private MimicBeamPass() {}
}
