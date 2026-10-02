package ml.mypals.meshcut.cut.graph;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The connectivity of one 16³ section of blocks, in two layers.
 * <p>
 * <b>Regions</b> are axis-aligned boxes of ordinary solid blocks (they are joined by definition). A mimic block is
 * not part of a region: each of its fragments is a node of its own. <b>Groups</b> are the sets of nodes (regions and
 * fragments) of this section that touch each other face to face, where touching means that the 8×8 face masks of the
 * two overlap. A group also knows which blocks on the section's border could connect to the next section
 * ({@link #portals}); the next section is only looked at when a search crosses into it, so rebuilding a section never
 * leaves stale links in its neighbours.
 * <p>
 * Edits ({@link #apply}) are resolved on the region layer first: removing a block splits its region into at most six
 * boxes, adding one adds a 1×1×1 region. Groups are only touched if that can have changed what is connected: the
 * pieces left by a removal are checked among themselves and the nodes that touched the removed block, and only if
 * they have come apart is the whole section's group layer recomputed.
 */
public final class SectionIndex {
    public static final int SIZE = 16;
    public static final int CELLS = SIZE * SIZE * SIZE;
    /** More live regions than this and the section is rebuilt, which merges the fragments edits have left. */
    private static final int REGION_LIMIT = 96;

    public final int sx, sy, sz;
    private final BlockNodes[] blocks = new BlockNodes[CELLS];
    /** Bumped by every change; a cached result that depends on this section is valid while it is unchanged. */
    private int version;

    private final List<Node> nodes = new ArrayList<>();
    private final int[] regionAt = new int[CELLS];
    private final int[] fragFirst = new int[CELLS];
    private final Set<Group> groups = new LinkedHashSet<>();
    private int liveRegions;

    /** One region (a box, bounds inclusive and local) or one fragment of a mimic block. */
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

    /** Whether a group is cut off from everything but itself, found by a search that crossed sections. */
    static final class Island {
        boolean attached;
        long epoch;
        SectionIndex[] sections;
        int[] versions;
        Group[] members;
    }

    public static final class Group {
        final SectionIndex section;
        final List<Node> nodes = new ArrayList<>();
        /** The result of the last search from here, so a group already known to be an island needs no new one. */
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

    /** A copy with some blocks replaced, for looking at what a change would do before making it. Not cached. */
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

    /** The cells that hold mimic blocks. */
    public List<Integer> mimicCells() {
        List<Integer> out = new ArrayList<>();
        for (int c = 0; c < CELLS; c++) {
            if (blocks[c] != null && blocks[c].isMimic()) {
                out.add(c);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- building

    private void rebuild() {
        rebuildNodes();
        rebuildGroups();
    }

    private void rebuildNodes() {
        nodes.clear();
        liveRegions = 0;
        java.util.Arrays.fill(regionAt, -1);
        java.util.Arrays.fill(fragFirst, -1);
        boolean[] taken = new boolean[CELLS];
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                for (int z = 0; z < SIZE; z++) {
                    int c = cell(x, y, z);
                    BlockNodes b = blocks[c];
                    if (b == null) {
                        continue;
                    }
                    if (b.isMimic()) {
                        addFragments(c, x, y, z);
                    } else if (!taken[c]) {
                        int z1 = z;
                        while (z1 + 1 < SIZE && plain(cell(x, y, z1 + 1)) && !taken[cell(x, y, z1 + 1)]) {
                            z1++;
                        }
                        int y1 = y;
                        while (y1 + 1 < SIZE && rowFree(x, y1 + 1, z, z1, taken)) {
                            y1++;
                        }
                        int x1 = x;
                        while (x1 + 1 < SIZE && slabFree(x1 + 1, y, y1, z, z1, taken)) {
                            x1++;
                        }
                        for (int i = x; i <= x1; i++) {
                            for (int j = y; j <= y1; j++) {
                                for (int k = z; k <= z1; k++) {
                                    taken[cell(i, j, k)] = true;
                                }
                            }
                        }
                        addRegion(x, y, z, x1, y1, z1);
                    }
                }
            }
        }
    }

    private boolean plain(int c) {
        return blocks[c] != null && !blocks[c].isMimic();
    }

    private boolean rowFree(int x, int y, int z0, int z1, boolean[] taken) {
        for (int z = z0; z <= z1; z++) {
            int c = cell(x, y, z);
            if (!plain(c) || taken[c]) {
                return false;
            }
        }
        return true;
    }

    private boolean slabFree(int x, int y0, int y1, int z0, int z1, boolean[] taken) {
        for (int y = y0; y <= y1; y++) {
            if (!rowFree(x, y, z0, z1, taken)) {
                return false;
            }
        }
        return true;
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

    // ---------------------------------------------------------------- queries

    /** The ids of the nodes in a cell: one region, or the fragments of a mimic. */
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

    /** The group of fragment {@code fragment} of the block in the cell, or null if there is no such piece. */
    public Group groupOf(int c, int fragment) {
        if (regionAt[c] >= 0) {
            return nodes.get(regionAt[c]).group;
        }
        if (fragFirst[c] >= 0 && fragment < blocks[c].count()) {
            return nodes.get(fragFirst[c] + fragment).group;
        }
        return null;
    }

    /** The groups of the pieces in the cell whose face {@code face} overlaps {@code mask}. */
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

    /**
     * Where the group meets the section's border: {x, y, z, face} in absolute block coordinates of the block inside
     * the section, with the face mask of the piece on that face in {@link #portalMask}.
     */
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

    /** Every box (inclusive absolute bounds) the group is made of: its regions, and one per mimic fragment's cell. */
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

    /** A region box or a fragment, with the number of the group (within this section) it belongs to; for debug views. */
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

    /** The groups of the pieces in the cell (a mimic may have fragments in several); for debug views. */
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

    /** The groups in other sections that the group touches across the section border; for debug views. */
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

    /** A line across a section border, from a block of a group to the block of the group it touches on the other side. */
    public record DebugLink(double x0, double y0, double z0, double x1, double y1, double z1) {}

    /**
     * The links from this section's groups to the groups they touch across the section border: one line for each
     * pair, drawn between the two blocks that touch.
     *
     * @param neighbours finds the section at the given section coordinates, or null
     */
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

    // ---------------------------------------------------------------- editing

    /**
     * Replaces what the block in the cell is.
     *
     * @return {@code true} if the connectivity of the section's groups may have changed in a way that was not simply
     *         "things joined": a group was split (the group layer was recomputed), so anything depending on it is
     *         worth another look
     */
    public boolean apply(int c, BlockNodes now) {
        BlockNodes old = blocks[c];
        if (old == now || (old != null && old.sameAs(now))) {
            return false;
        }
        version++;
        int x = c / (SIZE * SIZE), y = c / SIZE % SIZE, z = c % SIZE;

        // what is there now, and what touched it (while the old block still decides the masks)
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

        if (liveRegions > REGION_LIMIT) {
            rebuild(); // merges the boxes edits have cut up; what is connected does not change
            return false;
        }

        // Region layer: do the pieces that replace what was removed still tie together what it tied together?
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

        // Group layer: the replacements take the removed nodes' place; whatever they touch joins them
        Set<Group> touchedGroups = new LinkedHashSet<>(oldGroups);
        for (Node n : replacement) {
            for (Node o : contacts(n, -1)) {
                if (o.group != null) {
                    touchedGroups.add(o.group);
                }
            }
        }
        for (Node r : removed) {
            if (r.group != null) {
                r.group.nodes.remove(r);
            }
        }
        Group target = touchedGroups.isEmpty() ? null : touchedGroups.iterator().next();
        for (Node n : replacement) {
            if (target == null) {
                target = new Group(this);
                groups.add(target);
            }
            n.group = target;
            target.nodes.add(n);
        }
        for (Group other : touchedGroups) {
            if (other != target && target != null) {
                for (Node n : other.nodes) {
                    n.group = target;
                    target.nodes.add(n);
                }
                other.nodes.clear();
                groups.remove(other);
            }
        }
        for (Group g : touchedGroups) {
            g.touched();
        }
        if (target != null) {
            target.touched();
        }
        groups.removeIf(g -> g.nodes.isEmpty());
        return false;
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

    /** The region without the one cell, as up to six boxes. */
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

    /**
     * Whether the new pieces and the old neighbours all end up in one connected set, using only contacts among
     * themselves. (Contacts that go out of the section are ignored: this can only make the answer "no" too often,
     * never "yes" wrongly.)
     */
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

    /**
     * Live nodes of this section that touch the node face to face.
     *
     * @param only if not -1, only the cell of the node's that has this index is looked at
     */
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
