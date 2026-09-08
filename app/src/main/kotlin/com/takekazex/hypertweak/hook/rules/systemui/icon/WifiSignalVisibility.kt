package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.hook.base.BaseHooker
import com.takekazex.hypertweak.util.DebugLog
import java.util.WeakHashMap

/**
 * Keeps the host WifiIcon StateFlow intact while masking it only after a custom slot is ready.
 * The original flow is collected through its saved reference, so the getter never feeds the
 * replacement back into the source collector.
 */
object WifiSignalVisibility {
    private const val TAG = "IconTuner"
    private const val VM_CLASS =
        "com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel.WifiViewModel"

    private val lock = Any()
    private val registrations = WeakHashMap<Any, Registration>()

    class Registration internal constructor(
        val exposedFlow: Any,
        private val mutableFlow: Any,
        private val hiddenValue: Any,
        initial: Any
    ) {
        private var original: Any = initial
        private var hidden = false
        private var lastPublished: Any? = null

        init {
            publishIfChanged(force = true)
        }

        @Synchronized
        fun updateOriginal(value: Any?): Boolean {
            if (value == null) return false
            original = value
            return publishIfChanged()
        }

        @Synchronized
        fun setHidden(value: Boolean): Boolean {
            hidden = value
            return publishIfChanged()
        }

        @Synchronized
        fun clearMask(): Boolean {
            hidden = false
            return publishIfChanged()
        }

        private fun publishIfChanged(force: Boolean = false): Boolean {
            val next = if (hidden) hiddenValue else original
            if (!force && next === lastPublished) return false
            IconTunerFlows.setFlowValue(mutableFlow, next)
            lastPublished = next
            return true
        }
    }

    /** Installs the host getter bridge; no concrete WifiViewModel field is overwritten. */
    fun installGetter(hooker: BaseHooker): Boolean {
        val vmClass = with(hooker) { VM_CLASS.toClassOrNull() } ?: run {
            DebugLog.hookSkipped(TAG, VM_CLASS, "class not found")
            return false
        }
        val getter = with(hooker) {
            vmClass.findMethodOrNull { name("getWifiIcon"); noParams() }
        } ?: run {
            DebugLog.hookSkipped(TAG, "$VM_CLASS#getWifiIcon", "method not found")
            return false
        }
        with(hooker) {
            getter.hook {
                before { param -> exposedFor(param.thisObject)?.let { param.result = it } }
            }
        }
        return true
    }

    fun register(viewModel: Any, originalFlow: Any, hiddenValue: Any): Registration? {
        val initial = IconTunerFlows.readFlowValue(originalFlow) ?: run {
            DebugLog.w(TAG, "Wi-Fi icon flow has no initial value")
            return null
        }
        val exposed = runCatching {
            IconTunerFlows.createReadonlyStateFlow(initial)
        }.onFailure { DebugLog.w(TAG, "could not create Wi-Fi visibility flow", it) }
            .getOrNull() ?: return null
        val mutable = IconTunerFlows.mutableOfReadonly(exposed) ?: exposed
        val registration = Registration(exposed, mutable, hiddenValue, initial)
        synchronized(lock) { registrations[viewModel] = registration }
        return registration
    }

    fun exposedFor(viewModel: Any?): Any? = synchronized(lock) {
        viewModel?.let { registrations[it]?.exposedFlow }
    }

    fun unregister(viewModel: Any): Registration? = synchronized(lock) {
        registrations.remove(viewModel)
    }

    fun clearForHotReload() {
        synchronized(lock) {
            registrations.values.toList().forEach(Registration::clearMask)
            registrations.clear()
        }
    }

    fun setHidden(viewModel: Any, hidden: Boolean): Boolean = synchronized(lock) {
        registrations[viewModel]?.setHidden(hidden) ?: false
    }

    fun isVisibleIcon(value: Any?, visibleClass: Class<*>?): Boolean =
        value != null && (visibleClass?.isInstance(value) == true ||
            value.javaClass.name.endsWith("WifiIcon\$Visible"))
}
