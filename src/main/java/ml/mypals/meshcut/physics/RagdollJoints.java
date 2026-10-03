package ml.mypals.meshcut.physics;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelObserver;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.platform.SableEventPlatform;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.block.EntityAppearance;
import ml.mypals.meshcut.block.MimicBlockEntity;
import ml.mypals.meshcut.cut.CutPlane;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * What holds a cut-down body together, and keeps it from tearing itself apart.
 * <ul>
 *   <li><b>Joints</b>, as in sable-player-ragdoll: where a limb hung from its parent, a generic joint whose position
 *       is locked and whose rotation is pulled back towards how it was by a soft motor on each axis. The two limbs do
 *       not collide.</li>
 *   <li><b>Ghost pairs</b>: limbs (and pieces cut off them) that overlap one another do not collide either, until they
 *       have come apart. A model's parts overlap all the time, and two bodies that start inside each other are
 *       thrown apart with all the force the physics engine has.</li>
 * </ul>
 * Sable keeps constraints only while both bodies are loaded, so both are saved here and made again whenever they are.
 * <p>
 * <b>When a joint breaks.</b> Each end of a joint holds on to a point of its limb's material: the point of the limb
 * nearest the joint's pivot, a little inside it ({@link End#hold}). The end belongs to whatever piece that very point
 * is still part of, exactly (the limb's boxes, the kept side of every cut, not in any part cut out). A cut that
 * leaves the point on a piece cut off the limb moves the end to that piece; a cut through the point, a burn that takes
 * it, or a hand that breaks the fragment it is in leaves no piece holding it, and the joint is gone: the limb has been
 * cut off there. A piece that only comes near the point (the other side of a cut a hair away) does not count.
 */
public final class RagdollJoints extends SavedData {
    private static final String NAME = "meshcut_joints";
    /** How hard a joint pulls a limb back to how it was, and how much it damps it (as in the ragdoll mod). */
    private static final double STIFFNESS = 20, DAMPING = 20;
    /** How far inside the limb's material (in blocks) a joint's hold point is put, at most. */
    private static final double HOLD_DEPTH = 0.05;
    /** Slack (in blocks) when telling whether a point is in the material. */
    private static final double EPS = 1e-3;
    /** Every this many ticks, every joint checks that its limbs still hold on to it, and ghost pairs that came apart part. */
    private static final int CHECK_EVERY = 10;
    /** Ghost pairs whose bounds are further apart than this (in blocks) have come apart. */
    private static final double APART = 0.25;
    /** At most this many ghost pairs; each is a constraint the physics engine keeps. */
    private static final int MAX_GHOSTS = 2048;

    /** One end of a joint. */
    private static final class End {
        UUID body;
        @Nullable
        final String limb;
        /** Where the joint is, in the body's plot (block coordinates). */
        final Vector3d anchor;
        /** The point of the limb's material the end holds on to, in the body's plot; null until worked out. */
        @Nullable
        Vector3d hold;

        End(UUID body, @Nullable String limb, Vector3d anchor, @Nullable Vector3d hold) {
            this.body = body;
            this.limb = limb;
            this.anchor = anchor;
            this.hold = hold;
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("body", body);
            if (limb != null) {
                tag.putString("limb", limb);
            }
            putVector(tag, "anchor", anchor);
            if (hold != null) {
                putVector(tag, "hold", hold);
            }
            return tag;
        }

        static End load(CompoundTag tag) {
            // joints saved before ends held on to a point had the anchor as "x", "y", "z"
            Vector3d anchor = tag.contains("anchor") ? getVector(tag, "anchor")
                    : new Vector3d(tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z"));
            return new End(tag.getUUID("body"), tag.contains("limb") ? tag.getString("limb") : null, anchor,
                    tag.contains("hold") ? getVector(tag, "hold") : null);
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
            handle = RagdollJoints.drop(handle);
        }
    }

    /** Two bodies that do not collide until they have come apart. */
    private static final class Ghost {
        final UUID a, b;
        @Nullable
        PhysicsConstraintHandle handle;

        Ghost(UUID a, UUID b) {
            this.a = a;
            this.b = b;
        }

        boolean touches(UUID id) {
            return a.equals(id) || b.equals(id);
        }

        boolean is(UUID x, UUID y) {
            return a.equals(x) && b.equals(y) || a.equals(y) && b.equals(x);
        }

        void drop() {
            handle = RagdollJoints.drop(handle);
        }
    }

    private final List<Joint> joints = new ArrayList<>();
    private final List<Ghost> ghosts = new ArrayList<>();
    /** Sub-levels whose blocks changed (some left, or their mass did), with where they were just before. */
    private final Map<UUID, Pose3d> touched = new HashMap<>();
    /** Sub-levels made of blocks that left another one, by where they came from. */
    private final Map<UUID, Set<UUID>> madeFrom = new HashMap<>();
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
        Joint joint = new Joint(new End(parent.getUniqueId(), parentLimb, inParent, holdPoint(level, parent, parentLimb, inParent)),
                new End(child.getUniqueId(), childLimb, inChild, holdPoint(level, child, childLimb, inChild)), frame);
        RagdollJoints data = of(level);
        data.joints.add(joint);
        data.setDirty();
        data.connect(level, joint);
    }

    /** The limbs of a body just built: those that overlap do not collide until they have come apart. */
    public static void ghostOverlapping(ServerLevel level, List<ServerSubLevel> limbs) {
        RagdollJoints data = of(level);
        for (int i = 0; i < limbs.size(); i++) {
            for (int j = i + 1; j < limbs.size(); j++) {
                data.ghostIfOverlapping(level, limbs.get(i), limbs.get(j));
            }
        }
    }

    /**
     * The blocks of {@code source} changed: some left it for {@code made} (if not null), or its mass changed. Its
     * joints are looked at again, and the new piece does not collide with the rest of the body where it overlaps it.
     * Call before the blocks leave (with {@code made} null) and again once they have (with it).
     */
    public static void noteSplit(ServerLevel level, ServerSubLevel source, @Nullable ServerSubLevel made) {
        RagdollJoints data = of(level);
        UUID id = source.getUniqueId();
        if (data.joints.stream().noneMatch(j -> j.touches(id)) && data.ghosts.stream().noneMatch(g -> g.touches(id))
                && !data.madeFrom.containsKey(id)) {
            return; // not part of a body
        }
        data.touched.putIfAbsent(id, copy(source.logicalPose()));
        if (made != null) {
            data.madeFrom.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(made.getUniqueId());
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
            for (Ghost ghost : data.ghosts) {
                if (ghost.touches(id)) {
                    ghost.drop();
                    any = true;
                }
            }
            if (any && reason == SubLevelRemovalReason.REMOVED) {
                // its last piece may have gone to another sub-level that takes its joints over; see tick
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
        if (joints.isEmpty() && ghosts.isEmpty()) {
            touched.clear();
            madeFrom.clear();
            removed.clear();
            return;
        }
        boolean all = ++ticks % CHECK_EVERY == 0;
        Set<UUID> changed = new HashSet<>(touched.keySet());
        changed.addAll(removed.keySet());

        // 1. new pieces do not collide with what the piece they came off did not collide with (or was joined to)
        for (var entry : madeFrom.entrySet()) {
            Set<UUID> partners = new LinkedHashSet<>();
            for (Joint j : joints) {
                if (j.parent.body.equals(entry.getKey())) {
                    partners.add(j.child.body);
                } else if (j.child.body.equals(entry.getKey())) {
                    partners.add(j.parent.body);
                }
            }
            for (Ghost g : ghosts) {
                if (g.touches(entry.getKey())) {
                    partners.add(g.a.equals(entry.getKey()) ? g.b : g.a);
                }
            }
            partners.addAll(entry.getValue()); // pieces of the same cut may overlap one another too
            for (UUID made : entry.getValue()) {
                if (container.getSubLevel(made) instanceof ServerSubLevel piece && !piece.isRemoved()) {
                    for (UUID p : partners) {
                        if (!p.equals(made) && container.getSubLevel(p) instanceof ServerSubLevel other && !other.isRemoved()) {
                            ghostIfOverlapping(level, piece, other);
                        }
                    }
                }
            }
        }

        // 2. joints follow the material they hold on to, or break
        if (all || !changed.isEmpty()) {
            for (Joint joint : new ArrayList<>(joints)) {
                boolean involved = changed.contains(joint.parent.body) || changed.contains(joint.child.body);
                if (!all && !involved) {
                    continue;
                }
                boolean kept = follow(level, container, joint.parent, joint.child) && follow(level, container, joint.child, joint.parent);
                if (!kept) {
                    joint.drop();
                    joints.remove(joint);
                    setDirty();
                } else if (involved) {
                    joint.drop(); // made again below: the ends may have moved, or the bodies' centres of mass
                }
            }
        }

        // 3. ghost pairs part once their bodies have come apart, or one of them is gone
        if (all || !removed.isEmpty()) {
            for (Ghost ghost : new ArrayList<>(ghosts)) {
                SubLevel a = container.getSubLevel(ghost.a), b = container.getSubLevel(ghost.b);
                boolean gone = removed.containsKey(ghost.a) || removed.containsKey(ghost.b);
                boolean apart = a instanceof ServerSubLevel sa && !sa.isRemoved() && b instanceof ServerSubLevel sb && !sb.isRemoved()
                        && !grown(worldBounds(sa), APART).intersects(worldBounds(sb));
                if (gone || apart) {
                    ghost.drop();
                    ghosts.remove(ghost);
                    setDirty();
                }
            }
        }
        touched.clear();
        madeFrom.clear();
        removed.clear();

        // 4. whatever is not in the physics engine (both bodies loaded) goes in
        for (Joint joint : joints) {
            if (joint.handle == null || !joint.handle.isValid()) {
                connect(level, joint);
            }
        }
        for (Ghost ghost : ghosts) {
            if (ghost.handle == null || !ghost.handle.isValid()) {
                connect(level, ghost);
            }
        }
    }

    /**
     * Makes sure the end still holds on to its point of material: if its body no longer has it, the piece that does
     * takes the end over.
     *
     * @return false if no piece has it any more (the limb was cut off there)
     */
    private boolean follow(ServerLevel level, SubLevelContainer container, End end, End other) {
        SubLevel current = container.getSubLevel(end.body);
        boolean loaded = current instanceof ServerSubLevel sub && !sub.isRemoved();
        if (end.hold == null) {
            if (!loaded) {
                return true; // worked out once it is loaded
            }
            end.hold = holdPoint(level, (ServerSubLevel) current, end.limb, end.anchor);
            if (end.hold == null) {
                return false; // nothing of the limb is left
            }
            setDirty();
        }
        Pose3dc pose;
        if (loaded) {
            if (holds(level, (ServerSubLevel) current, end.hold, end.limb)) {
                return true;
            }
            pose = touched.containsKey(end.body) ? touched.get(end.body) : current.logicalPose();
        } else if (removed.containsKey(end.body)) {
            pose = removed.get(end.body);
        } else {
            return true; // not loaded: it is looked at when it is
        }
        Vector3d hold = pose.transformPosition(new Vector3d(end.hold));
        Vector3d anchor = pose.transformPosition(new Vector3d(end.anchor));
        double r = 0.5;
        for (SubLevel s : Sable.HELPER.getAllIntersecting(level, new BoundingBox3d(hold.x - r, hold.y - r, hold.z - r,
                hold.x + r, hold.y + r, hold.z + r))) {
            if (!(s instanceof ServerSubLevel candidate) || candidate.isRemoved() || candidate.getUniqueId().equals(end.body)
                    || candidate.getUniqueId().equals(other.body)) {
                continue;
            }
            Vector3d local = candidate.logicalPose().transformPositionInverse(new Vector3d(hold));
            if (holds(level, candidate, local, end.limb)) {
                end.body = candidate.getUniqueId();
                end.hold.set(local);
                end.anchor.set(candidate.logicalPose().transformPositionInverse(anchor));
                setDirty();
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the point (plot space) is in the material of the limb in the sub-level: in one of its blocks' boxes,
     * on the kept side of every cut of that block, and in no part cut out of it.
     */
    private static boolean holds(ServerLevel level, ServerSubLevel sub, Vector3d point, @Nullable String limb) {
        int bx = (int) Math.floor(point.x), by = (int) Math.floor(point.y), bz = (int) Math.floor(point.z);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos p = new BlockPos(bx + dx, by + dy, bz + dz);
                    double x = point.x - p.getX(), y = point.y - p.getY(), z = point.z - p.getZ();
                    if (x < -EPS || y < -EPS || z < -EPS || x > 1 + EPS || y > 1 + EPS || z > 1 + EPS
                            || !sub.getPlot().getBoundingBox().contains(p.getX(), p.getY(), p.getZ())
                            || !(level.getBlockEntity(p) instanceof MimicBlockEntity be)) {
                        continue;
                    }
                    EntityAppearance appearance = be.getAppearance();
                    if (appearance != null && Objects.equals(appearance.part(), limb) && inside(be, appearance, x, y, z)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean inside(MimicBlockEntity be, EntityAppearance appearance, double x, double y, double z) {
        boolean inBox = false;
        for (double[] b : appearance.boxes()) {
            if (x >= b[0] - EPS && x <= b[3] + EPS && y >= b[1] - EPS && y <= b[4] + EPS && z >= b[2] - EPS && z <= b[5] + EPS) {
                inBox = true;
                break;
            }
        }
        if (!inBox) {
            return false;
        }
        for (CutPlane plane : be.getPlanes()) {
            if (plane.signedDistance(x, y, z) < -EPS) {
                return false;
            }
        }
        for (List<CutPlane> region : be.getExcluded()) {
            boolean in = true;
            for (CutPlane plane : region) {
                if (plane.signedDistance(x, y, z) <= EPS) {
                    in = false;
                    break;
                }
            }
            if (in) {
                return false;
            }
        }
        return true;
    }

    /**
     * The point of the limb's material nearest {@code near} (plot space), moved a little into it: what an end of a
     * joint at {@code near} holds on to. Null if the limb has no material in the sub-level.
     */
    @Nullable
    private static Vector3d holdPoint(ServerLevel level, ServerSubLevel sub, @Nullable String limb, Vector3d near) {
        BoundingBox3ic bounds = sub.getPlot().getBoundingBox();
        Vector3d best = null;
        double bestDistance = Double.MAX_VALUE;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    p.set(x, y, z);
                    if (!(level.getBlockEntity(p) instanceof MimicBlockEntity be) || be.getAppearance() == null
                            || !Objects.equals(be.getAppearance().part(), limb)) {
                        continue;
                    }
                    for (double[] b : be.getAppearance().boxes()) {
                        Vector3d c = new Vector3d(x, y, z);
                        double[] point = new double[3];
                        double[] target = {near.x - x, near.y - y, near.z - z};
                        for (int a = 0; a < 3; a++) {
                            double depth = Math.min(HOLD_DEPTH, (b[a + 3] - b[a]) / 2);
                            point[a] = Math.max(b[a] + depth, Math.min(b[a + 3] - depth, target[a]));
                        }
                        c.add(point[0], point[1], point[2]);
                        if (!inside(be, be.getAppearance(), point[0], point[1], point[2])) {
                            continue; // already cut away there
                        }
                        double d = c.distanceSquared(near);
                        if (d < bestDistance) {
                            bestDistance = d;
                            best = c;
                        }
                    }
                }
            }
        }
        return best;
    }

    private void ghostIfOverlapping(ServerLevel level, ServerSubLevel a, ServerSubLevel b) {
        UUID x = a.getUniqueId(), y = b.getUniqueId();
        if (ghosts.size() >= MAX_GHOSTS || ghosts.stream().anyMatch(g -> g.is(x, y))
                || joints.stream().anyMatch(j -> j.parent.body.equals(x) && j.child.body.equals(y) || j.parent.body.equals(y) && j.child.body.equals(x))
                || !worldBounds(a).intersects(worldBounds(b))) {
            return;
        }
        Ghost ghost = new Ghost(x, y);
        ghosts.add(ghost);
        setDirty();
        connect(level, ghost);
    }

    /** Where the sub-level's blocks are in the world, roughly (its plot box, turned and moved as the body is). */
    private static BoundingBox3d worldBounds(ServerSubLevel sub) {
        BoundingBox3ic b = sub.getPlot().getBoundingBox();
        Pose3dc pose = sub.logicalPose();
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (int c = 0; c < 8; c++) {
            Vector3d w = pose.transformPosition(new Vector3d((c & 1) == 0 ? b.minX() : b.maxX() + 1,
                    (c & 2) == 0 ? b.minY() : b.maxY() + 1, (c & 4) == 0 ? b.minZ() : b.maxZ() + 1));
            minX = Math.min(minX, w.x);
            minY = Math.min(minY, w.y);
            minZ = Math.min(minZ, w.z);
            maxX = Math.max(maxX, w.x);
            maxY = Math.max(maxY, w.y);
            maxZ = Math.max(maxZ, w.z);
        }
        return new BoundingBox3d(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static BoundingBox3d grown(BoundingBox3d b, double by) {
        return new BoundingBox3d(b.minX() - by, b.minY() - by, b.minZ() - by, b.maxX() + by, b.maxY() + by, b.maxZ() + by);
    }

    // ---------------------------------------------------------------- the physics engine

    /** Makes the joint in the physics engine, if both its limbs are there. */
    private void connect(ServerLevel level, Joint joint) {
        ServerSubLevel parent = loaded(level, joint.parent.body), child = loaded(level, joint.child.body);
        if (parent == null || child == null) {
            return;
        }
        PhysicsConstraintHandle handle = add(level, parent, child, new GenericConstraintConfiguration(
                new Vector3d(joint.parent.anchor), new Vector3d(joint.child.anchor), new Quaterniond(joint.frame), new Quaterniond(),
                Set.of(ConstraintJointAxis.LINEAR_X, ConstraintJointAxis.LINEAR_Y, ConstraintJointAxis.LINEAR_Z)));
        if (handle != null) {
            for (ConstraintJointAxis axis : ConstraintJointAxis.ANGULAR) {
                handle.setMotor(axis, 0.0, STIFFNESS, DAMPING, false, 0.0);
            }
        }
        joint.handle = handle;
    }

    /** A constraint that locks nothing: it is only there so the two do not collide. */
    private void connect(ServerLevel level, Ghost ghost) {
        ServerSubLevel a = loaded(level, ghost.a), b = loaded(level, ghost.b);
        if (a == null || b == null) {
            return;
        }
        ghost.handle = add(level, a, b, new GenericConstraintConfiguration(new Vector3d(a.logicalPose().rotationPoint()),
                new Vector3d(b.logicalPose().rotationPoint()), new Quaterniond(), new Quaterniond(), Set.of()));
    }

    @Nullable
    private static PhysicsConstraintHandle add(ServerLevel level, ServerSubLevel a, ServerSubLevel b,
                                               GenericConstraintConfiguration config) {
        var container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return null;
        }
        try {
            PhysicsConstraintHandle handle = container.physicsSystem().getPipeline().addConstraint(a, b, config);
            if (handle != null) {
                handle.setContactsEnabled(false);
            }
            return handle;
        } catch (RuntimeException e) {
            MeshCut.LOGGER.warn("Couldn't join two parts of a body", e);
            return null;
        }
    }

    @Nullable
    private static ServerSubLevel loaded(ServerLevel level, UUID id) {
        var container = SubLevelContainer.getContainer(level);
        return container != null && container.getSubLevel(id) instanceof ServerSubLevel sub && !sub.isRemoved() ? sub : null;
    }

    @Nullable
    private static PhysicsConstraintHandle drop(@Nullable PhysicsConstraintHandle handle) {
        if (handle != null) {
            try {
                if (handle.isValid()) {
                    handle.remove();
                }
            } catch (RuntimeException e) {
                MeshCut.LOGGER.debug("Couldn't remove a constraint", e);
            }
        }
        return null;
    }

    private static Pose3d copy(Pose3dc pose) {
        Pose3d out = new Pose3d();
        out.position().set(pose.position());
        out.orientation().set(pose.orientation());
        out.rotationPoint().set(pose.rotationPoint());
        return out;
    }

    // ---------------------------------------------------------------- saving

    private static void putVector(CompoundTag tag, String key, Vector3d v) {
        CompoundTag t = new CompoundTag();
        t.putDouble("x", v.x);
        t.putDouble("y", v.y);
        t.putDouble("z", v.z);
        tag.put(key, t);
    }

    private static Vector3d getVector(CompoundTag tag, String key) {
        CompoundTag t = tag.getCompound(key);
        return new Vector3d(t.getDouble("x"), t.getDouble("y"), t.getDouble("z"));
    }

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
        ListTag pairs = new ListTag();
        for (Ghost ghost : ghosts) {
            CompoundTag g = new CompoundTag();
            g.putUUID("a", ghost.a);
            g.putUUID("b", ghost.b);
            pairs.add(g);
        }
        tag.put("ghosts", pairs);
        return tag;
    }

    private static RagdollJoints load(CompoundTag tag, HolderLookup.Provider registries) {
        RagdollJoints data = new RagdollJoints();
        for (Tag t : tag.getList("joints", Tag.TAG_COMPOUND)) {
            CompoundTag j = (CompoundTag) t;
            data.joints.add(new Joint(End.load(j.getCompound("parent")), End.load(j.getCompound("child")),
                    new Quaterniond(j.getDouble("fx"), j.getDouble("fy"), j.getDouble("fz"), j.getDouble("fw"))));
        }
        for (Tag t : tag.getList("ghosts", Tag.TAG_COMPOUND)) {
            CompoundTag g = (CompoundTag) t;
            data.ghosts.add(new Ghost(g.getUUID("a"), g.getUUID("b")));
        }
        return data;
    }
}
