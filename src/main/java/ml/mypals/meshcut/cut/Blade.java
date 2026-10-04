package ml.mypals.meshcut.cut;

import ml.mypals.meshcut.item.LightsaberMotion;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector2f;
import org.joml.Vector3f;

/**
 * A lightsaber blade at one instant: a line segment from {@code base} to {@code tip}. Server (cutting) and client
 * (drawing) derive it from the same delayed aim and hand pose.
 */
public record Blade(Vec3 base, Vec3 tip) {
    public static final double LENGTH = 3.5;

    public static Blade of(Player player, float partialTick) {
        return of(player, partialTick, LightsaberMotion.angles(player, partialTick),
                LightsaberMotion.handOffset(player, partialTick));
    }

    /** The blade for an aim and hand offset given (what the server worked out), not the ones this side has. */
    public static Blade of(Player player, float partialTick, Vector2f aim, Vector3f handOffset) {
        Vector3f direction = SaberPose.direction(aim.x, aim.y);
        Vector3f offset = SaberPose.emitterOffset(Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot),
                direction, player.getMainArm() == HumanoidArm.LEFT, player.isCrouching(), player.getScale());
        offset.add(new Vector3f(handOffset).mul(player.getScale()));
        Vec3 look = new Vec3(direction.x, direction.y, direction.z);
        Vec3 base = player.getPosition(partialTick).add(offset.x, offset.y, offset.z);
        return new Blade(base, base.add(look.scale(LENGTH * ml.mypals.meshcut.item.LightsaberItem.lengthScale(player.getMainHandItem()) * Math.max(0.0001f,
                LightsaberMotion.visualExtension(player, partialTick)))));
    }

    public Vec3 direction() {
        return tip.subtract(base);
    }
}
