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
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityInvulnerabilityCheckEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
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
    /**
     * The parts of a limb in a block lie on a grid this fine (per block), and are never thinner than one step of it: a
     * sliver of a limb that just reaches into a block still gets that block, or what of the model is there would not
     * be drawn by anyone.
     */
    private static final int GRID = 16;
    /** At most this many collision boxes in one block of a body. */
    private static final int MAX_BOXES_PER_BLOCK = 6;
    /** At most this many blocks for one body (a client could ask for anything). */
    private static final int MAX_CELLS = 65536;
    /**
     * No part of a body is further than this (in blocks) from the entity's feet. The biggest bodies (the ender dragon)
     * reach a few blocks; numbers beyond it would only come from a client sending nonsense, and would make the cells
     * counted below overflow, or the loops over them run for ever.
     */
    private static final double MAX_REACH = 64;
    /** Saved data of an entity that are never needed to draw it. */
    private static final Set<String> NOT_DRAWN = Set.of("EnderItems", "recipeBook", "Brain", "Gossips", "Offers",
            "Passengers", "Items", "LastDeathLocation", "warden_spawn_tracker", "enteredNetherPosition", "abilities");
    /** At most this much of an entity's saved data goes into its body (every block of which holds it). */
    private static final int MAX_LOOK_BYTES = 8 * 1024;
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
            this.velocity = entity.getDeltaMovement().scale(20);
        }
    }

    private static final Map<Integer, Pending> PENDING = new HashMap<>();
    /** The entities held still while a client is asked about them; see {@link #freeze}. */
    private static final Map<Entity, Pending> FROZEN = Collections.synchronizedMap(new IdentityHashMap<>());
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
            for (Entity e : level.getEntities(wielder, reach, e -> true)) {
                // a part of a bigger entity (the ender dragon's wings, say) is hit: the whole entity is cut
                Entity whole = e instanceof net.neoforged.neoforge.entity.PartEntity<?> part ? part.getParent() : e;
                if (whole != wielder && !hit.contains(whole) && cuttable(whole) && crosses(sweep.quad(), e.getBoundingBox())) {
                    hit.add(whole);
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
                    cutLater.accept(finish(level, entity, look, wielder, entity.getDeltaMovement().scale(20), null));
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
        if (bodies.isEmpty()) {
            complete(pending, List.of()); // nothing of it can be seen (a marker, say): it is cut down and leaves nothing
            return;
        }
        String problem = problemWith(bodies);
        if (problem != null) {
            MeshCut.LOGGER.warn("{} sent limbs for a {} that cannot be built ({}); it falls as one piece",
                    player.getName().getString(), pending.look.type(), problem);
        }
        complete(pending, problem == null ? bodies : null);
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        if (PENDING.isEmpty()) {
            return;
        }
        for (Iterator<Map.Entry<Integer, Pending>> it = PENDING.entrySet().iterator(); it.hasNext(); ) {
            Pending pending = it.next().getValue();
            if (pending.level.getGameTime() >= pending.deadline || pending.entity.isRemoved()) {
                it.remove();
                if (!pending.entity.isRemoved()) {
                    MeshCut.LOGGER.warn("No client said in time what a {} is made of; it falls as one piece", pending.look.type());
                }
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

    /**
     * Holds the entity still while a client is asked about it. None of it is saved with the entity: saved meanwhile
     * (its chunk unloads, a player leaves, the server stops), it is saved as it was (see {@link #saved}), or it would
     * stand frozen and unkillable for good.
     */
    private static void freeze(Entity entity, boolean on, Pending pending) {
        if (on) {
            FROZEN.put(entity, pending);
        } else {
            FROZEN.remove(entity);
        }
        if (entity instanceof Mob mob) {
            mob.setNoAi(on || pending.wasNoAi);
        }
        if (on && !(entity instanceof Player)) {
            entity.setDeltaMovement(Vec3.ZERO);
        }
    }

    /** Nothing else may kill a frozen entity: it would die a second time. */
    public static void onInvulnerabilityCheck(EntityInvulnerabilityCheckEvent event) {
        if (!FROZEN.isEmpty() && FROZEN.containsKey(event.getEntity())
                && !event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            event.setInvulnerable(true);
        }
    }

    /** What a frozen entity was just saved as is put back to how it was before it was frozen. */
    public static void saved(Entity entity, CompoundTag tag) {
        Pending pending = FROZEN.isEmpty() ? null : FROZEN.get(entity);
        if (pending != null && entity instanceof Mob) {
            if (pending.wasNoAi) {
                tag.putBoolean("NoAI", true);
            } else {
                tag.remove("NoAI");
            }
        }
    }

    /** The server is stopping: what is frozen is let go before the world is saved, and nothing waits any more. */
    public static void onServerStopping(ServerStoppingEvent event) {
        for (Pending pending : new ArrayList<>(PENDING.values())) {
            freeze(pending.entity, false, pending);
        }
        PENDING.clear();
        FROZEN.clear();
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
        // no client said what it looks like: one piece the size of its box
        List<BodySpec> asBox = List.of(new BodySpec(null, EntityAppearance.NO_ROTATION.clone(), new double[3],
                List.of(new double[]{box.minX - feet.x, box.minY - feet.y, box.minZ - feet.z,
                        box.maxX - feet.x, box.maxY - feet.y, box.maxZ - feet.z}), -1, new double[3]));
        if (bodies == null) {
            bodies = asBox;
        }
        List<ServerSubLevel> built = build(level, look, bodies, feet, velocity);
        if (!bodies.isEmpty() && built.stream().allMatch(java.util.Objects::isNull) && bodies != asBox) {
            // it is dead already; whatever went wrong, it must not just vanish
            MeshCut.LOGGER.warn("None of the limbs of a {} could be built; it falls as one piece", look.type());
            bodies = asBox;
            built = build(level, look, bodies, feet, velocity);
        }
        for (int i = 0; i < bodies.size(); i++) {
            BodySpec spec = bodies.get(i);
            if (spec.parent() >= 0 && built.get(i) != null && built.get(spec.parent()) != null) {
                Vec3 anchor = feet.add(spec.anchor()[0], spec.anchor()[1], spec.anchor()[2]);
                RagdollJoints.join(level, built.get(spec.parent()), bodies.get(spec.parent()).name(), built.get(i), spec.name(), anchor);
            }
        }
        List<ServerSubLevel> made = built.stream().filter(java.util.Objects::nonNull).toList();
        RagdollJoints.ghostOverlapping(level, made); // limbs that overlap do not throw each other apart
        int limbs = made.size();
        MeshCut.LOGGER.info("Cut down a {}: {} limb(s){}", look.type(), limbs, bodies == asBox ? " (one piece the size of its box)" : "");
        built.removeIf(java.util.Objects::isNull);
        return built;
    }

    /** Builds each limb on its own: one that fails (null in its place) does not take the others with it. */
    private static List<ServerSubLevel> build(ServerLevel level, EntityAppearance.Look look, List<BodySpec> bodies, Vec3 feet,
                                              Vec3 velocity) {
        List<ServerSubLevel> built = new ArrayList<>(bodies.size());
        for (BodySpec spec : bodies) {
            ServerSubLevel limb = null;
            try {
                limb = makeLimb(level, look, spec, feet, velocity);
            } catch (RuntimeException e) {
                MeshCut.LOGGER.error("Couldn't build the {} of a cut-down {}", spec.name(), look.type(), e);
            }
            built.add(limb);
        }
        return built;
    }

    // ---------------------------------------------------------------- which entities, and how they go

    /**
     * Anything can be cut, except what cannot be hurt at all: an entity made invulnerable, a player in creative or
     * spectator mode, and the types in the {@link #UNCUTTABLE} tag.
     */
    private static boolean cuttable(Entity e) {
        if (e.isRemoved() || e.isInvulnerable() || e.getType().is(UNCUTTABLE)) {
            return false;
        }
        if (e instanceof LivingEntity living) {
            return !living.isDeadOrDying() && !(living instanceof Player player && (player.isCreative() || player.isSpectator()));
        }
        return true;
    }

    private static EntityAppearance.Look lookOf(Entity entity) {
        CompoundTag data = drawnData(entity);
        float bodyYaw = entity instanceof LivingEntity living ? living.yBodyRot : entity.getYRot();
        return new EntityAppearance.Look(EntityType.getKey(entity.getType()).toString(), data, bodyYaw,
                entity.getYHeadRot(), entity.getXRot(),
                entity instanceof Player p ? p.getGameProfile().getId() : null,
                entity instanceof Player p ? p.getGameProfile().getName() : null);
    }

    /**
     * What of the entity's saved data its body keeps: enough to draw it, nothing private or bulky. The body's every
     * block holds a copy, which is saved with the chunk and sent to every client nearby: a player's inventory and
     * ender chest, or a villager's memories and trades, have no place there.
     */
    private static CompoundTag drawnData(Entity entity) {
        CompoundTag data = entity.saveWithoutId(new CompoundTag());
        NOT_DRAWN.forEach(data::remove);
        if (entity instanceof Player) {
            // only what a player is seen wearing and holding
            int selected = data.getInt("SelectedItemSlot");
            ListTag worn = new ListTag();
            for (Tag t : data.getList("Inventory", Tag.TAG_COMPOUND)) {
                int slot = ((CompoundTag) t).getByte("Slot");
                if (slot >= 100 && slot <= 103 || slot == -106 || slot == selected) {
                    worn.add(t);
                }
            }
            data.put("Inventory", worn);
        } else {
            data.remove("Inventory"); // a villager's, an allay's, a piglin's: not seen
        }
        // whatever is still too big (a book in its hand, a modded entity's data) goes, biggest first
        while (data.sizeInBytes() > MAX_LOOK_BYTES && !data.isEmpty()) {
            String biggest = null;
            int size = -1;
            for (String key : data.getAllKeys()) {
                Tag t = data.get(key);
                if (t != null && t.sizeInBytes() > size) {
                    size = t.sizeInBytes();
                    biggest = key;
                }
            }
            data.remove(biggest);
        }
        return data;
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
        if (entity instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon dragon) {
            // hurt, it would only start its long death; this is the end of it
            var fight = dragon.getDragonFight();
            ExperienceOrb.award(level, dragon.position(), fight == null || fight.hasPreviouslyKilledDragon() ? 500 : 12000);
            if (fight != null) {
                fight.setDragonKilled(dragon); // the exit portal opens, the egg appears
            }
            dragon.remove(Entity.RemovalReason.KILLED);
            return true;
        }
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

    /**
     * Why what a client sent cannot be built, or null if it can. Only its shape is checked (limbs that hang from
     * limbs that exist, numbers that are numbers and stay within {@link #MAX_REACH}); what the model looks like is
     * the model's business.
     */
    @Nullable
    private static String problemWith(List<BodySpec> bodies) {
        if (bodies.size() > BodySpec.MAX_BODIES) {
            return bodies.size() + " limbs";
        }
        int boxes = 0;
        long cells = 0;
        for (int i = 0; i < bodies.size(); i++) {
            BodySpec b = bodies.get(i);
            if (b.parent() < -1 || b.parent() >= bodies.size() || b.parent() == i) {
                return "limb " + i + " hangs from " + b.parent();
            }
            if (b.boxes().isEmpty() || !finite(b.rotation()) || !within(b.origin()) || !within(b.anchor())) {
                return "limb " + i + " is empty, not a number, or out of reach";
            }
            double length = Math.sqrt(b.rotation()[0] * b.rotation()[0] + b.rotation()[1] * b.rotation()[1]
                    + b.rotation()[2] * b.rotation()[2] + b.rotation()[3] * b.rotation()[3]);
            if (length < 1e-6) {
                return "limb " + i + " has no rotation";
            }
            for (int k = 0; k < 4; k++) {
                b.rotation()[k] /= length;
            }
            for (double[] box : b.boxes()) {
                boxes++;
                // a model may reach far past its collision box, so only numbers and the total size (MAX_CELLS) are limited
                if (box.length != 6 || !finite(box) || box[3] < box[0] || box[4] < box[1] || box[5] < box[2]) {
                    return "limb " + i + " has a box inside out or not a number";
                }
                cells += (long) (Math.floor(box[3]) - Math.floor(box[0]) + 1) * (long) (Math.floor(box[4]) - Math.floor(box[1]) + 1)
                        * (long) (Math.floor(box[5]) - Math.floor(box[2]) + 1);
            }
        }
        if (boxes > BodySpec.MAX_BOXES || cells > MAX_CELLS) {
            return boxes + " boxes over " + cells + " blocks";
        }
        for (int i = 0; i < bodies.size(); i++) { // no loops
            int steps = 0;
            for (int p = bodies.get(i).parent(); p >= 0; p = bodies.get(p).parent()) {
                if (++steps > bodies.size()) {
                    return "limbs hang from each other in a loop";
                }
            }
        }
        return null;
    }

    /** Finite, and no further than {@link #MAX_REACH}: a box in a limb's frame, or a limb's place relative to the feet. */
    private static boolean within(double[] values) {
        for (double v : values) {
            if (!(Math.abs(v) <= MAX_REACH)) { // also false for NaN
                return false;
            }
        }
        return true;
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
                        for (int a = 0; a < 3; a++) {
                            // on the grid of a model pixel: blocks of the same shape share one collider
                            in[a] = Math.floor(in[a] * GRID + 1e-6) / GRID;
                            in[a + 3] = Math.ceil(in[a + 3] * GRID - 1e-6) / GRID;
                            if (in[a + 3] - in[a] < 1.0 / GRID) { // a sliver is thickened into the block, never cut off
                                if (in[a] + 1.0 / GRID <= 1) {
                                    in[a + 3] = in[a] + 1.0 / GRID;
                                } else {
                                    in[a] = in[a + 3] - 1.0 / GRID;
                                }
                            }
                        }
                        cells.computeIfAbsent(new BlockPos(x, y, z), k -> new ArrayList<>()).add(in);
                    }
                }
            }
        }
        if (cells.isEmpty()) {
            return null;
        }
        // every box of a block is a collider the physics engine tests on its own: a few per block are plenty
        cells.replaceAll((c, boxes) -> ml.mypals.meshcut.geom.VoxelBoxes.join(boxes, MAX_BOXES_PER_BLOCK));
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
        try {
            var plot = limb.getPlot();
            plot.newEmptyChunk(plot.getCenterChunk());
            BlockPos plotAnchor = plot.getCenterBlock();
            // a big limb (the dragon's) reaches into more chunks of the plot than the first
            Set<net.minecraft.world.level.ChunkPos> chunks = new java.util.HashSet<>();
            for (BlockPos c : cells.keySet()) {
                chunks.add(new net.minecraft.world.level.ChunkPos(plotAnchor.offset(c.subtract(shift))));
            }
            for (var chunk : chunks) {
                // the plot keeps its chunks by local position; newEmptyChunk takes the global one
                if (plot.getChunkHolder(plot.toLocal(chunk)) == null) {
                    plot.newEmptyChunk(chunk);
                }
            }
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
        } catch (RuntimeException e) {
            // half built, it would be left standing nowhere
            if (!limb.isRemoved()) {
                container.removeSubLevel(limb, dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason.REMOVED);
            }
            throw e;
        }
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
