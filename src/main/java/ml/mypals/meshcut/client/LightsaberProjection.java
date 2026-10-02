package ml.mypals.meshcut.client;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Converts a hand-model pose to camera-relative world coordinates without changing its screen position. */
public final class LightsaberProjection {
    private LightsaberProjection() {}

    public static Matrix4f itemToWorld(Matrix4f worldProjection, Matrix4f worldView,
                                       Matrix4f handProjection, Matrix4f handView, Matrix4f itemPose) {
        // Both sides produce the same clip coordinates: Pw * Vw * world = Ph * Vh * itemPose * local.
        return new Matrix4f(worldProjection).mul(worldView).invert()
                .mul(handProjection).mul(handView).mul(itemPose);
    }

    public static Vector3f localDirection(Matrix4f itemToWorld, Vector3f pivot, Vector3f worldDirection) {
        Vector3f worldPivot = itemToWorld.transformProject(new Vector3f(pivot));
        Vector3f localTarget = new Matrix4f(itemToWorld).invert()
                .transformProject(worldPivot.add(worldDirection));
        return localTarget.sub(pivot).normalize();
    }

    /** Local adjustment that puts the emitter on the cutting origin and aims its +Y axis along the blade. */
    public static Matrix4f bladeAdjustment(Matrix4f itemToWorld, Vector3f emitter,
                                           Vector3f worldBase, Vector3f worldDirection) {
        Vector3f offset = new Matrix4f(itemToWorld).invert()
                .transformProject(new Vector3f(worldBase)).sub(emitter);
        Matrix4f anchored = new Matrix4f(itemToWorld).translate(offset);
        // Calculate direction after translation: a projective pose need not preserve parallel axes.
        Vector3f direction = localDirection(anchored, emitter, worldDirection);
        return new Matrix4f().translate(offset).translate(emitter)
                .rotate(new Quaternionf().rotationTo(0, 1, 0, direction.x, direction.y, direction.z))
                .translate(-emitter.x, -emitter.y, -emitter.z);
    }
}
