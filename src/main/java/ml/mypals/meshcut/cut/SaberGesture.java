package ml.mypals.meshcut.cut;

public final class SaberGesture {
    public static final int COUNT = 8;
    public static final int DURATION_TICKS = 18;
    public record Sample(float yaw, float pitch, float x, float y) {}

    public static Sample sample(int preset, int seed, float ticks) {
        if (ticks <= 0 || ticks >= DURATION_TICKS) return new Sample(0, 0, 0, 0);
        double t = ticks / DURATION_TICKS;
        double envelope = Math.pow(Math.sin(Math.PI * t), 2);
        double stroke = Math.sin(2 * Math.PI * t) * envelope;
        float variation = 0.82f + (seed & 255) / 255f * 0.36f;
        float sign = (seed & 256) == 0 ? 1 : -1;
        float a = (float) (stroke * variation * sign);
        float b = (float) (envelope * variation);
        float twist = (float) (Math.sin(4 * Math.PI * t + ((seed >>> 9) & 31) * 0.2) * envelope * variation);
        return switch (preset) {
            case 0 -> new Sample(112 * a, 12 * b, 0.17f * a, -0.04f * b);
            case 1 -> new Sample(82 * a, 72 * a, 0.14f * a, -0.14f * a);
            case 2 -> new Sample(18 * a, 84 * a, 0.04f * a, -0.18f * a);
            case 3 -> new Sample(95 * a, 52 * twist, 0.16f * a, -0.08f * b);
            case 4 -> new Sample(72 * twist, -78 * a, 0.12f * twist, 0.15f * a);
            case 5 -> new Sample(105 * a, 45 * b - 42 * twist, 0.19f * a, 0.08f * twist);
            case 6 -> new Sample(58 * a + 42 * twist, 76 * twist, 0.11f * a, -0.17f * b);
            case 7 -> new Sample(-88 * twist, 62 * a, -0.16f * twist, -0.12f * a);
            default -> new Sample(0, 0, 0, 0);
        };
    }

    public static float extension(float ticks) {
        float t = Math.clamp(ticks / 10f, 0, 1);
        return t * t * (3 - 2 * t);
    }

    public static float retraction(float ticks) {
        float t = Math.clamp(ticks / 7f, 0, 1);
        return 1 - t * t * (3 - 2 * t);
    }

    public static float handOpacity(float viewPitch) {
        float t = Math.clamp((viewPitch - 35) / 45, 0, 1);
        return 1 - 0.85f * t * t * (3 - 2 * t);
    }
    private SaberGesture() {}
}
