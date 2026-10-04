package ml.mypals.meshcut.network;

import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.item.LaserGunItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@EventBusSubscriber(modid = MeshCut.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class LaserNet {
    public record Radius(int value) implements CustomPacketPayload {
        public static final Type<Radius> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MeshCut.MODID, "laser_radius"));
        public static final StreamCodec<FriendlyByteBuf, Radius> CODEC = StreamCodec.of(
                (buf, value) -> buf.writeVarInt(value.value), buf -> new Radius(buf.readVarInt()));
        @Override public Type<Radius> type() { return TYPE; }
    }

    public record Shot(int shooter, Vec3 start, Vec3 end, int color, int radius) implements CustomPacketPayload {
        public static final Type<Shot> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MeshCut.MODID, "laser_shot"));
        public static final StreamCodec<FriendlyByteBuf, Shot> CODEC = StreamCodec.of((buf, shot) -> {
            buf.writeVarInt(shot.shooter); write(buf, shot.start); write(buf, shot.end); buf.writeInt(shot.color); buf.writeVarInt(shot.radius);
        }, buf -> new Shot(buf.readVarInt(), read(buf), read(buf), buf.readInt(), buf.readVarInt()));
        private static void write(FriendlyByteBuf buf, Vec3 v) { buf.writeDouble(v.x); buf.writeDouble(v.y); buf.writeDouble(v.z); }
        private static Vec3 read(FriendlyByteBuf buf) { return new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()); }
        @Override public Type<Shot> type() { return TYPE; }
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("2");
        registrar.playToServer(Radius.TYPE, Radius.CODEC, (radius, context) -> context.enqueueWork(() -> {
            if (radius.value >= 1 && radius.value <= 4)
                LaserGunItem.setRadius(context.player().getMainHandItem(), radius.value);
        }));
        registrar.playToClient(Shot.TYPE, Shot.CODEC, (shot, context) -> context.enqueueWork(
                () -> ml.mypals.meshcut.client.LaserVisuals.accept(shot)));
    }

    private LaserNet() {}
}
