package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/**
 * Applies the order policy at OS4's list factory and mirrors the block policy to modern containers.
 * The factory hook runs before StatusBarIconControllerImpl can bind its first holder; it only
 * reorders the existing `mSlots` ArrayList and adds empty Slot records for enabled module slots.
 */
object IconPositionHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "IconTuner"
    private const val LIST_FACTORY_CLASS =
        "com.android.systemui.statusbar.dagger.CentralSurfacesDependenciesModule_ProvideStatusBarIconListFactory"
    private const val LIST_CLASS = "com.android.systemui.statusbar.phone.ui.StatusBarIconList"
    private const val SLOT_CLASS = "com.android.systemui.statusbar.phone.ui.StatusBarIconList\$Slot"
    private const val CONTAINER_CLASS = "com.android.systemui.statusbar.views.MiuiStatusIconContainer"

    private data class ContainerState(
        var hostIgnored: List<String>,
        var lastApplied: List<String>? = null
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateLock = Any()
    private val containerStates = WeakHashMap<Any, ContainerState>()

    @Volatile
    private var options = IconTunerOptions.snapshot()

    @Volatile
    private var restoring = false

    private var slotsField: Field? = null
    private var slotNameField: Field? = null
    private var ignoredSlotsField: Field? = null
    private var layoutFromField: Field? = null
    private var slotConstructor: Constructor<*>? = null

    override fun onPrepareHotReload() {
        // The replacement callback runs off the UI thread. Restore only the mutable container
        // lists on the main thread; the host Slot list itself is process-startup state.
        val pending = synchronized(stateLock) {
            containerStates.entries.map { (container, state) -> container to state.hostIgnored.toList() }
        }
        mainHandler.post {
            restoring = true
            try {
                pending.forEach { (container, hostIgnored) -> restoreIgnoredSlots(container, hostIgnored) }
            } finally {
                restoring = false
                synchronized(stateLock) { containerStates.clear() }
            }
        }
    }

    override fun onHook() {
        IconTunerFlows.init(classLoader)
        options = IconTunerOptions.snapshot()
        hookStatusBarIconListFactory()
        hookIgnoredSlots()
        DebugLog.i(TAG, "IconPosition hooks installed")
    }

    private fun hookStatusBarIconListFactory() {
        val factoryClass = LIST_FACTORY_CLASS.toClassOrNull()
        if (factoryClass == null) {
            DebugLog.hookSkipped(TAG, LIST_FACTORY_CLASS, "class not found")
            return
        }
        val method = factoryClass.declaredMethods.singleOrNull { candidate ->
            Modifier.isStatic(candidate.modifiers) &&
                candidate.name == "provideStatusBarIconList" &&
                candidate.parameterTypes.size == 1 &&
                Context::class.java.isAssignableFrom(candidate.parameterTypes[0]) &&
                candidate.returnType.name == LIST_CLASS
        }
        if (method == null) {
            DebugLog.hookSkipped(TAG, "$LIST_FACTORY_CLASS#provideStatusBarIconList", "method not found or ambiguous")
            return
        }
        method.isAccessible = true
        deoptimize(method)
        method.hook {
            after { param ->
                runCatching { rewriteIconList(param.result) }
                    .onFailure { DebugLog.w(TAG, "status-bar slot order rewrite failed", it) }
            }
        }
    }

    private fun rewriteIconList(listObject: Any?) {
        if (listObject == null) return
        val slots = (slotsField ?: findField(listObject.javaClass, "mSlots").also { slotsField = it })
            ?.get(listObject) as? MutableList<Any?> ?: return
        val current = slots.mapNotNull { rawSlot ->
            val slot = rawSlot ?: return@mapNotNull null
            val name = readSlotName(slot) ?: return@mapNotNull null
            name to slot
        }
        val currentNames = current.map { it.first }
        val normalizedNames = IconSlotPolicy.normalizeOrder(currentNames, options.policy)
        if (normalizedNames == currentNames) return

        val byName = LinkedHashMap<String, Any>()
        current.forEach { (name, slot) -> byName.putIfAbsent(name, slot) }
        normalizedNames.forEach { name ->
            if (!byName.containsKey(name)) {
                createSlot(listObject, name)?.let { byName[name] = it }
            }
        }
        val rewritten = normalizedNames.mapNotNull(byName::get)
        if (rewritten.size != normalizedNames.size) {
            DebugLog.w(TAG, "slot order skipped because a module Slot could not be created")
            return
        }
        slots.clear()
        slots.addAll(rewritten)
        DebugLog.i(TAG, "StatusBarIconList slots reordered count=${rewritten.size}")
    }

    private fun hookIgnoredSlots() {
        val containerClass = CONTAINER_CLASS.toClassOrNull()
        if (containerClass == null) {
            DebugLog.hookSkipped(TAG, CONTAINER_CLASS, "class not found")
            return
        }
        ignoredSlotsField = findField(containerClass, "ignoredSlots")
        layoutFromField = findField(containerClass, "_layoutFrom")
        if (ignoredSlotsField == null) {
            DebugLog.hookSkipped(TAG, "$CONTAINER_CLASS#ignoredSlots", "field not found")
            return
        }
        hookContainerMethod(containerClass, "setIgnoredSlots")
        hookContainerMethod(containerClass, "addIgnoredSlots")
    }

    private fun hookContainerMethod(containerClass: Class<*>, methodName: String) {
        val method = findMethod(containerClass, methodName) ?: run {
            DebugLog.hookSkipped(TAG, "$CONTAINER_CLASS#$methodName", "method not found")
            return
        }
        method.hook {
            before { param ->
                if (restoring) return@before
                val incoming = param.args.getOrNull(0) as? List<*> ?: return@before
                val state = captureContainerState(param.thisObject, incoming)
                val surface = surfaceFor(param.thisObject, incoming)
                val merged = IconSlotPolicy.blockedFor(surface, state.hostIgnored, options.policy)
                state.lastApplied = merged
                param.args[0] = ArrayList(merged)
            }
            after { param ->
                if (restoring) return@after
                synchronized(stateLock) {
                    containerStates[param.thisObject]?.lastApplied?.let { applied ->
                        restoreIgnoredSlots(param.thisObject, applied)
                    }
                }
            }
        }
    }

    private fun captureContainerState(container: Any, incoming: List<*>): ContainerState {
        val incomingStrings = stableStrings(incoming)
        synchronized(stateLock) {
            val existing = containerStates[container]
            val host = IconManagerHooker.pristineFor(incoming)
                ?: if (existing != null && existing.lastApplied == incomingStrings) {
                    existing.hostIgnored
                } else {
                    incomingStrings
                }
            return (existing ?: ContainerState(host)).also {
                it.hostIgnored = host
                containerStates[container] = it
            }
        }
    }

    private fun surfaceFor(container: Any, incoming: List<*>): IconSurface {
        IconManagerHooker.surfaceFor(incoming)?.let { return it }
        val layoutFrom = runCatching { layoutFromField?.get(container) as? Int }.getOrNull()
        return when (layoutFrom) {
            0, 1 -> IconSurface.STATUS_BAR
            4, 5, 6 -> IconSurface.CONTROL_CENTER
            else -> IconSurface.UNKNOWN
        }
    }

    private fun restoreIgnoredSlots(container: Any, values: List<String>) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { restoreIgnoredSlots(container, values) }
            return
        }
        runCatching {
            val ignored = ignoredSlotsField?.get(container) as? MutableList<Any?> ?: return
            ignored.clear()
            ignored.addAll(values)
        }.onFailure { DebugLog.w(TAG, "ignoredSlots restore failed", it) }
    }

    private fun createSlot(owner: Any, name: String): Any? {
        val constructor = slotConstructor ?: run {
            val slotClass = SLOT_CLASS.toClassOrNull() ?: return null
            slotClass.getDeclaredConstructor(String::class.java).apply { isAccessible = true }
                .also { slotConstructor = it }
        }
        return runCatching { constructor.newInstance(name) }.getOrNull()
    }

    private fun readSlotName(slot: Any?): String? {
        if (slot == null) return null
        val field = slotNameField ?: findField(slot.javaClass, "mName").also { slotNameField = it }
        return runCatching { field?.get(slot) as? String }.getOrNull()
    }

    private fun findField(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching {
                return current.getDeclaredField(name).apply { isAccessible = true }
            }
            current = current.superclass
        }
        return null
    }

    private fun findMethod(type: Class<*>, name: String): Method? {
        var current: Class<*>? = type
        while (current != null) {
            val matches = current.declaredMethods.filter {
                it.name == name && it.parameterTypes.size == 1
            }
            if (matches.size == 1) return matches.single().apply { isAccessible = true }
            current = current.superclass
        }
        return null
    }

    private fun stableStrings(values: List<*>): List<String> {
        val result = LinkedHashSet<String>()
        values.forEach { value ->
            if (value is String && value.isNotBlank()) result += value
        }
        return result.toList()
    }
}
