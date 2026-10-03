package ml.mypals.meshcut.physics;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelObserver;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.platform.SableEventPlatform;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.block.EntityAppearance;
import ml.mypals.meshcut.block.MimicBlockEntity;
import ml.mypals.meshcut.block.MimicShapes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The joints that hold the limbs of a cut-down body together, as in sable-player-ragdoll: a generic joint at the
 * point where a limb hung from its parent, its position locked and its rotation pulled back towards how it was by a
 * soft motor on each axis. Sable keeps joints only while both bodies are loaded, so they are saved here and made
 * again whenever both are.
 * <p>
 * A joint holds on to the material at its anchor. When a limb is cut, the joint goes with whichever piece still has
 * the anchor; when no piece has it, the limb has been cut off there and the joint is gone.
 */
public final class RagdollJoints extends SavedData {
    private static final String NAME = "meshcut_joints";
    /** How hard a joint pulls a limb back to how it was, and how much it damps it (as in the ragdoll mod). */
    private static final double STIFFNESS = 20, DAMPING = 20;
    /** How close (in blocks) the material of a limb must be to the anchor for the limb to still hang there. */
    private static final double REACH = 0.3;
    /** ...and for a limb that never quite reached the anchor, how close a piece of it must be to take the joint over. */
    private static final double LOOSE_REACH = 0.75;
    /** Every this many ticks, every joint checks that its limbs still hold on to it. */
    private static final int CHECK_EVERY = 40;

    private static final class End {
        UUID body;
        @Nullable
        final String limb;
        final Vector3d anchor;
        /** Whether there was material at the anchor to begin with (otherwise it is not missed when it is gone). */
        final boolean backed;

        End(UUID body, @Nullable String limb, Vector3d anchor, boolean backed) {
            this.body = body;
            this.limb = limb;
            this.anchor = anchor;
            this.backed = backed;
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("body", body);
            if (limb != null) {
                tag.putString("limb", limb);
            }
            tag.putDouble("x", anchor.x);
            tag.putDouble("y", anchor.y);
            tag.putDouble("z", anchor.z);
            tag.putBoolean("backed", backed);
            return tag;
        }

        static End load(CompoundTag tag) {
            return new End(tag.getUUID("body"), tag.contains("limb") ? tag.getString("limb") : null,
                    new Vector3d(tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z")), tag.getBoolean("backed"));
        }
    }

    private static final class Joint {
        final End parent, child;
        /** The child's orientation in the parent's frame when the joint was made: where the motors pull back to. */
        final Quaterniond frame;
        @Nullable
        PhysicsConstraintHandle handle;

        Joint(End parent, End child, Quaterniond frame) {
            this.parent = parent;
            this.child = child;
            this.frame = frame;
        }

        boolean touches(UUID id) {
            return parent.body.equals(id) || child.body.equals(id);
        }

        void drop() {
            if (handle != null) {
                try {
                    if (handle.isValid()) {
                        handle.remove();
                    }
                } catch (RuntimeException e) {
                    MeshCut.LOGGER.debug("Couldn't remove a joint", e);
                }
                handle = null;
            }
        }
    }

    private final List<Joint> joints = new ArrayList<>();
    /** Sub-levels that lost blocks to another one, or changed their mass: their joints are looked at again. */
    private final Map<UUID, Pose3d> touched = new HashMap<>();
    /** Sub-levels that are gone for good, with where they were last. */
    private final Map<UUID, Pose3d> removed = new HashMap<>();
    private long ticks;

    // ---------------------------------------------------------------- set up

    /** Watches the sub-levels of every server level. */
    public static void register() {
        SableEventPlatform.INSTANCE.onSubLevelContainerReady((level, container) -> {
            if (level instanceof ServerLevel server) {
                container.addObserver(new Observer(server));
            }
        });
    }

    public static RagdollJoints of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(RagdollJoints::new, RagdollJoints::load, null), NAME);
    }

    /**
     * Joins a limb to the limb it hangs from at {@code anchor} (in the world). Both must stand where they stood in
     * the body.
     */
    public static void join(ServerLevel level, ServerSubLevel parent, @Nullable String parentLimb, ServerSubLevel child,
                            @Nullable String childLimb, Vec3 anchor) {
        Vector3d world = new Vector3d(anchor.x, anchor.y, anchor.z);
        Vector3d inParent = parent.logicalPose().transformPositionInverse(new Vector3d(world));
        Vector3d inChild = child.logicalPose().transformPositionInverse(new Vector3d(world));
        Quaterniond frame = new Quaterniond(parent.logicalPose().orientation()).conjugate().mul(child.logicalPose().orientation());
        Joint joint = new Joint(new End(parent.getUniqueId(), parentLimb, inParent, holds(level, parent, inParent, parentLimb, REACH)),
                new End(child.getUniqueId(), childLimb, inChild, holds(level, child, inChild, childLimb, REACH)), frame);
        RagdollJoints data = of(level);
        data.joints.add(joint);
        data.setDirty();
        data.connect(level, joint);
    }

    /** The blocks of {@code source} changed (some left for a new sub-level, say): its joints are looked at again. */
    public static void noteSplit(ServerLevel level, ServerSubLevel source) {
        RagdollJoints data = of(level);
        if (data.joints.stream().anyMatch(j -> j.touches(source.getUniqueId()))) {
            data.touched.put(source.getUniqueId(), copy(source.logicalPose()));
        }
    }

    // ---------------------------------------------------------------- keeping them

    private static final class Observer implements SubLevelObserver {
        private final ServerLevel level;

        Observer(ServerLevel level) {
            this.level = level;
        }

        @Override
        public void onSubLevelRemoved(SubLevel sub, SubLevelRemovalReason reason) {
            RagdollJoints data = of(level);
            UUID id = sub.getUniqueId();
            boolean any = false;
            for (Joint joint : data.joints) {
                if (joint.touches(id)) {
                    joint.drop();
                    any = true;
                }
            }
            if (any && reason == SubLevelRemovalReason.REMOVED) {
                // its last piece may have gone to another sub-level that takes the joint over; see tick
                data.removed.put(id, copy(sub.logicalPose()));
            }
        }

        @Override
        public void tick(SubLevelContainer container) {
            try {
                of(level).tick(level, container);
            } catch (RuntimeException e) {
                MeshCut.LOGGER.error("Keeping the joints of cut-down bodies failed", e);
            }
        }
    }

    private void tick(ServerLevel level, SubLevelContainer container) {
        if (joints.isEmpty()) {
            touched.clear();
            removed.clear();
            return;
        }
        boolean all = ++ticks % CHECK_EVERY == 0;
        if (all || !touched.isEmpty() || !removed.isEmpty()) {
            Set<UUID> look = new HashSet<>(touched.keySet());
            look.addAll(removed.keySet());
            for (Joint joint : new ArrayList<>(joints)) {
                boolean recheck = all || look.contains(joint.parent.body) || look.contains(joint.child.body);
                if (!recheck) {
                    continue;
                }
                boolean kept = follow(level, container, joint, joint.parent, joint.child)
                        && follow(level, container, joint, joint.child, joint.parent);
                if (!kept) {
                    joint.drop();
                    joints.remove(joint);
                    setDirty();
                } else if (look.contains(joint.parent.body) || look.contains(joint.child.body)) {
                    joint.drop(); // made again below: the bodies' centres of mass may have moved
                }
            }
            touched.clear();
            removed.clear();
        }
        for (Joint joint : joints) {
            if (joint.handle == null || !joint.handle.isValid()) {
                connect(level, joint);
            }
        }
    }

    /**
     * Makes sure the end still holds on to its anchor: if its limb no longer has material there, a piece cut off it
     * that does takes the joint over.
     *
     * @return false if nothing holds the anchor any more (the limb was cut off there)
     */
    private boolean follow(ServerLevel level, SubLevelContainer container, Joint joint, End end, End other) {
        SubLevel current = container.getSubLevel(end.body);
        Pose3d pose = removed.get(end.body);
        if (current instanceof ServerSubLevel sub && !sub.isRemoved()) {
            if (holds(level, sub, end.anchor, end.limb, end.backed ? REACH : LOOSE_REACH)) {
                return true;
            }
            if (!end.backed && !touched.containsKey(end.body)) {
                return true; // it never reached the anchor; nothing has happened to it
            }
            pose = touched.getOrDefault(end.body, copy(sub.logicalPose()));
        } else if (pose == null) {
            return true; // not loaded: it is looked at when it is
        }
        Vector3d world = pose.transformPosition(new Vector3d(end.anchor));
        double r = LOOSE_REACH;
        for (SubLevel s : Sable.HELPER.getAllIntersecting(level, new BoundingBox3d(world.x - r, world.y - r, world.z - r,
                world.x + r, world.y + r, world.z + r))) {
            if (!(s instanceof ServerSubLevel candidate) || candidate.isRemoved() || candidate.getUniqueId().equals(end.body)
                    || candidate.getUniqueId().equals(other.body)) {
                continue;
            }
            Vector3d local = candidate.logicalPose().transformPositionInverse(new Vector3d(world));
            if (holds(level, candidate, local, end.limb, end.backed ? REACH : LOOSE_REACH)) {
                end.body = candidate.getUniqueId();
                end.anchor.set(local);
                joint.drop();
                setDirty();
                return true;
            }
        }
        return false;
    }

    /** Whether some block of the limb in the sub-level has material within {@code reach} of the point (plot space). */
    private static boolean holds(ServerLevel level, ServerSubLevel sub, Vector3d point, @Nullable String limb, double reach) {
        int bx = (int) Math.floor(point.x), by = (int) Math.floor(point.y), bz = (int) Math.floor(point.z);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos p = new BlockPos(bx + dx, by + dy, bz + dz);
                    if (!sub.getPlot().getBoundingBox().contains(p.getX(), p.getY(), p.getZ())
                            || !(level.getBlockEntity(p) instanceof MimicBlockEntity be)) {
                        continue;
                    }
                    EntityAppearance appearance = be.getAppearance();
                    if (appearance == null || !Objects.equals(appearance.part(), limb)) {
                        continue;
                    }
                    double x = point.x - p.getX(), y = point.y - p.getY(), z = point.z - p.getZ();
                    for (double[] b : MimicShapes.boxes(be.shapeKey())) {
                        double ex = Math.max(0, Math.max(b[0] - x, x - b[3]));
                        double ey = Math.max(0, Math.max(b[1] - y, y - b[4]));
                        double ez = Math.max(0, Math.max(b[2] - z, z - b[5]));
                        if (ex * ex + ey * ey + ez * ez <= reach * reach) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /** Makes the joint in the physics engine, if both its limbs are there. */
    private void connect(ServerLevel level, Joint joint) {
        var container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }
        if (!(container.getSubLevel(joint.parent.body) instanceof ServerSubLevel parent) || parent.isRemoved()
                || !(container.getSubLevel(joint.child.body) instanceof ServerSubLevel child) || child.isRemoved()) {
            return;
        }
        try {
            var config = new GenericConstraintConfiguration(new Vector3d(joint.parent.anchor), new Vector3d(joint.child.anchor),
                    new Quaterniond(joint.frame), new Quaterniond(),
                    Set.of(ConstraintJointAxis.LINEAR_X, ConstraintJointAxis.LINEAR_Y, ConstraintJointAxis.LINEAR_Z));
            PhysicsConstraintHandle handle = container.physicsSystem().getPipeline().addConstraint(parent, child, config);
            if (handle == null) {
                return;
            }
            handle.setContactsEnabled(false); // a limb rests against what it hangs from
            for (ConstraintJointAxis axis : ConstraintJointAxis.ANGULAR) {
                handle.setMotor(axis, 0.0, STIFFNESS, DAMPING, false, 0.0);
            }
            joint.handle = handle;
        } catch (RuntimeException e) {
            MeshCut.LOGGER.warn("Couldn't join two limbs of a body", e);
        }
    }

    private static Pose3d copy(dev.ryanhcode.sable.companion.math.Pose3dc pose) {
        Pose3d out = new Pose3d();
        out.position().set(pose.position());
        out.orientation().set(pose.orientation());
        out.rotationPoint().set(pose.rotationPoint());
        return out;
    }

    // ---------------------------------------------------------------- saving

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Joint joint : joints) {
            CompoundTag j = new CompoundTag();
            j.put("parent", joint.parent.save());
            j.put("child", joint.child.save());
            j.putDouble("fx", joint.frame.x);
            j.putDouble("fy", joint.frame.y);
            j.putDouble("fz", joint.frame.z);
            j.putDouble("fw", joint.frame.w);
            list.add(j);
        }
        tag.put("joints", list);
        return tag;
    }

    private static RagdollJoints load(CompoundTag tag, HolderLookup.Provider registries) {
        RagdollJoints data = new RagdollJoints();
        for (Tag t : tag.getList("joints", Tag.TAG_COMPOUND)) {
            CompoundTag j = (CompoundTag) t;
            data.joints.add(new Joint(End.load(j.getCompound("parent")), End.load(j.getCompound("child")),
                    new Quaterniond(j.getDouble("fx"), j.getDouble("fy"), j.getDouble("fz"), j.getDouble("fw"))));
        }
        return data;
    }
}
