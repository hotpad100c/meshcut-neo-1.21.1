package ml.mypals.meshcut.client.compat;

import java.lang.reflect.Method;

/** Whether Iris is running a shader pack. Looked up by name: Iris is not a dependency. */
public final class IrisCompat {
    private static Method inUse;
    private static Object api;
    private static boolean looked;

    public static boolean shadersInUse() {
        try {
            if (!looked) {
                looked = true;
                Class<?> type = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                api = type.getMethod("getInstance").invoke(null);
                inUse = type.getMethod("isShaderPackInUse");
            }
            return inUse != null && Boolean.TRUE.equals(inUse.invoke(api));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            inUse = null;
            return false;
        }
    }

    private IrisCompat() {}
}
