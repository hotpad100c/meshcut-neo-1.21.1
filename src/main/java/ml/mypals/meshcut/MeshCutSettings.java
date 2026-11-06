package ml.mypals.meshcut;

public final class MeshCutSettings {
    public static volatile int preciseEntries = 64;
    public static volatile boolean regionAllOrders;
    public static volatile boolean diagnosticLogs;
    public static int maxEntries() { return Integer.getInteger("meshcut.maxEntries", preciseEntries); }
    public static boolean allRegionOrders() {
        return Integer.getInteger("meshcut.regionOrders", regionAllOrders ? 6 : 1) >= 6;
    }
    public static boolean diagnostics() {
        return System.getProperty("meshcut.diag") == null ? diagnosticLogs : Boolean.getBoolean("meshcut.diag");
    }
    private MeshCutSettings() {}
}
