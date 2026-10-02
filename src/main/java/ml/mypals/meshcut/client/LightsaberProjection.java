package ml.mypals.meshcut.client;

import org.joml.Matrix4f;
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
}
