package ml.mypals.meshcut.cut;

/** Bounded procedural strokes. The same seed produces the same continuous motion on both logical sides. */
public final class SaberGesture {
    public static final int COUNT = 4;
    public static final int DURATION_TICKS = 14;
    public record Sample(float yaw, float pitch, float x, float y) {}

    public static Sample sample(int preset, int seed, float ticks) {
        if (ticks <= 0 || ticks >= DURATION_TICKS) return new Sample(0, 0, 0, 0);
        double t = ticks / DURATION_TICKS;
        // Zero position and velocity at both ends, including the return to the player's aim.
        double envelope = Math.pow(Math.sin(Math.PI * t), 2);
        double stroke = Math.sin(2 * Math.PI * t) * envelope;
        float variation = 0.85f + (seed & 255) / 255f * 0.15f;
        float sign = (seed & 256) == 0 ? 1 : -1;
        float a = (float) (stroke * variation * sign);
        float b = (float) (envelope * variation);
        return switch (preset) {
            case 0 -> new Sample(75 * a, 8 * b, 0.13f * a, -0.03f * b);
            case 1 -> new Sample(55 * a, 50 * a, 0.10f * a, -0.10f * a);
            case 2 -> new Sample(12 * a, 65 * a, 0.03f * a, -0.13f * a);
            case 3 -> new Sample(60 * a, (float) (35 * envelope * Math.cos(2 * Math.PI * t)),
                    0.12f * a, -0.06f * b);
            default -> new Sample(0, 0, 0, 0);
        };
    }

    public static float extension(float ticks) {
        float t = Math.clamp(ticks / 6f, 0, 1);
        return t * t * (3 - 2 * t);
    }

    public static float handOpacity(float viewPitch) {
        float t = Math.clamp((viewPitch - 35) / 45, 0, 1);
        return 1 - 0.85f * t * t * (3 - 2 * t);
    }
    private SaberGesture() {}
}
