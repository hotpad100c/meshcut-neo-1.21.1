package ml.mypals.meshcut.cut;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ml.mypals.meshcut.geom.PolygonCapsule;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.entity.PartEntity;
import org.joml.Vector3d;

import java.util.List;

/**
 * Lets AnvilCraft's laser blocks cut. Deliberately free of AnvilCraft types: the mixin hands over plain geometry, so
 * this class stays loadable (and AnvilCraft stays optional) whatever the dependency scope is.
 *
 * <p>AnvilCraft's beam only sees the blocks of the grid its laser stands in (the world, or one sub-level's plot). What
 * it stops at is burnt away at the head of the beam, a capsule through that one block. What lies across the border
 * (a sub-level the laser of the world points at, or the world seen from a laser on a sub-level) it knows nothing of;
 * that is found here and sliced along the beam like with the saber, since no part of it is in the laser's own grid.
 */
public final class AnvilLaserBeam {
    /** Cutting costs far more than AnvilCraft's own per-tick beam update. */
    private static final int INTERVAL = 4;
    /** How far the beam is followed across the border (AnvilCraft's own lasers are at most 128 long). */
    private static final double REACH = 128;
    /** The sliced strip is this wide: a swept surface needs two blade positions. */
    private static final double SLIT = 0.03;
    /** How deep the slice goes into what it hits. */
    private static final double DEPTH = 1.0;
    /** How much of the beam before its end is part of the blade. */
    private static final double BACK = 3.0;
    /** A blade that moved further than this between ticks did not sweep a surface, it was somewhere else. */
    private static final double JUMP = 4.0;

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
        int radius = Math.clamp((strength + 3) / 4, 1, 4);
        double width = PolygonCapsule.radius(radius);
        Vec3 worldFace = world(sub, face);
        Vec3 worldDir = worldDirection(sub, dir);
        Vec3 headEnd = blocked ? target.getCenter().add(dir.scale(0.5)) : null;
        Vec3 beamEnd = world(sub, headEnd != null ? headEnd : target.getCenter());

        if (ticks % INTERVAL == 0) {
            // What the beam stops at, in its own grid: only the head of the beam is burnt, not the way to it.
            if (headEnd != null) {
                // The capsule reaches back past its start by its own radius, so keep that clear of the emitter.
                Vec3 start = target.getCenter().subtract(dir.scale(0.5));
                Vec3 clear = face.add(dir.scale(width * 1.2 + 0.02));
                if (clear.subtract(start).dot(dir) > 0) start = clear;
                if (headEnd.subtract(start).dot(dir) > 0) {
                    LaserCutting.vaporize(server, sub, world(sub, start), world(sub, headEnd), radius);
                }
            }
            entities(server, worldFace.add(worldDir.scale(width * 1.2 + 0.02)), beamEnd, width, radius);
            expire(server.getGameTime());
        }

        // Across the border. The laser may be carried along (a sub-level moves), so the beam is followed every tick:
        // each tick's blade position sweeps a surface from the last one, and the surfaces are settled together.
        Key key = new Key(server, origin.immutable());
        Vec3 hit = crossing(server, sub, worldFace, worldDir, headEnd != null ? beamEnd : null);
        State state = STATES.get(key);
        if (hit == null) {
            if (state != null) { flush(server, state); STATES.remove(key); }
            return;
        }
        if (state == null) STATES.put(key, state = new State());
        state.seen = server.getGameTime();
        double length = Math.min(hit.subtract(worldFace).dot(worldDir), BACK);
        Vec3 base = hit.subtract(worldDir.scale(length)), tip = hit.add(worldDir.scale(DEPTH));
        Vec3 side = Math.abs(worldDir.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        side = worldDir.cross(side).cross(worldDir).normalize().scale(SLIT);
        Blade last = state.last;
        if (last != null && last.base().distanceToSqr(base) > JUMP * JUMP) last = null; // it was somewhere else: no surface between
        // From the left edge last time to the right edge now: wide enough to be a surface even when nothing moved.
        Blade from = last != null ? last : new Blade(base.subtract(side), tip.subtract(side));
        state.pending.add(new SlashService.Swipe(from, new Blade(base.add(side), tip.add(side))));
        state.last = new Blade(base.subtract(side), tip.subtract(side));
        if (ticks % INTERVAL == 0 || state.pending.size() >= 8) flush(server, state);
    }

    /** The first block the beam meets that is not in the laser's own grid, if the laser's own grid did not stop it first. */
    private static Vec3 crossing(ServerLevel server, SubLevel sub, Vec3 worldFace, Vec3 worldDir, Vec3 ownEnd) {
        var clip = server.clip(new ClipContext(worldFace, worldFace.add(worldDir.scale(REACH)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty()));
        if (clip.getType() != HitResult.Type.BLOCK) return null;
        SubLevel other = Sable.HELPER.getContaining(server, clip.getBlockPos());
        if (other == sub) return null;
        Vec3 hit = world(other, clip.getLocation());
        double along = hit.subtract(worldFace).dot(worldDir);
        if (along <= 0) return null;
        // The laser's own grid stopped the beam first: that is what the capsule is for.
        if (ownEnd != null && ownEnd.subtract(worldFace).dot(worldDir) < along) return null;
        return hit;
    }

    private static void flush(ServerLevel server, State state) {
        if (state.pending.isEmpty()) return;
        var swipes = List.copyOf(state.pending);
        state.pending.clear();
        SlashService.slashBlocks(server, swipes, CapMode.LAVA, null);
    }

    /** Lasers that were removed or switched off leave their last surface behind. */
    private static void expire(long now) {
        STATES.entrySet().removeIf(e -> {
            if (now - e.getValue().seen < 40) return false;
            flush(e.getKey().level(), e.getValue());
            return true;
        });
    }

    private record Key(ServerLevel level, BlockPos pos) {}

    private static final class State {
        final List<SlashService.Swipe> pending = new java.util.ArrayList<>();
        Blade last;
        long seen;
    }

    private static final java.util.Map<Key, State> STATES = new java.util.HashMap<>();

    /** Forgets every beam: they belong to a server that has stopped. */
    public static void clear() {
        STATES.clear();
    }

    private static void entities(ServerLevel server, Vec3 start, Vec3 end, double width, int radius) {
        if (end.subtract(start).lengthSqr() < 1e-6) return;
        for (var entity : server.getEntities((Entity) null, new AABB(start, end).inflate(width),
                e -> EntityCutting.accepts(e instanceof PartEntity<?> part ? part.getParent() : e))) {
            var whole = entity instanceof PartEntity<?> part ? part.getParent() : entity;
            var box = whole.getBoundingBox().inflate(width);
            var in = box.clip(start, end);
            var out = box.clip(end, start);
            if (in.isEmpty() || out.isEmpty()) continue;
            Vec3 a = in.get(), b = out.get();
            EntityCutting.cutEntity(server, whole, null, bodies -> bodies.forEach(body -> LaserCutting.vaporize(server, body, a, b, radius)));
        }
    }

    private static Vec3 world(SubLevel sub, Vec3 local) {
        if (sub == null) return local;
        var p = sub.logicalPose().transformPosition(new Vector3d(local.x, local.y, local.z));
        return new Vec3(p.x, p.y, p.z);
    }

    private static Vec3 worldDirection(SubLevel sub, Vec3 local) {
        if (sub == null) return local;
        var p = sub.logicalPose().transformNormal(new Vector3d(local.x, local.y, local.z));
        return new Vec3(p.x, p.y, p.z).normalize();
    }

    private AnvilLaserBeam() {}
}
