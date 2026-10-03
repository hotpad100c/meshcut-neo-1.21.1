package ml.mypals.meshcut.cut;

import org.joml.Vector3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/** Player arm and hilt dimensions used by both the server's blade and the client model. */
public final class SaberPose {
    public static final float EMITTER_Y = 15.35f / 16f;
    public static final float GRIP_Y = 6.4f / 16f;
    public static final float HILT_SCALE = 0.55f;
    public static final float PLAYER_MODEL_SCALE = 0.9375f;
    public static final float HAND_REACH = 10f / 16f;
    public static final float SHOULDER_SIDE = 5f / 16f;
    public static final float MOUTH_FROM_HAND = (EMITTER_Y - GRIP_Y) * HILT_SCALE;
    public static final float SUPPORT_GRIP_DISTANCE = 0.12f;

    public record ArmPose(float pitch, float yaw, float lengthScale) {}

    public static Matrix4f hiltPose(Vector3f base, Vector3f direction, float entityScale) {
        return new Matrix4f().translation(base)
                .rotate(new Quaternionf().rotationTo(0, 1, 0, direction.x, direction.y, direction.z))
                .scale(HILT_SCALE * PLAYER_MODEL_SCALE * entityScale)
                .translate(-0.5f, -EMITTER_Y, -0.5f);
    }

    public static Vector3f direction(float yaw, float pitch) {
        double y = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        return new Vector3f((float) (-Math.sin(y) * Math.cos(p)),
                (float) -Math.sin(p), (float) (Math.cos(y) * Math.cos(p)));
    }

    /** Positive X/Y mean screen-right/up, including when the camera looks straight down. */
    public static Vector3f screenTranslation(float yaw, float pitch, float x, float y) {
        double angle = Math.toRadians(yaw);
        Vector3f right = new Vector3f((float) -Math.cos(angle), 0, (float) -Math.sin(angle));
        Vector3f up = new Vector3f(right).cross(direction(yaw, pitch));
        return right.mul(x).add(up.mul(y));
    }

    /** Both hands meet on the hilt in front of the chest; the wrist moves slightly with the blade. */
    public static Vector3f grip(float relativeYaw, float pitch, boolean crouching) {
        float shoulderY = crouching ? 5.2f / 16f : 2f / 16f;
        return new Vector3f(0, 1.501f - shoulderY - 0.18f, 0.46f)
                .add(direction(relativeYaw, pitch).mul(0.08f));
    }

    public static ArmPose armPose(float relativeYaw, float pitch, boolean leftArm,
                                  boolean mainArm, boolean crouching) {
        return armPose(relativeYaw, pitch, leftArm, mainArm, crouching, new Vector3f());
    }

    public static ArmPose armPose(float relativeYaw, float pitch, boolean leftArm,
                                  boolean mainArm, boolean crouching, Vector3f translation) {
        float shoulderY = crouching ? 5.2f / 16f : 2f / 16f;
        Vector3f hand = grip(relativeYaw, pitch, crouching).add(translation);
        if (!mainArm) hand.sub(direction(relativeYaw, pitch).mul(SUPPORT_GRIP_DISTANCE));
        // PlayerRenderer flips Y/Z between the model's coordinates and this body-relative frame.
        Vector3f arm = hand.sub(leftArm ? SHOULDER_SIDE : -SHOULDER_SIDE, 1.501f - shoulderY, 0);
        arm.y = -arm.y;
        arm.z = -arm.z;
        float length = arm.length();
        return new ArmPose((float) -Math.acos(Math.clamp(arm.y / length, -1, 1)),
                (float) Math.atan2(-arm.x, -arm.z), length / HAND_REACH);
    }

    /** Offset from the player's feet: shared front grip, then the mouth along the blade. */
    public static Vector3f emitterOffset(float bodyYaw, Vector3f direction, boolean leftHand,
                                         boolean crouching, float entityScale) {
        Vector3f localDirection = new Vector3f(direction).rotateY((float) Math.toRadians(bodyYaw));
        float relativeYaw = (float) Math.toDegrees(Math.atan2(-localDirection.x, localDirection.z));
        float pitch = (float) Math.toDegrees(-Math.asin(Math.clamp(localDirection.y, -1, 1)));
        Vector3f hand = grip(relativeYaw, pitch, crouching).mul(PLAYER_MODEL_SCALE)
                .rotateY((float) Math.toRadians(-bodyYaw));
        if (crouching) hand.y -= 2f / 16f;
        return hand.add(new Vector3f(direction).mul(MOUTH_FROM_HAND * PLAYER_MODEL_SCALE)).mul(entityScale);
    }

    private SaberPose() {}
}
