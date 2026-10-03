package ml.mypals.meshcut.item;

import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.cut.Blade;
import ml.mypals.meshcut.cut.CapMode;
import ml.mypals.meshcut.cut.SlashService;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cuts with the surfaces a lit lightsaber's blade sweeps.
 * <p>
 * The saber is lit while right-click is held, and a cut lasts exactly that long: it begins the moment the blade is
 * lit and ends when it is put out. Nothing is changed while it is going on: each tick's sweep is only recorded, and
 * the whole cut is applied at once at the end. Replacing blocks as the blade passes would, for example, turn the
 * upper half of a door into a mimic before the lower half had been reached, and the door would break apart.
 */
@EventBusSubscriber(modid = MeshCut.MODID)
public final class LightsaberHandler {
    /** Larger jumps are teleports or dimension changes, not swings. */
    private static final double MAX_TIP_SPEED = 30;
    /** Slower than this the blade is standing still and sweeps nothing. */
    private static final double MIN_TIP_SPEED = 0.01;
    /** A cut longer than this is applied early (and carries on), so the work is not left to pile up. */
    private static final int MAX_SWIPES = 200;

    private static final class Swing {
        final ResourceKey<Level> dimension;
        final List<SlashService.Swipe> swipes = new ArrayList<>();

        Swing(ResourceKey<Level> dimension) {
            this.dimension = dimension;
        }
    }

    private static final Map<UUID, Blade> LAST = new HashMap<>();
    private static final Map<UUID, Swing> SWINGS = new HashMap<>();

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        UUID id = player.getUUID();
        if (!LightsaberItem.isActive(player)) {
            LAST.remove(id);
            finish(player); // let go: the cut is over
            return;
        }
        Blade now = Blade.of(player, 1f);
        Blade before = LAST.put(id, now);
        Swing swing = SWINGS.get(id);
        if (swing != null && swing.dimension != player.level().dimension()) {
            SWINGS.remove(id); // changed dimension mid-cut
            swing = null;
        }
        if (swing == null) {
            swing = new Swing(player.level().dimension());
            SWINGS.put(id, swing);
        }
        if (before == null) {
            return;
        }
        double speed = now.tip().distanceTo(before.tip());
        if (speed > MAX_TIP_SPEED) {
            finish(player); // a teleport, not a swing
            return;
        }
        if (speed >= MIN_TIP_SPEED) {
            swing.swipes.add(new SlashService.Swipe(before, now));
            if (swing.swipes.size() >= MAX_SWIPES) {
                finish(player);
            }
        }
    }

    /** Applies the swing that has just ended, if there is one. */
    private static void finish(ServerPlayer player) {
        Swing swing = SWINGS.remove(player.getUUID());
        if (swing == null || swing.dimension != player.level().dimension()) {
            return;
        }
        try {
            var result=SlashService.slashSwing(player.serverLevel(), swing.swipes, CapMode.LAVA, player);
            if(result!=null) {
                MeshCut.LOGGER.info("Applied saber stroke: {} swipes, {} static blocks, {} sub-levels cut, {} sub-levels created",
                        swing.swipes.size(),result.staticBlocks(),result.subLevelsCut(),result.subLevelsCreated());
            }
        } catch (RuntimeException e) {
            MeshCut.LOGGER.error("Applying a lightsaber swing failed", e);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            finish(player);
        }
        LAST.remove(event.getEntity().getUUID());
        SWINGS.remove(event.getEntity().getUUID());
    }

    private LightsaberHandler() {}
}
