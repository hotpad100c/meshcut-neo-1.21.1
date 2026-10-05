package ml.mypals.meshcut.physics;

import ml.mypals.meshcut.MeshCut;

/**
 * Where a laggy physics step comes from. Rapier's step is native code that a Java profiler cannot see into, so what is
 * counted here is what is fed to it: the timing of each step, how many bodies are awake, and how many colliders (and
 * boxes in them) the mimic blocks have made. Logged every {@link #EVERY} steps; {@code -Dmeshcut.diag=true} turns it on (off by default).
 */
public final class PhysicsDiag {

    private static final int EVERY = 100;

    private static long total;
    private static long max;
    private static int steps;
    private static long lastEntries;
    private static long lastBoxes;

    public static boolean enabled() {
        return ml.mypals.meshcut.MeshCutSettings.diagnostics();
    }

    public static void step(long nanos, int activeBodies) {
        total += nanos;
        max = Math.max(max, nanos);
        if (++steps < EVERY) {
            return;
        }
        long entries = MimicColliders.createdEntries;
        long boxes = MimicColliders.createdBoxes;
        MeshCut.LOGGER.info("[meshcut diag] rapier step avg {} ms, max {} ms over {} steps; awake bodies {}; mimic colliders {} cached, "
                        + "+{} new / +{} boxes since last report ({} boxes in total, {} coarsened, {} overflowed)",
                String.format("%.2f", total / 1e6 / steps), String.format("%.2f", max / 1e6), steps, activeBodies,
                MimicColliders.entryCount(), entries - lastEntries, boxes - lastBoxes, boxes,
                MimicColliders.coarsened, MimicColliders.overflowFallbacks);
        lastEntries = entries;
        lastBoxes = boxes;
        total = 0;
        max = 0;
        steps = 0;
    }

    private PhysicsDiag() {}
}
