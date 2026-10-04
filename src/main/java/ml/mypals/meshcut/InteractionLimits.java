package ml.mypals.meshcut;

/** Shared numerical boundaries; usable without booting a Minecraft world. */
public final class InteractionLimits {
    public static final float MIN_LENGTH = 0.25f, MAX_LENGTH = 2f;

    public static float bladeLength(float value) {
        return Float.isFinite(value) ? Math.clamp(value, MIN_LENGTH, MAX_LENGTH) : 1f;
    }

    public static boolean grabNumbers(double x, double y, double z, float distance) {
        double limit = Integer.MAX_VALUE - 1.0;
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) && Float.isFinite(distance)
                && Math.abs(x) <= limit && Math.abs(y) <= limit && Math.abs(z) <= limit;
    }

    private InteractionLimits() {}
}
