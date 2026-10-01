package ml.mypals.meshcut.item;

import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.cut.Blade;
import ml.mypals.meshcut.cut.CapMode;
import ml.mypals.meshcut.cut.SlashService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Cuts with the surface an active lightsaber's blade sweeps from one tick to the next. */
@EventBusSubscriber(modid = MeshCut.MODID)
public final class LightsaberHandler {
    /** Blocks the tip must travel in a tick for it to count as a swing, so merely holding it cuts nothing. */
    private static final double MIN_TIP_SPEED = 0.5;
    /** Larger jumps are teleports or dimension changes, not swings. */
    private static final double MAX_TIP_SPEED = 12;

    private static final Map<UUID, Blade> LAST = new HashMap<>();

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof LightsaberItem) || !LightsaberItem.isActive(stack)) {
            LAST.remove(player.getUUID());
            return;
        }
        Blade now = Blade.of(player, 1f);
        Blade before = LAST.put(player.getUUID(), now);
        if (before == null) {
            return;
        }
        double speed = now.tip().distanceTo(before.tip());
        if (speed >= MIN_TIP_SPEED && speed <= MAX_TIP_SPEED) {
            SlashService.slash(player.serverLevel(), before, now, CapMode.LAVA);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST.remove(event.getEntity().getUUID());
    }

    private LightsaberHandler() {}
}
