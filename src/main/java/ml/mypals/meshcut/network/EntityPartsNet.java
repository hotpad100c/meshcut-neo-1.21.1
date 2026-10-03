package ml.mypals.meshcut.network;

import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.block.EntityAppearance;
import ml.mypals.meshcut.cut.BodySpec;
import ml.mypals.meshcut.cut.EntityCutting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Only clients have entity models, so the server asks one to take a cut-down entity apart into limbs
 * ({@link PartsRequest}) and builds the body from the answer ({@link PartsReply}).
 */
public final class EntityPartsNet {
    /** Server to client: take this entity apart. */
    public record PartsRequest(int id, EntityAppearance.Look look) implements CustomPacketPayload {
        public static final Type<PartsRequest> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MeshCut.MODID, "parts_request"));
        public static final StreamCodec<FriendlyByteBuf, PartsRequest> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.id());
                    buf.writeNbt(p.look().save());
                },
                buf -> {
                    int id = buf.readVarInt();
                    CompoundTag tag = buf.readNbt();
                    return new PartsRequest(id, EntityAppearance.Look.load(tag == null ? new CompoundTag() : tag));
                });

        @Override
        public Type<PartsRequest> type() {
            return TYPE;
        }
    }

    /** Client to server: the limbs of the entity asked about. */
    public record PartsReply(int id, List<BodySpec> bodies) implements CustomPacketPayload {
        public static final Type<PartsReply> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MeshCut.MODID, "parts_reply"));
        public static final StreamCodec<FriendlyByteBuf, PartsReply> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.id());
                    buf.writeVarInt(p.bodies().size());
                    p.bodies().forEach(b -> b.write(buf));
                },
                buf -> {
                    int id = buf.readVarInt();
                    int count = buf.readVarInt();
                    if (count < 0 || count > BodySpec.MAX_BODIES) {
                        throw new IllegalArgumentException("Too many limbs: " + count);
                    }
                    List<BodySpec> bodies = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        bodies.add(BodySpec.read(buf));
                    }
                    return new PartsReply(id, bodies);
                });

        @Override
        public Type<PartsReply> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1").optional();
        registrar.playToClient(PartsRequest.TYPE, PartsRequest.CODEC, EntityPartsNet::onRequest);
        registrar.playToServer(PartsReply.TYPE, PartsReply.CODEC, EntityPartsNet::onReply);
    }

    private static void onRequest(PartsRequest request, IPayloadContext context) {
        context.enqueueWork(() -> ml.mypals.meshcut.client.capture.EntityParts.answer(request));
    }

    private static void onReply(PartsReply reply, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            context.enqueueWork(() -> EntityCutting.onReply(player, reply.id(), reply.bodies()));
        }
    }

    private EntityPartsNet() {}
}
