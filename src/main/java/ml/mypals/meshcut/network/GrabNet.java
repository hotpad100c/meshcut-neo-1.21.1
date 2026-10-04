package ml.mypals.meshcut.network;

import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.physics.FragmentGrab;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class GrabNet {
    public record Hold(boolean holding, UUID body, double x, double y, double z, float distance, int request) implements CustomPacketPayload {
        public static final Type<Hold> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MeshCut.MODID, "grab_hold"));
        public static final StreamCodec<FriendlyByteBuf, Hold> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeBoolean(p.holding());
                    UUIDUtil.STREAM_CODEC.encode(buf, p.body());
                    buf.writeDouble(p.x());
                    buf.writeDouble(p.y());
                    buf.writeDouble(p.z());
                    buf.writeFloat(p.distance());
                    buf.writeVarInt(p.request());
                },
                buf -> new Hold(buf.readBoolean(), UUIDUtil.STREAM_CODEC.decode(buf), buf.readDouble(), buf.readDouble(),
                        buf.readDouble(), buf.readFloat(), buf.readVarInt()));

        @Override
        public Type<Hold> type() {
            return TYPE;
        }
    }

    public record Result(int request, UUID body, int reason) implements CustomPacketPayload {
        public static final Type<Result> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MeshCut.MODID, "grab_result"));
        public static final StreamCodec<FriendlyByteBuf, Result> CODEC = StreamCodec.of(
                (buf, p) -> { buf.writeVarInt(p.request()); UUIDUtil.STREAM_CODEC.encode(buf, p.body()); buf.writeVarInt(p.reason()); },
                buf -> new Result(buf.readVarInt(), UUIDUtil.STREAM_CODEC.decode(buf), buf.readVarInt()));
        @Override public Type<Result> type() { return TYPE; }
    }

    public record Holders(List<UUID> players) implements CustomPacketPayload {
        public static final Type<Holders> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MeshCut.MODID, "grab_holders"));
        public static final StreamCodec<FriendlyByteBuf, Holders> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.players().size());
                    p.players().forEach(id -> UUIDUtil.STREAM_CODEC.encode(buf, id));
                },
                buf -> {
                    int n = Math.min(buf.readVarInt(), 4096);
                    List<UUID> players = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        players.add(UUIDUtil.STREAM_CODEC.decode(buf));
                    }
                    return new Holders(players);
                });

        @Override
        public Type<Holders> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("2").optional();
        registrar.playToClient(Result.TYPE, Result.CODEC, (result, context) -> context.enqueueWork(
                () -> ml.mypals.meshcut.client.FragmentGrabClient.accept(result)));
        registrar.playToServer(Hold.TYPE, Hold.CODEC, GrabNet::onHold);
        registrar.playToClient(Holders.TYPE, Holders.CODEC, GrabNet::onHolders);
    }

    private static void onHold(Hold hold, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            context.enqueueWork(() -> {
                if (hold.holding()) {
                    int reason = FragmentGrab.hold(player, hold.body(), new Vector3d(hold.x(), hold.y(), hold.z()), hold.distance(), hold.request());
                    if (reason != 0) FragmentGrab.release(player);
                    PacketDistributor.sendToPlayer(player, new Result(hold.request(), hold.body(), reason));
                } else {
                    FragmentGrab.release(player);
                }
            });
        }
    }

    private static void onHolders(Holders holders, IPayloadContext context) {
        context.enqueueWork(() -> ml.mypals.meshcut.client.FragmentGrabClient.setHolders(holders.players()));
    }

    private GrabNet() {}
}
