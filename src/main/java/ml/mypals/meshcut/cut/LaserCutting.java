package ml.mypals.meshcut.cut;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import ml.mypals.meshcut.block.MimicBlock;
import ml.mypals.meshcut.block.MimicBlockEntity;
import ml.mypals.meshcut.block.MimicShapes;
import ml.mypals.meshcut.geom.CellGrid;
import ml.mypals.meshcut.geom.HoleReducer;
import ml.mypals.meshcut.geom.PolygonCapsule;
import ml.mypals.meshcut.geom.Polytope;
import ml.mypals.meshcut.physics.RagdollJoints;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import java.util.ArrayList;
import java.util.List;

/** Removes one continuous polygon capsule across every block it overlaps in a world or body plot. */
public final class LaserCutting {
    public static void vaporizeImpact(ServerLevel level, SubLevel primary, Vec3 a, Vec3 b, int radius) {
        double r=PolygonCapsule.radius(radius)*1.1;
        var bounds=new BoundingBox3d(
                new Vec3(Math.min(a.x,b.x)-r,Math.min(a.y,b.y)-r,Math.min(a.z,b.z)-r),
                new Vec3(Math.max(a.x,b.x)+r,Math.max(a.y,b.y)+r,Math.max(a.z,b.z)+r));
        // Snapshot candidates before edits can remove or split a body.
        List<SubLevel> subs=new ArrayList<>();
        for(var body:Sable.HELPER.getAllIntersecting(level,bounds)) subs.add(body);
        vaporize(level,null,a,b,radius);
        if(primary!=null && !subs.contains(primary)) subs.add(primary);
        for(var body:subs) vaporize(level,body,a,b,radius);
    }
    public static void vaporize(ServerLevel level, SubLevel sub, Vec3 worldA, Vec3 worldB, int radius) {
        try (var timing = ml.mypals.meshcut.util.CutTiming.begin()) {
            vaporizeBlocks(level, sub, worldA, worldB, radius);
        }
    }

    private static void vaporizeBlocks(ServerLevel level, SubLevel sub, Vec3 worldA, Vec3 worldB, int radius) {
        Vector3d a = new Vector3d(worldA.x,worldA.y,worldA.z), b = new Vector3d(worldB.x,worldB.y,worldB.z);
        if (sub != null) {
            if (sub.isRemoved()) return;
            sub.logicalPose().transformPositionInverse(a);
            sub.logicalPose().transformPositionInverse(b);
        }
        double r = PolygonCapsule.radius(radius);
        var capsule = PolygonCapsule.planes(a,b,r);
        if (capsule.isEmpty()) return;
        double reach=r*1.1;
        int minX=(int)Math.floor(Math.min(a.x,b.x)-reach), maxX=(int)Math.floor(Math.max(a.x,b.x)+reach);
        int minY=(int)Math.floor(Math.min(a.y,b.y)-reach), maxY=(int)Math.floor(Math.max(a.y,b.y)+reach);
        int minZ=(int)Math.floor(Math.min(a.z,b.z)-reach), maxZ=(int)Math.floor(Math.max(a.z,b.z)+reach);
        if (sub != null) {
            var bounds=sub.getPlot().getBoundingBox();
            minX=Math.max(minX,bounds.minX()); maxX=Math.min(maxX,bounds.maxX());
            minY=Math.max(minY,bounds.minY()); maxY=Math.min(maxY,bounds.maxY());
            minZ=Math.max(minZ,bounds.minZ()); maxZ=Math.min(maxZ,bounds.maxZ());
        }
        List<BlockPos> affected=new ArrayList<>();
        for(int x=minX;x<=maxX;x++) for(int y=minY;y<=maxY;y++) for(int z=minZ;z<=maxZ;z++) {
            var pos=new BlockPos(x,y,z);
            if(!level.hasChunkAt(pos) || Sable.HELPER.getContaining(level,pos)!=sub) continue;
            // The capsule's polygon circumscribes the true one by at most 1/cos(15 degrees); the cube sticks out half its diagonal.
            double near=r*1.04+0.8661;
            if(distanceSquared(a,b,x+0.5,y+0.5,z+0.5)>near*near) continue;
            if(CutService.pieceAt(level,pos,CapMode.LAVA)!=null) affected.add(pos);
        }
        if(affected.isEmpty()) return;
        if(sub instanceof ServerSubLevel server) RagdollJoints.noteSplit(level,server,null);
        List<BlockPos> changed=new ArrayList<>();
        for(var pos:affected) {
            if(sub!=null && sub.isRemoved()) break;
            if(vaporizeBlock(level,pos,PolygonCapsule.local(capsule,pos.getX(),pos.getY(),pos.getZ()))) changed.add(pos);
        }
        // Whatever the beam left disconnected (inside a block, or between blocks) comes apart now, like after a slash.
        Vector3d axis=new Vector3d(b).sub(a);
        if(axis.lengthSquared()<1e-12) axis.set(0,0,1); else axis.normalize();
        Vector3d side=axis.cross(Math.abs(axis.y)>0.9 ? new Vector3d(1,0,0) : new Vector3d(0,1,0),new Vector3d()).normalize();
        SlashService.settleCut(level,sub instanceof ServerSubLevel server ? server : null,changed,side,side.dot(a));
    }

    private static boolean vaporizeBlock(ServerLevel level, BlockPos pos, List<CutPlane> hole) {
        // Only what the block can see of the hole is kept: a few planes of its 62.
        var reduced=HoleReducer.reduce(hole);
        if(reduced==null) return false;
        hole=reduced.planes();
        var piece=CutService.pieceAt(level,pos,CapMode.LAVA);
        if(piece==null) return false;
        for(var old:piece.excluded()) {
            // already burnt away, or burnt away by something bigger
            if(CutPlane.sameRegion(old,hole) || HoleReducer.contains(old,reduced.vertices())) return false;
        }
        var shape=level.getBlockEntity(pos) instanceof MimicBlockEntity be ? be.cutBoxes() : MimicShapes.cutBoxes(piece.target());
        boolean intersects=false;
        for(var box:shape) {
            var material=new ArrayList<>(piece.planes());
            material.addAll(hole);
            material.addAll(ml.mypals.meshcut.geom.VoxelFragments.planes(box));
            if(Polytope.volume(material)>1e-8) { intersects=true; break; }
        }
        if(!intersects) return false;
        var holes=new ArrayList<>(piece.excluded());
        holes.add(hole);
        // Cells still determine support; the region cutting the visible triangles stays polygonal.
        var grid=CellGrid.of(piece.planes(),holes,piece.patches(),shape);
        piece=CutService.detachBlockEntity(level,pos,piece);
        if(grid.filledCells()==0) {
            level.setBlock(pos,level.getFluidState(pos).createLegacyBlock(),3);
        } else {
            boolean glow=MimicBlock.glowingCuts();
            MimicBlock.setGlowingCuts(true);
            try {
                MimicBlockEntity.presetGrid(grid);
                MimicBlock.place(level,pos,piece.target(),piece.planes(),holes,piece.patches(),piece.mode(),piece.beTag(),piece.entity(),piece.glow());
            } finally { MimicBlockEntity.presetGrid(null); MimicBlock.setGlowingCuts(glow); }
        }
        return true;
    }
    /** Squared distance from a point to the segment a-b. */
    private static double distanceSquared(Vector3d a, Vector3d b, double x, double y, double z) {
        double dx=b.x-a.x, dy=b.y-a.y, dz=b.z-a.z;
        double length=dx*dx+dy*dy+dz*dz;
        double t=length<1e-12 ? 0 : Math.max(0,Math.min(1,((x-a.x)*dx+(y-a.y)*dy+(z-a.z)*dz)/length));
        double px=a.x+dx*t-x, py=a.y+dy*t-y, pz=a.z+dz*t-z;
        return px*px+py*py+pz*pz;
    }
    private LaserCutting() {}
}
