package ml.mypals.meshcut.cut;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * A lightsaber blade at one instant: a line segment from {@code base} to {@code tip}. Server (cutting) and client
 * (drawing) derive it the same way from the player's eye position and view direction.
 */
public record Blade(Vec3 base, Vec3 tip) {
    public static final double LENGTH = 3.5;

    public static Blade of(Player player, float partialTick) {
        Vec3 look = player.getViewVector(partialTick);
        Vec3 right = look.cross(new Vec3(0, 1, 0));
        right = right.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : right.normalize();
        Vec3 up = right.cross(look);
        // held low and to the right, a little ahead of the face, so it is visible in first person too
        Vec3 base = player.getEyePosition(partialTick).add(look.scale(0.5)).add(right.scale(0.3)).subtract(up.scale(0.3));
        return new Blade(base, base.add(look.scale(LENGTH)));
    }

    public Vec3 direction() {
        return tip.subtract(base);
    }
}
