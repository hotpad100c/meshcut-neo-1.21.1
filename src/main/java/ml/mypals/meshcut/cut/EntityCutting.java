package ml.mypals.meshcut.cut;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.block.EntityAppearance;
import ml.mypals.meshcut.block.MimicBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Creatures the blade passes through die on the spot and leave their body behind: a sub-level made of blocks that
 * each hold a part of it (see {@link EntityAppearance}). The body is then cut like anything else.
 */
public final class EntityCutting {
    /** Bodies larger than this (in blocks, along any axis) are not turned into blocks. */
    private static final double MAX_SIZE = 4;
    /** A block that holds less of the body than this (along any axis) is left out. */
    private static final double MIN_PART = 0.02;

    /** The quads the blade swept, in the world. */
    public record Swept(Vec3[] quad) {}

    /**
     * Kills every creature one of the swept quads passes through and puts its body in its place.
     *
     * @param wielder who swung the blade (never cut), or null
     * @return the number of bodies made
     */
    public static int cut(ServerLevel level, List<Swept> sweeps, @Nullable Entity wielder) {
        Set<LivingEntity> hit = new LinkedHashSet<>();
        for (Swept sweep : sweeps) {
            AABB reach = bounds(sweep.quad()).inflate(0.5);
            for (Entity e : level.getEntities(wielder, reach, e -> e instanceof LivingEntity)) {
                LivingEntity living = (LivingEntity) e;
                if (!hit.contains(living) && cuttable(living) && crosses(sweep.quad(), living.getBoundingBox())) {
                    hit.add(living);
                }
            }
        }
        int made = 0;
        for (LivingEntity living : hit) {
            try {
                if (cutDown(level, living, wielder)) {
                    made++;
                }
            } catch (RuntimeException e) {
                MeshCut.LOGGER.error("Couldn't turn {} into a body", living, e);
            }
        }
        return made;
    }

    private static boolean cuttable(LivingEntity living) {
        if (living.isRemoved() || living.isDeadOrDying() || living.isInvulnerable() || living.isMultipartEntity()
                || living.getType().is(Tags.EntityTypes.BOSSES)) {
            return false;
        }
        if (living instanceof Player player && (player.isCreative() || player.isSpectator())) {
            return false;
        }
        AABB box = living.getBoundingBox();
        return box.getXsize() <= MAX_SIZE && box.getYsize() <= MAX_SIZE && box.getZsize() <= MAX_SIZE;
    }

    private static boolean cutDown(ServerLevel level, LivingEntity living, @Nullable Entity wielder) {
        // what it looks like, before it dies
        CompoundTag data = living.saveWithoutId(new CompoundTag());
        EntityAppearance.Look look = new EntityAppearance.Look(
                net.minecraft.world.entity.EntityType.getKey(living.getType()).toString(), data,
                living.yBodyRot, living.getYHeadRot(), living.getXRot(),
                living instanceof Player p ? p.getGameProfile().getId() : null,
                living instanceof Player p ? p.getGameProfile().getName() : null);
        AABB box = living.getBoundingBox();
        Vec3 feet = living.position();
        Vec3 velocity = living.getDeltaMovement().scale(20);

        DamageSource source = wielder instanceof Player player ? level.damageSources().playerAttack(player)
                : wielder instanceof LivingEntity mob ? level.damageSources().mobAttack(mob) : level.damageSources().generic();
        living.hurt(source, Float.MAX_VALUE);
        if (!living.isDeadOrDying()) {
            return false; // saved (a totem, say): it stays as it is
        }
        if (living instanceof Player player) {
            // a player's own death goes on as usual (death screen, respawn); only the falling body is not shown
            player.setInvisible(true);
        } else {
            // its drops fell when it died; the experience would only come at the end of the death animation
            int xp = living.getExperienceReward(level, wielder);
            if (xp > 0) {
                ExperienceOrb.award(level, feet, xp);
            }
            living.remove(Entity.RemovalReason.KILLED);
        }
        return makeBody(level, look, box, feet, velocity);
    }

    /** A sub-level of blocks, each holding the part of the body inside it, standing where the creature stood. */
    private static boolean makeBody(ServerLevel level, EntityAppearance.Look look, AABB box, Vec3 feet, Vec3 velocity) {
        var container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return false;
        }
        int x0 = (int) Math.floor(box.minX), y0 = (int) Math.floor(box.minY), z0 = (int) Math.floor(box.minZ);
        int x1 = (int) Math.floor(box.maxX - 1e-6), y1 = (int) Math.floor(box.maxY - 1e-6), z1 = (int) Math.floor(box.maxZ - 1e-6);
        record Part(BlockPos cell, EntityAppearance appearance) {}
        List<Part> parts = new ArrayList<>();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    double[] bounds = {
                            Math.max(box.minX - x, 0), Math.max(box.minY - y, 0), Math.max(box.minZ - z, 0),
                            Math.min(box.maxX - x, 1), Math.min(box.maxY - y, 1), Math.min(box.maxZ - z, 1)};
                    if (bounds[3] - bounds[0] < MIN_PART || bounds[4] - bounds[1] < MIN_PART || bounds[5] - bounds[2] < MIN_PART) {
                        continue;
                    }
                    double[] origin = {feet.x - x, feet.y - y, feet.z - z};
                    parts.add(new Part(new BlockPos(x, y, z), new EntityAppearance(look, origin, bounds)));
                }
            }
        }
        if (parts.isEmpty()) {
            return false;
        }

        // as Sable assembles a sub-level, but straight into a new plot: nothing in the world is touched
        BlockPos anchor = new BlockPos(x0, y0, z0);
        Pose3d pose = new Pose3d();
        pose.position().set(anchor.getX() + 0.5, anchor.getY() + 0.5, anchor.getZ() + 0.5);
        ServerSubLevel body = (ServerSubLevel) container.allocateNewSubLevel(pose);
        var plot = body.getPlot();
        plot.newEmptyChunk(plot.getCenterChunk());
        BlockPos plotAnchor = plot.getCenterBlock();
        for (Part part : parts) {
            BlockPos at = plotAnchor.offset(part.cell().subtract(anchor));
            MimicBlock.place(level, at, MeshCut.ENTITY_BODY.get().defaultBlockState(), List.of(), List.of(), List.of(),
                    CapMode.LAVA, null, part.appearance());
        }
        // put the plot's blocks where the creature was: plot block p is at p - plotAnchor + anchor
        Vector3dc center = body.getMassTracker().getCenterOfMass();
        Vector3d position;
        if (center != null) {
            position = new Vector3d(center).sub(plotAnchor.getX(), plotAnchor.getY(), plotAnchor.getZ()).add(anchor.getX(), anchor.getY(), anchor.getZ());
        } else {
            body.logicalPose().rotationPoint().set(plotAnchor.getX() + 0.5, plotAnchor.getY() + 0.5, plotAnchor.getZ() + 0.5);
            position = new Vector3d(anchor.getX() + 0.5, anchor.getY() + 0.5, anchor.getZ() + 0.5);
        }
        body.logicalPose().position().set(position);
        var pipeline = container.physicsSystem().getPipeline();
        if (!body.isRemoved()) {
            pipeline.teleport(body, body.logicalPose().position(), body.logicalPose().orientation());
            body.updateLastPose();
            CutService.push(body, velocity);
        }
        return true;
    }

    private static AABB bounds(Vec3[] quad) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (Vec3 p : quad) {
            minX = Math.min(minX, p.x);
            minY = Math.min(minY, p.y);
            minZ = Math.min(minZ, p.z);
            maxX = Math.max(maxX, p.x);
            maxY = Math.max(maxY, p.y);
            maxZ = Math.max(maxZ, p.z);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Whether the (flat, convex) quad passes through the box: what is left of it after clipping by the box's faces. */
    private static boolean crosses(Vec3[] quad, AABB box) {
        List<double[]> poly = new ArrayList<>();
        for (Vec3 p : quad) {
            poly.add(new double[]{p.x, p.y, p.z});
        }
        double[] lo = {box.minX, box.minY, box.minZ}, hi = {box.maxX, box.maxY, box.maxZ};
        for (int axis = 0; axis < 3 && poly.size() >= 3; axis++) {
            poly = clip(poly, axis, lo[axis], true);
            if (poly.size() >= 3) {
                poly = clip(poly, axis, hi[axis], false);
            }
        }
        return poly.size() >= 3;
    }

    private static List<double[]> clip(List<double[]> in, int axis, double bound, boolean keepAbove) {
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            double[] a = in.get(i), b = in.get((i + 1) % in.size());
            double da = (a[axis] - bound) * (keepAbove ? 1 : -1), db = (b[axis] - bound) * (keepAbove ? 1 : -1);
            if (da >= 0) {
                out.add(a);
            }
            if ((da >= 0) != (db >= 0)) {
                double t = da / (da - db);
                out.add(new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t});
            }
        }
        return out;
    }

    private EntityCutting() {}
}
