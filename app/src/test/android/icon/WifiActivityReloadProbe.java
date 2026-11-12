import android.content.Context;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Exercises retained production gates with actual host flows; no settings or host UI writes. */
public final class WifiActivityReloadProbe {
    private static final String P = "com.takekazex.hypertweak.hook.rules.systemui.icon.";
    private static Field field(Object owner, String name) throws Exception {
        Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f;
    }
    private static void hide(Object adapter, boolean value) throws Exception {
        Method set = adapter.getClass().getDeclaredMethod("setHideActivity", boolean.class);
        set.setAccessible(true); set.invoke(adapter, value);
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void await(List<Object> events, Object expected) throws Exception {
        long until = System.nanoTime() + 3_000_000_000L;
        do {
            if (!events.isEmpty() && Objects.equals(events.get(events.size() - 1), expected)) return;
            Thread.sleep(5);
        } while (System.nanoTime() < until);
        throw new AssertionError("bound collector did not receive " + expected + ": " + events);
    }
    public static void main(String[] args) throws Exception {
        android.os.Looper.prepareMainLooper();
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object activityThread = thread.getMethod("systemMain").invoke(null);
        Context context = (Context) thread.getMethod("getSystemContext").invoke(activityThread);
        run(context, new dalvik.system.PathClassLoader(args[0], ClassLoader.getSystemClassLoader()));
    }
    public static void run(Context context, ClassLoader module) throws Exception {
        ClassLoader host = context.createPackageContext("com.android.systemui",
            Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY).getClassLoader();
        Class<?> flows = module.loadClass(P + "IconTunerFlows"); Object flowFactory = flows.getField("INSTANCE").get(null);
        flows.getMethod("init", ClassLoader.class).invoke(flowFactory, host);
        Object original = flows.getMethod("createMutableStateFlow", Object.class).invoke(flowFactory, 1234);
        check(original != null, "actual host mutable flow");
        Class<?> gateType = module.loadClass(P + "LiveFlowGate");
        Object gate = gateType.getConstructor(String.class, boolean.class).newInstance("Wi-Fi activity test", true);
        Class<?> function1 = module.loadClass("kotlin.jvm.functions.Function1");
        Object zero = Proxy.newProxyInstance(module, new Class<?>[]{function1}, (p, m, a) -> 0);
        Class<?> stateFlow = host.loadClass("kotlinx.coroutines.flow.StateFlow");
        Object wrapped = gateType.getMethod("wrap", Object.class, Class.class, function1).invoke(gate, original, stateFlow, zero);
        check(wrapped != null && stateFlow.isInstance(wrapped), "actual host StateFlow wrapper");

        Object adapter = module.loadClass(P + "WifiPipelineAdapter").getField("INSTANCE").get(null);
        Class<?> vmType = module.loadClass(P + "WifiPipelineAdapter$VmState");
        Constructor<?> vmConstructor = vmType.getDeclaredConstructor(); vmConstructor.setAccessible(true);
        Object vm = vmConstructor.newInstance(); field(vm, "activityGate").set(vm, gate);
        Class<?> function0 = module.loadClass("kotlin.jvm.functions.Function0");
        Object registry = field(adapter, "vmStates").get(adapter);
        Object create = Proxy.newProxyInstance(module, new Class<?>[]{function0}, (p, m, a) -> vm);
        Object retainedVm = new Object();
        registry.getClass().getMethod("getOrPut", Object.class, function0).invoke(registry, retainedVm, create);
        hide(adapter, true);
        Method rearm = adapter.getClass().getDeclaredMethod("restoreVmGates"); rearm.setAccessible(true);
        Method retire = adapter.getClass().getMethod("onPrepareHotReload");
        Class<?> dispatchers = host.loadClass("kotlinx.coroutines.Dispatchers");
        Object unconfined = dispatchers.getField("Unconfined").get(null);
        Object scope = host.loadClass("kotlinx.coroutines.CoroutineScopeKt").getMethod("CoroutineScope",
            host.loadClass("kotlin.coroutines.CoroutineContext")).invoke(null, unconfined);
        Method collect = Arrays.stream(host.loadClass("com.android.systemui.util.kotlin.JavaAdapter").getMethods())
            .filter(m -> m.getName().equals("alwaysCollectFlow") && Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 3)
            .findFirst().orElseThrow();
        List<Object> values = new CopyOnWriteArrayList<>();
        Object job = collect.invoke(null, scope, wrapped, (Consumer<Object>) values::add);
        try {
            await(values, 0);
            // A binder keeps this exact wrapper while generations retire/reinstall; no getter,
            // binder rebind, or native activity emission occurs between retire and re-arm.
            for (int i = 0; i < 4; i++) {
                retire.invoke(adapter); await(values, 1234);
                field(adapter, "retired").set(adapter, false);
                rearm.invoke(adapter); await(values, 0);
                check(gateType.getMethod("wrap", Object.class, Class.class, function1).invoke(gate, original, stateFlow, zero) == wrapped,
                    "reinstall retains the wrapper already collected by the binder");
            }
            hide(adapter, false); rearm.invoke(adapter); await(values, 1234);
            flows.getMethod("setFlowValue", Object.class, Object.class).invoke(flowFactory, original, 5678); await(values, 5678);
            hide(adapter, true); rearm.invoke(adapter); await(values, 0);
            retire.invoke(adapter); await(values, 5678);
        } finally {
            Method cancel = Arrays.stream(job.getClass().getMethods()).filter(m -> m.getName().equals("cancel") && m.getParameterCount() == 1)
                .findFirst().orElseThrow(); cancel.setAccessible(true); cancel.invoke(job, new Object[]{null});
        }
        System.out.println("PASS wifi_activity_retained_flow reload_cycles=4 live_toggle=2 native_updates=1 disable_restore=1");
    }
}
