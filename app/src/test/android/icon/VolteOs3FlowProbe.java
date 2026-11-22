import dalvik.system.PathClassLoader;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.CopyOnWriteArrayList;

/** Runs the production VoLTE gate against an extracted OS3 SystemUI in a detached ART process. */
public final class VolteOs3FlowProbe {
    private static final String ICON_PACKAGE = "com.takekazex.hypertweak.hook.rules.systemui.icon.";

    public static void main(String[] args) throws Exception {
        android.os.Looper.prepareMainLooper();
        ClassLoader module = new PathClassLoader(args[0], ClassLoader.getSystemClassLoader());
        ClassLoader host = new PathClassLoader(args[1], ClassLoader.getSystemClassLoader());
        Class<?> flow = host.loadClass("kotlinx.coroutines.flow.Flow");
        Class<?> function1 = module.loadClass("kotlin.jvm.functions.Function1");
        Class<?> flowFactory = module.loadClass(ICON_PACKAGE + "IconTunerFlows");
        Object factory = flowFactory.getField("INSTANCE").get(null);
        flowFactory.getMethod("init", ClassLoader.class).invoke(factory, host);

        Method createPair = flowFactory.getMethod("createPair", Object.class, Object.class);
        Object visible = createPair.invoke(factory, true, true);
        Object hidden = createPair.invoke(factory, false, false);
        Object newNative = createPair.invoke(factory, true, false);
        Method setNative = flowFactory.getMethod("setFlowValue", Object.class, Object.class);
        Object nativeFlow = flowFactory.getMethod("createMutableStateFlow", Object.class).invoke(factory, visible);

        Class<?> cellular = module.loadClass(ICON_PACKAGE + "CellularIconHooker");
        Object hooker = cellular.getField("INSTANCE").get(null);
        Method hideValue = cellular.getDeclaredMethod("hiddenValue", Object.class);
        hideValue.setAccessible(true);
        Object transform = Proxy.newProxyInstance(module, new Class<?>[]{function1},
            (proxy, method, arguments) -> hideValue.invoke(hooker, arguments[0]));
        Class<?> gateType = module.loadClass(ICON_PACKAGE + "LiveFlowGate");
        var constructor = gateType.getConstructor(String.class, boolean.class, boolean.class);
        Object gate = constructor.newInstance("VoLTE", false, true);
        Method wrap = gateType.getMethod("wrap", Object.class, Class.class, function1);
        Object wrapped = wrap.invoke(gate, nativeFlow, flow, transform);
        if (!flow.isInstance(wrapped) || wrapped == nativeFlow) {
            throw new AssertionError("OS3 host combine did not wrap the native flow");
        }

        Class<?> scopeType = host.loadClass("kotlinx.coroutines.CoroutineScope");
        Class<?> contextType = host.loadClass("kotlin.coroutines.CoroutineContext");
        Object unconfined = host.loadClass("kotlinx.coroutines.Dispatchers").getField("Unconfined").get(null);
        Object scope = host.loadClass("kotlinx.coroutines.CoroutineScopeKt")
            .getMethod("CoroutineScope", contextType).invoke(null, unconfined);
        Class<?> adapterType = host.loadClass("com.android.systemui.util.kotlin.JavaAdapter");
        Object adapter = adapterType.getConstructor(scopeType).newInstance(scope);
        List<Object> events = new CopyOnWriteArrayList<>();
        Object job = adapterType.getMethod("alwaysCollectFlow", flow, Consumer.class)
            .invoke(adapter, wrapped, (Consumer<Object>) events::add);
        Method set = gateType.getMethod("set", boolean.class);
        try {
            WifiActivityReloadProbe.await(events, visible);
            set.invoke(gate, true);
            WifiActivityReloadProbe.await(events, hidden);
            setNative.invoke(factory, nativeFlow, newNative);
            set.invoke(gate, false);
            WifiActivityReloadProbe.await(events, newNative);
            setNative.invoke(factory, nativeFlow, visible);
            WifiActivityReloadProbe.await(events, visible);
        } finally {
            host.loadClass("kotlinx.coroutines.Job")
                .getMethod("cancel", java.util.concurrent.CancellationException.class)
                .invoke(job, new Object[]{null});
        }

        Object otherGate = constructor.newInstance("no OS3 fallback", false, false);
        if (wrap.invoke(otherGate, nativeFlow, flow, transform) != null) {
            throw new AssertionError("OS3 fallback leaked to a gate that did not opt in");
        }
        System.out.println("PASS volte_os3_flow live_toggle=2 native_restore=1 fallback_isolated=1");
    }
}
