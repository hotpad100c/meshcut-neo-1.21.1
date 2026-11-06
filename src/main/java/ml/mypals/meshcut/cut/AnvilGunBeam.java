package ml.mypals.meshcut.cut;

import ml.mypals.meshcut.geom.PolygonCapsule;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

public final class AnvilGunBeam {
    private static final double RANGE = 48;
    private static final double RADIUS = PolygonCapsule.radius(2) * 2 / 3;

    public static void fire(ServerPlayer player) {
        var level = player.serverLevel();
        Vec3 from = player.getEyePosition();
        Vec3 dir = player.getLookAngle();
        var hit = BeamCutter.find(level, null, false, from, dir, RANGE);
        Vec3 end = hit != null ? hit.world() : from.add(dir.scale(RANGE));
        BeamCutter.entities(level, from.add(dir.scale(0.6)), end, RADIUS, player);
        if (hit != null) {
            BeamCutter.bore(level, hit, dir, RADIUS);
        }
    }

    private AnvilGunBeam() {}
}
