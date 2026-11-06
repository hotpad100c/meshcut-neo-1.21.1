package ml.mypals.meshcut.physics;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

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

    public static void made(ServerSubLevel sub, long gameTime) {
        data(sub).putLong(BORN, gameTime);
    }

    public static void keep(ServerSubLevel sub) {
        CompoundTag tag = sub.getUserDataTag();
        if (tag != null && tag.getBoolean(KEPT)) return;
        data(sub).putBoolean(KEPT, true);
    }

    public static boolean isKept(ServerSubLevel sub) {
        CompoundTag tag = sub.getUserDataTag();
        return tag != null && tag.getBoolean(KEPT);
    }

    public static long bornAt(ServerSubLevel sub) {
        CompoundTag tag = sub.getUserDataTag();
        return tag != null && tag.contains(BORN) ? tag.getLong(BORN) : -1;
    }

    private CutMarks() {}
}
