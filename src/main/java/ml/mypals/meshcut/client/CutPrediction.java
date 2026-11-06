package ml.mypals.meshcut.client;

import dev.ryanhcode.sable.Sable;
import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.block.*;
import ml.mypals.meshcut.cut.*;
import ml.mypals.meshcut.geom.*;
import ml.mypals.meshcut.item.LaserGunItem;
import ml.mypals.meshcut.network.LaserNet;
import ml.mypals.meshcut.network.PendingCuts;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Vector3d;
import java.util.*;
import java.util.concurrent.*;

@EventBusSubscriber(modid=MeshCut.MODID,value=Dist.CLIENT)
public final class CutPrediction {
    public record View(MimicRenderData data,boolean empty,BlockState source){}
    private record Input(List<CutPlane> hole,BlockState target){}
    private static final class Entry {
        BlockState state;
        MimicRenderData base;
        long generation;
        boolean scheduled;
    }
    private static final PendingCuts<BlockPos,Input,LaserNet.Stamp> OPERATIONS=new PendingCuts<>();
    private static final Map<BlockPos,Entry> ENTRIES=new HashMap<>();
    private static final Set<BlockPos> DIRTY=new LinkedHashSet<>();
    private static final ConcurrentMap<Long,View> VIEWS=new ConcurrentHashMap<>();
    private static volatile Object world;
    private static long sequence;
    private static final ExecutorService WORKER=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(256),r->{
        Thread thread=new Thread(r,"meshcut-prediction");thread.setDaemon(true);return thread;
    },new ThreadPoolExecutor.AbortPolicy());

    public static long nextOperation(){return ++sequence;}
    public static View view(BlockPos pos){return pos==null||world!=Minecraft.getInstance().level?null:VIEWS.get(pos.asLong());}

    private static void syncWorld(){
        var now=Minecraft.getInstance().level;
        if(world!=now){OPERATIONS.clear();ENTRIES.clear();DIRTY.clear();VIEWS.clear();world=now;}
    }

    public static void shoot(Player player,ItemStack stack,long operation) {
        syncWorld();var level=Minecraft.getInstance().level;
        if(level==null || Boolean.getBoolean("meshcut.noPrediction") || OPERATIONS.size()>=32)return;
        Vec3 start=player.getEyePosition(),direction=player.getLookAngle(),end=start.add(direction.scale(LaserGunItem.RANGE));
        for(int step=1;step<=LaserGunItem.RANGE;step++) if(!level.hasChunkAt(BlockPos.containing(start.add(direction.scale(step))))) {
            end=start.add(direction.scale(step-1));break;
        }
        var hit=level.clip(new ClipContext(start,end,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,player));
        if(hit.getType()!=HitResult.Type.BLOCK)return;
        var sub=Sable.HELPER.getContaining(level,hit.getBlockPos());
        Vec3 local=hit.getLocation(),localDirection=direction;
        Vec3 worldHit=local;
        if(sub!=null) {
            var at=sub.logicalPose().transformPosition(new Vector3d(local.x,local.y,local.z));worldHit=new Vec3(at.x,at.y,at.z);
            var normal=sub.logicalPose().transformNormalInverse(new Vector3d(direction.x,direction.y,direction.z));
            localDirection=new Vec3(normal.x,normal.y,normal.z).normalize();
        }
        double nearest=start.distanceToSqr(worldHit);
        for(var entity:level.getEntities(player,new AABB(start,worldHit).inflate(1),e->!e.isSpectator())) {
            var point=entity.getBoundingBox().clip(start,worldHit);
            if(point.isPresent()&&start.distanceToSqr(point.get())<nearest)return;
        }
        Vec3 exit=local.add(localDirection.scale(LaserGunItem.exitDistance(local,localDirection,new AABB(hit.getBlockPos()))));
        double radius=PolygonCapsule.radius(LaserGunItem.radius(stack));
        var capsule=PolygonCapsule.planes(new Vector3d(local.x,local.y,local.z),new Vector3d(exit.x,exit.y,exit.z),radius);
        double reach=radius*1.1;
        int x0=(int)Math.floor(Math.min(local.x,exit.x)-reach),x1=(int)Math.floor(Math.max(local.x,exit.x)+reach);
        int y0=(int)Math.floor(Math.min(local.y,exit.y)-reach),y1=(int)Math.floor(Math.max(local.y,exit.y)+reach);
        int z0=(int)Math.floor(Math.min(local.z,exit.z)-reach),z1=(int)Math.floor(Math.max(local.z,exit.z)+reach);
        Map<BlockPos,Input> pending=new LinkedHashMap<>();
        for(int x=x0;x<=x1;x++)for(int y=y0;y<=y1;y++)for(int z=z0;z<=z1;z++) {
            if(pending.size()>=64||ENTRIES.size()+pending.size()>=256)continue;
            BlockPos pos=new BlockPos(x,y,z);
            if(!level.hasChunkAt(pos)||Sable.HELPER.getContaining(level,pos)!=sub)continue;
            MimicRenderData data=base(pos,null);
            if(data==null)continue;
            pending.put(pos,new Input(PolygonCapsule.local(capsule,x,y,z),data.target()));
            DIRTY.add(pos);
        }
        if(pending.isEmpty())return;
        OPERATIONS.add(operation,level.getGameTime(),pending);
        rebuildDirty();
    }

    private static MimicRenderData base(BlockPos pos,Entry previous) {
        var level=Minecraft.getInstance().level;
        BlockState state=level.getBlockState(pos);
        if(state.isAir()||state.getBlock() instanceof LiquidBlock||state.getRenderShape()!=RenderShape.MODEL)return null;
        if(level.getBlockEntity(pos) instanceof MimicBlockEntity be) {
            MimicRenderData data=be.renderData();
            return data.entity()!=null||data.target().hasBlockEntity()||data.target().isAir()?null:data;
        }
        if(state.hasBlockEntity())return null;
        if(previous!=null && previous.state==state && previous.base!=null)return previous.base;
        return new MimicRenderData(state,null,List.of(),List.of(),List.of(),CapMode.LAVA,null,List.of());
    }

    public static void acknowledge(LaserNet.Acknowledged ack) {
        syncWorld();Map<BlockPos,LaserNet.Stamp> stamps=new HashMap<>();
        for(var stamp:ack.stamps())stamps.put(stamp.pos(),stamp);
        DIRTY.addAll(OPERATIONS.acknowledge(ack.operation(),ack.accepted(),stamps));
        reconcile();rebuildDirty();
    }

    private static void reconcile() {
        var level=Minecraft.getInstance().level;if(level==null)return;
        DIRTY.addAll(OPERATIONS.reconcile(level.getGameTime(),level::hasChunkAt,(pos,stamp)->
                Block.getId(level.getBlockState(pos))==stamp.state() && (stamp.epoch()==0
                    || level.getBlockEntity(pos) instanceof MimicBlockEntity be
                        && be.syncEpoch()==stamp.epoch()&&be.syncRevision()>=stamp.revision())));
    }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        syncWorld();if(Minecraft.getInstance().level==null)return;
        reconcile();
        for(var pair:ENTRIES.entrySet()) {
            BlockPos pos=pair.getKey();Entry entry=pair.getValue();
            if(!Minecraft.getInstance().level.hasChunkAt(pos)||!entry.scheduled||base(pos,entry)!=entry.base
                    ||Minecraft.getInstance().level.getBlockState(pos)!=entry.state)DIRTY.add(pos);
        }
        rebuildDirty();
    }

    private static void rebuildDirty() {
        var level=Minecraft.getInstance().level;if(level==null)return;
        List<BlockPos> dirty=List.copyOf(DIRTY);DIRTY.clear();
        for(BlockPos pos:dirty) {
            List<Input> inputs=OPERATIONS.inputs(pos);
            Entry entry=ENTRIES.get(pos);
            MimicRenderData base=level.hasChunkAt(pos)?base(pos,entry):null;
            if(inputs.isEmpty()||base==null) {
                ENTRIES.remove(pos);if(VIEWS.remove(pos.asLong())!=null)redraw(pos);continue;
            }
            if(entry==null){entry=new Entry();ENTRIES.put(pos,entry);}
            entry.state=level.getBlockState(pos);entry.base=base;entry.scheduled=true;
            long generation=++entry.generation;Entry owner=entry;
            BlockState source=entry.state;Object expectedWorld=world;
            List<double[]> shape=level.getBlockEntity(pos) instanceof MimicBlockEntity be?be.cutBoxes():MimicShapes.cutBoxes(base.target());
            try {
                WORKER.execute(()->{
                    MimicRenderData result=base;boolean empty=false;
                    try {
                        for(Input input:inputs) {
                            if(input.target!=base.target())continue;
                            var prepared=CapsuleCut.prepare(result.planes(),result.excluded(),result.patches(),shape,HoleReducer.reduce(input.hole));
                            if(prepared==null)continue;
                            result=new MimicRenderData(result.target(),result.beTag(),result.planes(),prepared.holes(),result.patches(),CapMode.LAVA,result.entity(),prepared.hole());
                            if(prepared.grid().filledCells()==0){empty=true;break;}
                        }
                        View view=new View(result,empty,source);
                        Minecraft.getInstance().execute(()->{
                            if(world==expectedWorld&&ENTRIES.get(pos)==owner&&owner.generation==generation) {
                                View old=VIEWS.put(pos.asLong(),view);
                                if(!view.equals(old))redraw(pos);
                            }
                        });
                    } catch(RuntimeException exception) {
                        Minecraft.getInstance().execute(()->{
                            if(world==expectedWorld&&ENTRIES.get(pos)==owner&&owner.generation==generation){
                                ENTRIES.remove(pos);if(VIEWS.remove(pos.asLong())!=null)redraw(pos);
                            }
                        });
                        MeshCut.LOGGER.debug("Prediction at {} fell back to server geometry",pos,exception);
                    }
                });
            } catch(RejectedExecutionException ignored){entry.scheduled=false;}
        }
    }

    private static void redraw(BlockPos pos) {
        var level=Minecraft.getInstance().level;if(level==null||!level.hasChunkAt(pos))return;
        var state=level.getBlockState(pos);level.sendBlockUpdated(pos,state,state,0);
    }
    private CutPrediction(){}
}
