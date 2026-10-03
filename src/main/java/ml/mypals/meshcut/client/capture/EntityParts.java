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

/**
 * Takes a recorded entity apart into limbs, the way sable-player-ragdoll does: by the model parts that drew it.
 * <ul>
 *   <li>The name first. A part named as a limb (head, body, arm, leg, wing, tail) is one, unless it only continues
 *       the limb of the same kind it hangs from (a wing's tip). A part named as an attachment (a nose, a hat, a
 *       mane), or a single tiny cube, goes with the limb it hangs from, or the limb drawn at the same pivot.</li>
 *   <li>Then the hierarchy. A part whose name tells nothing ("bone3", "tentacle", "neck") is a limb of its own,
 *       joined to the limb it hangs from where it is drawn; drawn where a limb that hangs from nothing is drawn, it
 *       is a layer of that limb (a player's sleeves). A part with nothing to see ("root") only passes its children
 *       on. A part drawn several times in different places (the dragon's neck and tail) is a limb each time.</li>
 *   <li>The body is the first part called a body, or the biggest limb. A limb that hangs from nothing hangs from the
 *       limb nearest its pivot that already hangs from the body, so drawn segments chain up from it.</li>
 *   <li>Limbs that would weigh almost nothing go with what they hang from.</li>
 *   <li>Each limb keeps the frame its part was drawn in (pivot and rotation), and is made of one box per cube of its
 *       parts that can be seen, in that frame.</li>
 *   <li>Anything that is not an animal is one rigid piece.</li>
 * </ul>
 * Faces drawn by other models (armour, layers drawn over the body) go with the limb of the same name or pivot;
 * faces drawn by no model part (a held item) go with the nearest limb.
 */
public final class EntityParts {
    /** Limbs smaller than this (in cubic model pixels) go with the limb they hang from: they would weigh nothing. */
    private static final double MIN_LIMB_VOLUME = 8;
    /** A part with a single cube no bigger than this (cubic model pixels) is an attachment, as in the ragdoll mod. */
    private static final double ATTACHMENT_VOLUME = 12;
    private static final int MAX_LIMBS = 24;
    /** Two parts whose pivots are closer than this (in blocks) are drawn at the same place. */
    private static final double SAME_PIVOT = 0.05;
    /** No box is thinner than this (in blocks): it would have nothing to collide with. */
    private static final double MIN_THICKNESS = 0.02;

    private enum Role {
        HEAD, TORSO, ARM, LEG, WING, TAIL, ATTACHMENT, OTHER;

        boolean limb() {
            return this != ATTACHMENT && this != OTHER;
        }
    }

    /** Answers the server: what the entity it asks about is made of. */
    public static void answer(EntityPartsNet.PartsRequest request) {
        List<BodySpec> bodies;
        try {
            bodies = ModelCapturer.captureEntityParts(request.look()).bodies();
        } catch (RuntimeException e) {
            // No answer, rather than an empty one: that would say nothing of the entity can be seen, and it would
            // vanish. Without an answer the server lets it fall as one piece.
            MeshCut.LOGGER.warn("Couldn't take a {} apart for the server", request.look().type(), e);
            return;
        }
        PacketDistributor.sendToServer(new EntityPartsNet.PartsReply(request.id(), bodies));
    }

    /** A limb while it is being worked out. */
    private static final class Limb {
        final String name;
        final Role role;
        final PartTags.DrawnPart frame;
        int parent;
        double volume;
        int mergedInto = -1;
        // worked out at the end
        final Quaternionf rotation = new Quaternionf();
        final Vector3f origin = new Vector3f();
        final Map<Object, double[]> boxes = new LinkedHashMap<>();
        final Map<RenderType, List<MeshPoly>> faces = new LinkedHashMap<>();

        Limb(String name, Role role, PartTags.DrawnPart frame, int parent) {
            this.name = name;
            this.role = role;
            this.frame = frame;
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
                if (t.tag() != null && t.tag().cube() != null) {
                    visibleCubes.computeIfAbsent(t.tag().part().part(), k -> Collections.newSetFromMap(new IdentityHashMap<>()))
                            .add(t.tag().cube());
                }
            }
        }

        // 1. which parts are limbs. A part is looked at each time it is drawn: drawn again where it was (by a layer
        // over the body), it is the same; drawn somewhere else (the ender dragon's neck, five times), it is another.
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
                continue; // nothing of it to see (a "root"): its children look past it
            }
            // a part drawn again further along (the dragon draws its neck and its tail with one part) hangs from
            // nothing here; it is hung from the nearest limb later, so the segments chain up from the body
            int parent = ancestorLimb(d, limbOf);
            boolean attachment = role == Role.ATTACHMENT || cubes.size() == 1 && volume <= ATTACHMENT_VOLUME;
            int join; // the limb it goes with, or -1 for a limb of its own
            if (attachment) {
                join = parent >= 0 ? parent : samePivot(d, limbs);
            } else if (role.limb()) {
                // a limb, unless it only goes on with the limb it hangs from (a wing's tip)
                join = parent >= 0 && limbs.get(parent).role == role ? parent : -1;
            } else {
                // The name tells nothing. Drawn where a limb that hangs from nothing is drawn, it is a layer of that
                // limb (a player's sleeves); otherwise it is a limb of its own, hanging where it is drawn.
                join = parent < 0 ? samePivot(d, limbs) : -1;
            }
            if (join >= 0) {
                limbOf.put(d, join);
                limbs.get(join).volume += volume;
            } else {
                limbOf.put(d, limbs.size());
                Limb limb = new Limb(name, role, d, parent);
                limb.volume = volume;
                limbs.add(limb);
            }
        }
        if (limbs.isEmpty()) {
            return single(entity, faces, drawn, whole);
        }

        // 2. the body; tiny limbs go with what they hang from (a body that weighs nothing makes the physics unstable)
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

        // 3. frames
        for (Limb limb : limbs) {
            frameOf(limb.frame.pose(), limb.rotation, limb.origin);
        }

        // 4. faces and boxes
        Map<String, Integer> byName = new HashMap<>();
        for (var e : limbOf.entrySet()) {
            String name = names.get(e.getKey().part());
            if (name != null) {
                byName.putIfAbsent(name, e.getValue());
            }
        }
        List<Object[]> loose = new ArrayList<>(); // {type, poly}
        for (var entry : faces.entrySet()) {
            for (RecordingBufferSource.Tagged t : entry.getValue()) {
                int li = t.tag() == null ? -1 : limbFor(t.tag(), limbOf, byName, names, drawn);
                if (li < 0) {
                    loose.add(new Object[]{entry.getKey(), t.poly()});
                    continue;
                }
                Limb limb = limbs.get(resolve(limbs, li));
                add(limb, entry.getKey(), t.poly(), t.tag().part());
            }
        }
        for (Object[] f : loose) {
            MeshPoly poly = (MeshPoly) f[1];
            Limb limb = nearest(limbs, poly);
            add(limb, (RenderType) f[0], poly, limb);
        }

        // 5. a limb with nothing to hang from hangs from the nearest limb that hangs from the body, one after another
        //    (the dragon's head from the last of its neck, not from its body)
        attach(limbs, torso);

        // 6. what goes to the server, and what each limb draws
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

    /**
     * The limbs as the server is to build them: each made of one box per model part, the box around the part as it
     * lies in the limb (a part turned with the limb lies straight in it, and its box is exactly its cubes). If that is
     * more boxes than the server takes ({@link BodySpec#MAX_BOXES}), the nearest boxes of the limb with the most are
     * joined.
     */
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
        return out;
    }

    /**
     * Joins boxes until there are at most {@code limit}: each time the two whose joint box adds the least empty space.
     * Everything stays covered (so nothing of the model goes undrawn); only the collision gets coarser.
     */
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

    /** Everything as one rigid piece, in the frame of its biggest part (a boat's hull lies along the boat). */
    private static ModelCapturer.EntityCapture single(Entity entity, Map<RenderType, List<RecordingBufferSource.Tagged>> faces,
                                                     List<PartTags.DrawnPart> drawn, ModelCapturer.CapturedMesh whole) {
        PartTags.DrawnPart biggest = null;
        double most = 0;
        Map<ModelPart, Set<ModelPart.Cube>> cubes = new IdentityHashMap<>();
        for (List<RecordingBufferSource.Tagged> list : faces.values()) {
            for (RecordingBufferSource.Tagged t : list) {
                if (t.tag() != null && t.tag().cube() != null) {
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
        Limb limb = new Limb("all", Role.TORSO, biggest, -1);
        if (biggest != null) {
            frameOf(biggest.pose(), limb.rotation, limb.origin);
        }
        limb.origin.zero();
        for (var entry : faces.entrySet()) {
            for (RecordingBufferSource.Tagged t : entry.getValue()) {
                add(limb, entry.getKey(), t.poly(), t.tag() == null ? limb : t.tag().part());
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

    /** Adds a face to the limb: to what it draws, and to the box of the part that drew it (or the limb's loose faces). */
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

    /** The limb whose boxes are nearest the middle of the face. */
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

    /** Which limb a face drawn by some model part belongs to, or -1 if that can't be told. */
    private static int limbFor(PartTags.Tag tag, Map<PartTags.DrawnPart, Integer> limbOf, Map<String, Integer> byName,
                               Map<ModelPart, String> names, List<PartTags.DrawnPart> drawn) {
        Integer direct = limbOf.get(tag.part());
        if (direct != null) {
            return direct;
        }
        // another model drawn over the body (armour, say): its parts are named and placed like the body's
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

    /** The pivot and the rotation (without scale, or a mirroring) a part was drawn with. */
    private static void frameOf(org.joml.Matrix4f pose, Quaternionf rotation, Vector3f origin) {
        pose.getTranslation(origin);
        Matrix3f m = new Matrix3f(pose);
        if (m.determinant() < 0) {
            m.scale(-1, 1, 1);
        }
        m.getNormalizedRotation(rotation);
    }

    /**
     * Gives every limb that hangs from nothing (other than the body) a limb to hang from: of all such limbs, the one
     * whose pivot is nearest a limb already hanging from the body hangs from that limb, and so on until all hang.
     */
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
                    continue; // hangs from another loose limb: it comes along with that one
                }
                Vector3f pivot = limbs.get(r).frame.pose().getTranslation(new Vector3f());
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

    /** Where following the limbs it hangs from ends: the body, or a limb that hangs from nothing. */
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

    /** Squared distance from a point (where the entity was drawn) to the limb's boxes. */
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

    private static int samePivot(PartTags.DrawnPart d, List<Limb> limbs) {
        Vector3f pivot = d.pose().getTranslation(new Vector3f());
        int best = -1;
        double bestDistance = SAME_PIVOT;
        for (int i = 0; i < limbs.size(); i++) {
            double dist = limbs.get(i).frame.pose().getTranslation(new Vector3f()).distance(pivot);
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

    /** The (not merged) limb that {@code i} hangs from; the body for anything that hangs from nothing. */
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

    // ---------------------------------------------------------------- names

    /**
     * Names the parts of the entity's own model (from its fields and its parts' children) and marks them as the
     * body's own; the models of its render layers are only named.
     */
    private static void findNames(Entity entity, Map<ModelPart, String> names, Set<ModelPart> base) {
        var renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
        if (!(renderer instanceof LivingEntityRenderer<?, ?> living)) {
            // a renderer of its own (the ender dragon's): every model it holds is the body's
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
                            // a field we may not read: its parts go unnamed
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
                    // a field we may not read: its part goes unnamed
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

    /** rightHindLeg → right_hind_leg */
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
