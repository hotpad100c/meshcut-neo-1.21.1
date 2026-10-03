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
    public record Input(float yaw, float pitch) implements CustomPacketPayload {
        public static final Type<Input> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MeshCut.MODID, "saber_input"));
        public static final StreamCodec<FriendlyByteBuf, Input> CODEC = StreamCodec.of(
                (buf, input) -> { buf.writeFloat(input.yaw); buf.writeFloat(input.pitch); },
                buf -> new Input(buf.readFloat(), buf.readFloat()));
        @Override public Type<Input> type() { return TYPE; }
    }

    public record Target(int id, float yaw, float pitch) implements CustomPacketPayload {
        public static final Type<Target> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MeshCut.MODID, "saber_target"));
        public static final StreamCodec<FriendlyByteBuf, Target> CODEC = StreamCodec.of(
                (buf, target) -> { buf.writeVarInt(target.id); buf.writeFloat(target.yaw); buf.writeFloat(target.pitch); },
                buf -> new Target(buf.readVarInt(), buf.readFloat(), buf.readFloat()));
        @Override public Type<Target> type() { return TYPE; }
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToServer(Input.TYPE, Input.CODEC, LightsaberNet::onInput);
        registrar.playToClient(Target.TYPE, Target.CODEC, (target, context) -> context.enqueueWork(
                () -> ml.mypals.meshcut.client.LightsaberRemoteAim.accept(target)));
    }

    private static void onInput(Input input, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player && LightsaberItem.isActive(player)) {
                LightsaberMotion.setOffsets(player, input.yaw, input.pitch);
            }
        });
    }

    public static void broadcast(ServerPlayer player, float yaw, float pitch) {
        PacketDistributor.sendToPlayersTrackingEntity(player, new Target(player.getId(), yaw, pitch));
    }

    private LightsaberNet() {}
}
