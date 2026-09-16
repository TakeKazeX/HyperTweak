package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.hook.base.BaseHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Method
import java.util.WeakHashMap

/**
 * Keeps the host MIUI visibility pair intact while applying an optional first-value mask.
 *
 * SystemUI consumes both values of `MiuiMobileIconVMImpl.isVisible()`. Replacing it with a plain
 * Boolean flow loses the second value and makes the detail indicators diverge from the signal
 * group. This registry exposes a host-loader StateFlow<Pair<Boolean, Boolean>>, updates only the
 * first value, and returns the original flow through the getter whenever no registration exists.
 *
 * Because [installGetter] answers **every** caller of `isVisible()` — including this module's own
 * reflective lookup — the host flow must be resolved through [hostVisibilityFlow] and kept in
 * sync through [collectOriginal]. A registration whose `original` was captured from the exposed
 * flow instead (or never updated) reports the module's own mask back as the host's opinion, which
 * is how the stacked-signal replacement gate once ended up permanently closed.
 */
object MobileSignalVisibility {
    private const val TAG = "IconTuner"
    private const val VM_IMPL_CLASS =
        "com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MiuiMobileIconVMImpl"

    /** Host field behind the hooked getter (verified on OS4: `MiuiMobileIconVMImpl.isVisible`). */
    private const val VISIBILITY_FIELD = "isVisible"

    private val lock = Any()
    private val registrations = WeakHashMap<Any, Registration>()

    class Registration internal constructor(
        val subId: Int,
        val exposedFlow: Any,
        private val mutableFlow: Any,
        initial: PairValue
    ) {
        private var original = initial
        private var hidden = false
        private var lastPublished: PairValue? = null

        init {
            publishIfChanged(force = true)
        }

        /** Receives the original MIUI pair and never changes its second member. */
        @Synchronized
        fun updateOriginal(value: Any?): Boolean {
            val pair = pairValue(value) ?: return false
            original = pair
            return publishIfChanged()
        }

        /** Applies the native suppression mask to the first member only. */
        @Synchronized
        fun setHidden(value: Boolean): Boolean {
            hidden = value
            return publishIfChanged()
        }

        /** Hot reload must first pass through the original value before the collector is removed. */
        @Synchronized
        fun clearMask(): Boolean {
            hidden = false
            return publishIfChanged()
        }

        private fun publishIfChanged(force: Boolean = false): Boolean {
            val next = PairValue(original.first && !hidden, original.second)
            if (!force && next == lastPublished) return false
            val pair = IconTunerFlows.createPair(next.first, next.second) ?: return false
            IconTunerFlows.setFlowValue(mutableFlow, pair)
            lastPublished = next
            return true
        }
    }

    data class PairValue(val first: Boolean, val second: Boolean)

    /** Installs the VM getter bridge; no field of the concrete host VM is overwritten. */
    fun installGetter(hooker: BaseHooker): Boolean {
        val vmClass = with(hooker) { VM_IMPL_CLASS.toClassOrNull() } ?: run {
            DebugLog.hookSkipped(TAG, VM_IMPL_CLASS, "class not found")
            return false
        }
        val method = with(hooker) {
            vmClass.findMethodOrNull { name("isVisible"); noParams() }
        } ?: run {
            DebugLog.hookSkipped(TAG, "$VM_IMPL_CLASS#isVisible", "method not found")
            return false
        }
        with(hooker) {
            method.hook {
                before { param ->
                    exposedFor(param.thisObject)?.let { param.result = it }
                }
            }
        }
        return true
    }

    /** Creates an exposed StateFlow wrapper for one MIUI VM. */
    fun register(viewModel: Any, subId: Int, originalFlow: Any): Registration? {
        val initial = pairValue(IconTunerFlows.readFlowValue(originalFlow)) ?: run {
            DebugLog.w(TAG, "MIUI visibility flow has no Pair value subId=$subId")
            return null
        }
        val exposed = runCatching {
            IconTunerFlows.createReadonlyStateFlow(
                IconTunerFlows.createPair(initial.first, initial.second)
                    ?: error("could not create host visibility Pair")
            )
        }.onFailure { DebugLog.w(TAG, "could not create MIUI visibility replacement subId=$subId", it) }
            .getOrNull() ?: return null
        val mutable = IconTunerFlows.mutableOfReadonly(exposed) ?: exposed
        val registration = Registration(subId, exposed, mutable, initial)
        synchronized(lock) {
            registrations[viewModel] = registration
        }
        return registration
    }

    fun exposedFor(viewModel: Any?): Any? = synchronized(lock) {
        viewModel?.let { registrations[it]?.exposedFlow }
    }

    fun pairValueOf(value: Any?): PairValue? = pairValue(value)

    /**
     * Resolves the host Pair flow for one MIUI VM.
     *
     * The getter hook answers `isVisible()` with the module-owned exposed flow for as long as a
     * registration exists, so calling it here would record the module's own (possibly masked)
     * Pair as the host original. The host field is read first; the hooked getter is only a
     * fallback for a build whose field name differs, and it runs after dropping any registration
     * for this VM so this module can never capture its own flow.
     */
    fun hostVisibilityFlow(viewModel: Any, getter: () -> Any?): Any? {
        readVisibilityField(viewModel)?.let { return it }
        unregister(viewModel)
        return getter()
    }

    /**
     * Removes one registration and returns it so the caller can cancel its collector.
     *
     * The mask is cleared before the entry disappears: the host binder keeps collecting the
     * exposed flow it received from the getter, so leaving a masked value behind would keep the
     * native icon hidden after the module stopped replacing it.
     */
    fun unregister(viewModel: Any): Registration? = synchronized(lock) {
        registrations.remove(viewModel)?.also { it.clearMask() }
    }

    fun setHiddenForSubIds(subIds: Set<Int>) {
        synchronized(lock) {
            registrations.values.toList().forEach { it.setHidden(it.subId in subIds) }
        }
    }

    fun clearAllMasks() {
        synchronized(lock) { registrations.values.toList().forEach(Registration::clearMask) }
    }

    /** Clears registrations after passing the original visibility through every exposed flow. */
    fun clearForHotReload() {
        synchronized(lock) {
            registrations.values.toList().forEach(Registration::clearMask)
            registrations.clear()
        }
    }

    fun registeredSubIds(): Set<Int> = synchronized(lock) { registrations.values.mapTo(LinkedHashSet()) { it.subId } }

    /** Attaches the original flow to a registration and returns false if collection did not start. */
    fun collectOriginal(
        scope: Any,
        originalFlow: Any,
        registration: Registration,
        isCurrent: () -> Boolean,
        consumer: ((Any?) -> Unit)? = null
    ): HostFlowCollector.Handle? = HostFlowCollector.collect(
        scope = scope,
        flow = originalFlow,
        isCurrent = isCurrent,
        consumer = { value ->
            registration.updateOriginal(value)
            consumer?.invoke(value)
        }
    )

    private fun pairValue(value: Any?): PairValue? {
        if (value == null) return null
        return runCatching {
            val first = value.javaClass.findNoArg("getFirst")?.invoke(value) as? Boolean
                ?: return@runCatching null
            val second = value.javaClass.findNoArg("getSecond")?.invoke(value) as? Boolean
                ?: return@runCatching null
            PairValue(first, second)
        }.getOrNull()
    }

    /** Reads the host's own Pair flow field, ignoring whatever the hooked getter would return. */
    private fun readVisibilityField(viewModel: Any): Any? {
        var current: Class<*>? = viewModel.javaClass
        while (current != null) {
            val field = runCatching { current.getDeclaredField(VISIBILITY_FIELD) }.getOrNull()
            // A primitive/Boolean field of the same name is not the visibility Pair; keep walking
            // (and finally fall back to the getter) instead of handing a wrong value to the
            // reducer, which would disable the replacement silently.
            if (field != null && field.type != Boolean::class.javaObjectType &&
                !field.type.isPrimitive
            ) {
                return runCatching {
                    field.isAccessible = true
                    field.get(viewModel)
                }.getOrNull()
            }
            current = current.superclass
        }
        return null
    }

    private fun Class<*>.findNoArg(name: String): Method? {
        var current: Class<*>? = this
        while (current != null) {
            current.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
                ?.let { return it.apply { isAccessible = true } }
            current = current.superclass
        }
        return methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
            ?.apply { isAccessible = true }
    }
}
