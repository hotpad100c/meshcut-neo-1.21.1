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
 *   <li>A part becomes a limb of its own if its name says it is one (head, body, arm, leg, wing, tail) and it does
 *       not merely continue a limb of the same kind it hangs from. Everything else (a nose, a hat, the lower half
 *       of a tentacle) goes with the limb it hangs from; a part that hangs from nothing goes with the limb drawn at
 *       the same pivot (a player's hat with its head), or with the body.</li>
 *   <li>Limbs that hang from nothing hang from the body (the first part called a body, or the biggest limb).</li>
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
    private static final int MAX_LIMBS = 16;
    /** Two parts whose pivots are closer than this (in blocks) are drawn at the same place. */
    private static final double SAME_PIVOT = 0.05;
    /** No box is thinner than this (in blocks): it would have nothing to collide with. */
    private static final double MIN_THICKNESS = 0.02;
    private static final int MAX_BOXES_PER_LIMB = 48;

    private enum Role {
        HEAD, TORSO, ARM, LEG, WING, TAIL, ATTACHMENT, OTHER;

        boolean limb() {
            return this != ATTACHMENT && this != OTHER;
        }
    }

    /** Answers the server: what the entity it asks about is made of. */
    public static void answer(EntityPartsNet.PartsRequest request) {
        List<BodySpec> bodies = List.of();
        try {
            bodies = ModelCapturer.captureEntityParts(request.look()).bodies();
        } catch (RuntimeException e) {
            MeshCut.LOGGER.warn("Couldn't take a {} apart for the server", request.look().type(), e);
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

        // 1. which parts are limbs
        List<Limb> limbs = new ArrayList<>();
        Map<ModelPart, Integer> limbOf = new IdentityHashMap<>();
        List<PartTags.DrawnPart> orphans = new ArrayList<>();
        Set<ModelPart> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PartTags.DrawnPart d : drawn) {
            ModelPart part = d.part();
            if (!base.contains(part) || !seen.add(part)) {
                continue;
            }
            String name = names.getOrDefault(part, "part");
            Role role = roleOf(name);
            Set<ModelPart.Cube> cubes = visibleCubes.getOrDefault(part, Set.of());
            double volume = volumeOf(cubes);
            int parent = ancestorLimb(d, limbOf);
            boolean attachment = role == Role.ATTACHMENT || cubes.size() == 1 && volume <= ATTACHMENT_VOLUME;
            if (role.limb() && !attachment && (parent < 0 || limbs.get(parent).role != role)) {
                limbOf.put(part, limbs.size());
                Limb limb = new Limb(name, role, d, parent);
                limb.volume = volume;
                limbs.add(limb);
            } else if (parent >= 0) {
                limbOf.put(part, parent);
                limbs.get(parent).volume += volume;
            } else {
                orphans.add(d);
            }
        }
        if (limbs.isEmpty()) {
            return single(entity, faces, drawn, whole);
        }
        // a part hanging from nothing goes with the limb drawn at the same place (a player's hat, sleeves, jacket)
        for (PartTags.DrawnPart d : orphans) {
            int at = samePivot(d, limbs);
            if (at >= 0) {
                limbOf.put(d.part(), at);
                limbs.get(at).volume += volumeOf(visibleCubes.getOrDefault(d.part(), Set.of()));
            }
        }

        // 2. the body everything hangs from; empty and tiny limbs go with what they hang from
        int torso = torsoOf(limbs);
        limbs.get(torso).parent = -1;
        for (PartTags.DrawnPart d : orphans) {
            if (!limbOf.containsKey(d.part())) {
                limbOf.put(d.part(), torso);
                limbs.get(torso).volume += volumeOf(visibleCubes.getOrDefault(d.part(), Set.of()));
            }
        }
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
            String name = names.get(e.getKey());
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
                add(limb, entry.getKey(), t.poly(), t.tag().cube() == null ? limb : List.of(t.tag().part(), t.tag().cube()));
            }
        }
        for (Object[] f : loose) {
            MeshPoly poly = (MeshPoly) f[1];
            Limb limb = nearest(limbs, poly);
            add(limb, (RenderType) f[0], poly, limb);
        }

        // 5. what goes to the server, and what each limb draws
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
        List<BodySpec> bodies = new ArrayList<>();
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
            bodies.add(spec(name, limb, parent));
            meshes.put(name, new ModelCapturer.CapturedMesh(limb.faces, ModelCapturer.opaqueOf(limb.faces), whole.capUv()));
        }
        return new ModelCapturer.EntityCapture(whole, meshes, bodies);
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
                add(limb, entry.getKey(), t.poly(), t.tag() == null || t.tag().cube() == null ? limb : List.of(t.tag().part(), t.tag().cube()));
            }
        }
        if (limb.boxes.isEmpty()) {
            return new ModelCapturer.EntityCapture(whole, Map.of(), List.of());
        }
        Map<String, ModelCapturer.CapturedMesh> meshes = Map.of(limb.name,
                new ModelCapturer.CapturedMesh(limb.faces, ModelCapturer.opaqueOf(limb.faces), whole.capUv()));
        return new ModelCapturer.EntityCapture(whole, meshes, List.of(spec(limb.name, limb, -1)));
    }

    private static BodySpec spec(String name, Limb limb, int parent) {
        List<double[]> boxes = new ArrayList<>(limb.boxes.values());
        if (boxes.size() > MAX_BOXES_PER_LIMB) {
            double[] all = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
            boxes.forEach(b -> grow(all, b));
            boxes = List.of(all);
        }
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

    /** Adds a face to the limb, and grows the box it belongs to (by its cube, or the limb's loose box). */
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
    private static int limbFor(PartTags.Tag tag, Map<ModelPart, Integer> limbOf, Map<String, Integer> byName,
                               Map<ModelPart, String> names, List<PartTags.DrawnPart> drawn) {
        Integer direct = limbOf.get(tag.part().part());
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
            Integer li = limbOf.get(d.part());
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
            Integer li = limbOf.get(p.part());
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

    private static int ancestorLimb(PartTags.DrawnPart d, Map<ModelPart, Integer> limbOf) {
        for (PartTags.DrawnPart p = d.parent(); p != null; p = p.parent()) {
            Integer li = limbOf.get(p.part());
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
