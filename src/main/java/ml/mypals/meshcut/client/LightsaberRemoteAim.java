package ml.mypals.meshcut.client;

import ml.mypals.meshcut.item.LightsaberMotion;
import ml.mypals.meshcut.network.LightsaberNet;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

public final class LightsaberRemoteAim {
    public static void accept(LightsaberNet.Target target) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && minecraft.level.getEntity(target.id()) instanceof Player player
                && player != minecraft.player) {
            LightsaberMotion.setRemoteTarget(player, target.yaw(), target.pitch());
            LightsaberMotion.setRemoteHandTarget(player, target.x(), target.y());
            ml.mypals.meshcut.item.LightsaberItem.setLengthScale(player.getMainHandItem(), target.length());
        }
    }

    private LightsaberRemoteAim() {}
}
