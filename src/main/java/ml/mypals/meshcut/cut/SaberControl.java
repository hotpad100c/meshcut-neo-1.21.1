package ml.mypals.meshcut.cut;

/** Mouse input in degrees: the blade uses the free range, and only overflow turns the camera. */
public final class SaberControl {
    public static final float YAW_LIMIT = 30;
    public static final float PITCH_LIMIT = 22;
    private float yaw;
    private float pitch;

    public record Turn(double yaw, double pitch) {}

    public Turn move(double yawDelta, double pitchDelta, float viewPitch) {
        double requestedYaw = yaw + yawDelta;
        float nextYaw = (float) Math.clamp(requestedYaw, -YAW_LIMIT, YAW_LIMIT);
        double requestedPitch = Math.clamp(viewPitch + pitch + pitchDelta, -90, 90) - viewPitch;
        float nextPitch = (float) Math.clamp(requestedPitch, -PITCH_LIMIT, PITCH_LIMIT);
        yaw = nextYaw;
        pitch = nextPitch;
        return new Turn(requestedYaw - yaw, requestedPitch - pitch);
    }

    public float yaw() { return yaw; }
    public float pitch() { return pitch; }
}
