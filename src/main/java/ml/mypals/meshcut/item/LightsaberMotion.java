package ml.mypals.meshcut.item;

import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.cut.SaberAim;
import ml.mypals.meshcut.cut.SaberControl;
import ml.mypals.meshcut.network.LightsaberNet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
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

    private static final class State {
        final SaberAim aim;
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
            tick = player.tickCount;
        }
    }

    // Advance before LightsaberHandler samples the blade for this tick's actual cut.
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (!LightsaberItem.isActive(player)) {
            player.removeData(STATE);
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
        state.aim.advance(yaw, pitch, (player.tickCount - state.tick) / 20f);
        state.position = player.position();
        state.tick = player.tickCount;
        if (player instanceof ServerPlayer serverPlayer) LightsaberNet.broadcast(serverPlayer, yaw, pitch);
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

    private LightsaberMotion() {}
}
