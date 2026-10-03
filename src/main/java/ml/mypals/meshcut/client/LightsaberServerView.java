package ml.mypals.meshcut.client;

import ml.mypals.meshcut.cut.Blade;
import ml.mypals.meshcut.cut.SaberAim;
import ml.mypals.meshcut.cut.SaberTranslation;
import ml.mypals.meshcut.item.LightsaberMotion;
import ml.mypals.meshcut.network.LightsaberNet;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * The local player's blade as the server has it. The client works the aim out for itself the moment the mouse moves,
 * the server a moment later from what it is sent, so the two blades are never quite in one place; the burn marks must
 * lie where the server cuts. The server tells its player the aim and hand it cut with each tick, and this runs them
 * through the same springs the server runs.
 */
final class LightsaberServerView {
    /** The server sends one every tick; one this old has stopped coming. */
    private static final long STALE_TICKS = 4;
    private static SaberAim aim;
    private static SaberTranslation hand;
    private static long lastTick = Long.MIN_VALUE;

    static void accept(LightsaberNet.Target target) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        if (aim == null || minecraft.level.getGameTime() - lastTick > STALE_TICKS) {
            aim = new SaberAim(target.yaw(), target.pitch());
            hand = new SaberTranslation();
        }
        aim.advance(target.yaw(), target.pitch(), 0.05f);
        hand.advance(target.x(), target.y(), 0.05f);
        lastTick = minecraft.level.getGameTime();
    }

    static void reset() {
        aim = null;
        hand = null;
        lastTick = Long.MIN_VALUE;
    }

    /** The server's blade for the player, or null if it has not been heard from lately (the player's own is used). */
    @Nullable
    static Blade blade(Player player) {
        Minecraft minecraft = Minecraft.getInstance();
        if (aim == null || minecraft.level == null || minecraft.level.getGameTime() - lastTick > STALE_TICKS) {
            return null;
        }
        return Blade.of(player, 1f, aim.angles(1f), LightsaberMotion.handOffset(player, 1f, hand.position(1f), aim.angles(1f)));
    }

    private LightsaberServerView() {}
}
