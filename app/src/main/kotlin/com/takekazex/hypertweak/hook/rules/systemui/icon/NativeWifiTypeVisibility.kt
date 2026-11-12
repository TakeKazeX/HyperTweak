package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.hook.base.BaseHooker
import com.takekazex.hypertweak.hook.base.HookComponent
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Proxy

/** Supplies an extra visibility reason to the native binder while preserving its original flows. */
internal class NativeWifiTypeVisibility(owner: BaseHooker) : HookComponent(owner) {
    private companion object {
        const val TAG = "NativeWifiType"
        const val NO_SUBSCRIPTION = -1
        const val VM = "com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MiuiMobileIconVMImpl"
        const val INTERACTOR = "com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconInteractorImpl"
    }

    private var activeDataGate: Any? = null

    fun install(): Boolean {
        val installed = runCatching {
            val vm = VM.toClass()
            val interactor = INTERACTOR.toClass()
            val flow = IconTunerFlows.hostClassName("kotlinx.coroutines.flow", "Flow").toClass()
            val transform = IconTunerFlows.hostClassName("kotlin.jvm.functions", "Function3").toClass()
            val flowKt = IconTunerFlows.hostClassName("kotlinx.coroutines.flow", "FlowKt").toClass()
            val single = HostAccess.requirePublicMethod(vm, "getMobileTypeSingleVisible", accessible = false)
            val origin = HostAccess.requirePublicMethod(vm, "getOriginIconInteractor", accessible = false)
            val subId = HostAccess.requirePublicMethod(interactor, "getSubscriptionId", accessible = false)
            val combine = HostAccess.requirePublicMethod(flowKt, "flowCombine", flow, flow, transform, accessible = false)
            check(single.returnType == flow &&
                origin.returnType.isAssignableFrom(interactor) && subId.returnType == Int::class.javaPrimitiveType &&
                flow.isAssignableFrom(combine.returnType))
            // Existing host binders may still collect a flow combined with the previous hook
            // generation. Reuse that gate so producer recovery can update those living binders.
            val gate = synchronized(this) {
                activeDataGate?.takeIf(flow::isInstance)
                    ?: checkNotNull(IconTunerFlows.createMutableStateFlow(NO_SUBSCRIPTION))
            }
            check(flow.isInstance(gate))
            deoptimize(single)
            single.hook {
                after { param ->
                    val original = param.result?.takeIf(flow::isInstance) ?: return@after
                    runCatching {
                        val target = param.thisObject
                        val subscription = origin.invoke(target)?.takeIf(interactor::isInstance) ?: return@runCatching
                        val id = subId.invoke(subscription) as? Int ?: return@runCatching
                        if (id < 0) return@runCatching
                        val reducer = Proxy.newProxyInstance(classLoader, arrayOf(transform)) { proxy, method, args ->
                            when (method.name) {
                                "invoke" -> args?.getOrNull(0) == true || args?.getOrNull(1) == id
                                "equals" -> proxy === args?.firstOrNull()
                                "hashCode" -> System.identityHashCode(proxy)
                                "toString" -> TAG
                                else -> null
                            }
                        }
                        combine.invoke(null, original, gate, reducer)
                            ?.takeIf(flow::isInstance)?.let { param.result = it }
                    }.onFailure { DebugLog.w(TAG, "could not combine native visibility for one subscription", it) }
                }
            }
            synchronized(this) { activeDataGate = gate }
        }.onFailure { DebugLog.hookSkipped(TAG, "native type on Wi-Fi", it.message ?: "unsupported host flow") }
        if (installed.isSuccess) DebugLog.hookRegistered(TAG, "native single type on Wi-Fi")
        return installed.isSuccess
    }

    @Synchronized
    fun publish(subId: Int?) {
        activeDataGate?.let { gate ->
            val next = subId ?: NO_SUBSCRIPTION
            if (IconTunerFlows.readFlowValue(gate) != next) IconTunerFlows.setFlowValue(gate, next)
        }
    }

    @Synchronized
    fun retire() {
        publish(null)
    }
}
