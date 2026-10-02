package ml.mypals.meshcut.cut;

import org.joml.Vector3f;

/** Player arm and hilt dimensions used by both the server's blade and the client model. */
public final class SaberPose {
    public static final float EMITTER_Y = 15.35f / 16f;
    public static final float GRIP_Y = 6.4f / 16f;
    public static final float HILT_SCALE = 0.55f;
    public static final float PLAYER_MODEL_SCALE = 0.9375f;
    public static final float HAND_REACH = 10f / 16f;
    public static final float SHOULDER_SIDE = 5f / 16f;
    public static final float MOUTH_FROM_HAND = (EMITTER_Y - GRIP_Y) * HILT_SCALE;

    public static Vector3f direction(float yaw, float pitch) {
        double y = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        return new Vector3f((float) (-Math.sin(y) * Math.cos(p)),
                (float) -Math.sin(p), (float) (Math.cos(y) * Math.cos(p)));
    }

    /** Offset from the player's feet: shoulder, extended hand, then hilt mouth. */
    public static Vector3f emitterOffset(float bodyYaw, Vector3f direction, boolean leftHand,
                                         boolean crouching, float entityScale) {
        float shoulderY = crouching ? 5.2f / 16f : 2f / 16f;
        Vector3f shoulder = new Vector3f(leftHand ? SHOULDER_SIDE : -SHOULDER_SIDE,
                1.501f - shoulderY, 0).mul(PLAYER_MODEL_SCALE)
                .rotateY((float) Math.toRadians(-bodyYaw));
        if (crouching) shoulder.y -= 2f / 16f;
        return shoulder.add(new Vector3f(direction).mul((HAND_REACH + MOUTH_FROM_HAND) * PLAYER_MODEL_SCALE))
                .mul(entityScale);
    }

    private SaberPose() {}
}
