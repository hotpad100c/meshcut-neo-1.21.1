package ml.mypals.meshcut.network;

import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/** Ordered prediction inputs; confirmation retires them only after the matching authority arrives. */
public final class PendingCuts<K, I, S> {
    private static final class Operation<K,I,S> {
        final long started;
        final Map<K,I> inputs;
        Map<K,S> stamps;
        Operation(long started, Map<K,I> inputs) { this.started=started; this.inputs=new LinkedHashMap<>(inputs); }
    }
    private final Map<Long,Operation<K,I,S>> operations=new LinkedHashMap<>();

    public void add(long id,long tick,Map<K,I> inputs) {
        if (!inputs.isEmpty()) operations.putIfAbsent(id,new Operation<>(tick,inputs));
    }
    public int size() { return operations.size(); }
    public void clear() { operations.clear(); }
    public List<I> inputs(K key) {
        List<I> result=new ArrayList<>();
        for (var op:operations.values()) {
            I input=op.inputs.get(key); if(input!=null) result.add(input);
        }
        return result;
    }
    public Set<K> acknowledge(long id,boolean accepted,Map<K,S> stamps) {
        var op=operations.get(id);
        if(op==null) return Set.of();
        Set<K> dirty=new LinkedHashSet<>(op.inputs.keySet());
        if(!accepted) operations.remove(id);
        else {
            op.stamps=Map.copyOf(stamps);
            op.inputs.keySet().removeIf(key->!op.stamps.containsKey(key));
            if(op.inputs.isEmpty()) operations.remove(id);
        }
        return dirty;
    }
    public Set<K> reconcile(long tick,Predicate<K> loaded,BiPredicate<K,S> confirmed) {
        Set<K> dirty=new LinkedHashSet<>();
        var iterator=operations.values().iterator();
        while(iterator.hasNext()) {
            var op=iterator.next();
            var keys=op.inputs.keySet().iterator();
            while(keys.hasNext()) {
                K key=keys.next();
                if(!loaded.test(key)||tick-op.started>100 || op.stamps!=null&&confirmed.test(key,op.stamps.get(key))) {
                    keys.remove();dirty.add(key);
                }
            }
            if(op.inputs.isEmpty())iterator.remove();
        }
        return dirty;
    }
}
