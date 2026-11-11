package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap

/**
 * A module switch spliced into a host pipeline flow, so a setting applies without a rebind.
 *
 * The view-model getter keeps returning the host's own flow, combined with this gate's host
 * `MutableStateFlow<Boolean>` through the host's `flowCombine`. The native binder collects the
 * combined flow for the view's lifetime, so [set] re-emits through the binder and the icon changes
 * the way it does for a network event. A getter typed as `StateFlow` gets a `StateFlow` facade over
 * the same combined flow.
 *
 * Every host type is resolved from the host loader ([IconTunerFlows] explains why), and a missing
 * contract returns null so the caller keeps the native flow.
 */
internal class LiveFlowGate(private val name: String, initial: Boolean) {
    private val state: Any? = IconTunerFlows.createMutableStateFlow(initial)

    @Volatile
    var hidden: Boolean = initial
        private set

    /** Wrapped flows keyed by the host flow; a getter is called again on every rebind. */
    private val wrapped = Collections.synchronizedMap(WeakHashMap<Any, WeakReference<Any>>())

    fun set(value: Boolean) {
        if (hidden == value) return
        hidden = value
        state?.let { IconTunerFlows.setFlowValue(it, value) }
    }

    /**
     * The host flow [original], forced to [hiddenValue] while the gate is hidden. Null when the
     * host flow contract is unavailable or the result would not satisfy [returnType].
     */
    fun wrap(original: Any, returnType: Class<*>, hiddenValue: (Any?) -> Any?): Any? {
        wrapped[original]?.get()?.let { return it }
        val gate = state ?: return null
        val host = HostFlows.resolve(original.javaClass.classLoader) ?: return null
        return runCatching {
            val transform = Proxy.newProxyInstance(host.loader, arrayOf(host.function3)) { proxy, method, args ->
                when (method.name) {
                    "invoke" -> if (args?.getOrNull(1) == true) hiddenValue(args[0]) else args?.getOrNull(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "HyperTweakGate($name)"
                    else -> null
                }
            }
            val combined = host.distinct.invoke(null, host.combine.invoke(null, original, gate, transform))
                ?: return null
            val result = if (host.stateFlow.isAssignableFrom(returnType)) {
                stateFacade(host, original, combined, hiddenValue)
            } else combined
            result.takeIf(returnType::isInstance)?.also { wrapped[original] = WeakReference(it) }
        }.onFailure { DebugLog.w(TAG, "live gate $name unavailable; keeping the native flow", it) }.getOrNull()
    }

    private fun stateFacade(host: HostFlows, original: Any, combined: Any, hiddenValue: (Any?) -> Any?): Any {
        fun current(): Any? = IconTunerFlows.readFlowValue(original).let { if (hidden) hiddenValue(it) else it }
        return Proxy.newProxyInstance(host.loader, arrayOf(host.stateFlow)) { proxy, method, args ->
            when (method.name) {
                "getValue" -> current()
                "getReplayCache" -> listOf(current())
                "collect" -> try {
                    host.collect.invoke(combined, *(args ?: emptyArray()))
                } catch (failure: InvocationTargetException) {
                    throw failure.targetException
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "HyperTweakGateState($name)"
                else -> null
            }
        }
    }

    private class HostFlows(
        val loader: ClassLoader,
        val function3: Class<*>,
        val stateFlow: Class<*>,
        val combine: Method,
        val distinct: Method,
        val collect: Method
    ) {
        companion object {
            @Volatile private var cached: HostFlows? = null

            fun resolve(loader: ClassLoader?): HostFlows? {
                loader ?: return null
                cached?.takeIf { it.loader === loader }?.let { return it }
                return runCatching {
                    fun type(pkg: String, simple: String) =
                        Class.forName(IconTunerFlows.hostClassName(pkg, simple), false, loader)
                    val flow = type("kotlinx.coroutines.flow", "Flow")
                    val flowKt = type("kotlinx.coroutines.flow", "FlowKt")
                    val function3 = type("kotlin.jvm.functions", "Function3")
                    HostFlows(
                        loader, function3, type("kotlinx.coroutines.flow", "StateFlow"),
                        flowKt.getMethod("flowCombine", flow, flow, function3),
                        flowKt.getMethod("distinctUntilChanged", flow),
                        flow.getMethod("collect", type("kotlinx.coroutines.flow", "FlowCollector"),
                            type("kotlin.coroutines", "Continuation"))
                    )
                }.onFailure { DebugLog.w(TAG, "host flowCombine contract unavailable", it) }
                    .getOrNull()?.also { cached = it }
            }
        }
    }

    private companion object { const val TAG = "IconTuner" }
}
