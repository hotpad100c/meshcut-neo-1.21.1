package ml.mypals.meshcut.network;

import net.minecraft.nbt.*;
import java.util.Objects;

/** Versioned NBT deltas. Disk saves remain ordinary complete block-entity data. */
public final class GeometrySync {
    public static final String HEADER = "meshcut_sync";
    public enum Status { APPLIED, STALE, NEED_SNAPSHOT }
    public record Decoded(Status status, CompoundTag data, long epoch, long revision) {}

    private static CompoundTag header(long epoch, long revision) {
        CompoundTag header = new CompoundTag();
        header.putInt("protocol", 1);
        header.putLong("epoch", epoch);
        header.putLong("revision", revision);
        return header;
    }

    public static CompoundTag snapshot(CompoundTag data, long epoch, long revision) {
        CompoundTag result = data.copy();
        result.put(HEADER, header(epoch, revision));
        return result;
    }

    public static CompoundTag delta(CompoundTag before, CompoundTag after, long epoch, long base, long revision) {
        CompoundTag result = new CompoundTag(), set = new CompoundTag(), append = new CompoundTag();
        ListTag removed = new ListTag();
        CompoundTag h = header(epoch, revision);
        h.putBoolean("delta", true);
        h.putLong("base", base);
        for (String key : before.getAllKeys()) if (!after.contains(key)) removed.add(StringTag.valueOf(key));
        for (String key : after.getAllKeys()) {
            Tag old = before.get(key), now = after.get(key);
            if (Objects.equals(old, now)) continue;
            if (old instanceof ListTag a && now instanceof ListTag b && a.size() > 0 && b.size() > a.size() && prefix(a,b)) {
                CompoundTag edit = new CompoundTag();
                edit.putInt("from", a.size());
                ListTag tail = new ListTag();
                for (int i = a.size(); i < b.size(); i++) tail.add(b.get(i).copy());
                edit.put("tail", tail);
                append.put(key, edit);
            } else set.put(key, now.copy());
        }
        h.put("set",set); h.put("append",append); h.put("remove",removed);
        result.put(HEADER,h);
        // Use NBT's cheap size estimate to select a checkpoint for replacements/tiny payloads.
        // sizeInBytes is an allocation estimate, not an exact serialized or compressed wire length. The header is
        // in both, so only the data is compared: the copy a snapshot makes is not worth it for a comparison.
        return result.sizeInBytes() < after.sizeInBytes() + result.getCompound(HEADER).sizeInBytes() ? result : snapshot(after,epoch,revision);
    }

    private static boolean prefix(ListTag a, ListTag b) {
        if (a.getElementType() != b.getElementType()) return false;
        for (int i = 0; i < a.size(); i++) if (!a.get(i).equals(b.get(i))) return false;
        return true;
    }

    public static Decoded decode(CompoundTag wire, CompoundTag baseline, long epoch, long revision) {
        if (!wire.contains(HEADER,Tag.TAG_COMPOUND)) return new Decoded(Status.APPLIED,wire.copy(),0,-1);
        CompoundTag h = wire.getCompound(HEADER);
        long nextEpoch=h.getLong("epoch"), nextRevision=h.getLong("revision");
        if(h.getInt("protocol")!=1) return new Decoded(Status.NEED_SNAPSHOT,null,epoch,revision);
        if(nextEpoch==epoch && nextRevision<revision) return new Decoded(Status.STALE,null,epoch,revision);
        if(!h.getBoolean("delta")) {
            CompoundTag full=wire.copy(); full.remove(HEADER);
            return new Decoded(Status.APPLIED,full,nextEpoch,nextRevision);
        }
        if(nextEpoch==epoch && nextRevision==revision) return new Decoded(Status.STALE,null,epoch,revision);
        if(baseline==null || nextEpoch!=epoch || h.getLong("base")!=revision)
            return new Decoded(Status.NEED_SNAPSHOT,null,epoch,revision);
        CompoundTag full=baseline.copy();
        for(Tag t:h.getList("remove",Tag.TAG_STRING)) full.remove(t.getAsString());
        CompoundTag set=h.getCompound("set");
        for(String key:set.getAllKeys()) full.put(key,set.get(key).copy());
        CompoundTag append=h.getCompound("append");
        for(String key:append.getAllKeys()) {
            CompoundTag edit=append.getCompound(key);
            if(!(full.get(key) instanceof ListTag list) || list.size()!=edit.getInt("from"))
                return new Decoded(Status.NEED_SNAPSHOT,null,epoch,revision);
            Tag tail=edit.get("tail");
            if(!(tail instanceof ListTag tags)) return new Decoded(Status.NEED_SNAPSHOT,null,epoch,revision);
            for(Tag t:tags) if(!list.add(t.copy())) return new Decoded(Status.NEED_SNAPSHOT,null,epoch,revision);
        }
        return new Decoded(Status.APPLIED,full,nextEpoch,nextRevision);
    }

    private GeometrySync() {}
}
