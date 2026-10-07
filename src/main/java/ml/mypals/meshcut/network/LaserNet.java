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
    public record RequestShot(long operation) implements CustomPacketPayload {
        public static final Type<RequestShot> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(MeshCut.MODID,"laser_request"));
        public static final StreamCodec<FriendlyByteBuf,RequestShot> CODEC=StreamCodec.of((b,p)->b.writeVarLong(p.operation),b->new RequestShot(b.readVarLong()));
        @Override public Type<RequestShot> type(){return TYPE;}
    }
    public record Stamp(net.minecraft.core.BlockPos pos,int state,long epoch,long revision){}
    public record Acknowledged(long operation,boolean accepted,java.util.List<Stamp> stamps) implements CustomPacketPayload {
        public static final Type<Acknowledged> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(MeshCut.MODID,"laser_ack"));
        public static final StreamCodec<FriendlyByteBuf,Acknowledged> CODEC=StreamCodec.of((b,p)->{
            b.writeVarLong(p.operation);b.writeBoolean(p.accepted);b.writeVarInt(p.stamps.size());
            for(var s:p.stamps){b.writeBlockPos(s.pos);b.writeVarInt(s.state);b.writeVarLong(s.epoch);b.writeLong(s.revision);}
        },b->{
            long op=b.readVarLong();boolean accepted=b.readBoolean();int count=b.readVarInt();
            if(count<0||count>4096)throw new IllegalArgumentException("laser acknowledgement size");
            var stamps=new java.util.ArrayList<Stamp>(count);
            for(int i=0;i<count;i++)stamps.add(new Stamp(b.readBlockPos(),b.readVarInt(),b.readVarLong(),b.readLong()));
            return new Acknowledged(op,accepted,java.util.List.copyOf(stamps));
        });
        @Override public Type<Acknowledged> type(){return TYPE;}
    }
    private static final java.util.Map<net.minecraft.server.level.ServerPlayer,Long> LAST_SHOT=new java.util.WeakHashMap<>();
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
        var registrar = event.registrar("3");
        registrar.playToServer(RequestShot.TYPE,RequestShot.CODEC,(request,context)->context.enqueueWork(()->{
            if(!(context.player() instanceof net.minecraft.server.level.ServerPlayer player))return;
            long previous=LAST_SHOT.getOrDefault(player,0L);
            if(request.operation<=previous){net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,new Acknowledged(request.operation,false,java.util.List.of()));return;}
            LAST_SHOT.put(player,request.operation);
            LaserGunItem.requestShot(player,request.operation);
        }));
        registrar.playToClient(Acknowledged.TYPE,Acknowledged.CODEC,(ack,context)->context.enqueueWork(()->ml.mypals.meshcut.client.CutPrediction.acknowledge(ack)));
        registrar.playToServer(Radius.TYPE, Radius.CODEC, (radius, context) -> context.enqueueWork(() -> {
            if (radius.value >= 1 && radius.value <= 4)
                LaserGunItem.setRadius(context.player().getMainHandItem(), radius.value);
        }));
        registrar.playToClient(Shot.TYPE, Shot.CODEC, (shot, context) -> context.enqueueWork(
                () -> ml.mypals.meshcut.client.LaserVisuals.accept(shot)));
    }

    private LaserNet() {}
}
