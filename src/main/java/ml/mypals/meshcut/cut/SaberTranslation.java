package ml.mypals.meshcut.cut;

import org.joml.Vector2f;

/** Critically damped hand translation with velocity-continuous interpolation across ticks. */
public final class SaberTranslation {
    public static final float LIMIT_X = 0.52f;
    public static final float LIMIT_Y = 0.46f;
    private final Vector2f position = new Vector2f(), velocity = new Vector2f();
    private final Vector2f previous = new Vector2f(), previousVelocity = new Vector2f();
    private float interval = 0.05f;

    public void advance(float x, float y, float seconds) {
        if (!(seconds > 0) || !Float.isFinite(seconds) || !Float.isFinite(x) || !Float.isFinite(y)) return;
        previous.set(position);
        previousVelocity.set(velocity);
        interval = seconds;
        float frequency = 30;
        float decay = (float) Math.exp(-frequency * seconds);
        Vector2f error = new Vector2f(position).sub(x, y);
        Vector2f c = new Vector2f(error).mul(frequency).add(velocity);
        position.set(error).add(new Vector2f(c).mul(seconds)).mul(decay).add(x, y);
        velocity.sub(new Vector2f(c).mul(frequency * seconds)).mul(decay);
    }

    public Vector2f position(float partial) {
        float t = Math.clamp(partial, 0, 1), t2 = t * t, t3 = t2 * t;
        return new Vector2f(previous).mul(2 * t3 - 3 * t2 + 1)
                .add(new Vector2f(previousVelocity).mul((t3 - 2 * t2 + t) * interval))
                .add(new Vector2f(position).mul(-2 * t3 + 3 * t2))
                .add(new Vector2f(velocity).mul((t3 - t2) * interval));
    }
}
