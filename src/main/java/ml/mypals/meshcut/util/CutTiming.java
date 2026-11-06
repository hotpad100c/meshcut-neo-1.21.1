package ml.mypals.meshcut.util;

import java.util.LinkedHashMap;
import java.util.Map;
import ml.mypals.meshcut.MeshCut;

public final class CutTiming implements AutoCloseable {
    private static final ThreadLocal<Report> ACTIVE = new ThreadLocal<>();
    private static final CutTiming OFF = new CutTiming(null, null, false);
    private static final class Stat { long ns, max; int calls; }
    private static final class Report { final Map<String, Stat> phases = new LinkedHashMap<>(); }
    private final Report report;
    private final String name;
    private final boolean root;
    private final long start;
    private CutTiming(Report report, String name, boolean root) {
        this.report=report; this.name=name; this.root=root;
        start=report==null ? 0 : System.nanoTime();
    }
    public static CutTiming begin() {
        if (!ml.mypals.meshcut.MeshCutSettings.diagnostics()) return OFF;
        if (ACTIVE.get()!=null) return phase("nested settlement");
        Report report=new Report(); ACTIVE.set(report);
        return new CutTiming(report,"settlement",true);
    }
    public static CutTiming phase(String name) {
        Report report=ACTIVE.get();
        return report==null ? OFF : new CutTiming(report,name,false);
    }
    @Override public void close() {
        if(report==null) return;
        long ns=System.nanoTime()-start;
        Stat stat=report.phases.computeIfAbsent(name,k->new Stat());
        stat.ns+=ns; stat.max=Math.max(stat.max,ns); stat.calls++;
        if(root) {
            ACTIVE.remove();
            if(ns<50_000_000L) return;
            StringBuilder out=new StringBuilder();
            report.phases.forEach((label,s)->out.append(label).append("=").append(Math.round(s.ns/1e6))
                    .append("ms/").append(s.calls).append(" calls (max ").append(Math.round(s.max/1e6)).append("ms); "));
            MeshCut.LOGGER.info("[meshcut diag] slow cut phases (inclusive): {}",out);
        }
    }
}
