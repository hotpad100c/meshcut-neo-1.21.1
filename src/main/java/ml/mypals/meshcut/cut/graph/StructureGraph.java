package ml.mypals.meshcut.cut.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class StructureGraph {
    public static final int GROUP_BUDGET = 50;
    public static final int BLOCK_BUDGET = 20_000;

    public record NodeRef(int x, int y, int z, int fragment) {}

    public static final class Closure {
        private final List<SectionIndex.Group> groups;
        private final boolean attached;
        private final Set<NodeRef> seeds = new LinkedHashSet<>();

        private Closure(List<SectionIndex.Group> groups, boolean attached) {
            this.groups = groups;
            this.attached = attached;
        }

        public boolean attached() {
            return attached;
        }

        public Set<NodeRef> seeds() {
            return seeds;
        }

        public List<int[]> boxes() {
            List<int[]> out = new ArrayList<>();
            for (SectionIndex.Group g : groups) {
                out.addAll(g.section().boxes(g));
            }
            return out;
        }

        public int blocks() {
            int n = 0;
            for (SectionIndex.Group g : groups) {
                n += g.blocks();
            }
            return n;
        }

        public long[] positions() {
            Set<Long> seen = new LinkedHashSet<>();
            for (int[] box : boxes()) {
                for (int x = box[0]; x <= box[3]; x++) {
                    for (int y = box[1]; y <= box[4]; y++) {
                        for (int z = box[2]; z <= box[5]; z++) {
                            seen.add(pack(x, y, z));
                        }
                    }
                }
            }
            long[] out = new long[seen.size()];
            int i = 0;
            for (long p : seen) {
                out[i++] = p;
            }
            return out;
        }
    }

    private final BlockSource source;
    private final Map<Long, SectionIndex> cache = new HashMap<>();
    private long epoch;

    public StructureGraph(BlockSource source) {
        this.source = source;
    }

    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    public static int unpackX(long p) {
        return (int) (p >> 38);
    }

    public static int unpackY(long p) {
        return (int) (p << 52 >> 52);
    }

    public static int unpackZ(long p) {
        return (int) (p << 26 >> 38);
    }

    private static long key(int sx, int sy, int sz) {
        return ((long) (sx & 0x3FFFFF) << 42) | ((long) (sz & 0x3FFFFF) << 20) | (sy & 0xFFFFF);
    }

    public SectionIndex update(int x, int y, int z) {
        epoch++;
        int sx = x >> 4, sy = y >> 4, sz = z >> 4;
        SectionIndex section = cache.get(key(sx, sy, sz));
        if (section == null) {
            return null;
        }
        BlockNodes now = source.loaded(x, y, z) ? source.at(x, y, z) : null;
        return section.apply(SectionIndex.cell(x & 15, y & 15, z & 15), now) ? section : null;
    }

    public List<SectionIndex> updateBatch(Iterable<NodeRef> edits) {
        epoch++;
        Map<SectionIndex, Map<Integer, BlockNodes>> sections = new java.util.LinkedHashMap<>();
        for (NodeRef p : edits) {
            SectionIndex section = cache.get(key(p.x() >> 4, p.y() >> 4, p.z() >> 4));
            if (section == null) continue;
            BlockNodes now = source.loaded(p.x(), p.y(), p.z()) ? source.at(p.x(), p.y(), p.z()) : null;
            sections.computeIfAbsent(section, s -> new java.util.LinkedHashMap<>())
                    .put(SectionIndex.cell(p.x() & 15, p.y() & 15, p.z() & 15), now);
        }
        List<SectionIndex> rebuilt = new ArrayList<>();
        sections.forEach((section, changes) -> { if (section.applyBatch(changes)) rebuilt.add(section); });
        return rebuilt;
    }

    public boolean unchanged(int x, int y, int z) {
        SectionIndex section = cache.get(key(x >> 4, y >> 4, z >> 4));
        if (section == null) return false;
        BlockNodes old = section.blockAt(SectionIndex.cell(x & 15, y & 15, z & 15));
        BlockNodes now = source.loaded(x, y, z) ? source.at(x, y, z) : null;
        return old == now || old != null && old.sameAs(now);
    }

    public void unloadChunk(int chunkX, int chunkZ) {
        epoch++;
        cache.values().removeIf(sec -> sec.sx == chunkX && sec.sz == chunkZ);
    }

    public void clear() {
        epoch++;
        cache.clear();
    }

    private SectionIndex section(int sx, int sy, int sz) {
        long k = key(sx, sy, sz);
        SectionIndex s = cache.get(k);
        if (s == null) {
            if (!source.loaded(sx * 16, sy * 16, sz * 16)) {
                return null;
            }
            s = new SectionIndex(sx, sy, sz, source);
            cache.put(k, s);
        }
        return s;
    }

    public List<Closure> closures(Iterable<NodeRef> seeds, Map<Long, BlockNodes> overlay) {
        boolean hypothetical = false;
        Map<Long, SectionIndex> views = new HashMap<>();
        if (overlay != null && !overlay.isEmpty()) {
            Map<Long, int[]> origin = new HashMap<>();
            Map<Long, Map<Integer, BlockNodes>> bySection = new HashMap<>();
            overlay.forEach((p, nodes) -> {
                int x = unpackX(p), y = unpackY(p), z = unpackZ(p);
                long k = key(x >> 4, y >> 4, z >> 4);
                origin.putIfAbsent(k, new int[]{x >> 4, y >> 4, z >> 4});
                bySection.computeIfAbsent(k, kk -> new HashMap<>()).put(SectionIndex.cell(x & 15, y & 15, z & 15), nodes);
            });
            for (var entry : bySection.entrySet()) {
                int[] at = origin.get(entry.getKey());
                SectionIndex base = section(at[0], at[1], at[2]);
                if (base == null) {
                    continue;
                }
                entry.getValue().entrySet().removeIf(e -> {
                    BlockNodes now = base.blockAt(e.getKey());
                    return now != null && e.getValue().sameAs(now);
                });
                if (!entry.getValue().isEmpty()) {
                    views.put(entry.getKey(), base.overlaid(entry.getValue()));
                    hypothetical = true;
                }
            }
        }

        List<Closure> out = new ArrayList<>();
        Map<SectionIndex.Group, Closure> assigned = new IdentityHashMap<>();
        for (NodeRef seed : seeds) {
            SectionIndex s = view(views, seed.x() >> 4, seed.y() >> 4, seed.z() >> 4);
            SectionIndex.Group g = s == null ? null
                    : s.groupOf(SectionIndex.cell(seed.x() & 15, seed.y() & 15, seed.z() & 15), seed.fragment());
            if (g == null) {
                continue;
            }
            Closure closure = assigned.get(g);
            if (closure == null) {
                closure = search(g, views, hypothetical, assigned);
                out.add(closure);
            }
            closure.seeds.add(seed);
        }
        return out;
    }

    private SectionIndex view(Map<Long, SectionIndex> views, int sx, int sy, int sz) {
        SectionIndex v = views.get(key(sx, sy, sz));
        return v != null ? v : section(sx, sy, sz);
    }

    private Closure search(SectionIndex.Group start, Map<Long, SectionIndex> views, boolean hypothetical,
                           Map<SectionIndex.Group, Closure> assigned) {
        if (!hypothetical) {
            Closure known = remembered(start);
            if (known != null) {
                for (SectionIndex.Group g : known.groups) {
                    assigned.put(g, known);
                }
                return known;
            }
        }
        Set<SectionIndex> looked = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        List<SectionIndex.Group> visited = new ArrayList<>();
        Set<SectionIndex.Group> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<SectionIndex.Group> queue = new ArrayDeque<>();
        seen.add(start);
        visited.add(start);
        queue.add(start);
        boolean attached = false;
        boolean unloaded = false;
        int blocks = 0;
        search:
        while (!queue.isEmpty()) {
            SectionIndex.Group g = queue.poll();
            blocks += g.blocks();
            if (visited.size() > GROUP_BUDGET || blocks > BLOCK_BUDGET) {
                attached = true;
                break;
            }
            SectionIndex home = g.section();
            looked.add(home);
            List<int[]> portals = home.portals(g);
            for (int i = 0; i < portals.size(); i++) {
                int[] p = portals.get(i);
                int face = p[3], axis = face / 2, dir = face % 2 == 0 ? 1 : -1;
                int nx = p[0] + (axis == 0 ? dir : 0), ny = p[1] + (axis == 1 ? dir : 0), nz = p[2] + (axis == 2 ? dir : 0);
                SectionIndex other = view(views, nx >> 4, ny >> 4, nz >> 4);
                if (other == null) {
                    attached = true;
                    unloaded = true;
                    break search;
                }
                looked.add(other);
                for (SectionIndex.Group n : other.touching(SectionIndex.cell(nx & 15, ny & 15, nz & 15), face ^ 1, home.portalMask(g, i))) {
                    if (seen.add(n)) {
                        Closure meets = assigned.get(n);
                        if (meets != null) {
                            return merge(meets, visited, assigned, hypothetical);
                        }
                        visited.add(n);
                        queue.add(n);
                    }
                }
            }
        }
        Closure closure = new Closure(visited, attached);
        for (SectionIndex.Group g : visited) {
            assigned.put(g, closure);
        }
        if (!hypothetical) {
            remember(closure, looked, unloaded);
        }
        return closure;
    }

    private Closure merge(Closure into, List<SectionIndex.Group> extra, Map<SectionIndex.Group, Closure> assigned,
                          boolean hypothetical) {
        for (SectionIndex.Group g : extra) {
            if (!into.groups.contains(g)) {
                into.groups.add(g);
            }
            assigned.put(g, into);
        }
        return into;
    }

    private void remember(Closure closure, Set<SectionIndex> looked, boolean unloaded) {
        SectionIndex.Island info = new SectionIndex.Island();
        info.attached = closure.attached;
        info.unloaded = unloaded;
        info.epoch = epoch;
        info.sections = looked.toArray(new SectionIndex[0]);
        info.versions = new int[info.sections.length];
        for (int i = 0; i < info.sections.length; i++) {
            info.versions[i] = info.sections[i].version();
        }
        info.members = closure.groups.toArray(new SectionIndex.Group[0]);
        for (SectionIndex.Group g : closure.groups) {
            g.island = info;
        }
    }

    private Closure remembered(SectionIndex.Group g) {
        SectionIndex.Island info = g.island;
        if (info == null) {
            return null;
        }
        if (info.unloaded && info.epoch != epoch) {
            return null;
        }
        for (int i = 0; i < info.sections.length; i++) {
            SectionIndex s = info.sections[i];
            if (s.version() != info.versions[i] || cache.get(key(s.sx, s.sy, s.sz)) != s) {
                return null;
            }
        }
        List<SectionIndex.Group> members = new ArrayList<>(List.of(info.members));
        for (SectionIndex.Group m : members) {
            if (m.island != info) {
                return null;
            }
        }
        return new Closure(members, info.attached);
    }
}
