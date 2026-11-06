package ml.mypals.meshcut.physics;

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

public final class RagdollJoints extends SavedData {
    private static final String NAME = "meshcut_joints";
    private static final double STIFFNESS = 20, DAMPING = 20;
    private static final double EPS = 1e-3;
    private static final int CHECK_EVERY = 10;
    private static final double APART = 0.25;
    private static final int CUT_GHOST_TICKS = 40;
    private static final int MAX_GHOSTS = 2048;

    private static final class End {
        UUID body;
        @Nullable
        final String limb;
        final Vector3d anchor;
        @Nullable
        Vector3d hold;
        final List<Vector3d> supports = new ArrayList<>();

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
            ListTag supportTag = new ListTag();
            for (Vector3d point : supports) {
                CompoundTag t = new CompoundTag();
                putVector(t, "point", point);
                supportTag.add(t);
            }
            tag.put("supports", supportTag);
            return tag;
        }

        static End load(CompoundTag tag) {
            Vector3d anchor = tag.contains("anchor") ? getVector(tag, "anchor")
                    : new Vector3d(tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z"));
            End end = new End(tag.getUUID("body"), tag.contains("limb") ? tag.getString("limb") : null, anchor,
                    tag.contains("hold") ? getVector(tag, "hold") : null);
            for (Tag t : tag.getList("supports", Tag.TAG_COMPOUND)) end.supports.add(getVector((CompoundTag) t, "point"));
            return end;
        }
    }

    private static final class Joint {
        final End parent, child;
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

    private static final class Ghost {
        final UUID a, b;
        final long until;
        @Nullable
        PhysicsConstraintHandle handle;

        Ghost(UUID a, UUID b, long until) {
            this.a = a;
            this.b = b;
            this.until = until;
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
    private final Map<UUID, Pose3d> touched = new HashMap<>();
    private final Map<UUID, Set<UUID>> madeFrom = new HashMap<>();
    private final Map<UUID, Pose3d> removed = new HashMap<>();
    private long ticks;

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

    public static void join(ServerLevel level, ServerSubLevel parent, @Nullable String parentLimb, ServerSubLevel child,
                            @Nullable String childLimb, Vec3 anchor) {
        Vector3d world = new Vector3d(anchor.x, anchor.y, anchor.z);
        Vector3d inParent = parent.logicalPose().transformPositionInverse(new Vector3d(world));
        Vector3d inChild = child.logicalPose().transformPositionInverse(new Vector3d(world));
        Quaterniond frame = new Quaterniond(parent.logicalPose().orientation()).conjugate().mul(child.logicalPose().orientation());
        Joint joint = new Joint(new End(parent.getUniqueId(), parentLimb, inParent, holdPoint(level, parent, parentLimb, inParent)),
                new End(child.getUniqueId(), childLimb, inChild, holdPoint(level, child, childLimb, inChild)), frame);
        supportPoints(level, parent, joint.parent);
        supportPoints(level, child, joint.child);
        RagdollJoints data = of(level);
        data.joints.add(joint);
        data.setDirty();
        data.connect(level, joint);
    }

    public static void ghostOverlapping(ServerLevel level, List<ServerSubLevel> limbs) {
        RagdollJoints data = of(level);
        for (int i = 0; i < limbs.size(); i++) {
            for (int j = i + 1; j < limbs.size(); j++) {
                data.ghostIfOverlapping(level, limbs.get(i), limbs.get(j), Long.MAX_VALUE);
            }
        }
    }

    public static void noteSplit(ServerLevel level, ServerSubLevel source, @Nullable ServerSubLevel made) {
        RagdollJoints data = of(level);
        UUID id = source.getUniqueId();
        if (data.joints.stream().anyMatch(j -> j.touches(id)) || data.ghosts.stream().anyMatch(g -> g.touches(id))) {
            data.touched.putIfAbsent(id, copy(source.logicalPose()));
        }
        if (made != null) {
            data.madeFrom.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(made.getUniqueId());
        }
    }

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
        if (joints.isEmpty() && ghosts.isEmpty() && madeFrom.isEmpty()) {
            touched.clear();
            madeFrom.clear();
            removed.clear();
            return;
        }
        boolean all = ++ticks % CHECK_EVERY == 0;
        Set<UUID> changed = new HashSet<>(touched.keySet());
        changed.addAll(removed.keySet());

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
            // the pieces cut off it (and one another), whose colliders reach right up to the cut: they would shove each
            // other away. They pass through each other until they have come apart, or for a moment at most.
            Set<UUID> cutFrom = new LinkedHashSet<>(entry.getValue());
            cutFrom.add(entry.getKey());
            long until = level.getGameTime() + CUT_GHOST_TICKS;
            for (UUID made : entry.getValue()) {
                if (container.getSubLevel(made) instanceof ServerSubLevel piece && !piece.isRemoved()) {
                    for (UUID p : partners) {
                        if (!p.equals(made) && container.getSubLevel(p) instanceof ServerSubLevel other && !other.isRemoved()) {
                            ghostIfOverlapping(level, piece, other, Long.MAX_VALUE);
                        }
                    }
                    for (UUID p : cutFrom) {
                        if (!p.equals(made) && container.getSubLevel(p) instanceof ServerSubLevel other && !other.isRemoved()) {
                            ghostIfOverlapping(level, piece, other, until);
                        }
                    }
                }
            }
        }

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
                    joint.drop();
                }
            }
        }

        if (all || !removed.isEmpty()) {
            for (Ghost ghost : new ArrayList<>(ghosts)) {
                SubLevel a = container.getSubLevel(ghost.a), b = container.getSubLevel(ghost.b);
                boolean gone = removed.containsKey(ghost.a) || removed.containsKey(ghost.b) || level.getGameTime() > ghost.until;
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

    private boolean follow(ServerLevel level, SubLevelContainer container, End end, End other) {
        SubLevel current = container.getSubLevel(end.body);
        boolean loaded = current instanceof ServerSubLevel sub && !sub.isRemoved();
        if (end.hold == null) {
            if (!loaded) {
                return true;
            }
            end.hold = holdPoint(level, (ServerSubLevel) current, end.limb, end.anchor);
            if (end.hold == null) {
                return false;
            }
            setDirty();
        }
        Pose3dc pose;
        if (loaded) {
            ServerSubLevel body = (ServerSubLevel) current;
            if (end.supports.isEmpty()) { supportPoints(level, body, end); setDirty(); }
            Vector3d surviving = end.supports.stream().filter(p -> holds(level, body, p, end.limb)).findFirst().orElse(null);
            if (surviving != null) {
                if (!end.hold.equals(surviving)) { end.hold.set(surviving); setDirty(); }
                return true;
            }
            pose = touched.containsKey(end.body) ? touched.get(end.body) : current.logicalPose();
        } else if (removed.containsKey(end.body)) {
            pose = removed.get(end.body);
        } else {
            return true;
        }
        Vector3d anchor = pose.transformPosition(new Vector3d(end.anchor));
        if (end.supports.isEmpty()) end.supports.add(new Vector3d(end.hold));
        for (UUID id : madeFrom.getOrDefault(end.body, Set.of())) {
            ServerSubLevel candidate = loaded(level, id);
            if (candidate == null || id.equals(other.body)) continue;
            for (Vector3d point : end.supports) {
                Vector3d world = pose.transformPosition(new Vector3d(point));
                Vector3d local = candidate.logicalPose().transformPositionInverse(world);
                if (!holds(level, candidate, local, end.limb)) continue;
                List<Vector3d> transformed = end.supports.stream().map(p -> candidate.logicalPose().transformPositionInverse(
                        pose.transformPosition(new Vector3d(p)))).toList();
                end.body = candidate.getUniqueId();
                end.hold.set(local);
                end.supports.clear(); end.supports.addAll(transformed);
                end.anchor.set(candidate.logicalPose().transformPositionInverse(anchor));
                setDirty();
                return true;
            }
        }
        return false;
    }

    private static void supportPoints(ServerLevel level, ServerSubLevel body, End end) {
        if (end.hold == null || !end.supports.isEmpty()) return;
        for (Vector3d point : JointSupport.samples(end.hold)) {
            if (holds(level, body, point, end.limb)) end.supports.add(point);
        }
    }

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
                        double[] target = {near.x - x, near.y - y, near.z - z};
                        Vector3d point = JointSupport.inset(b, target);
                        c.add(point);
                        if (!inside(be, be.getAppearance(), point.x, point.y, point.z)) {
                            continue;
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

    private void ghostIfOverlapping(ServerLevel level, ServerSubLevel a, ServerSubLevel b, long until) {
        UUID x = a.getUniqueId(), y = b.getUniqueId();
        if (ghosts.stream().anyMatch(g -> g.is(x, y))
                || joints.stream().anyMatch(j -> j.parent.body.equals(x) && j.child.body.equals(y) || j.parent.body.equals(y) && j.child.body.equals(x))
                || !worldBounds(a).intersects(worldBounds(b))) {
            return;
        }
        makeRoom();
        Ghost ghost = new Ghost(x, y, until);
        ghosts.add(ghost);
        setDirty();
        connect(level, ghost);
    }

    private void makeRoom() {
        if (ghosts.size() < MAX_GHOSTS) {
            return;
        }
        int victim = 0;
        for (int i = 0; i < ghosts.size(); i++) {
            if (ghosts.get(i).handle == null) {
                victim = i;
                break;
            }
        }
        ghosts.remove(victim).drop();
        setDirty();
    }

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
            g.putBoolean("cut", ghost.until != Long.MAX_VALUE);
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
            if (!g.getBoolean("cut")) {
                data.ghosts.add(new Ghost(g.getUUID("a"), g.getUUID("b"), Long.MAX_VALUE));
            }
        }
        return data;
    }
}
