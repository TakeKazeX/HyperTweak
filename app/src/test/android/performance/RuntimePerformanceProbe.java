import android.os.Debug;
import android.util.Log;
import com.takekazex.hypertweak.hook.base.HostMembers;
import com.takekazex.hypertweak.hook.base.HostAccess;
import com.takekazex.hypertweak.util.logging.DebugLog;
import com.takekazex.hypertweak.hook.rules.backgesture.hooks.core.HookRuntimeCore;
import java.util.Locale;

/** Detached ART microbenchmarks. No hooks, windows, settings writes or device input. */
public final class RuntimePerformanceProbe {
    public static class Fixture {
        public int value;
        public Object unique() { return this; }
        public void overload() {}
        public void overload(int a) {}
        public void overload(String a) {}
        public void overload(long a) {}
        public void overload(float a) {}
        public void overload(double a) {}
        public void overload(boolean a) {}
        public void overload(byte a) {}
    }
    private static final Class<?>[] EMPTY = {};
    private static final Class<?>[] INT = {int.class};
    private static final Class<?>[] BYTE = {byte.class};
    private static volatile Object sink;
    private static int descriptions;
    private static boolean guarded;
    private static ClassValue<?> cache;
    private static final Fixture fixture = new Fixture();
    private static final Object[] NO_ARGS = {};
    private static Core core;
    /** Only inherited reflection helpers run; no hook registration or host mutation. */
    private static final class Core extends HookRuntimeCore {
        Object fieldLookup() throws Exception { return findCachedField(Fixture.class, "value"); }
        Object exactInvoke() throws Exception { return invokeMethod(fixture, "unique", EMPTY, NO_ARGS); }
        Object anyInvoke() throws Exception { return invokeAnyMethod(fixture, "unique", NO_ARGS); }
        void verify() throws Exception {
            if (exactInvoke() != fixture || anyInvoke() != fixture) throw new AssertionError("invocation");
            Object a = readFieldOrNull(fixture, "missing"), b = readFieldOrNull(fixture, "missing");
            if (!(a instanceof String) || a == b) throw new AssertionError("failure sentinel identity");
            for (int i = 0; i < 2; i++) {
                try { findCachedField(Fixture.class, "missing"); throw new AssertionError("missing field"); }
                catch (NoSuchFieldException expected) { }
                try { invokeMethod(fixture, "absent", EMPTY, NO_ARGS); throw new AssertionError("missing method"); }
                catch (NoSuchMethodException expected) { }
            }
        }
        protected void invalidateOpenTransitionSnapshot(OpenTransitionSnapshot s, String r) {}
        protected int readTransitionDebugId(Object o) { return 0; }
        protected boolean isMiuiHomeLauncherOpenType(String s) { return false; }
        protected int resolveTaskInfoActivityType(Object o) { return 0; }
        protected int resolveTaskInfoWindowingMode(Object o) { return 0; }
        protected void publishSystemUiInputArbiterState(android.content.Context c, boolean b, String r) {}
        protected void publishStandardReturnHomeCommit(int t, int d, Object c, Object f, boolean b) {}
        protected void clearLegacyBackGuard(String r) {}
        protected boolean clearSystemUiReturnHomeCommitIdentity(Object o, long id, String r) { return false; }
        protected LegacyBackAttempt armLegacyBackGuard(Object c, Object i) { return null; }
        protected void ensureAospBackAnimations(Object c, String s) {}
        protected void onSystemUiInputMonitorAttached(android.content.Context c) {}
        protected void onSystemUiInputMonitorDetached(android.content.Context c) {}
        protected int readMotionEventId(android.view.MotionEvent e) { return 0; }
        protected int readMotionEventDisplayId(android.view.MotionEvent e) { return 0; }
        protected boolean isCurrentHeadlessNavBarLifecycle(Object o) { return false; }
        protected boolean isHyperOsSlideAnimationEnabled() { return false; }
        protected java.lang.reflect.Method requireExactDeclaredMethod(Class<?> c, String n, String r, String... p)
                throws NoSuchMethodException { throw new NoSuchMethodException(); }
    }
    private static String describe(int i) {
        descriptions++;
        return "frame=" + i + ", fields=" + java.util.Arrays.toString(Fixture.class.getDeclaredFields());
    }
    private static void verifyLogging() throws Exception {
        java.lang.reflect.Field backendField = DebugLog.class.getDeclaredField("backend");
        backendField.setAccessible(true);
        Object backend = backendField.get(null);
        java.lang.reflect.Method bind = java.util.Arrays.stream(backend.getClass().getDeclaredMethods())
            .filter(m -> m.getName().equals("bind")).findFirst().orElseThrow();
        bind.setAccessible(true);
        java.util.concurrent.atomic.AtomicInteger level = new java.util.concurrent.atomic.AtomicInteger(Log.ERROR);
        kotlin.jvm.functions.Function0<Integer> reader = level::get;
        bind.invoke(backend, reader, null);
        int before = DebugLog.INSTANCE.reportedProblemCount();
        DebugLog.INSTANCE.w("PerfProbe", "filtered warning", null);
        DebugLog.INSTANCE.hookSkippedDebug("PerfProbe", "fixture", "expected skip");
        if (DebugLog.INSTANCE.reportedProblemCount() != before + 2) throw new AssertionError("audit count");
        descriptions = 0;
        DebugLog.INSTANCE.d("PerfProbe", () -> describe(0));
        if (descriptions != 0 || DebugLog.INSTANCE.isEnabled(Log.DEBUG)) throw new AssertionError("disabled supplier");
        level.set(Log.DEBUG);
        DebugLog.INSTANCE.d("PerfProbe", () -> describe(0));
        if (descriptions != 1 || !DebugLog.INSTANCE.isEnabled(Log.DEBUG)) throw new AssertionError("live enabled supplier");
        java.util.concurrent.atomic.AtomicInteger controls = new java.util.concurrent.atomic.AtomicInteger();
        AutoCloseable observer = DebugLog.INSTANCE.observeControls(controls::incrementAndGet);
        DebugLog.INSTANCE.controlsChanged();
        if (controls.get() != 1) throw new AssertionError("control observer");
        observer.close(); observer.close(); DebugLog.INSTANCE.controlsChanged();
        if (controls.get() != 1) throw new AssertionError("retired control observer");
        DebugLog.INSTANCE.prepareForHotReload();
        if (DebugLog.INSTANCE.isEnabled(Log.DEBUG)) throw new AssertionError("retired logger");
        System.out.println("PASS logging disabled_supplier enabled_supplier live_level audit_count retirement");
    }
    private static long allocated() {
        return Long.parseLong(Debug.getRuntimeStat("art.gc.bytes-allocated"));
    }
    private static void operation(String name, int i) throws Exception {
        switch (name) {
            case "control": sink = Fixture.class; break;
            case "cold_bundle":
                cache.remove(Fixture.class);
                sink = HostMembers.INSTANCE.field(Fixture.class, "value");
                sink = HostMembers.INSTANCE.declaredField(Fixture.class, "value", true);
                sink = HostMembers.INSTANCE.method(Fixture.class, "overload", INT);
                sink = HostMembers.INSTANCE.requirePublicMethod(Fixture.class, "overload", INT, true);
                break;
            case "allocation_control": sink = new byte[64]; break;
            case "gesture_field": sink = core.fieldLookup(); break;
            case "gesture_exact": sink = core.exactInvoke(); break;
            case "gesture_any_unchanged": sink = core.anyInvoke(); break;
            case "field_inherited": sink = HostMembers.INSTANCE.field(Fixture.class, "value"); break;
            case "field_exact": sink = HostMembers.INSTANCE.declaredField(Fixture.class, "value", true); break;
            case "method_inherited": sink = HostMembers.INSTANCE.method(Fixture.class, "overload", INT); break;
            case "method_forwarded": sink = HostAccess.INSTANCE.method(Fixture.class, "overload", INT); break;
            case "method_exact": sink = HostMembers.INSTANCE.requirePublicMethod(Fixture.class, "overload", INT, true); break;
            case "method_miss": sink = HostMembers.INSTANCE.publicMethod(Fixture.class, "absent", EMPTY, true); break;
            case "overload_eight":
                // Populate a single name with eight distinct actual Class signatures.
                sink = HostMembers.INSTANCE.publicMethod(Fixture.class, "overload", BYTE, true); break;
            case "diagnostic_disabled":
                if (!guarded || DebugLog.INSTANCE.isEnabled(Log.DEBUG)) DebugLog.INSTANCE.d("PerfProbe", describe(i));
                break;
            default: throw new IllegalArgumentException(name);
        }
    }
    public static void main(String[] args) throws Exception {
        android.os.Looper.prepareMainLooper();
        core = new Core();
        core.verify();
        guarded = args.length > 0 && args[0].equals("guarded");
        if (args.length > 0 && args[0].equals("logging-check")) { verifyLogging(); return; }
        java.lang.reflect.Field field = HostMembers.class.getDeclaredField("members");
        field.setAccessible(true);
        cache = (ClassValue<?>) field.get(null);
        for (Class<?> p : new Class<?>[]{int.class, String.class, long.class, float.class,
                double.class, boolean.class, byte.class}) {
            HostMembers.INSTANCE.publicMethod(Fixture.class, "overload", new Class<?>[]{p}, true);
        }
        HostMembers.INSTANCE.publicMethod(Fixture.class, "overload", EMPTY, true);
        System.out.println("META fingerprint=" + android.os.Build.FINGERPRINT
            + " runtime=" + System.getProperty("java.vm.version"));
        System.out.println("case,round,iterations,wall_ns_op,cpu_ns_op,allocated_bytes_op,descriptions");
        for (String name : new String[]{"control", "allocation_control", "field_inherited", "field_exact",
                "method_inherited", "method_forwarded", "method_exact", "method_miss", "overload_eight", "gesture_field",
                "gesture_exact", "gesture_any_unchanged", "diagnostic_disabled", "cold_bundle"}) {
            int n = name.equals("cold_bundle") ? 2000 : name.equals("diagnostic_disabled") ? 20000 : 200000;
            for (int round = -4; round < 9; round++) {
                descriptions = 0;
                long bytes = allocated(), cpu = Debug.threadCpuTimeNanos(), start = System.nanoTime();
                for (int i = 0; i < n; i++) operation(name, i);
                long time = System.nanoTime() - start, usedCpu = Debug.threadCpuTimeNanos() - cpu;
                long used = allocated() - bytes;
                if (round >= 0) System.out.printf(Locale.US, "%s,%d,%d,%.3f,%.3f,%.3f,%d%n",
                    name, round, n, (double) time/n, (double) usedCpu/n, (double) used/n, descriptions);
            }
        }
    }
}
