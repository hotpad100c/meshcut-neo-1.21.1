package ml.mypals.meshcut.item;

import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.cut.SaberAim;
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
        state.aim.advance(player.getYRot(), player.getXRot(), (player.tickCount - state.tick) / 20f);
        state.position = player.position();
        state.tick = player.tickCount;
    }

    public static Vector2f angles(Player player, float partialTick) {
        State state = player.getExistingDataOrNull(STATE);
        return state != null ? state.aim.angles(partialTick) : new Vector2f(
                Mth.rotLerp(partialTick, player.yRotO, player.getYRot()),
                Mth.lerp(partialTick, player.xRotO, player.getXRot()));
    }

    private LightsaberMotion() {}
}
