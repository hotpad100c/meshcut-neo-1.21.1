package ml.mypals.meshcut.client;

import org.joml.Vector3f;

/** Render-time recoil, independent of FPS and server packet latency. */
public final class GunRecoil {
    public static float amount(double age) {
        return amount(age, 0);
    }

    public static float amount(double age, float initial) {
        if (!Double.isFinite(age) || age < 0 || age > 24) return 0;
        // A quick impulse followed by Create's characteristic 0.8-per-tick recovery.
        double attack = Math.min(1, age / 0.35);
        attack = attack * attack * (3 - 2 * attack);
        double baseline = Float.isFinite(initial) ? Math.clamp(initial, 0, 1) : 0;
        return (float) ((baseline + (1 - baseline) * attack) * Math.pow(0.8, Math.max(0, age - 0.35)));
    }

    public static Vector3f grip() {
        return new Vector3f(0, 0.5f, -0.1f).rotateX((float)Math.toRadians(-22.5))
                .add(8, 3, 13.1f).div(16).sub(0.5f, 0.5f, 0.5f);
    }
    private GunRecoil() {}
}
