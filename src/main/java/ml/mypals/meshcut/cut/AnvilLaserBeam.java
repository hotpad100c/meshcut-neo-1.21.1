package ml.mypals.meshcut.cut;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Lets AnvilCraft's laser blocks cut. Deliberately free of AnvilCraft types: the mixin hands over plain geometry, so
 * this class stays loadable (and AnvilCraft stays optional) whatever the dependency scope is.
 *
 * <p>AnvilCraft's beam only sees the blocks of the grid its laser stands in (the world, or one sub-level's plot). What
 * it stops at is burnt away at the head of the beam, a capsule through that one block. What lies across the border
 * (a sub-level the laser of the world points at, or the world seen from a laser on a sub-level) it knows nothing of;
 * that is found here and cut as {@link BeamCutter} does for every beam.
 */
public final class AnvilLaserBeam {
    /** How far the beam is followed across the border (AnvilCraft's own lasers are at most 128 long). */
    private static final double REACH = 128;

    /**
     * @param origin   block the beam leaves from
     * @param target   first block the beam could not pass (or its last block when nothing blocks it)
     * @param blocked  whether {@code target} stopped the beam
     * @param strength AnvilCraft's laser level, which sets how wide the burnt hole is
     */
    public static void emit(Level level, BlockPos origin, BlockPos target, Direction direction, boolean blocked, int strength, int ticks) {
        if (!(level instanceof ServerLevel server) || strength <= 0) return;
        Vec3 dir = new Vec3(direction.getStepX(), direction.getStepY(), direction.getStepZ());
        Vec3 face = origin.getCenter().add(dir.scale(0.5));
        SubLevel sub = Sable.HELPER.getContaining(server, origin);
        double width = holeRadius(strength);
        Vec3 worldFace = BeamCutter.world(sub, face);
        Vec3 worldDir = BeamCutter.worldDirection(sub, dir);
        Vec3 headEnd = blocked ? target.getCenter().add(dir.scale(0.5)) : null;
        Vec3 beamEnd = BeamCutter.world(sub, headEnd != null ? headEnd : target.getCenter());

        // Something of another grid in the way of the beam, nearer than what stops it in its own?
        var hit = BeamCutter.find(server, sub, true, worldFace, worldDir, REACH);
        if (hit != null && headEnd != null && beamEnd.subtract(worldFace).dot(worldDir) < hit.world().subtract(worldFace).dot(worldDir)) {
            hit = null; // the laser's own grid stopped the beam first: that is what the capsule is for
        }
        Vec3 end = hit != null ? hit.world() : beamEnd;

        // What the beam stops at, in its own grid: only the head of the beam is burnt, not the way to it.
        if (hit == null && headEnd != null) {
            // The capsule reaches back past its start by its own radius, so keep that clear of the emitter.
            Vec3 start = target.getCenter().subtract(dir.scale(0.5));
            Vec3 clear = face.add(dir.scale(width * 1.2 + 0.02));
            if (clear.subtract(start).dot(dir) > 0) start = clear;
            if (headEnd.subtract(start).dot(dir) > 0) {
                LaserCutting.vaporize(server, sub, BeamCutter.world(sub, start), BeamCutter.world(sub, headEnd), width);
            }
        }
        BeamCutter.entities(server, worldFace.add(worldDir.scale(width * 1.2 + 0.02)), end, width, null);
        // Across the border (it is not in the laser's own grid, which knows nothing of it).
        if (hit != null) {
            BeamCutter.bore(server, hit, worldDir, width);
        }
    }

    /**
     * The radius of the hole a laser of this power burns, in blocks: it follows the width of the beam AnvilCraft draws
     * (which grows with the square root of the power, up to 64 for a creative laser), with a margin so that the weakest
     * beam still leaves a hole one can see.
     */
    public static double holeRadius(int strength) {
        double drawn = Math.sqrt(Math.max(1, Math.min(64, strength))) / 2 * 0.0625 + 0.03125; // the halo round the core
        return Math.clamp(drawn * 1.5, 0.09, 0.6);
    }

    private AnvilLaserBeam() {}
}
