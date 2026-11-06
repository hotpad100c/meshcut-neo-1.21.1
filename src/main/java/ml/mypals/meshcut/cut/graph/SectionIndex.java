package ml.mypals.meshcut.cut.graph;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SectionIndex {
    public static final int SIZE = 16;
    public static final int CELLS = SIZE * SIZE * SIZE;
    private static final int REGION_MIN = 12;

    public final int sx, sy, sz;
    private final BlockNodes[] blocks = new BlockNodes[CELLS];
    private int version;

    private final List<Node> nodes = new ArrayList<>();
    private final int[] regionAt = new int[CELLS];
    private final int[] fragFirst = new int[CELLS];
    private final Set<Group> groups = new LinkedHashSet<>();
    private int liveRegions;
    private int builtRegions;

    static final class Node {
        boolean alive = true;
        final boolean region;
        final int x0, y0, z0, x1, y1, z1;
        final int fragment;
        int id;
        Group group;

        Node(int x0, int y0, int z0, int x1, int y1, int z1, int fragment, boolean region) {
            this.region = region;
            this.x0 = x0;
            this.y0 = y0;
            this.z0 = z0;
            this.x1 = x1;
            this.y1 = y1;
            this.z1 = z1;
            this.fragment = fragment;
        }

        int volume() {
            return region ? (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1) : 1;
        }
    }

    static final class Island {
        boolean attached;
        boolean unloaded;
        long epoch;
        SectionIndex[] sections;
        int[] versions;
        Group[] members;
    }

    public static final class Group {
        final SectionIndex section;
        final List<Node> nodes = new ArrayList<>();
        Island island;
        private List<int[]> portals;
        private List<Long> portalMasks;

        Group(SectionIndex section) {
            this.section = section;
        }

        int blocks() {
            int n = 0;
            for (Node node : nodes) {
                n += node.volume();
            }
            return n;
        }

        boolean hasMimic() {
            for (Node node : nodes) {
                if (!node.region) {
                    return true;
                }
            }
            return false;
        }

        void touched() {
            portals = null;
            portalMasks = null;
            island = null;
        }

        public SectionIndex section() {
            return section;
        }
    }

    public SectionIndex(int sx, int sy, int sz, BlockSource src) {
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                for (int z = 0; z < SIZE; z++) {
                    blocks[cell(x, y, z)] = src.at(sx * SIZE + x, sy * SIZE + y, sz * SIZE + z);
                }
            }
        }
        rebuild();
    }

    private SectionIndex(SectionIndex base, Map<Integer, BlockNodes> overrides) {
        this.sx = base.sx;
        this.sy = base.sy;
        this.sz = base.sz;
        System.arraycopy(base.blocks, 0, blocks, 0, CELLS);
        overrides.forEach((cell, nodes) -> blocks[cell] = nodes);
        rebuild();
    }

    public SectionIndex overlaid(Map<Integer, BlockNodes> overrides) {
        return new SectionIndex(this, overrides);
    }

    public static int cell(int x, int y, int z) {
        return (x * SIZE + y) * SIZE + z;
    }

    public int version() {
        return version;
    }

    public BlockNodes blockAt(int cell) {
        return blocks[cell];
    }

    public List<Integer> mimicCells() {
        List<Integer> out = new ArrayList<>();
        for (int c = 0; c < CELLS; c++) {
            if (blocks[c] != null && blocks[c].isMimic()) {
                out.add(c);
            }
        }
        return out;
    }

    private void rebuild() {
        try (var timing = ml.mypals.meshcut.util.CutTiming.phase("SectionIndex.rebuild")) {
        rebuildNodes();
        rebuildGroups();
    
        }
    }

    private void rebuildNodes() {
        try (var timing = ml.mypals.meshcut.util.CutTiming.phase("SectionIndex.rebuildNodes")) {
        nodes.clear();
        liveRegions = 0;
        java.util.Arrays.fill(regionAt, -1);
        java.util.Arrays.fill(fragFirst, -1);
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                for (int z = 0; z < SIZE; z++) {
                    int c = cell(x, y, z);
                    if (blocks[c] != null && blocks[c].isMimic()) {
                        addFragments(c, x, y, z);
                    }
                }
            }
        }
        List<int[]> best = null;
        int[][] orders = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};
        int attempts = ml.mypals.meshcut.MeshCutSettings.allRegionOrders() ? orders.length : 1;
        for (int attempt = 0; attempt < attempts; attempt++) {
            int[] order = orders[attempt];
            List<int[]> boxes = greedyBoxes(order);
            if (best == null || boxes.size() < best.size()) {
                best = boxes;
            }
        }
        for (int[] b : best) {
            addRegion(b[0], b[1], b[2], b[3], b[4], b[5]);
        }
        builtRegions = liveRegions;
    
        }
    }

    private List<int[]> greedyBoxes(int[] order) {
        boolean[] taken = new boolean[CELLS];
        List<int[]> boxes = new ArrayList<>();
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                for (int z = 0; z < SIZE; z++) {
                    if (!plain(cell(x, y, z)) || taken[cell(x, y, z)]) {
                        continue;
                    }
                    int[] lo = {x, y, z}, hi = {x, y, z};
                    for (int axis : order) {
                        while (hi[axis] + 1 < SIZE && slabFree(lo, hi, axis, taken)) {
                            hi[axis]++;
                        }
                    }
                    for (int i = lo[0]; i <= hi[0]; i++) {
                        for (int j = lo[1]; j <= hi[1]; j++) {
                            for (int k = lo[2]; k <= hi[2]; k++) {
                                taken[cell(i, j, k)] = true;
                            }
                        }
                    }
                    boxes.add(new int[]{lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]});
                }
            }
        }
        return boxes;
    }

    private boolean slabFree(int[] lo, int[] hi, int axis, boolean[] taken) {
        int[] from = lo.clone(), to = hi.clone();
        from[axis] = to[axis] = hi[axis] + 1;
        for (int i = from[0]; i <= to[0]; i++) {
            for (int j = from[1]; j <= to[1]; j++) {
                for (int k = from[2]; k <= to[2]; k++) {
                    int c = cell(i, j, k);
                    if (!plain(c) || taken[c]) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean plain(int c) {
        return blocks[c] != null && !blocks[c].isMimic();
    }

    private Node addRegion(int x0, int y0, int z0, int x1, int y1, int z1) {
        Node node = new Node(x0, y0, z0, x1, y1, z1, 0, true);
        int id = nodes.size();
        node.id = id;
        nodes.add(node);
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    regionAt[cell(x, y, z)] = id;
                }
            }
        }
        liveRegions++;
        return node;
    }

    private List<Node> addFragments(int c, int x, int y, int z) {
        List<Node> out = new ArrayList<>();
        int count = blocks[c].count();
        fragFirst[c] = nodes.size();
        for (int f = 0; f < count; f++) {
            Node node = new Node(x, y, z, x, y, z, f, false);
            node.id = nodes.size();
            nodes.add(node);
            out.add(node);
        }
        return out;
    }

    private void rebuildGroups() {
        try (var timing = ml.mypals.meshcut.util.CutTiming.phase("SectionIndex.rebuildGroups")) {
        groups.clear();
        int[] parent = new int[nodes.size()];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                for (int z = 0; z < SIZE; z++) {
                    int c = cell(x, y, z);
                    if (blocks[c] == null) {
                        continue;
                    }
                    for (int axis = 0; axis < 3; axis++) {
                        int nx = x + (axis == 0 ? 1 : 0), ny = y + (axis == 1 ? 1 : 0), nz = z + (axis == 2 ? 1 : 0);
                        if (nx >= SIZE || ny >= SIZE || nz >= SIZE || blocks[cell(nx, ny, nz)] == null) {
                            continue;
                        }
                        int n = cell(nx, ny, nz);
                        for (int a : nodesAt(c)) {
                            for (int b : nodesAt(n)) {
                                if (a != b && (mask(a, axis * 2) & mask(b, axis * 2 + 1)) != 0) {
                                    union(parent, a, b);
                                }
                            }
                        }
                    }
                }
            }
        }
        Map<Integer, Group> byRoot = new java.util.HashMap<>();
        for (int i = 0; i < parent.length; i++) {
            Node node = nodes.get(i);
            if (!node.alive) {
                continue;
            }
            Group g = byRoot.computeIfAbsent(find(parent, i), r -> {
                Group created = new Group(this);
                groups.add(created);
                return created;
            });
            g.nodes.add(node);
            node.group = g;
        }
    
        }
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        parent[find(parent, a)] = find(parent, b);
    }

    private int[] nodesAt(int c) {
        if (regionAt[c] >= 0) {
            return new int[]{regionAt[c]};
        }
        if (fragFirst[c] >= 0) {
            int[] ids = new int[blocks[c].count()];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = fragFirst[c] + i;
            }
            return ids;
        }
        return new int[0];
    }

    private long mask(int nodeId, int face) {
        Node node = nodes.get(nodeId);
        if (node.region) {
            return -1L;
        }
        return blocks[cell(node.x0, node.y0, node.z0)].mask(node.fragment, face);
    }

    public Group groupOf(int c, int fragment) {
        if (regionAt[c] >= 0) {
            return nodes.get(regionAt[c]).group;
        }
        if (fragFirst[c] >= 0 && fragment < blocks[c].count()) {
            return nodes.get(fragFirst[c] + fragment).group;
        }
        return null;
    }

    public List<Group> touching(int c, int face, long mask) {
        List<Group> out = new ArrayList<>(2);
        for (int id : nodesAt(c)) {
            if ((mask(id, face) & mask) != 0 && nodes.get(id).group != null && !out.contains(nodes.get(id).group)) {
                out.add(nodes.get(id).group);
            }
        }
        return out;
    }

    public Set<Group> groups() {
        return groups;
    }

    List<int[]> portals(Group g) {
        if (g.portals == null) {
            List<int[]> out = new ArrayList<>();
            List<Long> masks = new ArrayList<>();
            for (Node node : g.nodes) {
                for (int x = node.x0; x <= node.x1; x++) {
                    for (int y = node.y0; y <= node.y1; y++) {
                        for (int z = node.z0; z <= node.z1; z++) {
                            for (int face = 0; face < 6; face++) {
                                int axis = face / 2;
                                int coord = axis == 0 ? x : axis == 1 ? y : z;
                                if (coord != (face % 2 == 0 ? SIZE - 1 : 0)) {
                                    continue;
                                }
                                long m = node.region ? -1L : blocks[cell(x, y, z)].mask(node.fragment, face);
                                if (m != 0) {
                                    out.add(new int[]{sx * SIZE + x, sy * SIZE + y, sz * SIZE + z, face});
                                    masks.add(m);
                                }
                            }
                        }
                    }
                }
            }
            g.portals = out;
            g.portalMasks = masks;
        }
        return g.portals;
    }

    long portalMask(Group g, int index) {
        portals(g);
        return g.portalMasks.get(index);
    }

    public List<int[]> boxes(Group g) {
        List<int[]> out = new ArrayList<>(g.nodes.size());
        for (Node node : g.nodes) {
            if (node.region || !contains(out, node)) {
                out.add(new int[]{sx * SIZE + node.x0, sy * SIZE + node.y0, sz * SIZE + node.z0,
                        sx * SIZE + node.x1, sy * SIZE + node.y1, sz * SIZE + node.z1});
            }
        }
        return out;
    }

    private boolean contains(List<int[]> boxes, Node node) {
        int[] b = {sx * SIZE + node.x0, sy * SIZE + node.y0, sz * SIZE + node.z0, sx * SIZE + node.x1, sy * SIZE + node.y1, sz * SIZE + node.z1};
        for (int[] o : boxes) {
            if (java.util.Arrays.equals(o, b)) {
                return true;
            }
        }
        return false;
    }

    public record DebugNode(boolean region, int x0, int y0, int z0, int x1, int y1, int z1, int fragment, int group) {}

    public List<DebugNode> debugNodes() {
        List<DebugNode> out = new ArrayList<>();
        int number = 0;
        for (Group g : groups) {
            for (Node node : g.nodes) {
                out.add(new DebugNode(node.region, sx * SIZE + node.x0, sy * SIZE + node.y0, sz * SIZE + node.z0,
                        sx * SIZE + node.x1, sy * SIZE + node.y1, sz * SIZE + node.z1, node.fragment, number));
            }
            number++;
        }
        return out;
    }

    public List<Group> groupsAt(int c) {
        List<Group> out = new ArrayList<>(2);
        for (int id : nodesAt(c)) {
            Group g = nodes.get(id).group;
            if (g != null && !out.contains(g)) {
                out.add(g);
            }
        }
        return out;
    }

    public List<Group> neighbourGroups(Group g, java.util.function.Function<int[], SectionIndex> neighbours) {
        List<Group> out = new ArrayList<>();
        List<int[]> portals = portals(g);
        for (int i = 0; i < portals.size(); i++) {
            int[] p = portals.get(i);
            int face = p[3], axis = face / 2, dir = face % 2 == 0 ? 1 : -1;
            int nx = p[0] + (axis == 0 ? dir : 0), ny = p[1] + (axis == 1 ? dir : 0), nz = p[2] + (axis == 2 ? dir : 0);
            SectionIndex other = neighbours.apply(new int[]{nx >> 4, ny >> 4, nz >> 4});
            if (other != null) {
                for (Group n : other.touching(cell(nx & 15, ny & 15, nz & 15), face ^ 1, portalMask(g, i))) {
                    if (!out.contains(n)) {
                        out.add(n);
                    }
                }
            }
        }
        return out;
    }

    public record DebugLink(double x0, double y0, double z0, double x1, double y1, double z1) {}

    public List<DebugLink> debugLinks(java.util.function.Function<int[], SectionIndex> neighbours) {
        List<DebugLink> out = new ArrayList<>();
        for (Group g : groups) {
            Set<Group> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            List<int[]> portals = portals(g);
            for (int i = 0; i < portals.size(); i++) {
                int[] p = portals.get(i);
                int face = p[3], axis = face / 2, dir = face % 2 == 0 ? 1 : -1;
                int nx = p[0] + (axis == 0 ? dir : 0), ny = p[1] + (axis == 1 ? dir : 0), nz = p[2] + (axis == 2 ? dir : 0);
                SectionIndex other = neighbours.apply(new int[]{nx >> 4, ny >> 4, nz >> 4});
                if (other == null) {
                    continue;
                }
                for (Group n : other.touching(cell(nx & 15, ny & 15, nz & 15), face ^ 1, portalMask(g, i))) {
                    if (seen.add(n)) {
                        out.add(new DebugLink(p[0] + 0.5, p[1] + 0.5, p[2] + 0.5, nx + 0.5, ny + 0.5, nz + 0.5));
                    }
                }
            }
        }
        return out;
    }

    public boolean apply(int c, BlockNodes now) {
        BlockNodes old = blocks[c];
        if (old == now || (old != null && old.sameAs(now))) {
            return false;
        }
        version++;
        int x = c / (SIZE * SIZE), y = c / SIZE % SIZE, z = c % SIZE;

        List<Node> removed = new ArrayList<>();
        List<Node> replacement = new ArrayList<>();
        for (int id : nodesAt(c)) {
            removed.add(nodes.get(id));
        }
        Set<Node> neighbours = new LinkedHashSet<>();
        for (Node r : removed) {
            neighbours.addAll(contacts(r, r.region ? -1 : c));
        }
        blocks[c] = now;
        if (!removed.isEmpty() && removed.get(0).region) {
            Node region = removed.get(0);
            kill(region);
            splitAround(region, x, y, z, replacement);
        } else {
            for (Node r : removed) {
                kill(r);
            }
        }
        fragFirst[c] = -1;
        if (now != null) {
            if (now.isMimic()) {
                replacement.addAll(addFragments(c, x, y, z));
            } else {
                replacement.add(addRegion(x, y, z, x, y, z));
            }
        }
        neighbours.removeAll(removed);
        neighbours.removeIf(n -> !n.alive);

        if (liveRegions > Math.max(REGION_MIN, builtRegions * 2)) {
            rebuild();
            return false;
        }

        Set<Group> oldGroups = new LinkedHashSet<>();
        for (Node r : removed) {
            if (r.group != null) {
                oldGroups.add(r.group);
            }
        }
        boolean splitOff = false;
        if (!removed.isEmpty()) {
            splitOff = oldGroups.size() > 1 || !stillTied(replacement, neighbours);
        }
        if (splitOff) {
            rebuildGroups();
            return true;
        }

        for (Node r : removed) {
            if (r.group != null) {
                r.group.nodes.remove(r);
            }
        }
        Map<Node, Group> contactOf = new IdentityHashMap<>();
        Map<Object, Object> parent = new IdentityHashMap<>();
        for (Node n : replacement) {
            parent.put(n, n);
        }
        for (Node n : replacement) {
            for (Node o : contacts(n, -1)) {
                if (o.group != null && o.alive) {
                    parent.putIfAbsent(o.group, o.group);
                    unionObjects(parent, n, o.group);
                } else if (parent.containsKey(o)) {
                    unionObjects(parent, n, o);
                }
            }
        }
        Map<Object, List<Node>> components = new IdentityHashMap<>();
        for (Node n : replacement) {
            components.computeIfAbsent(findObject(parent, n), k -> new ArrayList<>()).add(n);
        }
        Set<Group> touched = new LinkedHashSet<>(oldGroups);
        for (var entry : components.entrySet()) {
            Group target = null;
            Set<Group> joined = new LinkedHashSet<>();
            for (Object o : parent.keySet()) {
                if (o instanceof Group g && findObject(parent, o) == entry.getKey()) {
                    joined.add(g);
                }
            }
            for (Group g : joined) {
                if (target == null) {
                    target = g;
                } else {
                    for (Node n : g.nodes) {
                        n.group = target;
                        target.nodes.add(n);
                    }
                    g.nodes.clear();
                    groups.remove(g);
                }
            }
            if (target == null) {
                target = new Group(this);
                groups.add(target);
            }
            for (Node n : entry.getValue()) {
                n.group = target;
                target.nodes.add(n);
            }
            touched.add(target);
        }
        for (Group g : touched) {
            g.touched();
        }
        groups.removeIf(g -> g.nodes.isEmpty());
        return false;
    }

    public boolean applyBatch(Map<Integer, BlockNodes> changes) {
        Map<Integer, BlockNodes> different = new java.util.LinkedHashMap<>();
        changes.forEach((c, now) -> {
            BlockNodes old = blocks[c];
            if (old != now && (old == null || !old.sameAs(now))) different.put(c, now);
        });
        if (different.isEmpty()) return false;
        if (different.size() <= 8) {
            boolean split = false;
            for (var edit : different.entrySet()) split |= apply(edit.getKey(), edit.getValue());
            return split;
        }
        different.forEach((c, now) -> blocks[c] = now);
        version++;
        rebuild();
        return true;
    }

    private static Object findObject(Map<Object, Object> parent, Object o) {
        while (parent.get(o) != o) {
            parent.put(o, parent.get(parent.get(o)));
            o = parent.get(o);
        }
        return o;
    }

    private static void unionObjects(Map<Object, Object> parent, Object a, Object b) {
        parent.put(findObject(parent, a), findObject(parent, b));
    }

    private void kill(Node node) {
        node.alive = false;
        if (node.region) {
            liveRegions--;
            for (int x = node.x0; x <= node.x1; x++) {
                for (int y = node.y0; y <= node.y1; y++) {
                    for (int z = node.z0; z <= node.z1; z++) {
                        regionAt[cell(x, y, z)] = -1;
                    }
                }
            }
        }
    }

    private void splitAround(Node r, int x, int y, int z, List<Node> out) {
        if (x > r.x0) {
            out.add(addRegion(r.x0, r.y0, r.z0, x - 1, r.y1, r.z1));
        }
        if (x < r.x1) {
            out.add(addRegion(x + 1, r.y0, r.z0, r.x1, r.y1, r.z1));
        }
        if (y > r.y0) {
            out.add(addRegion(x, r.y0, r.z0, x, y - 1, r.z1));
        }
        if (y < r.y1) {
            out.add(addRegion(x, y + 1, r.z0, x, r.y1, r.z1));
        }
        if (z > r.z0) {
            out.add(addRegion(x, y, r.z0, x, y, z - 1));
        }
        if (z < r.z1) {
            out.add(addRegion(x, y, z + 1, x, y, r.z1));
        }
    }

    private boolean stillTied(List<Node> replacement, Set<Node> neighbours) {
        List<Node> all = new ArrayList<>(replacement);
        all.addAll(neighbours);
        if (all.size() <= 1) {
            return true;
        }
        Map<Node, Integer> index = new IdentityHashMap<>();
        for (int i = 0; i < all.size(); i++) {
            index.put(all.get(i), i);
        }
        int[] parent = new int[all.size()];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        for (Node n : all) {
            for (Node o : contacts(n, -1)) {
                Integer j = index.get(o);
                if (j != null) {
                    union(parent, index.get(n), j);
                }
            }
        }
        int root = find(parent, 0);
        for (int i = 1; i < parent.length; i++) {
            if (find(parent, i) != root) {
                return false;
            }
        }
        return true;
    }

    private Set<Node> contacts(Node node, int only) {
        Set<Node> out = new LinkedHashSet<>();
        for (int x = node.x0; x <= node.x1; x++) {
            for (int y = node.y0; y <= node.y1; y++) {
                for (int z = node.z0; z <= node.z1; z++) {
                    int c = cell(x, y, z);
                    if (only >= 0 && c != only) {
                        continue;
                    }
                    for (int face = 0; face < 6; face++) {
                        int axis = face / 2, dir = face % 2 == 0 ? 1 : -1;
                        int nx = x + (axis == 0 ? dir : 0), ny = y + (axis == 1 ? dir : 0), nz = z + (axis == 2 ? dir : 0);
                        if (nx < 0 || ny < 0 || nz < 0 || nx >= SIZE || ny >= SIZE || nz >= SIZE) {
                            continue;
                        }
                        int n = cell(nx, ny, nz);
                        long mine = mask(node.id, face);
                        for (int id : nodesAt(n)) {
                            Node other = nodes.get(id);
                            if (other != node && other.alive && (mine & mask(id, face ^ 1)) != 0) {
                                out.add(other);
                            }
                        }
                    }
                }
            }
        }
        return out;
    }
}
