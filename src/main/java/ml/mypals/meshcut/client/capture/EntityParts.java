package ml.mypals.meshcut.client.capture;

import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.block.EntityAppearance;
import ml.mypals.meshcut.cut.BodySpec;
import ml.mypals.meshcut.geom.MeshPoly;
import ml.mypals.meshcut.mixin.ModelPartAccessor;
import ml.mypals.meshcut.network.EntityPartsNet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class EntityParts {
    private static final double MIN_LIMB_VOLUME = 8;
    private static final double ATTACHMENT_VOLUME = 12;
    private static final int MAX_LIMBS = 24;
    private static final double SAME_PIVOT = 0.05;
    private static final double MIN_THICKNESS = 0.02;

    private enum Role {
        HEAD, TORSO, ARM, LEG, WING, TAIL, ATTACHMENT, OTHER;

        boolean limb() {
            return this != ATTACHMENT && this != OTHER;
        }
    }

    public static void answer(EntityPartsNet.PartsRequest request) {
        List<BodySpec> bodies;
        try {
            bodies = ModelCapturer.captureEntityParts(request.look()).bodies();
        } catch (RuntimeException e) {
            MeshCut.LOGGER.warn("Couldn't take a {} apart for the server", request.look().type(), e);
            return;
        }
        PacketDistributor.sendToServer(new EntityPartsNet.PartsReply(request.id(), bodies));
    }

    private static final class Limb {
        final String name;
        final Role role;
        final Matrix4f pose;
        int parent;
        double volume;
        int mergedInto = -1;
        final Quaternionf rotation = new Quaternionf();
        final Vector3f origin = new Vector3f();
        final Map<Object, double[]> boxes = new LinkedHashMap<>();
        final Map<RenderType, List<MeshPoly>> faces = new LinkedHashMap<>();

        Limb(String name, Role role, Matrix4f pose, int parent) {
            this.name = name;
            this.role = role;
            this.pose = pose;
            this.parent = parent;
        }
    }

    static ModelCapturer.EntityCapture split(Entity entity, EntityAppearance.Look look,
                                             Map<RenderType, List<RecordingBufferSource.Tagged>> faces,
                                             List<PartTags.DrawnPart> drawn, ModelCapturer.CapturedMesh whole) {
        Map<ModelPart, String> names = new IdentityHashMap<>();
        Set<ModelPart> base = Collections.newSetFromMap(new IdentityHashMap<>());
        if (entity instanceof LivingEntity) {
            findNames(entity, names, base);
        }
        for (PartTags.DrawnPart d : drawn) {
            childNames(d.part(), names);
        }
        Map<ModelPart, Set<ModelPart.Cube>> visibleCubes = new IdentityHashMap<>();
        for (List<RecordingBufferSource.Tagged> list : faces.values()) {
            for (RecordingBufferSource.Tagged t : list) {
                if (t.tag() != null && t.tag().part() != null && t.tag().cube() != null) {
                    visibleCubes.computeIfAbsent(t.tag().part().part(), k -> Collections.newSetFromMap(new IdentityHashMap<>()))
                            .add(t.tag().cube());
                }
            }
        }

        List<Limb> limbs = new ArrayList<>();
        Map<PartTags.DrawnPart, Integer> limbOf = new IdentityHashMap<>();
        Map<ModelPart, List<PartTags.DrawnPart>> draws = new IdentityHashMap<>();
        for (PartTags.DrawnPart d : drawn) {
            ModelPart part = d.part();
            if (!base.contains(part)) {
                continue;
            }
            List<PartTags.DrawnPart> earlier = draws.computeIfAbsent(part, k -> new ArrayList<>());
            PartTags.DrawnPart same = null;
            for (PartTags.DrawnPart e : earlier) {
                if (e.pose().equals(d.pose(), 1e-4f)) {
                    same = e;
                    break;
                }
            }
            if (same != null) {
                if (limbOf.containsKey(same)) {
                    limbOf.put(d, limbOf.get(same));
                }
                continue;
            }
            earlier.add(d);
            String name = names.getOrDefault(part, "part");
            Role role = roleOf(name);
            Set<ModelPart.Cube> cubes = visibleCubes.getOrDefault(part, Set.of());
            double volume = volumeOf(cubes);
            if (volume <= 0) {
                continue;
            }
            int parent = ancestorLimb(d, limbOf);
            boolean attachment = role == Role.ATTACHMENT || cubes.size() == 1 && volume <= ATTACHMENT_VOLUME;
            int join;
            if (attachment) {
                join = parent >= 0 ? parent : samePivot(d.pose(), limbs);
            } else if (role.limb()) {
                join = parent >= 0 && limbs.get(parent).role == role ? parent : -1;
            } else {
                join = parent < 0 ? samePivot(d.pose(), limbs) : -1;
            }
            if (join >= 0) {
                limbOf.put(d, join);
                limbs.get(join).volume += volume;
            } else {
                limbOf.put(d, limbs.size());
                Limb limb = new Limb(name, role, d.pose(), parent);
                limb.volume = volume;
                limbs.add(limb);
            }
        }
        if (limbs.isEmpty()) {
            ModelCapturer.EntityCapture byPose = entity instanceof LivingEntity ? byPose(entity, faces, drawn, whole) : null;
            return byPose != null ? byPose : single(entity, faces, drawn, whole);
        }
        return finish(entity, faces, drawn, whole, limbs, (all, loose) -> {
            Map<String, Integer> byName = new HashMap<>();
            for (var e : limbOf.entrySet()) {
                String name = names.get(e.getKey().part());
                if (name != null) {
                    byName.putIfAbsent(name, e.getValue());
                }
            }
            for (var entry : faces.entrySet()) {
                for (RecordingBufferSource.Tagged t : entry.getValue()) {
                    int li = t.tag() == null || t.tag().part() == null ? -1 : limbFor(t.tag(), limbOf, byName, names, drawn);
                    if (li < 0) {
                        loose.add(new Object[]{entry.getKey(), t.poly()});
                    } else {
                        add(all.get(resolve(all, li)), entry.getKey(), t.poly(), t.tag().part());
                    }
                }
            }
        });
    }

    @Nullable
    private static ModelCapturer.EntityCapture byPose(Entity entity, Map<RenderType, List<RecordingBufferSource.Tagged>> faces,
                                                      List<PartTags.DrawnPart> drawn, ModelCapturer.CapturedMesh whole) {
        Map<PartTags.PoseNode, List<MeshPoly>> byNode = new IdentityHashMap<>();
        for (List<RecordingBufferSource.Tagged> list : faces.values()) {
            for (RecordingBufferSource.Tagged t : list) {
                if (t.tag() != null && t.tag().node() != null && t.tag().node().pose != null) {
                    byNode.computeIfAbsent(t.tag().node(), k -> new ArrayList<>()).add(t.poly());
                }
            }
        }
        if (byNode.size() < 2) {
            return null;
        }
        if (byNode.keySet().stream().anyMatch(n -> boneOf(n) != null)) {
            Map<PartTags.PoseNode, List<MeshPoly>> byBone = new IdentityHashMap<>();
            byNode.forEach((node, polys) -> {
                PartTags.PoseNode bone = boneOf(node);
                if (bone != null) {
                    byBone.computeIfAbsent(bone, k -> new ArrayList<>()).addAll(polys);
                }
            });
            byNode = byBone;
            MeshCut.LOGGER.debug("Taking an entity apart by its {} drawn bones", byNode.size());
        }
        List<PartTags.PoseNode> order = new ArrayList<>(byNode.keySet());
        order.sort(java.util.Comparator.comparingInt(n -> n.index));

        List<Limb> limbs = new ArrayList<>();
        Map<PartTags.PoseNode, Integer> limbOf = new IdentityHashMap<>();
        Map<Object, List<PartTags.PoseNode>> draws = new HashMap<>();
        for (PartTags.PoseNode n : order) {
            Object key = n.owner != null ? n.owner : n.name != null ? n.name : n;
            List<PartTags.PoseNode> earlier = draws.computeIfAbsent(key, k -> new ArrayList<>());
            PartTags.PoseNode same = null;
            for (PartTags.PoseNode e : earlier) {
                if (e.pose.equals(n.pose, 1e-4f)) {
                    same = e;
                    break;
                }
            }
            if (same != null) {
                if (limbOf.containsKey(same)) {
                    limbOf.put(n, limbOf.get(same));
                }
                continue;
            }
            earlier.add(n);
            if (!usable(n.pose)) {
                continue;
            }
            double volume = volumeIn(n.pose, byNode.get(n));
            if (!(volume > 0) || !Double.isFinite(volume)) {
                continue;
            }
            String name = n.name != null ? n.name : "part";
            Role role = n.name != null ? roleOf(n.name) : Role.OTHER;
            int parent = -1;
            for (PartTags.PoseNode p = n.parent; p != null && parent < 0; p = p.parent) {
                parent = limbOf.getOrDefault(p, -1);
            }
            boolean attachment = role == Role.ATTACHMENT || volume <= ATTACHMENT_VOLUME;
            int join;
            if (attachment) {
                join = parent >= 0 ? parent : samePivot(n.pose, limbs);
            } else if (role.limb()) {
                join = parent >= 0 && limbs.get(parent).role == role ? parent : -1;
            } else {
                join = parent < 0 ? samePivot(n.pose, limbs) : -1;
            }
            if (join >= 0) {
                limbOf.put(n, join);
                limbs.get(join).volume += volume;
            } else {
                limbOf.put(n, limbs.size());
                Limb limb = new Limb(name, role, n.pose, parent);
                limb.volume = volume;
                limbs.add(limb);
            }
        }
        if (limbs.size() < 2) {
            return null;
        }
        return finish(entity, faces, drawn, whole, limbs, (all, loose) -> {
            for (var entry : faces.entrySet()) {
                for (RecordingBufferSource.Tagged t : entry.getValue()) {
                    Integer li = null;
                    for (PartTags.PoseNode n = t.tag() == null ? null : t.tag().node(); n != null && li == null; n = n.parent) {
                        li = limbOf.get(n);
                    }
                    if (li == null) {
                        loose.add(new Object[]{entry.getKey(), t.poly()});
                    } else {
                        add(all.get(resolve(all, li)), entry.getKey(), t.poly(), t.tag().node());
                    }
                }
            }
        });
    }

    @Nullable
    private static PartTags.PoseNode boneOf(PartTags.PoseNode node) {
        PartTags.PoseNode bone = node;
        while (bone != null && !bone.hinged) {
            bone = bone.parent;
        }
        return bone;
    }

    private static double volumeIn(Matrix4f pose, List<MeshPoly> polys) {
        Matrix4f inverse = new Matrix4f(pose).invert();
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        Vector3f p = new Vector3f();
        for (MeshPoly poly : polys) {
            float[] v = poly.verts();
            for (int i = 0; i < poly.vertexCount(); i++) {
                inverse.transformPosition(p.set(v[i * MeshPoly.STRIDE], v[i * MeshPoly.STRIDE + 1], v[i * MeshPoly.STRIDE + 2]));
                min[0] = Math.min(min[0], p.x);
                min[1] = Math.min(min[1], p.y);
                min[2] = Math.min(min[2], p.z);
                max[0] = Math.max(max[0], p.x);
                max[1] = Math.max(max[1], p.y);
                max[2] = Math.max(max[2], p.z);
            }
        }
        double scale = Math.cbrt(Math.abs(new Matrix3f(pose).determinant()));
        double v = 1;
        for (int a = 0; a < 3; a++) {
            v *= Math.max(max[a] - min[a], 1 / 16.0 / Math.max(scale, 1e-6)) * scale * 16;
        }
        return v;
    }

    private static Object boxKey(RecordingBufferSource.Tagged t, Limb limb) {
        if (t.tag() == null) {
            return limb;
        }
        return t.tag().part() != null ? t.tag().part() : t.tag().node() != null ? t.tag().node() : limb;
    }

    private interface Sorter {
        void sort(List<Limb> limbs, List<Object[]> loose);
    }

    private static ModelCapturer.EntityCapture finish(Entity entity, Map<RenderType, List<RecordingBufferSource.Tagged>> faces,
                                                      List<PartTags.DrawnPart> drawn, ModelCapturer.CapturedMesh whole,
                                                      List<Limb> limbs, Sorter sorter) {
        int torso = torsoOf(limbs);
        limbs.get(torso).parent = -1;
        for (int i = 0; i < limbs.size(); i++) {
            if (i != torso && limbs.get(i).volume < MIN_LIMB_VOLUME) {
                merge(limbs, i, parentOf(limbs, i, torso));
            }
        }
        while (alive(limbs) > MAX_LIMBS) {
            int smallest = -1;
            for (int i = 0; i < limbs.size(); i++) {
                if (i != torso && limbs.get(i).mergedInto < 0 && (smallest < 0 || limbs.get(i).volume < limbs.get(smallest).volume)) {
                    smallest = i;
                }
            }
            merge(limbs, smallest, parentOf(limbs, smallest, torso));
        }

        for (Limb limb : limbs) {
            frameOf(limb.pose, limb.rotation, limb.origin);
        }

        List<Object[]> loose = new ArrayList<>();
        sorter.sort(limbs, loose);
        for (Object[] f : loose) {
            MeshPoly poly = (MeshPoly) f[1];
            Limb limb = nearest(limbs, poly);
            add(limb, (RenderType) f[0], poly, limb);
        }

        for (int i = 0; i < limbs.size(); i++) {
            Limb limb = limbs.get(i);
            if (i != torso && limb.mergedInto < 0 && !limb.boxes.isEmpty() && !sane(limb)) {
                MeshCut.LOGGER.warn("Limb {} of {} is not part of the visible body (frame {} {}); left out", limb.name,
                        entity.getType(), limb.origin, limb.rotation);
                limb.boxes.clear();
                limb.faces.clear();
                limb.mergedInto = torso;
            }
        }

        attach(limbs, torso);
        if (entity instanceof net.minecraft.world.entity.player.Player) {
            for (int i = 0; i < limbs.size(); i++) {
                Limb limb = limbs.get(i);
                if (i != torso && limb.mergedInto < 0
                        && (limb.role == Role.ARM || limb.role == Role.LEG || limb.role == Role.HEAD)) limb.parent = torso;
            }
        }

        List<Integer> alive = new ArrayList<>();
        Map<Integer, Integer> index = new HashMap<>();
        for (int i = 0; i < limbs.size(); i++) {
            if (limbs.get(i).mergedInto < 0 && !limbs.get(i).boxes.isEmpty()) {
                index.put(i, alive.size());
                alive.add(i);
            }
        }
        if (alive.isEmpty()) {
            return single(entity, faces, drawn, whole);
        }
        Set<String> used = new HashSet<>();
        List<Limb> kept = new ArrayList<>();
        List<String> keptNames = new ArrayList<>();
        List<Integer> keptParents = new ArrayList<>();
        Map<String, ModelCapturer.CapturedMesh> meshes = new LinkedHashMap<>();
        for (int i : alive) {
            Limb limb = limbs.get(i);
            String name = limb.name;
            for (int k = 2; !used.add(name); k++) {
                name = limb.name + "#" + k;
            }
            int parent = -1;
            for (int p = parentOf(limbs, i, torso); p >= 0; p = parentOf(limbs, p, torso)) {
                if (index.containsKey(p)) {
                    parent = index.get(p);
                    break;
                }
            }
            kept.add(limb);
            keptNames.add(name);
            keptParents.add(parent);
            meshes.put(name, new ModelCapturer.CapturedMesh(limb.faces, ModelCapturer.opaqueOf(limb.faces), whole.capUv()));
        }
        return new ModelCapturer.EntityCapture(whole, meshes, specs(kept, keptNames, keptParents));
    }

    private static List<BodySpec> specs(List<Limb> limbs, List<String> names, List<Integer> parents) {
        List<BodySpec> out = new ArrayList<>(limbs.size());
        for (int i = 0; i < limbs.size(); i++) {
            out.add(spec(names.get(i), limbs.get(i), parents.get(i)));
        }
        while (out.stream().mapToInt(b -> b.boxes().size()).sum() > BodySpec.MAX_BOXES) {
            int most = 0;
            for (int i = 1; i < out.size(); i++) {
                if (out.get(i).boxes().size() > out.get(most).boxes().size()) {
                    most = i;
                }
            }
            BodySpec b = out.get(most);
            int excess = out.stream().mapToInt(x -> x.boxes().size()).sum() - BodySpec.MAX_BOXES;
            List<double[]> fewer = merged(new ArrayList<>(b.boxes()), Math.max(1, b.boxes().size() - excess));
            out.set(most, new BodySpec(b.name(), b.rotation(), b.origin(), fewer, b.parent(), b.anchor()));
        }
        while (totalCells(out) > ml.mypals.meshcut.cut.EntityCutting.MAX_CELLS) {
            int canMerge = -1;
            for (int i = 0; i < out.size(); i++) {
                if (out.get(i).boxes().size() > 1) {
                    if (canMerge < 0 || out.get(i).boxes().size() > out.get(canMerge).boxes().size()) {
                        canMerge = i;
                    }
                }
            }
            if (canMerge < 0) break;
            BodySpec b = out.get(canMerge);
            List<double[]> fewer = merged(new ArrayList<>(b.boxes()), Math.max(1, b.boxes().size() / 2));
            out.set(canMerge, new BodySpec(b.name(), b.rotation(), b.origin(), fewer, b.parent(), b.anchor()));
        }
        return out;
    }

    private static long totalCells(List<BodySpec> specs) {
        long cells = 0;
        for (BodySpec b : specs) {
            for (double[] box : b.boxes()) {
                cells += (long) (Math.floor(box[3]) - Math.floor(box[0]) + 1)
                        * (long) (Math.floor(box[4]) - Math.floor(box[1]) + 1)
                        * (long) (Math.floor(box[5]) - Math.floor(box[2]) + 1);
            }
        }
        return cells;
    }

    private static List<double[]> merged(List<double[]> boxes, int limit) {
        while (boxes.size() > limit) {
            int bestA = 0, bestB = 1;
            double bestWaste = Double.MAX_VALUE;
            for (int a = 0; a < boxes.size(); a++) {
                for (int b = a + 1; b < boxes.size(); b++) {
                    double[] u = boxes.get(a).clone();
                    grow(u, boxes.get(b));
                    double waste = volume(u) - volume(boxes.get(a)) - volume(boxes.get(b));
                    if (waste < bestWaste) {
                        bestWaste = waste;
                        bestA = a;
                        bestB = b;
                    }
                }
            }
            double[] u = boxes.get(bestA).clone();
            grow(u, boxes.get(bestB));
            boxes.set(bestA, u);
            boxes.remove(bestB);
        }
        return boxes;
    }

    private static double volume(double[] b) {
        return Math.max(0, b[3] - b[0]) * Math.max(0, b[4] - b[1]) * Math.max(0, b[5] - b[2]);
    }

    private static ModelCapturer.EntityCapture single(Entity entity, Map<RenderType, List<RecordingBufferSource.Tagged>> faces,
                                                     List<PartTags.DrawnPart> drawn, ModelCapturer.CapturedMesh whole) {
        PartTags.DrawnPart biggest = null;
        double most = 0;
        Map<ModelPart, Set<ModelPart.Cube>> cubes = new IdentityHashMap<>();
        for (List<RecordingBufferSource.Tagged> list : faces.values()) {
            for (RecordingBufferSource.Tagged t : list) {
                if (t.tag() != null && t.tag().part() != null && t.tag().cube() != null) {
                    cubes.computeIfAbsent(t.tag().part().part(), k -> Collections.newSetFromMap(new IdentityHashMap<>())).add(t.tag().cube());
                }
            }
        }
        for (PartTags.DrawnPart d : drawn) {
            double v = volumeOf(cubes.getOrDefault(d.part(), Set.of()));
            if (v > most) {
                most = v;
                biggest = d;
            }
        }
        Limb limb = new Limb("all", Role.TORSO, biggest == null ? new Matrix4f() : biggest.pose(), -1);
        if (biggest != null) {
            frameOf(biggest.pose(), limb.rotation, limb.origin);
        }
        limb.origin.zero();
        for (var entry : faces.entrySet()) {
            for (RecordingBufferSource.Tagged t : entry.getValue()) {
                add(limb, entry.getKey(), t.poly(), boxKey(t, limb));
            }
        }
        if (limb.boxes.isEmpty()) {
            return new ModelCapturer.EntityCapture(whole, Map.of(), List.of());
        }
        Map<String, ModelCapturer.CapturedMesh> meshes = Map.of(limb.name,
                new ModelCapturer.CapturedMesh(limb.faces, ModelCapturer.opaqueOf(limb.faces), whole.capUv()));
        return new ModelCapturer.EntityCapture(whole, meshes, specs(List.of(limb), List.of(limb.name), List.of(-1)));
    }

    private static BodySpec spec(String name, Limb limb, int parent) {
        List<double[]> boxes = new ArrayList<>(limb.boxes.values());
        List<double[]> out = new ArrayList<>(boxes.size());
        for (double[] b : boxes) {
            double[] t = b.clone();
            for (int a = 0; a < 3; a++) {
                if (t[a + 3] - t[a] < MIN_THICKNESS) {
                    double mid = (t[a] + t[a + 3]) / 2;
                    t[a] = mid - MIN_THICKNESS / 2;
                    t[a + 3] = mid + MIN_THICKNESS / 2;
                }
            }
            out.add(t);
        }
        Quaternionf q = limb.rotation;
        double[] origin = {limb.origin.x, limb.origin.y, limb.origin.z};
        return new BodySpec(name, new double[]{q.x, q.y, q.z, q.w}, origin, out, parent, origin.clone());
    }

    private static void add(Limb limb, RenderType type, MeshPoly poly, Object box) {
        limb.faces.computeIfAbsent(type, k -> new ArrayList<>()).add(poly);
        double[] b = limb.boxes.computeIfAbsent(box,
                k -> new double[]{Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE});
        float[] v = poly.verts();
        Quaternionf inverse = new Quaternionf(limb.rotation).conjugate();
        Vector3f p = new Vector3f();
        for (int i = 0; i < poly.vertexCount(); i++) {
            p.set(v[i * MeshPoly.STRIDE], v[i * MeshPoly.STRIDE + 1], v[i * MeshPoly.STRIDE + 2]).sub(limb.origin);
            inverse.transform(p);
            b[0] = Math.min(b[0], p.x);
            b[1] = Math.min(b[1], p.y);
            b[2] = Math.min(b[2], p.z);
            b[3] = Math.max(b[3], p.x);
            b[4] = Math.max(b[4], p.y);
            b[5] = Math.max(b[5], p.z);
        }
    }

    private static void grow(double[] into, double[] b) {
        for (int a = 0; a < 3; a++) {
            into[a] = Math.min(into[a], b[a]);
            into[a + 3] = Math.max(into[a + 3], b[a + 3]);
        }
    }

    private static Limb nearest(List<Limb> limbs, MeshPoly poly) {
        float[] v = poly.verts();
        Vector3f c = new Vector3f();
        for (int i = 0; i < poly.vertexCount(); i++) {
            c.add(v[i * MeshPoly.STRIDE], v[i * MeshPoly.STRIDE + 1], v[i * MeshPoly.STRIDE + 2]);
        }
        c.div(poly.vertexCount());
        Limb best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Limb limb : limbs) {
            if (limb.mergedInto >= 0) {
                continue;
            }
            Vector3f local = new Quaternionf(limb.rotation).conjugate().transform(new Vector3f(c).sub(limb.origin));
            double d = limb.boxes.isEmpty() ? local.length() : Double.MAX_VALUE;
            for (double[] b : limb.boxes.values()) {
                double dx = Math.max(0, Math.max(b[0] - local.x, local.x - b[3]));
                double dy = Math.max(0, Math.max(b[1] - local.y, local.y - b[4]));
                double dz = Math.max(0, Math.max(b[2] - local.z, local.z - b[5]));
                d = Math.min(d, dx * dx + dy * dy + dz * dz);
            }
            if (d < bestDistance) {
                bestDistance = d;
                best = limb;
            }
        }
        return best;
    }

    private static int limbFor(PartTags.Tag tag, Map<PartTags.DrawnPart, Integer> limbOf, Map<String, Integer> byName,
                               Map<ModelPart, String> names, List<PartTags.DrawnPart> drawn) {
        Integer direct = limbOf.get(tag.part());
        if (direct != null) {
            return direct;
        }
        String name = names.get(tag.part().part());
        if (name != null && byName.containsKey(name)) {
            return byName.get(name);
        }
        Vector3f pivot = tag.part().pose().getTranslation(new Vector3f());
        int best = -1;
        double bestDistance = SAME_PIVOT;
        for (PartTags.DrawnPart d : drawn) {
            Integer li = limbOf.get(d);
            if (li != null) {
                double dist = d.pose().getTranslation(new Vector3f()).distance(pivot);
                if (dist < bestDistance) {
                    bestDistance = dist;
                    best = li;
                }
            }
        }
        if (best >= 0) {
            return best;
        }
        for (PartTags.DrawnPart p = tag.part().parent(); p != null; p = p.parent()) {
            Integer li = limbOf.get(p);
            if (li != null) {
                return li;
            }
        }
        return -1;
    }

    private static boolean usable(Matrix4f pose) {
        float det = new Matrix3f(pose).determinant();
        return Float.isFinite(det) && Math.abs(det) > 1e-9f && Float.isFinite(pose.m30()) && Float.isFinite(pose.m31())
                && Float.isFinite(pose.m32());
    }

    private static boolean sane(Limb limb) {
        double[] values = {limb.origin.x, limb.origin.y, limb.origin.z, limb.rotation.x, limb.rotation.y, limb.rotation.z,
                limb.rotation.w};
        for (double v : values) {
            if (!(Math.abs(v) <= 64)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The rotation and origin of a pose. A pose may be scaled, unevenly (a bone made longer, a model drawn bigger), which
     * the quaternion of the matrix as it stands would be skewed by: the axes are taken one by one, made unit length and
     * square to each other, and a mirrored pose is turned back to a plain rotation.
     */
    private static void frameOf(org.joml.Matrix4f pose, Quaternionf rotation, Vector3f origin) {
        pose.getTranslation(origin);
        Vector3f x = new Matrix3f(pose).getColumn(0, new Vector3f());
        Vector3f y = new Matrix3f(pose).getColumn(1, new Vector3f());
        Vector3f z = new Matrix3f(pose).getColumn(2, new Vector3f());
        if (x.lengthSquared() < 1e-12f || y.lengthSquared() < 1e-12f || z.lengthSquared() < 1e-12f
                || !Float.isFinite(x.lengthSquared() + y.lengthSquared() + z.lengthSquared())) {
            rotation.identity();
            return;
        }
        x.normalize();
        y.sub(new Vector3f(x).mul(x.dot(y)));
        if (y.lengthSquared() < 1e-12f) {
            rotation.identity();
            return;
        }
        y.normalize();
        Vector3f cross = new Vector3f(x).cross(y);
        z.set(cross);
        Matrix3f m = new Matrix3f(x, y, z);
        rotation.setFromNormalized(m).normalize();
    }

    private static void attach(List<Limb> limbs, int torso) {
        Set<Integer> hanging = new HashSet<>();
        Set<Integer> loose = new HashSet<>();
        for (int i = 0; i < limbs.size(); i++) {
            if (limbs.get(i).mergedInto < 0) {
                (rootOf(limbs, i, torso) == torso ? hanging : loose).add(i);
            }
        }
        while (!loose.isEmpty()) {
            int bestRoot = -1, bestTo = torso;
            double bestDistance = Double.MAX_VALUE;
            for (int r : loose) {
                if (rootOf(limbs, r, torso) != r) {
                    continue;
                }
                Vector3f pivot = limbs.get(r).pose.getTranslation(new Vector3f());
                for (int t : hanging) {
                    double d = distanceToBoxes(limbs.get(t), pivot);
                    if (d < bestDistance) {
                        bestDistance = d;
                        bestRoot = r;
                        bestTo = t;
                    }
                }
            }
            if (bestRoot < 0) {
                break;
            }
            limbs.get(bestRoot).parent = bestTo;
            for (int i : new ArrayList<>(loose)) {
                if (rootOf(limbs, i, torso) == torso) {
                    loose.remove(i);
                    hanging.add(i);
                }
            }
        }
    }

    private static int rootOf(List<Limb> limbs, int i, int torso) {
        int at = resolve(limbs, i);
        for (int steps = 0; steps <= limbs.size(); steps++) {
            if (at == torso) {
                return torso;
            }
            int p = limbs.get(at).parent;
            if (p < 0) {
                return at;
            }
            p = resolve(limbs, p);
            if (p == at) {
                return at;
            }
            at = p;
        }
        return at;
    }

    private static double distanceToBoxes(Limb limb, Vector3f point) {
        Vector3f local = new Quaternionf(limb.rotation).conjugate().transform(new Vector3f(point).sub(limb.origin));
        double best = limb.boxes.isEmpty() ? local.lengthSquared() : Double.MAX_VALUE;
        for (double[] b : limb.boxes.values()) {
            double dx = Math.max(0, Math.max(b[0] - local.x, local.x - b[3]));
            double dy = Math.max(0, Math.max(b[1] - local.y, local.y - b[4]));
            double dz = Math.max(0, Math.max(b[2] - local.z, local.z - b[5]));
            best = Math.min(best, dx * dx + dy * dy + dz * dz);
        }
        return best;
    }

    private static int ancestorLimb(PartTags.DrawnPart d, Map<PartTags.DrawnPart, Integer> limbOf) {
        for (PartTags.DrawnPart p = d.parent(); p != null; p = p.parent()) {
            Integer li = limbOf.get(p);
            if (li != null) {
                return li;
            }
        }
        return -1;
    }

    private static int samePivot(Matrix4f pose, List<Limb> limbs) {
        Vector3f pivot = pose.getTranslation(new Vector3f());
        int best = -1;
        double bestDistance = SAME_PIVOT;
        for (int i = 0; i < limbs.size(); i++) {
            double dist = limbs.get(i).pose.getTranslation(new Vector3f()).distance(pivot);
            if (dist < bestDistance) {
                bestDistance = dist;
                best = i;
            }
        }
        return best;
    }

    private static int torsoOf(List<Limb> limbs) {
        int biggest = 0;
        for (int i = 0; i < limbs.size(); i++) {
            if (limbs.get(i).role == Role.TORSO && limbs.get(i).volume >= MIN_LIMB_VOLUME) {
                return i;
            }
            if (limbs.get(i).volume > limbs.get(biggest).volume) {
                biggest = i;
            }
        }
        return biggest;
    }

    private static int resolve(List<Limb> limbs, int i) {
        while (limbs.get(i).mergedInto >= 0) {
            i = limbs.get(i).mergedInto;
        }
        return i;
    }

    private static int parentOf(List<Limb> limbs, int i, int torso) {
        if (i == torso) {
            return -1;
        }
        int p = limbs.get(i).parent;
        if (p < 0) {
            return torso;
        }
        p = resolve(limbs, p);
        return p == i ? torso : p;
    }

    private static void merge(List<Limb> limbs, int from, int into) {
        if (from < 0 || into < 0 || from == into) {
            return;
        }
        limbs.get(from).mergedInto = into;
        limbs.get(into).volume += limbs.get(from).volume;
    }

    private static int alive(List<Limb> limbs) {
        int n = 0;
        for (Limb l : limbs) {
            if (l.mergedInto < 0) {
                n++;
            }
        }
        return n;
    }

    private static double volumeOf(Set<ModelPart.Cube> cubes) {
        double v = 0;
        for (ModelPart.Cube c : cubes) {
            v += Math.abs((c.maxX - c.minX) * (c.maxY - c.minY) * (c.maxZ - c.minZ));
        }
        return v;
    }

    private static Role roleOf(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (containsAny(n, "beak", "nose", "snout", "mouth", "jaw", "hat", "rim", "red_thing", "comb", "jacket", "mane",
                "horn", "antler", "ear", "wattle")) {
            return Role.ATTACHMENT;
        }
        if (containsAny(n, "head", "skull")) {
            return Role.HEAD;
        }
        if (containsAny(n, "body", "torso", "chest", "abdomen")) {
            return Role.TORSO;
        }
        if (containsAny(n, "arm", "hand")) {
            return Role.ARM;
        }
        if (containsAny(n, "leg", "foot", "hoof")) {
            return Role.LEG;
        }
        if (n.contains("wing")) {
            return Role.WING;
        }
        if (n.contains("tail")) {
            return Role.TAIL;
        }
        return Role.OTHER;
    }

    private static boolean containsAny(String s, String... parts) {
        for (String p : parts) {
            if (s.contains(p)) {
                return true;
            }
        }
        return false;
    }

    private static void findNames(Entity entity, Map<ModelPart, String> names, Set<ModelPart> base) {
        var renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
        if (!(renderer instanceof LivingEntityRenderer<?, ?> living)) {
            for (Class<?> c = renderer.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers()) && net.minecraft.client.model.Model.class.isAssignableFrom(f.getType())) {
                        try {
                            f.setAccessible(true);
                            Object model = f.get(renderer);
                            if (model != null) {
                                scanModel(model, names, base);
                            }
                        } catch (ReflectiveOperationException | RuntimeException e) {
                        }
                    }
                }
            }
            return;
        }
        scanModel(living.getModel(), names, base);
        try {
            Field layers = LivingEntityRenderer.class.getDeclaredField("layers");
            layers.setAccessible(true);
            for (Object layer : (List<?>) layers.get(living)) {
                for (Class<?> c = layer.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        if (!Modifier.isStatic(f.getModifiers()) && net.minecraft.client.model.Model.class.isAssignableFrom(f.getType())) {
                            f.setAccessible(true);
                            Object model = f.get(layer);
                            if (model != null) {
                                scanModel(model, names, null);
                            }
                        }
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            MeshCut.LOGGER.debug("Couldn't look at the render layers of {}", entity, e);
        }
    }

    private static void scanModel(Object model, Map<ModelPart, String> names, @Nullable Set<ModelPart> base) {
        for (Class<?> c = model.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || !ModelPart.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    ModelPart part = (ModelPart) f.get(model);
                    if (part != null) {
                        names.putIfAbsent(part, snake(f.getName()));
                        tree(part, names, base);
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                }
            }
        }
    }

    private static void tree(ModelPart part, Map<ModelPart, String> names, @Nullable Set<ModelPart> base) {
        if (base != null && !base.add(part)) {
            return;
        }
        for (var child : children(part).entrySet()) {
            names.putIfAbsent(child.getValue(), child.getKey());
            tree(child.getValue(), names, base);
        }
    }

    private static void childNames(ModelPart part, Map<ModelPart, String> names) {
        for (var child : children(part).entrySet()) {
            names.putIfAbsent(child.getValue(), child.getKey());
        }
    }

    private static Map<String, ModelPart> children(ModelPart part) {
        try {
            return ((ModelPartAccessor) (Object) part).meshcut$children();
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private static String snake(String field) {
        StringBuilder b = new StringBuilder();
        for (char ch : field.toCharArray()) {
            if (Character.isUpperCase(ch)) {
                b.append('_').append(Character.toLowerCase(ch));
            } else {
                b.append(ch);
            }
        }
        return b.toString();
    }

    private EntityParts() {}
}
