package ml.mypals.meshcut.cut;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Real-time PCM synthesis, with sample-level parameter smoothing instead of discontinuous sound restarts. */
public final class SaberSynthesizer {
    public static final int RATE = 22050;
    private volatile float targetSpeed;
    private double speed, phase, time, noise;
    private int random = 0x6d2b79f5;

    public void speed(float value) { targetSpeed = Float.isFinite(value) ? Math.clamp(value, 0, 1) : 0; }

    public ByteBuffer read(int bytes) {
        // Four short queued buffers keep changes audible within about 0.2 s, rather than four whole seconds.
        int count = Math.max(0, Math.min(bytes / 2, 1024));
        ByteBuffer pcm = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            speed += (targetSpeed - speed) * 0.002;
            time += 1d / RATE;
            phase = (phase + 2 * Math.PI * (92 + 95 * speed) / RATE) % (2 * Math.PI);
            random ^= random << 13;
            random ^= random >>> 17;
            random ^= random << 5;
            noise += ((random / 2147483648d) - noise) * (0.12 + 0.25 * speed);
            double ignition = Math.min(1, time / 0.25);
            double hum = Math.sin(phase) * 0.16 + Math.sin(phase * 2) * 0.06
                    + Math.sin(phase * 5 + Math.sin(time * 31) * 0.4) * 0.025;
            double sample = (hum + noise * (0.025 + speed * 0.24)) * ignition;
            pcm.putShort((short) (Math.clamp(sample, -1, 1) * 32767));
        }
        return pcm.flip();
    }
}
