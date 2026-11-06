package ml.mypals.meshcut.cut;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public final class AnvilLaserBeam {
    private static final double REACH = 128;

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

        var hit = BeamCutter.find(server, sub, true, worldFace, worldDir, REACH);
        if (hit != null && headEnd != null && beamEnd.subtract(worldFace).dot(worldDir) < hit.world().subtract(worldFace).dot(worldDir)) {
            hit = null;
        }
        Vec3 end = hit != null ? hit.world() : beamEnd;

        if (hit == null && headEnd != null) {
            Vec3 start = target.getCenter().subtract(dir.scale(0.5));
            Vec3 clear = face.add(dir.scale(width * 1.2 + 0.02));
            if (clear.subtract(start).dot(dir) > 0) start = clear;
            if (headEnd.subtract(start).dot(dir) > 0) {
                LaserCutting.vaporize(server, sub, BeamCutter.world(sub, start), BeamCutter.world(sub, headEnd), width);
            }
        }
        BeamCutter.entities(server, worldFace.add(worldDir.scale(width * 1.2 + 0.02)), end, width, null);
        if (hit != null) {
            BeamCutter.bore(server, hit, worldDir, width);
        }
    }

    public static double holeRadius(int strength) {
        double drawn = Math.sqrt(Math.max(1, Math.min(64, strength))) / 2 * 0.0625 + 0.03125;
        return Math.clamp(drawn * 1.5, 0.09, 0.6);
    }

    private AnvilLaserBeam() {}
}
