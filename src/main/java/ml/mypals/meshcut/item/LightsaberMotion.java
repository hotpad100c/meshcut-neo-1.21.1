package ml.mypals.meshcut.item;

import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.cut.SaberAim;
import ml.mypals.meshcut.cut.SaberControl;
import ml.mypals.meshcut.cut.SaberGesture;
import ml.mypals.meshcut.cut.SaberTranslation;
import ml.mypals.meshcut.cut.SaberPose;
import org.joml.Vector3f;
import ml.mypals.meshcut.network.LightsaberNet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.joml.Vector2f;

import java.util.function.Supplier;

/** Each logical side owns its players' aim state; rendering only interpolates these tick snapshots. */
@EventBusSubscriber(modid = MeshCut.MODID)
public final class LightsaberMotion {
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, MeshCut.MODID);
    // Entity.equals compares IDs, so a shared player-keyed map aliases client and server copies.
    // An unsynced, transient attachment belongs to each entity instance, including after respawn/reconnect.
    private static final Supplier<AttachmentType<State>> STATE = ATTACHMENTS.register("saber_motion",
            () -> AttachmentType.builder(holder -> new State((Player) holder)).build());

    public static void register(IEventBus modEventBus) {
        ATTACHMENTS.register(modEventBus);
    }

    public static void begin(Player player) {
        player.setData(STATE, new State(player));
    }

    public static void end(Player player) {
        State state = player.getExistingDataOrNull(STATE);
        if (state != null && state.stopped == Integer.MIN_VALUE) {
            state.stopped = player.tickCount;
            state.stoppedExtension = SaberGesture.extension(player.tickCount - state.started);
        }
    }

    private static final class State {
        final SaberAim aim;
        final SaberTranslation hand = new SaberTranslation();
        final int started;
        int stopped = Integer.MIN_VALUE;
        float stoppedExtension;
        int gestureStarted = Integer.MIN_VALUE / 2;
        int preset, seed;
        float handX, handY;
        final ResourceKey<Level> dimension;
        Vec3 position;
        int tick;
        float yawOffset;
        float pitchOffset;
        Vector2f remoteTarget;

        State(Player player) {
            aim = new SaberAim(player.getYRot(), player.getXRot());
            dimension = player.level().dimension();
            position = player.position();
            tick = started = player.tickCount;
        }
    }

    // Advance before LightsaberHandler samples the blade for this tick's actual cut.
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (!LightsaberItem.isActive(player)) {
            State state = player.getExistingDataOrNull(STATE);
            if (state == null) return;
            if (state.stopped == Integer.MIN_VALUE) end(player);
            else if (player.tickCount - state.stopped > 8) {
                player.removeData(STATE);
            }
            return;
        }
        State state = player.getData(STATE);
        if (state.dimension != player.level().dimension()
                || state.position.distanceToSqr(player.position()) > 144
                || player.tickCount < state.tick) {
            begin(player);
            return;
        }
        if (state.tick == player.tickCount) return;
        // Missing callbacks should advance elapsed simulation time, rather than snap to the target.
        float yaw = state.remoteTarget == null ? player.getYRot() + state.yawOffset : state.remoteTarget.x;
        float pitch = state.remoteTarget == null ? player.getXRot() + state.pitchOffset : state.remoteTarget.y;
        SaberGesture.Sample gesture = state.remoteTarget == null
                ? SaberGesture.sample(state.preset, state.seed, player.tickCount - state.gestureStarted)
                : new SaberGesture.Sample(0, 0, 0, 0);
        yaw += gesture.yaw();
        pitch += gesture.pitch();
        float x = state.handX + gesture.x(), y = state.handY + gesture.y();
        float seconds = (player.tickCount - state.tick) / 20f;
        state.aim.advance(yaw, pitch, seconds);
        state.hand.advance(x, y, seconds);
        state.position = player.position();
        state.tick = player.tickCount;
        if (player instanceof ServerPlayer serverPlayer) LightsaberNet.broadcast(serverPlayer, yaw, pitch, x, y);
    }

    public static Object session(Player player) {
        return player.getExistingDataOrNull(STATE);
    }

    public static void setOffsets(Player player, float yaw, float pitch) {
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch) || !LightsaberItem.isActive(player)) return;
        State state = player.getData(STATE);
        state.yawOffset = Mth.clamp(yaw, -SaberControl.YAW_LIMIT, SaberControl.YAW_LIMIT);
        state.pitchOffset = Mth.clamp(pitch, -SaberControl.PITCH_LIMIT, SaberControl.PITCH_LIMIT);
    }

    public static void setRemoteTarget(Player player, float yaw, float pitch) {
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) return;
        player.getData(STATE).remoteTarget = new Vector2f(Mth.wrapDegrees(yaw), Mth.clamp(pitch, -90, 90));
    }

    public static Vector2f angles(Player player, float partialTick) {
        State state = player.getExistingDataOrNull(STATE);
        return state != null ? state.aim.angles(partialTick) : new Vector2f(
                Mth.rotLerp(partialTick, player.yRotO, player.getYRot()),
                Mth.lerp(partialTick, player.xRotO, player.getXRot()));
    }

    public static void setHandTarget(Player player, float x, float y) {
        if (!LightsaberItem.isActive(player) || !Float.isFinite(x) || !Float.isFinite(y)) return;
        State state = player.getData(STATE);
        state.handX = Math.clamp(x, -SaberTranslation.LIMIT_X, SaberTranslation.LIMIT_X);
        state.handY = Math.clamp(y, -SaberTranslation.LIMIT_Y, SaberTranslation.LIMIT_Y);
    }

    public static void setRemoteHandTarget(Player player, float x, float y) {
        if (!Float.isFinite(x) || !Float.isFinite(y)) return;
        State state = player.getData(STATE);
        state.handX = Math.clamp(x, -0.52f, 0.52f);
        state.handY = Math.clamp(y, -0.46f, 0.46f);
    }

    public static boolean startGesture(Player player, int preset, int seed) {
        if (!LightsaberItem.isActive(player) || preset < 0 || preset >= SaberGesture.COUNT) return false;
        State state = player.getData(STATE);
        if (player.tickCount - state.gestureStarted < SaberGesture.DURATION_TICKS) return false;
        state.gestureStarted = player.tickCount;
        state.preset = preset;
        state.seed = seed;
        return true;
    }

    /** Translation in world coordinates before entity scale, shared by the grip and the cutting emitter. */
    public static Vector3f handOffset(Player player, float partial) {
        State state = player.getExistingDataOrNull(STATE);
        if (state == null) return new Vector3f();
        return handOffset(player, partial, state.hand.position(partial), angles(player, partial));
    }

    /** As {@link #handOffset(Player, float)}, for a hand position and aim given (the server's, as the client sees it). */
    public static Vector3f handOffset(Player player, float partial, Vector2f hand, Vector2f aim) {
        float yaw = Mth.rotLerp(partial, player.yRotO, player.getYRot());
        float pitch = Mth.lerp(partial, player.xRotO, player.getXRot());
        Vector3f offset = SaberPose.screenTranslation(yaw, pitch, hand.x, hand.y).mul(SaberPose.PLAYER_MODEL_SCALE);
        // never further than the arms reach: they are not stretched to follow
        return SaberPose.reachable(offset, aim.x, aim.y, Mth.rotLerp(partial, player.yBodyRotO, player.yBodyRot),
                player.isCrouching(), player.getMainArm() == HumanoidArm.RIGHT);
    }

    public static float extension(Player player, float partial) {
        State state = player.getExistingDataOrNull(STATE);
        return state == null ? 1 : SaberGesture.extension(player.tickCount - state.started - 1 + partial);
    }

    public static boolean isVisible(Player player) {
        State state = player.getExistingDataOrNull(STATE);
        return LightsaberItem.isActive(player) || state != null && state.stopped != Integer.MIN_VALUE
                && player.tickCount - state.stopped <= 8;
    }

    public static float visualExtension(Player player, float partial) {
        State state = player.getExistingDataOrNull(STATE);
        if (state == null) return 0;
        if (state.stopped == Integer.MIN_VALUE) {
            return SaberGesture.extension(player.tickCount - state.started - 1 + partial);
        }
        return state.stoppedExtension * SaberGesture.retraction(player.tickCount - state.stopped + partial);
    }

    private LightsaberMotion() {}
}
