package ml.mypals.meshcut.physics;

import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import ml.mypals.meshcut.MeshCut;
import org.joml.Vector3dc;

import java.util.ArrayList;


public final class MassGuard {
    private static final double MIN_MASS = 1e-9;
    private static boolean warned;

    public static void check(SubLevelPhysicsSystem system, ServerSubLevelContainer container) {
        for (SubLevel s : new ArrayList<>(container.getAllSubLevels())) {
            if (!(s instanceof ServerSubLevel sub) || sub.isRemoved() || healthy(sub.getMassTracker())) {
                continue;
            }
            double before = sub.getMassTracker() == null ? Double.NaN : sub.getMassTracker().getMass();
            try {
                sub.buildMassTracker();
                sub.updateMergedMassData((float) system.getPartialPhysicsTick());
                system.getPipeline().onStatsChanged(sub);
            } catch (RuntimeException e) {
                MeshCut.LOGGER.error("Couldn't work out the mass of {} again", sub, e);
            }
            if (healthy(sub.getMassTracker())) {
                if (!warned) {
                    warned = true;
                    MeshCut.LOGGER.warn("Sub-level {} had a mass of {} and no centre of mass; worked out again from its blocks: mass {}",
                            sub.getUniqueId(), before, sub.getMassTracker().getMass());
                }
            } else {
                // nothing in it weighs anything: stepped, it would bring the whole server down
                MeshCut.LOGGER.error("Sub-level {} weighs nothing even counted again from its blocks; it is removed", sub.getUniqueId());
                container.removeSubLevel(sub, dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason.REMOVED);
            }
        }
    }

    private static boolean healthy(MassData mass) {
        if (mass == null) {
            return true;
        }
        Vector3dc center = mass.getCenterOfMass();
        return mass.getMass() > MIN_MASS && center != null
                && Double.isFinite(center.x()) && Double.isFinite(center.y()) && Double.isFinite(center.z());
    }

    private MassGuard() {}
}
