package ml.mypals.meshcut.cut;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.entity.PartEntity;
import org.joml.Vector3d;

/**
 * What a beam from a gun or an emitter does to what it hits, whatever shape that is in (the static world, or a
 * sub-level the beam does not belong to): every tick it burns a bore half a block deep along the beam at the point it
 * hits. A beam that stays where it is goes deeper by half a block each tick, one that moves across a surface leaves a
 * trench. Nothing is timed or kept between ticks: what is burnt is remembered by the blocks.
 */
public final class BeamCutter {
    /** The bore of one tick is this deep. */
    private static final double DEPTH = 0.5;

    /** @param body the sub-level that was hit (null: the static world) */
    public record Hit(Vec3 world, SubLevel body) {}

    /**
     * The first block the beam meets, in whatever it is made of.
     *
     * @param own       the body the beam leaves from (null: the static world)
     * @param ignoreOwn whether blocks of {@code own} are passed over (a laser's own grid is dealt with by its own rules)
     * @return null if there is none, or it is behind the origin
     */
    public static Hit find(ServerLevel level, SubLevel own, boolean ignoreOwn, Vec3 from, Vec3 dir, double reach) {
        var clip = level.clip(new ClipContext(from, from.add(dir.scale(reach)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty()));
        if (clip.getType() != HitResult.Type.BLOCK) return null;
        SubLevel body = Sable.HELPER.getContaining(level, clip.getBlockPos());
        if (ignoreOwn && body == own) return null;
        Vec3 world = world(body, clip.getLocation());
        return world.subtract(from).dot(dir) > 0 ? new Hit(world, body) : null;
    }

    /** Burns the bore at the point the beam hit. */
    public static void bore(ServerLevel level, Hit hit, Vec3 dir, double radius) {
        // The capsule starts a hair before the surface, since it also reaches back by its own radius.
        LaserCutting.vaporize(level, hit.body(), hit.world().subtract(dir.scale(0.02)), hit.world().add(dir.scale(DEPTH)), radius);
    }

    /** Living things along the beam fall apart where it goes through them. */
    public static void entities(ServerLevel level, Vec3 start, Vec3 end, double radius, Entity wielder) {
        if (end.subtract(start).lengthSqr() < 1e-6) return;
        for (var entity : level.getEntities(wielder, new AABB(start, end).inflate(radius),
                e -> e instanceof LivingEntity || e instanceof PartEntity<?> part && part.getParent() instanceof LivingEntity)) {
            // Living things only: the beam of a gun is itself an entity, right in the way of its own ray, and cutting it
            // made it vanish and be made anew every tick.
            if (!EntityCutting.accepts(entity instanceof PartEntity<?> part ? part.getParent() : entity)) continue;
            var whole = entity instanceof PartEntity<?> part ? part.getParent() : entity;
            var box = whole.getBoundingBox().inflate(radius);
            var in = box.clip(start, end);
            var out = box.clip(end, start);
            if (in.isEmpty() || out.isEmpty()) continue;
            Vec3 a = in.get(), b = out.get();
            EntityCutting.cutEntity(level, whole, wielder, bodies -> bodies.forEach(body -> LaserCutting.vaporize(level, body, a, b, radius)));
        }
    }

    public static Vec3 world(SubLevel sub, Vec3 local) {
        if (sub == null) return local;
        var p = sub.logicalPose().transformPosition(new Vector3d(local.x, local.y, local.z));
        return new Vec3(p.x, p.y, p.z);
    }

    public static Vec3 worldDirection(SubLevel sub, Vec3 local) {
        if (sub == null) return local;
        var p = sub.logicalPose().transformNormal(new Vector3d(local.x, local.y, local.z));
        return new Vec3(p.x, p.y, p.z).normalize();
    }

    private BeamCutter() {}
}
