package ml.mypals.meshcut.network;

import dev.ryanhcode.sable.Sable;
import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.block.MimicBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.Map;
import java.util.WeakHashMap;

@EventBusSubscriber(modid=MeshCut.MODID,bus=EventBusSubscriber.Bus.MOD)
public final class CutSyncNet {
    public record SnapshotRequest(BlockPos pos) implements CustomPacketPayload {
        public static final Type<SnapshotRequest> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(MeshCut.MODID,"cut_snapshot"));
        public static final StreamCodec<FriendlyByteBuf,SnapshotRequest> CODEC=StreamCodec.of((b,p)->b.writeBlockPos(p.pos),b->new SnapshotRequest(b.readBlockPos()));
        @Override public Type<SnapshotRequest> type(){return TYPE;}
    }
    private static final class Budget { long tick; int count; }
    private static final Map<ServerPlayer,Budget> BUDGETS=new WeakHashMap<>();
    private static final ThreadLocal<ShotChanges> SHOT=new ThreadLocal<>();
    public static final class ShotChanges implements AutoCloseable {
        private final net.minecraft.server.level.ServerLevel level;
        private final java.util.Set<BlockPos> positions=new java.util.LinkedHashSet<>();
        private final ShotChanges previous;
        private ShotChanges(net.minecraft.server.level.ServerLevel level){this.level=level;previous=SHOT.get();SHOT.set(this);}
        public java.util.List<LaserNet.Stamp> stamps(){
            var result=new java.util.ArrayList<LaserNet.Stamp>();
            for(BlockPos pos:positions) {
                var state=level.getBlockState(pos);
                var be=level.getBlockEntity(pos);
                result.add(new LaserNet.Stamp(pos,net.minecraft.world.level.block.Block.getId(state),
                        be instanceof MimicBlockEntity mimic?mimic.syncEpoch():0,
                        be instanceof MimicBlockEntity mimic?mimic.syncRevision():-1));
            }
            return java.util.List.copyOf(result);
        }
        @Override public void close(){if(previous==null)SHOT.remove();else SHOT.set(previous);}
    }
    public static ShotChanges shot(net.minecraft.server.level.ServerLevel level){return new ShotChanges(level);}
    public static void changed(net.minecraft.server.level.ServerLevel level,BlockPos pos){
        ShotChanges shot=SHOT.get();if(shot!=null&&shot.level==level&&shot.positions.size()<4096)shot.positions.add(pos.immutable());
    }

    public static void request(BlockPos pos) { PacketDistributor.sendToServer(new SnapshotRequest(pos)); }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(SnapshotRequest.TYPE,SnapshotRequest.CODEC,(request,context)->context.enqueueWork(()->{
            if(!(context.player() instanceof ServerPlayer player)) return;
            var level=player.serverLevel();
            Budget budget=BUDGETS.computeIfAbsent(player,p->new Budget());
            long tick=level.getGameTime();
            if(budget.tick!=tick){budget.tick=tick;budget.count=0;}
            if(budget.count++>=16 || !level.hasChunkAt(request.pos)) return;
            var at=Sable.HELPER.projectOutOfSubLevel(level,net.minecraft.world.phys.Vec3.atCenterOf(request.pos));
            if(player.position().distanceToSqr(at)>128*128) return;
            if(level.getBlockEntity(request.pos) instanceof MimicBlockEntity be)
                player.connection.send(ClientboundBlockEntityDataPacket.create(be,(entity,registries)->be.getUpdateTag(registries)));
        }));
    }
    private CutSyncNet(){}
}
