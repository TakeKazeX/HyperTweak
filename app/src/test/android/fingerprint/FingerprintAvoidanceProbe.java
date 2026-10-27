import android.content.Context;
import android.graphics.Rect;
import java.lang.reflect.*;

/** No windows, activities or hooks: compare production policy with the host's actual transforms. */
public final class FingerprintAvoidanceProbe {
    static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field field = unsafeType.getDeclaredField("theUnsafe"); field.setAccessible(true);
        Object unsafe = field.get(null);
        return unsafeType.getMethod("allocateInstance", Class.class).invoke(unsafe, type);
    }
    static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f;
    }
    public static void main(String[] args) {
        try { run(args); } catch (Throwable error) { error.printStackTrace(); System.exit(1); }
    }
    static void run(String[] args) throws Exception {
        System.out.println("PROBE_STAGE start");
        ClassLoader cl = FingerprintAvoidanceProbe.class.getClassLoader();
        android.os.Looper.prepareMainLooper();
        android.graphics.Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap").invoke(null);
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object activityThread = thread.getMethod("systemMain").invoke(null);
        Context system = (Context) thread.getMethod("getSystemContext").invoke(activityThread);
        Context context = system.createPackageContext("com.android.systemui", Context.CONTEXT_IGNORE_SECURITY);
        System.out.println("PROBE_STAGE package_context");
        Class<?> panelType = cl.loadClass("com.android.keyguard.panel.KeyguardPanelViewController");
        System.out.println("PROBE_STAGE panel_class");
        Object panel = allocate(panelType); field(panelType, "context").set(panel, context);
        Class<?> transform = cl.loadClass(args[0]);
        Class<?> bottom = cl.loadClass("com.miui.systemui.keyguard.data.repository.MiuiKeyguardRepositoryImpl$notificationBottomOnKeyguard$2$1");
        Class<?> collector = cl.loadClass("kotlinx.coroutines.flow.FlowCollector");
        Object unit = cl.loadClass("kotlin.Unit").getField("INSTANCE").get(null);
        Class<?> gxzw = cl.loadClass("com.miui.keyguard.biometrics.fod.MiuiGxzwUtils");
        Class<?> configs = cl.loadClass("com.miui.utils.configs.MiuiConfigs");
        boolean sensor = field(configs, "GXZW_SENSOR").getBoolean(null);
        boolean low = (Boolean) cl.loadClass("com.miui.systemui.util.BaseKeyguardUtils").getDeclaredMethod("isGxzwLowPosition").invoke(null);
        String prefix = "com.takekazex.hypertweak.hook.rules.systemui.lockscreen.FingerprintAvoidancePolicy";
        // Keep the module runtime separate from SystemUI's R8-shrunk Kotlin runtime.
        ClassLoader module = new dalvik.system.PathClassLoader(args[1], cl.getParent());
        Class<?> policy = module.loadClass(prefix), geometry = module.loadClass(prefix + "$Geometry");
        Constructor<?> geometryConstructor = geometry.getConstructors()[0];
        Method calculate = policy.getMethod("bottom", int.class, geometry);
        System.out.println("PROBE_STAGE native_geometry sensor=" + sensor + " low=" + low);
        int passed = 0;
        for (int mode : new int[]{1, 2}) for (int remote : new int[]{0, 2100, -1}) for (int length : new int[]{7, 8}) {
            Object[] emitted = new Object[1];
            Object sink = Proxy.newProxyInstance(cl, new Class<?>[]{collector}, (proxy, method, values) -> {
                if (method.getName().equals("emit")) { emitted[0] = values[0]; return unit; }
                throw new UnsupportedOperationException(method.toString());
            });
            Object[] inputs = new Object[length];
            Object[] nativeInputs = {18, 44, 200, remote, 2400, mode == 2, mode == 2};
            System.arraycopy(nativeInputs, 0, inputs, 0, 7);
            if (length == 8) inputs[7] = 99; // Another module's appended policy cannot alter our inputs.
            Object lambda = allocate(transform);
            field(transform, "this$0").set(lambda, panel);
            field(transform, "L$0").set(lambda, sink);
            field(transform, "L$1").set(lambda, inputs);
            transform.getMethod("invokeSuspend", Object.class).invoke(lambda, unit);
            if (emitted[0] == null) throw new AssertionError("Native transform did not emit synchronously");
            Object downstream = allocate(bottom);
            field(bottom, "L$0").set(downstream, emitted[0]);
            field(bottom, "L$1").set(downstream, new Rect(0, 0, 200, 60));
            field(bottom, "I$0").setInt(downstream, 22);
            Object expected = bottom.getMethod("invokeSuspend", Object.class).invoke(downstream, unit);
            Object g = geometryConstructor.newInstance(18, 44, remote, 2400, sensor,
                field(gxzw, "GXZW_ICON_Y").getInt(null), field(gxzw, "mGxzwIconYOffset").getInt(null), low, 60, 22);
            Object actual = calculate.invoke(policy.getField("INSTANCE").get(null), mode, g);
            if (!expected.equals(actual)) throw new AssertionError("mode=" + mode + " remote=" + remote + " native=" + expected + " module=" + actual);
            passed++;
        }
        System.out.println("PASS native_transform_equivalence=" + passed + " sensor=" + sensor + " lowPosition=" + low);
    }
}
