package ml.mypals.meshcut.cut;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.block.EntityAppearance;
import ml.mypals.meshcut.block.MimicBlock;
import ml.mypals.meshcut.network.EntityPartsNet;
import ml.mypals.meshcut.physics.RagdollJoints;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.EvokerFangs;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Entities the blade passes through are cut down and leave their body behind: one sub-level per limb, made of blocks
 * that each hold a part of it (see {@link EntityAppearance}), the limbs held together by joints (see
 * {@link RagdollJoints}). The body is then cut like anything else. A creature dies (and drops what it drops); a
 * thing (a boat, a painting) breaks into its item, and its body is one rigid piece.
 * <p>
 * Only clients have entity models, so the server asks one (the wielder's, or another player's nearby) to take the
 * entity apart into limbs; meanwhile the entity stands frozen. Without an answer the body is one piece the size of
 * the entity's box.
 */
public final class EntityCutting {
    /** Entities larger than this (in blocks, along any axis) are not cut. */
    private static final double MAX_SIZE = 4;
    /** A block that would hold less of a limb than this (along any axis) is left out. */
    private static final double MIN_PART = 0.02;
    /** How long a client may take to answer (ticks). */
    private static final int ANSWER_TICKS = 20;
    /** Things of these types are never cut. */
    public static final TagKey<EntityType<?>> UNCUTTABLE = TagKey.create(Registries.ENTITY_TYPE,
            ResourceLocation.fromNamespaceAndPath(MeshCut.MODID, "uncuttable"));

    /** The quads the blade swept, in the world. */
    public record Swept(Vec3[] quad) {}

    /** An entity waiting for a client to say what its limbs are. */
    private static final class Pending {
        final ServerLevel level;
        final Entity entity;
        final EntityAppearance.Look look;
        @Nullable
        final UUID wielder;
        final UUID asked;
        final long deadline;
        final Consumer<List<ServerSubLevel>> cut;
        final boolean wasNoAi;
        final boolean wasInvulnerable;
        final Vec3 velocity;

        Pending(ServerLevel level, Entity entity, EntityAppearance.Look look, @Nullable UUID wielder, UUID asked,
                Consumer<List<ServerSubLevel>> cut) {
            this.level = level;
            this.entity = entity;
            this.look = look;
            this.wielder = wielder;
            this.asked = asked;
            this.deadline = level.getGameTime() + ANSWER_TICKS;
            this.cut = cut;
            this.wasNoAi = entity instanceof Mob mob && mob.isNoAi();
            this.wasInvulnerable = entity.isInvulnerable();
            this.velocity = entity.getDeltaMovement().scale(20);
        }
    }

    private static final Map<Integer, Pending> PENDING = new HashMap<>();
    private static int nextId;

    /**
     * Cuts down every entity one of the swept quads passes through. Those whose limbs are known at once (no client to
     * ask) are in place when this returns, so the rest of the swing cuts them; the others are asked about and cut by
     * {@code cutLater} once their body has been built.
     *
     * @param wielder  who swung the blade (never cut), or null
     * @param cutLater cuts the bodies built later with the same swing
     */
    public static void cut(ServerLevel level, List<Swept> sweeps, @Nullable Entity wielder, Consumer<List<ServerSubLevel>> cutLater) {
        Set<Entity> hit = new LinkedHashSet<>();
        for (Swept sweep : sweeps) {
            AABB reach = bounds(sweep.quad()).inflate(0.5);
            for (Entity e : level.getEntities(wielder, reach, EntityCutting::cuttable)) {
                if (!hit.contains(e) && crosses(sweep.quad(), e.getBoundingBox())) {
                    hit.add(e);
                }
            }
        }
        for (Entity entity : hit) {
            if (PENDING.values().stream().anyMatch(p -> p.entity == entity)) {
                continue; // already waiting for its limbs
            }
            try {
                EntityAppearance.Look look = lookOf(entity);
                ServerPlayer client = clientFor(level, entity, wielder);
                if (client == null) {
                    finish(level, entity, look, wielder, entity.getDeltaMovement().scale(20), null);
                    continue;
                }
                int id = nextId++;
                Pending pending = new Pending(level, entity, look, wielder == null ? null : wielder.getUUID(), client.getUUID(), cutLater);
                PENDING.put(id, pending);
                freeze(entity, true, pending);
                PacketDistributor.sendToPlayer(client, new EntityPartsNet.PartsRequest(id, look));
            } catch (RuntimeException e) {
                MeshCut.LOGGER.error("Couldn't cut {}", entity, e);
            }
        }
    }

    /** A client's answer: the limbs of the entity it was asked about. */
    public static void onReply(ServerPlayer player, int id, List<BodySpec> bodies) {
        Pending pending = PENDING.get(id);
        if (pending == null || !pending.asked.equals(player.getUUID())) {
            return;
        }
        PENDING.remove(id);
        List<BodySpec> valid = validate(bodies, pending.entity);
        if (valid == null) {
            MeshCut.LOGGER.warn("{} sent limbs for a {} that make no sense; it falls as one piece", player.getName().getString(), pending.look.type());
        }
        complete(pending, valid);
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        if (PENDING.isEmpty()) {
            return;
        }
        for (Iterator<Map.Entry<Integer, Pending>> it = PENDING.entrySet().iterator(); it.hasNext(); ) {
            Pending pending = it.next().getValue();
            if (pending.level.getGameTime() >= pending.deadline || pending.entity.isRemoved()) {
                it.remove();
                complete(pending, null);
            } else if (!(pending.entity instanceof Player)) {
                pending.entity.setDeltaMovement(Vec3.ZERO);
            }
        }
    }

    private static void complete(Pending pending, @Nullable List<BodySpec> bodies) {
        freeze(pending.entity, false, pending);
        if (pending.entity.isRemoved()) {
            return; // gone meanwhile (it despawned, or something else killed it)
        }
        Entity wielder = pending.wielder == null ? null : pending.level.getEntity(pending.wielder);
        try {
            List<ServerSubLevel> built = finish(pending.level, pending.entity, pending.look, wielder, pending.velocity, bodies);
            if (!built.isEmpty()) {
                pending.cut.accept(built);
            }
        } catch (RuntimeException e) {
            MeshCut.LOGGER.error("Couldn't cut {}", pending.entity, e);
        }
    }

    private static void freeze(Entity entity, boolean on, Pending pending) {
        if (entity instanceof Mob mob) {
            mob.setNoAi(on || pending.wasNoAi);
        }
        // nothing else may kill it meanwhile: it would die a second time
        entity.setInvulnerable(on || pending.wasInvulnerable);
        if (on && !(entity instanceof Player)) {
            entity.setDeltaMovement(Vec3.ZERO);
        }
    }

    /**
     * Cuts the entity down and builds its body in its place.
     *
     * @param bodies its limbs, or null for one piece the size of its box
     * @return the limbs built
     */
    private static List<ServerSubLevel> finish(ServerLevel level, Entity entity, EntityAppearance.Look look, @Nullable Entity wielder,
                                               Vec3 velocity, @Nullable List<BodySpec> bodies) {
        AABB box = entity.getBoundingBox();
        Vec3 feet = entity.position();
        if (!cutDown(level, entity, wielder)) {
            return List.of();
        }
        if (bodies == null) {
            bodies = List.of(new BodySpec(null, EntityAppearance.NO_ROTATION.clone(), new double[3],
                    List.of(new double[]{box.minX - feet.x, box.minY - feet.y, box.minZ - feet.z,
                            box.maxX - feet.x, box.maxY - feet.y, box.maxZ - feet.z}), -1, new double[3]));
        }
        List<ServerSubLevel> built = new ArrayList<>(bodies.size());
        for (BodySpec spec : bodies) {
            built.add(makeLimb(level, look, spec, feet, velocity));
        }
        for (int i = 0; i < bodies.size(); i++) {
            BodySpec spec = bodies.get(i);
            if (spec.parent() >= 0 && built.get(i) != null && built.get(spec.parent()) != null) {
                Vec3 anchor = feet.add(spec.anchor()[0], spec.anchor()[1], spec.anchor()[2]);
                RagdollJoints.join(level, built.get(spec.parent()), bodies.get(spec.parent()).name(), built.get(i), spec.name(), anchor);
            }
        }
        built.removeIf(java.util.Objects::isNull);
        return built;
    }

    // ---------------------------------------------------------------- which entities, and how they go

    private static boolean cuttable(Entity e) {
        if (e.isRemoved() || e.isInvulnerable() || e.isMultipartEntity() || e.getType().is(Tags.EntityTypes.BOSSES)
                || e.getType().is(UNCUTTABLE)) {
            return false;
        }
        AABB box = e.getBoundingBox();
        if (box.getXsize() > MAX_SIZE || box.getYsize() > MAX_SIZE || box.getZsize() > MAX_SIZE) {
            return false;
        }
        if (e instanceof LivingEntity living) {
            return !living.isDeadOrDying() && !(living instanceof Player player && (player.isCreative() || player.isSpectator()));
        }
        if (e instanceof Projectile || e instanceof ItemEntity || e instanceof ExperienceOrb || e instanceof Display
                || e instanceof Marker || e instanceof Interaction || e instanceof FallingBlockEntity || e instanceof PrimedTnt
                || e instanceof AreaEffectCloud || e instanceof EvokerFangs || e instanceof LightningBolt
                || e instanceof EndCrystal || e instanceof LeashFenceKnotEntity) {
            return false;
        }
        if (e instanceof VehicleEntity || e instanceof HangingEntity) {
            return true;
        }
        ItemStack pick = e.getPickResult();
        return pick != null && !pick.isEmpty();
    }

    private static EntityAppearance.Look lookOf(Entity entity) {
        CompoundTag data = entity.saveWithoutId(new CompoundTag());
        float bodyYaw = entity instanceof LivingEntity living ? living.yBodyRot : entity.getYRot();
        return new EntityAppearance.Look(EntityType.getKey(entity.getType()).toString(), data, bodyYaw,
                entity.getYHeadRot(), entity.getXRot(),
                entity instanceof Player p ? p.getGameProfile().getId() : null,
                entity instanceof Player p ? p.getGameProfile().getName() : null);
    }

    /** Who to ask for the entity's limbs: the wielder, or the nearest player that sees it. */
    @Nullable
    private static ServerPlayer clientFor(ServerLevel level, Entity entity, @Nullable Entity wielder) {
        if (wielder instanceof ServerPlayer player && !player.hasDisconnected()) {
            return player;
        }
        ServerPlayer best = null;
        for (ServerPlayer player : level.getChunkSource().chunkMap.getPlayers(entity.chunkPosition(), false)) {
            if (best == null || player.distanceToSqr(entity) < best.distanceToSqr(entity)) {
                best = player;
            }
        }
        return best;
    }

    /** @return whether it is gone (a creature dead, a thing broken into its item) */
    private static boolean cutDown(ServerLevel level, Entity entity, @Nullable Entity wielder) {
        DamageSource source = wielder instanceof Player player ? level.damageSources().playerAttack(player)
                : wielder instanceof LivingEntity mob ? level.damageSources().mobAttack(mob) : level.damageSources().generic();
        entity.ejectPassengers();
        entity.stopRiding();
        if (entity instanceof LivingEntity living) {
            Vec3 feet = living.position();
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
            return true;
        }
        // a thing is broken as a hit breaks it (a framed item first, then the frame), so it drops what it drops
        for (int i = 0; i < 3 && !entity.isRemoved(); i++) {
            entity.hurt(source, Float.MAX_VALUE);
        }
        if (!entity.isRemoved()) {
            ItemStack pick = entity.getPickResult();
            if (pick != null && !pick.isEmpty() && !(wielder instanceof Player player && player.isCreative())) {
                entity.spawnAtLocation(pick);
            }
            entity.discard();
        }
        return true;
    }

    // ---------------------------------------------------------------- building

    /** What a client sent, if it is sound: a few limbs, each near where the entity stood. */
    @Nullable
    private static List<BodySpec> validate(List<BodySpec> bodies, Entity entity) {
        if (bodies.isEmpty() || bodies.size() > BodySpec.MAX_BODIES) {
            return null;
        }
        AABB allowed = entity.getBoundingBox().move(entity.position().scale(-1)).inflate(1);
        int boxes = 0;
        for (int i = 0; i < bodies.size(); i++) {
            BodySpec b = bodies.get(i);
            if (b.parent() < -1 || b.parent() >= bodies.size() || b.parent() == i || b.boxes().isEmpty()
                    || !finite(b.rotation()) || !finite(b.origin()) || !finite(b.anchor())
                    || b.parent() >= 0 && !allowed.contains(b.anchor()[0], b.anchor()[1], b.anchor()[2])) {
                return null;
            }
            double length = Math.sqrt(b.rotation()[0] * b.rotation()[0] + b.rotation()[1] * b.rotation()[1]
                    + b.rotation()[2] * b.rotation()[2] + b.rotation()[3] * b.rotation()[3]);
            if (Math.abs(length - 1) > 1e-3) {
                return null;
            }
            Quaterniond q = new Quaterniond(b.rotation()[0], b.rotation()[1], b.rotation()[2], b.rotation()[3]);
            for (double[] box : b.boxes()) {
                boxes++;
                if (box.length != 6 || !finite(box) || box[3] < box[0] || box[4] < box[1] || box[5] < box[2]) {
                    return null;
                }
                for (int c = 0; c < 8; c++) {
                    Vector3d p = q.transform(new Vector3d(box[(c & 1) == 0 ? 0 : 3], box[(c & 2) == 0 ? 1 : 4], box[(c & 4) == 0 ? 2 : 5]))
                            .add(b.origin()[0], b.origin()[1], b.origin()[2]);
                    if (!allowed.contains(p.x, p.y, p.z)) {
                        return null;
                    }
                }
            }
        }
        if (boxes > BodySpec.MAX_BOXES) {
            return null;
        }
        for (int i = 0; i < bodies.size(); i++) { // no loops
            int steps = 0;
            for (int p = bodies.get(i).parent(); p >= 0; p = bodies.get(p).parent()) {
                if (p >= bodies.size() || ++steps > bodies.size()) {
                    return null;
                }
            }
        }
        return bodies;
    }

    private static boolean finite(double[] values) {
        for (double v : values) {
            if (!Double.isFinite(v)) {
                return false;
            }
        }
        return true;
    }

    /**
     * One limb as a sub-level, standing where it stood: blocks only where the limb has boxes, each holding the
     * boxes' part inside it, laid out in the limb's frame and turned with it.
     */
    @Nullable
    private static ServerSubLevel makeLimb(ServerLevel level, EntityAppearance.Look look, BodySpec spec, Vec3 feet, Vec3 velocity) {
        var container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return null;
        }
        // the cells (in the limb's frame) its boxes cover, and what of the boxes each holds
        Map<BlockPos, List<double[]>> cells = new java.util.LinkedHashMap<>();
        for (double[] b : spec.boxes()) {
            for (int x = (int) Math.floor(b[0]); x <= (int) Math.floor(b[3] - 1e-9); x++) {
                for (int y = (int) Math.floor(b[1]); y <= (int) Math.floor(b[4] - 1e-9); y++) {
                    for (int z = (int) Math.floor(b[2]); z <= (int) Math.floor(b[5] - 1e-9); z++) {
                        double[] in = {Math.max(b[0] - x, 0), Math.max(b[1] - y, 0), Math.max(b[2] - z, 0),
                                Math.min(b[3] - x, 1), Math.min(b[4] - y, 1), Math.min(b[5] - z, 1)};
                        if (in[3] - in[0] >= MIN_PART && in[4] - in[1] >= MIN_PART && in[5] - in[2] >= MIN_PART) {
                            cells.computeIfAbsent(new BlockPos(x, y, z), k -> new ArrayList<>()).add(in);
                        }
                    }
                }
            }
        }
        if (cells.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos c : cells.keySet()) {
            minX = Math.min(minX, c.getX());
            minY = Math.min(minY, c.getY());
            minZ = Math.min(minZ, c.getZ());
            maxX = Math.max(maxX, c.getX());
            maxY = Math.max(maxY, c.getY());
            maxZ = Math.max(maxZ, c.getZ());
        }
        // cell c goes to plotAnchor + c - shift, which keeps the limb around the middle of the plot's first chunk
        BlockPos shift = new BlockPos((minX + maxX) >> 1, (minY + maxY) >> 1, (minZ + maxZ) >> 1);

        Quaterniond rotation = new Quaterniond(spec.rotation()[0], spec.rotation()[1], spec.rotation()[2], spec.rotation()[3]);
        Quaterniond inverse = new Quaterniond(rotation).conjugate();
        Vector3d frameOrigin = new Vector3d(spec.origin()[0], spec.origin()[1], spec.origin()[2]);
        // a point p of the entity (relative to its feet) is at inverse·(p − frameOrigin) in the limb's frame
        Vector3d toFrame = inverse.transform(new Vector3d(frameOrigin)).negate();

        Pose3d pose = new Pose3d();
        pose.position().set(feet.x, feet.y, feet.z);
        ServerSubLevel limb = (ServerSubLevel) container.allocateNewSubLevel(pose);
        var plot = limb.getPlot();
        plot.newEmptyChunk(plot.getCenterChunk());
        BlockPos plotAnchor = plot.getCenterBlock();
        for (var cell : cells.entrySet()) {
            BlockPos c = cell.getKey();
            BlockPos at = plotAnchor.offset(c.subtract(shift));
            double[] origin = {toFrame.x - c.getX(), toFrame.y - c.getY(), toFrame.z - c.getZ()};
            MimicBlock.place(level, at, MeshCut.ENTITY_BODY.get().defaultBlockState(), List.of(), List.of(), List.of(),
                    CapMode.LAVA, null, new EntityAppearance(look, spec.name(), spec.rotation().clone(), origin, cell.getValue()));
        }
        if (limb.isRemoved()) {
            return null;
        }
        // plot point P is the limb-frame point L = P − plotAnchor + shift, which is at feet + origin + rotation·L
        Vector3dc center = limb.getMassTracker().getCenterOfMass();
        Vector3d rotationPoint;
        if (center != null) {
            rotationPoint = new Vector3d(center);
        } else {
            rotationPoint = new Vector3d(plotAnchor.getX() + 0.5, plotAnchor.getY() + 0.5, plotAnchor.getZ() + 0.5);
            limb.logicalPose().rotationPoint().set(rotationPoint);
        }
        Vector3d local = new Vector3d(rotationPoint).sub(plotAnchor.getX(), plotAnchor.getY(), plotAnchor.getZ())
                .add(shift.getX(), shift.getY(), shift.getZ());
        Vector3d position = rotation.transform(local).add(frameOrigin).add(feet.x, feet.y, feet.z);
        limb.logicalPose().position().set(position);
        limb.logicalPose().orientation().set(rotation);
        container.physicsSystem().getPipeline().teleport(limb, limb.logicalPose().position(), limb.logicalPose().orientation());
        limb.updateLastPose();
        CutService.push(limb, velocity);
        return limb;
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
