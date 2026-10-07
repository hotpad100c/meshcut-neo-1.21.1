package ml.mypals.meshcut.cut;

import ml.mypals.meshcut.geom.PolygonCapsule;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * What AnvilCraft's laser gun does to blocks and living things, as a laser emitter does: see {@link BeamCutter}. Free of
 * AnvilCraft's types for the same reason as {@link AnvilLaserBeam}.
 */
public final class AnvilGunBeam {
    /** AnvilCraft's gun reaches this far. */
    private static final double RANGE = 48;
    /** The hole is a third narrower than the radius-two hole of the gun of this mod. */
    private static final double RADIUS = PolygonCapsule.radius(2) * 2 / 3;

    /** Called every tick the gun is firing. */
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
