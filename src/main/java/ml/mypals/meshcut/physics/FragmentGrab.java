package ml.mypals.meshcut.physics;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.api.physics.constraint.FreeConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.platform.SableEventPlatform;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.MeshCutConfig;
import ml.mypals.meshcut.block.MimicBlock;
import ml.mypals.meshcut.network.GrabNet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public final class FragmentGrab {
    private static final double STIFFNESS = 240, DAMPING = 30, TURN_DAMPING = 4.5;
    private static final double SLACK = 2;
    private static final double MIN_DISTANCE = 1;

    private static final class Grab {
        final ServerLevel level;
        final UUID body;
        final Vector3d point;
        float distance;
        int request;
        @Nullable
        PhysicsConstraintHandle handle;

        Grab(ServerLevel level, UUID body, Vector3d point, float distance) {
            this.level = level;
            this.body = body;
            this.point = point;
            this.distance = distance;
        }

        void drop() {
            if (handle != null) {
                try {
                    if (handle.isValid()) {
                        handle.remove();
                    }
                } catch (RuntimeException e) {
                    MeshCut.LOGGER.debug("Couldn't let go of a piece", e);
                }
                handle = null;
            }
        }
    }

    private static final Map<UUID, Grab> GRABS = new HashMap<>();
    private static boolean changed;
    private static int ticks;

    public static void register() {
        SableEventPlatform.INSTANCE.onPhysicsTick((system, timeStep) -> physicsTick(system));
        NeoForge.EVENT_BUS.addListener(FragmentGrab::serverTick);
    }

    /** 0 accepted, 1 invalid coordinates/target, 2 out of reach, 3 cannot pull here. */
    public static int hold(ServerPlayer player, UUID body, Vector3d point, float distance, int request) {
        if (!ml.mypals.meshcut.InteractionLimits.grabNumbers(point.x, point.y, point.z, distance)) return 1;
        ServerLevel level = player.serverLevel();
        ServerSubLevel sub = subLevel(level, body);
        if (sub == null || !sub.getPlot().getBoundingBox().contains((int) Math.floor(point.x), (int) Math.floor(point.y), (int) Math.floor(point.z))
                || !(level.getBlockState(BlockPos.containing(point.x, point.y, point.z)).getBlock() instanceof MimicBlock)) {
            return 1; // only cut pieces, at a point of one of their blocks
        }
        Vector3d world = sub.logicalPose().transformPosition(new Vector3d(point));
        double reach = reach(player);
        if (!Double.isFinite(world.x) || !Double.isFinite(world.y) || !Double.isFinite(world.z)
                || !Double.isFinite(reach) || player.getEyePosition().distanceToSqr(world.x, world.y, world.z) > (reach + SLACK) * (reach + SLACK)) {
            return 2;
        }
        if (!canPull(player, sub)) return 3;
        CutMarks.keep(sub); // held by hand: it is not to be cleared away
        float clamped = (float) Math.max(MIN_DISTANCE, Math.min(distance, reach));
        Grab grab = GRABS.get(player.getUUID());
        if (grab != null && grab.body.equals(body) && grab.level == level) {
            grab.distance = clamped;
            grab.request = request;
            return 0;
        }
        if (grab != null) {
            grab.drop();
        }
        Grab added = new Grab(level, body, new Vector3d(point), clamped);
        added.request = request;
        GRABS.put(player.getUUID(), added);
        changed = true;
        return 0;
    }

    public static void release(ServerPlayer player) {
        Grab grab = GRABS.remove(player.getUUID());
        if (grab != null) {
            grab.drop();
            changed = true;
        }
    }

    private static void physicsTick(SubLevelPhysicsSystem system) {
        if (GRABS.isEmpty()) {
            return;
        }
        ServerLevel level = system.getLevel();
        for (var entry : GRABS.entrySet()) {
            Grab grab = entry.getValue();
            if (grab.level != level) {
                continue;
            }
            grab.drop();
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getKey());
            ServerSubLevel sub = subLevel(level, grab.body);
            if (player == null || sub == null || player.level() != level || !canPull(player, sub)) {
                continue;
            }
            Vec3 goal = player.getEyePosition().add(player.getLookAngle().scale(grab.distance));
            Vector3d target = new Vector3d(goal.x, goal.y, goal.z);
            Vector3d held = sub.logicalPose().transformPosition(new Vector3d(grab.point));
            double reach = reach(player) + SLACK;
            if (held.distanceSquared(target) > reach * reach) {
                continue;
            }
            try {
                PhysicsConstraintHandle handle = system.getPipeline().addConstraint(null, sub,
                        new FreeConstraintConfiguration(target, new Vector3d(grab.point), new Quaterniond()));
                if (handle == null) {
                    continue;
                }
                double maxForce = MeshCutConfig.GRAB_MAX_FORCE.get();
                for (ConstraintJointAxis axis : ConstraintJointAxis.LINEAR) {
                    handle.setMotor(axis, 0.0, STIFFNESS, DAMPING, true, maxForce);
                }
                for (ConstraintJointAxis axis : ConstraintJointAxis.ANGULAR) {
                    handle.setMotor(axis, 0.0, 0.0, TURN_DAMPING, true, maxForce);
                }
                grab.handle = handle;
            } catch (RuntimeException e) {
                MeshCut.LOGGER.warn("Couldn't hold a piece", e);
            }
        }
    }

    private static boolean canPull(ServerPlayer player, ServerSubLevel sub) {
        if (player.isDeadOrDying() || player.isSpectator()) {
            return false;
        }
        boolean footing = player.onGround() || player.isInWater() || player.getAbilities().flying || player.onClimbable();
        return footing && Sable.HELPER.getTrackingSubLevel(player) != sub;
    }

    private static void serverTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        for (Iterator<Map.Entry<UUID, Grab>> it = GRABS.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null || player.isDeadOrDying() || player.level() != entry.getValue().level
                    || subLevel(entry.getValue().level, entry.getValue().body) == null) {
                entry.getValue().drop();
                if (player != null) PacketDistributor.sendToPlayer(player,
                        new GrabNet.Result(entry.getValue().request, entry.getValue().body, 1));
                it.remove();
                changed = true;
            } else if (player.fallDistance > 0 && entry.getValue().handle != null) {
                player.resetFallDistance();
            }
        }
        if (changed || ++ticks % 20 == 0) {
            changed = false;
            PacketDistributor.sendToAllPlayers(new GrabNet.Holders(new ArrayList<>(GRABS.keySet())));
        }
    }

    @Nullable
    private static ServerSubLevel subLevel(ServerLevel level, UUID id) {
        var container = SubLevelContainer.getContainer(level);
        return container != null && container.getSubLevel(id) instanceof ServerSubLevel sub && !sub.isRemoved() ? sub : null;
    }

    private static double reach(ServerPlayer player) {
        return Math.min(5, player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE));
    }

    private FragmentGrab() {}
}
