package ml.mypals.meshcut.client;

import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.cut.Blade;
import ml.mypals.meshcut.cut.SaberSynthesizer;
import ml.mypals.meshcut.item.LightsaberItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@EventBusSubscriber(modid = MeshCut.MODID, value = Dist.CLIENT)
public final class LightsaberAudio {
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
                mc.getSoundManager().play(hum);
            }
        }
    }

    private static final class Hum extends AbstractTickableSoundInstance {
        final Player player;
        final SaberSynthesizer synth = new SaberSynthesizer();
        Blade previous;
        float speed;
        int fade;
        Hum(Player player) {
            super(net.minecraft.sounds.SoundEvent.createVariableRangeEvent(
                    ResourceLocation.fromNamespaceAndPath(MeshCut.MODID, "saber_hum")),
                    SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
            this.player = player;
            looping = true;
            volume = 0.3f;
            updatePosition();
            previous = Blade.of(player, 1);
        }

        /** Generated stream has its own descriptor: no OGG resource or vanilla sound sample is needed. */
        @Override public WeighedSoundEvents resolve(SoundManager manager) {
            sound = new Sound(location, ConstantFloat.of(1), ConstantFloat.of(1), 1, Sound.Type.FILE, true, false, 16);
            WeighedSoundEvents event = new WeighedSoundEvents(location, "subtitles.meshcut.saber");
            event.addSound(sound);
            return event;
        }

        @Override public CompletableFuture<AudioStream> getStream(SoundBufferLibrary buffers, Sound sound, boolean loop) {
            return CompletableFuture.completedFuture(new AudioStream() {
                private volatile boolean closed;
                @Override public AudioFormat getFormat() { return new AudioFormat(SaberSynthesizer.RATE, 16, 1, true, false); }
                @Override public ByteBuffer read(int bytes) { return closed ? ByteBuffer.allocate(0) : synth.read(bytes); }
                @Override public void close() { closed = true; }
            });
        }

        @Override public void tick() {
            Minecraft mc = Minecraft.getInstance();
            if (player.isRemoved() || mc.level != player.level()) { stop(); return; }
            updatePosition();
            if (!LightsaberItem.isActive(player)) {
                volume *= 0.4f;
                if (++fade >= 3) stop();
                return;
            }
            Blade now = Blade.of(player, 1);
            // Subtract the emitter's translation: ordinary walking must not sound like a fast swing.
            double angularSpeed = now.direction().normalize().distanceTo(previous.direction().normalize()) * Blade.LENGTH * 20;
            double handSpeed = now.base().subtract(previous.base()).subtract(player.getDeltaMovement()).length() * 20;
            float target = (float) Math.clamp((angularSpeed + handSpeed * 0.5) / 18, 0, 1);
            speed += (target - speed) * 0.5f;
            synth.speed(speed);
            volume = 0.28f + speed * 0.4f;
            pitch = 0.9f + speed * 0.65f;
            previous = now;
        }
        void updatePosition() { x = player.getX(); y = player.getEyeY(); z = player.getZ(); }
        void dispose() { stop(); }
    }
    private LightsaberAudio() {}
}
