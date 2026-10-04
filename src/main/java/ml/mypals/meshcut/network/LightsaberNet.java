package ml.mypals.meshcut.network;

import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.item.LightsaberItem;
import ml.mypals.meshcut.item.LightsaberMotion;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

@EventBusSubscriber(modid = MeshCut.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class LightsaberNet {
    public record Input(float yaw, float pitch, float x, float y) implements CustomPacketPayload {
        public static final Type<Input> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MeshCut.MODID, "saber_input"));
        public static final StreamCodec<FriendlyByteBuf, Input> CODEC = StreamCodec.of(
                (buf, input) -> { buf.writeFloat(input.yaw); buf.writeFloat(input.pitch); buf.writeFloat(input.x); buf.writeFloat(input.y); },
                buf -> new Input(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat()));
        @Override public Type<Input> type() { return TYPE; }
    }

    /**
     * Where the player's blade was this tick, as the client has it, relative to the player: the base, and the tip
     * from the base. The cut is made along what the client drew (within what is believable, see LightsaberHandler),
     * so what the player sees burn is what is cut.
     */
    public record Swing(float bx, float by, float bz, float dx, float dy, float dz) implements CustomPacketPayload {
        public static final Type<Swing> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MeshCut.MODID, "saber_swing"));
        public static final StreamCodec<FriendlyByteBuf, Swing> CODEC = StreamCodec.of(
                (buf, s) -> { buf.writeFloat(s.bx); buf.writeFloat(s.by); buf.writeFloat(s.bz); buf.writeFloat(s.dx); buf.writeFloat(s.dy); buf.writeFloat(s.dz); },
                buf -> new Swing(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat()));
        @Override public Type<Swing> type() { return TYPE; }
    }

    public record Target(int id, float yaw, float pitch, float x, float y) implements CustomPacketPayload {
        public static final Type<Target> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MeshCut.MODID, "saber_target"));
        public static final StreamCodec<FriendlyByteBuf, Target> CODEC = StreamCodec.of(
                (buf, target) -> { buf.writeVarInt(target.id); buf.writeFloat(target.yaw); buf.writeFloat(target.pitch); buf.writeFloat(target.x); buf.writeFloat(target.y); },
                buf -> new Target(buf.readVarInt(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat()));
        @Override public Type<Target> type() { return TYPE; }
    }

    public record Gesture(int preset, int seed) implements CustomPacketPayload {
        public static final Type<Gesture> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MeshCut.MODID, "saber_gesture"));
        public static final StreamCodec<FriendlyByteBuf, Gesture> CODEC = StreamCodec.of(
                (buf, gesture) -> { buf.writeVarInt(gesture.preset); buf.writeInt(gesture.seed); },
                buf -> new Gesture(buf.readVarInt(), buf.readInt()));
        @Override public Type<Gesture> type() { return TYPE; }
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("2");
        registrar.playToServer(Gesture.TYPE, Gesture.CODEC, (gesture, context) -> context.enqueueWork(
                () -> LightsaberMotion.startGesture(context.player(), gesture.preset, gesture.seed)));
        registrar.playToServer(Input.TYPE, Input.CODEC, LightsaberNet::onInput);
        registrar.playToServer(Swing.TYPE, Swing.CODEC, (swing, context) -> context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                ml.mypals.meshcut.item.LightsaberHandler.receive(player, swing);
            }
        }));
        registrar.playToClient(Target.TYPE, Target.CODEC, (target, context) -> context.enqueueWork(
                () -> ml.mypals.meshcut.client.LightsaberRemoteAim.accept(target)));
    }

    private static void onInput(Input input, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player && LightsaberItem.isActive(player)) {
                LightsaberMotion.setOffsets(player, input.yaw, input.pitch);
                LightsaberMotion.setHandTarget(player, input.x, input.y);
            }
        });
    }

    public static void broadcast(ServerPlayer player, float yaw, float pitch, float x, float y) {
        PacketDistributor.sendToPlayersTrackingEntity(player, new Target(player.getId(), yaw, pitch, x, y));
    }

    private LightsaberNet() {}
}
