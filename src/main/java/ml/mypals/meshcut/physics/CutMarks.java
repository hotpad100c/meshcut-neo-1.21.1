package ml.mypals.meshcut.physics;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * What is remembered of a sub-level that a cut made, in the user data Sable saves with it: when it was made, and
 * whether anyone has taken an interest in it (held it by hand, clicked it). Pieces nobody has are cleared away after a
 * while (see {@code Debris}); pieces somebody has are never cleared away by any of the cleaning up.
 */
public final class CutMarks {
    private static final String BORN = "meshcut_cut_born";
    private static final String KEPT = "meshcut_kept";

    public static void register() {
        NeoForge.EVENT_BUS.addListener((PlayerInteractEvent.RightClickBlock e) -> touched(e));
        NeoForge.EVENT_BUS.addListener((PlayerInteractEvent.LeftClickBlock e) -> touched(e));
    }

    private static void touched(PlayerInteractEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (Sable.HELPER.getContaining(event.getLevel(), event.getPos()) instanceof ServerSubLevel sub) {
            keep(sub);
        }
    }

    private static CompoundTag data(ServerSubLevel sub) {
        CompoundTag tag = sub.getUserDataTag();
        if (tag == null) {
            tag = new CompoundTag();
            sub.setUserDataTag(tag);
        }
        return tag;
    }

    /** Notes that a cut made this sub-level just now. */
    public static void made(ServerSubLevel sub, long gameTime) {
        data(sub).putLong(BORN, gameTime);
    }

    /** Somebody took an interest in it: it is not to be cleared away. */
    public static void keep(ServerSubLevel sub) {
        CompoundTag tag = sub.getUserDataTag();
        if (tag != null && tag.getBoolean(KEPT)) return;
        data(sub).putBoolean(KEPT, true);
    }

    public static boolean isKept(ServerSubLevel sub) {
        CompoundTag tag = sub.getUserDataTag();
        return tag != null && tag.getBoolean(KEPT);
    }

    /** When a cut made it, or -1 if one did not (or it was made before this was kept track of). */
    public static long bornAt(ServerSubLevel sub) {
        CompoundTag tag = sub.getUserDataTag();
        return tag != null && tag.contains(BORN) ? tag.getLong(BORN) : -1;
    }

    private CutMarks() {}
}
