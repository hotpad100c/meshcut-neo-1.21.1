package ml.mypals.meshcut.cut;

import dev.dubhe.anvilcraft.block.entity.BaseLaserBlockEntity;
import dev.ryanhcode.sable.Sable;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.joml.Vector3d;

public final class AnvilLaserSync {
    public static void send(BlockEntity be) {
        if (!(be instanceof BaseLaserBlockEntity laser) || !(be.getLevel() instanceof ServerLevel level)) return;
        var sub = Sable.HELPER.getContaining(level, be.getBlockPos());
        if (sub == null) return;
        var pos = be.getBlockPos();
        var at = sub.logicalPose().transformPosition(new Vector3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
        for (var player : level.players()) {
            if (player.distanceToSqr(at.x, at.y, at.z) < 256 * 256) laser.syncTo(player);
        }
    }

    private static long lastDebug;

    public static void debugRender(BlockEntity be, boolean enhanced) {
        long now = System.currentTimeMillis();
        if (now - lastDebug < 3000 || !(be instanceof BaseLaserBlockEntity laser)) return;
        lastDebug = now;
        ml.mypals.meshcut.MeshCut.LOGGER.info("[meshcut] laser on a sub-level drawn at {}: target={} level={} enhancedRendering={}",
                be.getBlockPos(), laser.getIrradiateBlockPos(), laser.getLaserLevel(), enhanced);
    }

    private AnvilLaserSync() {}
}
