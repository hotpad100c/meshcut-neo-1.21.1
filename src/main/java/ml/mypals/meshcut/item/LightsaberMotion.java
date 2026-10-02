package ml.mypals.meshcut.item;

import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.cut.SaberAim;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.joml.Vector2f;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Each logical side owns its players' aim state; rendering only interpolates these tick snapshots. */
@EventBusSubscriber(modid = MeshCut.MODID)
public final class LightsaberMotion {
    // Weak player keys release disconnected players, and distinguish client/server copies in singleplayer.
    private static final Map<Player, State> STATES = Collections.synchronizedMap(new WeakHashMap<>());

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
            STATES.remove(player);
            return;
        }
        State state = STATES.get(player);
        if (state == null || state.dimension != player.level().dimension()
                || state.position.distanceToSqr(player.position()) > 144
                || player.tickCount - state.tick > 1) {
            STATES.put(player, new State(player));
            return;
        }
        if (state.tick == player.tickCount) return;
        state.aim.advance(player.getYRot(), player.getXRot(), 1f / 20f);
        state.position = player.position();
        state.tick = player.tickCount;
    }

    public static Vector2f angles(Player player, float partialTick) {
        State state = STATES.get(player);
        return state != null ? state.aim.angles(partialTick) : new Vector2f(
                Mth.rotLerp(partialTick, player.yRotO, player.getYRot()),
                Mth.lerp(partialTick, player.xRotO, player.getXRot()));
    }

    private LightsaberMotion() {}
}
