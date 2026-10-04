package ml.mypals.meshcut.physics;

import org.joml.Vector3d;
import java.util.ArrayList;
import java.util.List;

/** Fixed material samples for an attachment, independent of body motion and world loading. */
public final class JointSupport {
    public static Vector3d inset(double[] box, double[] target) {
        double[] point = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            double depth = Math.min(0.15, (box[axis + 3] - box[axis]) / 2);
            point[axis] = Math.max(box[axis] + depth, Math.min(box[axis + 3] - depth, target[axis]));
        }
        return new Vector3d(point);
    }

    public static List<Vector3d> samples(Vector3d center) {
        List<Vector3d> samples = new ArrayList<>();
        samples.add(new Vector3d(center));
        for (int axis = 0; axis < 3; axis++) for (int sign : new int[]{-1, 1}) {
            Vector3d point = new Vector3d(center);
            point.setComponent(axis, point.get(axis) + sign * 0.04);
            samples.add(point);
        }
        return samples;
    }

    private JointSupport() {}
}
