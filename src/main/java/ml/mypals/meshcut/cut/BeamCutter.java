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

public final class BeamCutter {
    private static final double DEPTH = 0.5;

    public record Hit(Vec3 world, SubLevel body) {}

    public static Hit find(ServerLevel level, SubLevel own, boolean ignoreOwn, Vec3 from, Vec3 dir, double reach) {
        var clip = level.clip(new ClipContext(from, from.add(dir.scale(reach)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty()));
        if (clip.getType() != HitResult.Type.BLOCK) return null;
        SubLevel body = Sable.HELPER.getContaining(level, clip.getBlockPos());
        if (ignoreOwn && body == own) return null;
        Vec3 world = world(body, clip.getLocation());
        return world.subtract(from).dot(dir) > 0 ? new Hit(world, body) : null;
    }

    public static void bore(ServerLevel level, Hit hit, Vec3 dir, double radius) {
        LaserCutting.vaporize(level, hit.body(), hit.world().subtract(dir.scale(0.02)), hit.world().add(dir.scale(DEPTH)), radius);
    }

    public static void entities(ServerLevel level, Vec3 start, Vec3 end, double radius, Entity wielder) {
        if (end.subtract(start).lengthSqr() < 1e-6) return;
        for (var entity : level.getEntities(wielder, new AABB(start, end).inflate(radius),
                e -> e instanceof LivingEntity || e instanceof PartEntity<?> part && part.getParent() instanceof LivingEntity)) {
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
