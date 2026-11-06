package ml.mypals.meshcut.cut;

import org.joml.Vector2f;

public final class SaberAim {
    public static final float SETTLE_SECONDS = 0.22f;
    private static final float FREQUENCY = 4.743865f / SETTLE_SECONDS;
    private float previousYaw;
    private float previousPitch;
    private float previousYawVelocity;
    private float previousPitchVelocity;
    private float yaw;
    private float pitch;
    private float yawVelocity;
    private float pitchVelocity;
    private float interval;

    public SaberAim(float yaw, float pitch) {
        this.previousYaw = this.yaw = wrap(yaw);
        this.previousPitch = this.pitch = Math.clamp(pitch, -90f, 90f);
    }

    public void advance(float targetYaw, float targetPitch, float seconds) {
        previousYaw = yaw;
        previousPitch = pitch;
        previousYawVelocity = yawVelocity;
        previousPitchVelocity = pitchVelocity;
        interval = seconds;
        float decay = (float) Math.exp(-FREQUENCY * seconds);
        float yawError = -wrap(targetYaw - yaw);
        float yawTerm = yawVelocity + FREQUENCY * yawError;
        yaw += -yawError + (yawError + yawTerm * seconds) * decay;
        yawVelocity = (yawVelocity - FREQUENCY * yawTerm * seconds) * decay;
        float pitchTarget = Math.clamp(targetPitch, -90f, 90f);
        float pitchError = pitch - pitchTarget;
        float pitchTerm = pitchVelocity + FREQUENCY * pitchError;
        pitch = pitchTarget + (pitchError + pitchTerm * seconds) * decay;
        pitchVelocity = (pitchVelocity - FREQUENCY * pitchTerm * seconds) * decay;
        if (pitch > 90f || pitch < -90f) {
            pitch = Math.clamp(pitch, -90f, 90f);
            pitchVelocity = 0;
        }
        float yawShift = wrap(yaw) - yaw;
        yaw += yawShift;
        previousYaw += yawShift;
    }

    public Vector2f angles(float partialTick) {
        float t = Math.clamp(partialTick, 0f, 1f);
        return new Vector2f(wrap(interpolate(previousYaw, yaw, previousYawVelocity, yawVelocity, t)),
                Math.clamp(interpolate(previousPitch, pitch, previousPitchVelocity, pitchVelocity, t), -90f, 90f));
    }

    private float interpolate(float start, float end, float startVelocity, float endVelocity, float t) {
        float t2 = t * t;
        float t3 = t2 * t;
        return (2 * t3 - 3 * t2 + 1) * start + (t3 - 2 * t2 + t) * interval * startVelocity
                + (-2 * t3 + 3 * t2) * end + (t3 - t2) * interval * endVelocity;
    }

    private static float wrap(float angle) {
        return angle - (float) Math.floor((angle + 180f) / 360f) * 360f;
    }
}
