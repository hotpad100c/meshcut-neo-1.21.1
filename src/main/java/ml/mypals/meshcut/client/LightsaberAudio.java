package ml.mypals.meshcut.client;

import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.cut.Blade;
import ml.mypals.meshcut.item.LightsaberItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@EventBusSubscriber(modid = MeshCut.MODID, value = Dist.CLIENT)
public final class LightsaberAudio {
    private static final SoundEvent HUM = sound("saber_hum");
    private static final SoundEvent IGNITION = sound("saber_ignition");
    private static final SoundEvent SWING = sound("saber_swing");
    private static final Map<UUID, Hum> SOUNDS = new HashMap<>();

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        SOUNDS.values().removeIf(Hum::isStopped);
        if (mc.level == null) {
            SOUNDS.values().forEach(Hum::dispose);
            SOUNDS.clear();
            return;
        }
        for (Player player : mc.level.players()) {
            if (LightsaberItem.isActive(player) && (!SOUNDS.containsKey(player.getUUID())
                    || SOUNDS.get(player.getUUID()).player != player)) {
                Hum old = SOUNDS.remove(player.getUUID());
                if (old != null) old.dispose();
                Hum hum = new Hum(player);
                SOUNDS.put(player.getUUID(), hum);
                mc.getSoundManager().play(atPlayer(IGNITION, player, 0.72f, 1));
                mc.getSoundManager().play(hum);
            }
        }
    }

    private static final class Hum extends AbstractTickableSoundInstance {
        final Player player;
        Blade previous;
        float speed;
        int fade;
        int swingCooldown;
        boolean wasFast;

        Hum(Player player) {
            super(HUM, SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
            this.player = player;
            looping = true;
            volume = 0.22f;
            updatePosition();
            previous = Blade.of(player, 1);
        }

        @Override public void tick() {
            Minecraft mc = Minecraft.getInstance();
            if (player.isRemoved() || mc.level != player.level()) { stop(); return; }
            updatePosition();
            if (!LightsaberItem.isActive(player)) {
                volume *= 0.68f;
                if (++fade >= 10) stop();
                return;
            }
            fade = 0;
            Blade now = Blade.of(player, 1);
            // Subtract the emitter's translation: ordinary walking must not sound like a fast swing.
            double angularSpeed = now.direction().normalize().distanceTo(previous.direction().normalize()) * Blade.LENGTH * 20;
            double handSpeed = now.base().subtract(previous.base()).subtract(player.getDeltaMovement()).length() * 20;
            float rawSpeed = (float) Math.clamp((angularSpeed + handSpeed * 0.5) / 18, 0, 1);
            float target = Math.clamp((rawSpeed - 0.06f) / 0.94f, 0, 1);
            speed += (target - speed) * 0.5f;
            volume = 0.22f + speed * 0.18f;
            pitch = 0.97f + speed * 0.08f;
            if (swingCooldown > 0) swingCooldown--;
            boolean fast = speed > 0.28f;
            if (fast && !wasFast && swingCooldown == 0) {
                Minecraft.getInstance().getSoundManager().play(atPlayer(SWING, player,
                        0.35f + speed * 0.35f, 0.94f + speed * 0.12f));
                swingCooldown = 24;
            }
            wasFast = speed > 0.16f;
            previous = now;
        }
        void updatePosition() { x = player.getX(); y = player.getEyeY(); z = player.getZ(); }
        void dispose() { stop(); }
    }

    private static SoundEvent sound(String path) {
        return SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MeshCut.MODID, path));
    }

    private static SimpleSoundInstance atPlayer(SoundEvent event, Player player, float volume, float pitch) {
        return new SimpleSoundInstance(event, SoundSource.PLAYERS, volume, pitch,
                SoundInstance.createUnseededRandom(), player.getX(), player.getEyeY(), player.getZ());
    }

    private LightsaberAudio() {}
}
