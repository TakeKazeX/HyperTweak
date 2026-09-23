package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayDeque
import java.util.IdentityHashMap
import java.util.WeakHashMap
import kotlin.math.abs

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
    private const val NETWORK_SPEED_CLASS = "com.android.systemui.statusbar.views.NetworkSpeedView"
    private const val CONTROL_CENTER_ROW_CLASS =
        "com.android.systemui.controlcenter.phone.widget.ControlCenterStatusBarIcon"
    private const val CONTROL_CENTER_FAKE_ROW_CLASS =
        "com.android.systemui.controlcenter.phone.widget.ControlCenterFakeStatusIcons"

    private data class ContainerState(
        var hostIgnored: List<String>,
        var lastApplied: List<String>? = null,
        val nativeVisibleStates: WeakHashMap<View, Int> = WeakHashMap()
    )

    private data class HostRowStateAccess(
        val layoutStates: Field,
        val view: Field,
        val visibleState: Field,
        val hiddenBySpace: Field,
        val inIslandState: Field,
        val gone: Field
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateLock = Any()
    private val containerStates = WeakHashMap<Any, ContainerState>()
    private val maskOwners = IconMaskOwners()
    private val duoSlots = setOf("mobile", "stacked_mobile", "wifi", "demo_wifi",
        "stacked_mobile_icon", "stacked_mobile_type", "single_mobile_sim1", "single_mobile_sim2")

    @Volatile
    private var options = IconTunerOptions.snapshot()

    @Volatile
    private var restoring = false
    private var applyingNativeVisibility = false

    private val networkVisibility = LinkedHashMap<Class<*>, Method>()
    private val networkSlotFields = HashMap<Class<*>, Field?>()
    private val rowTranslations = WeakHashMap<View, TranslationState>()
    private val lastRestoredOverflow = WeakHashMap<ViewGroup, Set<String>>()
    private val iconVisibleGetters = HashMap<Class<*>, Method?>()
    private val iconBlockedGetters = HashMap<Class<*>, Method?>()
    private val removeFlagGetters = HashMap<Class<*>, Method?>()
    private val networkSpeedWidthGetters = HashMap<Class<*>, Method?>()
    private val batteryLayoutFields = HashMap<Class<*>, Field?>()
    private val hostStateTranslationFields = HashMap<Class<*>, Field?>()
    private var hostTranslationResetMethod: Method? = null
    private var hostTranslationTagId = 0
    private val activeHostLayouts = ThreadLocal<ArrayDeque<ViewGroup>>()
    private val pendingPlacements = java.util.Collections.newSetFromMap(
        WeakHashMap<ViewGroup, Boolean>()
    )
    private var slotsField: Field? = null
    private var slotNameField: Field? = null
    private var ignoredSlotsField: Field? = null
    private var layoutFromField: Field? = null
    private var slotConstructor: Constructor<*>? = null
    private val slotGetters = HashMap<Class<*>, Method?>()

    private class TranslationState(
        var hostX: Float,
        var appliedX: Float,
        var originalY: Float,
        var appliedY: Float
    )

    private data class RowPlacement(
        val view: View,
        val slot: String,
        val state: TranslationState,
        val childIndex: Int
    )

    override fun saveHotReloadState(): Any = synchronized(stateLock) {
        containerStates.entries.map { (container, state) -> listOf(container, ArrayList(state.hostIgnored)) }
    }

    override fun restoreHotReloadState(state: Any?) {
        val saved = state as? List<*> ?: return
        mainHandler.post {
            saved.forEach { entry ->
                val row = entry as? List<*> ?: return@forEach
                val container = row.getOrNull(0) as? View ?: return@forEach
                val ignored = (row.getOrNull(1) as? List<*>)?.filterIsInstance<String>() ?: return@forEach
                synchronized(stateLock) { containerStates[container] = ContainerState(ignored) }
                recoverExistingContainers(listOf(container))
            }
        }
    }

    internal fun recoverExistingContainers(views: List<View>) {
        views.filter { it.javaClass.name == CONTAINER_CLASS }.forEach { container ->
            runCatching {
                val state = synchronized(stateLock) { containerStates[container] }
                    ?: captureContainerState(container, ignoredSlotsField?.get(container) as? List<*> ?: return@runCatching)
                val merged = blockedFor(surfaceFor(container, state.hostIgnored), state.hostIgnored)
                state.lastApplied = merged
                restoreIgnoredSlots(container, merged)
                container.requestLayout()
            }.onFailure { DebugLog.w(TAG, "hot reload container recovery failed", it) }
        }
    }

    override fun onPrepareHotReload() {
        // The replacement callback runs off the UI thread. Restore only the mutable container
        // lists on the main thread; the host Slot list itself is process-startup state.
        val pending = synchronized(stateLock) { containerStates.entries.map { it.key to it.value } }
        mainHandler.post {
            restoring = true
            maskOwners.clear()
            try {
                pendingPlacements.clear()
                lastRestoredOverflow.clear()
                restoreRowTranslations()
                pending.forEach { (container, state) ->
                    hideNativeNetworkChildren(container)
                    restoreIgnoredSlots(container, state.hostIgnored.toList())
                    (container as? View)?.requestLayout()
                }
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
        networkVisibility.clear()
        networkSlotFields.clear()
        iconVisibleGetters.clear()
        iconBlockedGetters.clear()
        removeFlagGetters.clear()
        networkSpeedWidthGetters.clear()
        batteryLayoutFields.clear()
        hostStateTranslationFields.clear()
        hostTranslationResetMethod = null
        hostTranslationTagId = 0
        listOf(
            "com.android.systemui.statusbar.pipeline.mobile.ui.view.ModernStatusBarMobileView",
            "com.android.systemui.statusbar.pipeline.shared.ui.view.ModernStatusBarView"
        ).forEach { name ->
            val type = name.toClassOrNull() ?: return@forEach
            val method = type.declaredMethods.singleOrNull {
                it.name == "setVisibleState" && it.parameterTypes.contentEquals(arrayOf(
                    Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType))
            }?.apply { isAccessible = true } ?: return@forEach
            networkVisibility[type] = method
            networkSlotFields[type] = findField(type, "slot")
            deoptimize(method)
            method.hook { before { param ->
                val view = param.thisObject as? android.view.View ?: return@before
                val container = view.parent ?: return@before
                val slot = runCatching { networkSlotFields[type]?.get(view) as? String }.getOrNull()
                if (slot != null && slot in maskSlotsFor(container)) {
                    // Save host-visible requests before forcing this container's network child
                    // closed. Calls with state 2 can be the superclass half of a subclass call,
                    // so keep the last explicit visible state while our mask is active.
                    if (!applyingNativeVisibility && param.args.getOrNull(0) != 2) {
                        containerStates[container]?.nativeVisibleStates?.set(view,
                            param.args[0] as? Int ?: return@before)
                    }
                    param.args[0] = 2
                }
            } }
        }
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
        hookTwoLineHostState(containerClass)
        // View.onLayout is (changed, left, top, right, bottom): five parameters. A four-parameter
        // query silently misses this final host override, leaving every icon at the host's row.
        containerClass.findMethodOrNull { name("onLayout"); paramCount(5) }?.let { method ->
            deoptimize(method)
            method.hook {
                before { param ->
                    val container = param.thisObject as? ViewGroup ?: return@before
                    if (ownsTwoLineLayout(container)) {
                        val stack = activeHostLayouts.get() ?: ArrayDeque<ViewGroup>().also {
                            activeHostLayouts.set(it)
                        }
                        stack.addLast(container)
                    }
                    runCatching { restoreHostTranslationsForLayout(container) }
                        .onFailure { DebugLog.w(TAG, "host icon X restore failed", it) }
                }
                after { param ->
                    val container = param.thisObject as? ViewGroup ?: return@after
                    val active = activeHostLayouts.get()
                    if (active?.peekLast() === container) active.removeLast()
                    if (active?.isEmpty() == true) activeHostLayouts.remove()
                    // The host has already applied the corrected visibility state. Place its
                    // children in the same layout turn, then reconcile final sibling geometry.
                    runCatching { applyControlCenterRowPlacement(container) }
                        .onFailure { DebugLog.w(TAG, "immediate row placement failed", it) }
                    runCatching { scheduleControlCenterRowPlacement(container) }
                        .onFailure { DebugLog.w(TAG, "deferred row placement failed", it) }
                }
            }
        }
    }

    /** The host computes overflow just before this call and applies each state immediately after. */
    private fun hookTwoLineHostState(containerClass: Class<*>) {
        val stateClass = "com.android.systemui.statusbar.views.NewStatusIconState".toClassOrNull()
        val access = stateClass?.let { state ->
            val layoutStates = findField(containerClass, "layoutStates")
            val view = findField(state, "view")
            val visibleState = findField(state, "visibleState")
            val hiddenBySpace = findField(state, "hiddenBySpace")
            val inIslandState = findField(state, "inIslandState")
            val gone = findField(state, "gone")
            if (layoutStates == null || view == null || visibleState == null ||
                hiddenBySpace == null || inIslandState == null || gone == null
            ) null else HostRowStateAccess(
                layoutStates, view, visibleState, hiddenBySpace, inIslandState, gone
            )
        }
        val method = containerClass.findMethodOrNull { name("enableAnimation\$1"); noParams() }
        if (access == null || method == null) {
            DebugLog.hookSkipped(TAG, "$CONTAINER_CLASS#enableAnimation\$1", "two-line state boundary unavailable")
            return
        }
        deoptimize(method)
        method.hook {
            after { param ->
                val container = param.thisObject as? ViewGroup ?: return@after
                if (activeHostLayouts.get()?.peekLast() !== container) return@after
                runCatching { restoreTwoLineOverflowStates(container, access) }
                    .onFailure { DebugLog.w(TAG, "two-line overflow state failed", it) }
            }
        }
        DebugLog.hookRegistered(TAG, "two-line host overflow state")
    }

    private fun ownsTwoLineLayout(container: ViewGroup): Boolean {
        if (container.javaClass.name != CONTAINER_CLASS ||
            !ControlCenterHeaderHooker.secondRowStatusIconsEnabled()
        ) return false
        val layoutFrom = runCatching { layoutFromField?.getInt(container) }.getOrNull()
        return (layoutFrom == 5 || layoutFrom == 6) && isControlCenterContainer(container)
    }

    private fun restoreTwoLineOverflowStates(container: ViewGroup, access: HostRowStateAccess) {
        val states = access.layoutStates.get(container) as? List<*> ?: return
        val ignored = ignoredSlotsField?.get(container) as? List<*> ?: return
        val restoredSlots = LinkedHashSet<String>()
        for (raw in states) {
            val state = raw ?: continue
            if (access.visibleState.getInt(state) != 2 || !access.hiddenBySpace.getBoolean(state)) {
                continue
            }
            val view = access.view.get(state) as? View ?: continue
            if (view.parent !== container || view.visibility == View.GONE) continue
            val slot = slotOf(view) ?: continue
            val iconVisibleMethod = iconVisibleGetters.getOrPut(view.javaClass) {
                findNoArgMethod(view.javaClass, "isIconVisible")
            } ?: continue
            val iconVisible = runCatching { iconVisibleMethod.invoke(view) as? Boolean }
                .getOrNull() ?: continue
            val iconBlockedMethod = iconBlockedGetters.getOrPut(view.javaClass) {
                findNoArgMethod(view.javaClass, "isIconBlocked")
            } ?: continue
            val iconBlocked = runCatching { iconBlockedMethod.invoke(view) as? Boolean }
                .getOrNull() ?: continue
            val removeFlagMethod = removeFlagGetters.getOrPut(view.javaClass) {
                findNoArgMethod(view.javaClass, "getRemoveFlag")
            } ?: continue
            val removeFlag = runCatching { removeFlagMethod.invoke(view) as? Boolean }
                .getOrNull() ?: continue
            if (IconSlotPolicy.shouldRestoreTwoLineOverflow(
                    visibleState = 2,
                    hiddenBySpace = true,
                    inIslandState = access.inIslandState.getInt(state),
                    iconVisible = iconVisible,
                    iconBlocked = iconBlocked,
                    ignored = slot in ignored,
                    removing = removeFlag,
                    gone = access.gone.getBoolean(state)
                )
            ) {
                access.visibleState.setInt(state, 0)
                access.hiddenBySpace.setBoolean(state, false)
                restoredSlots += slot
            }
        }
        if (lastRestoredOverflow[container] != restoredSlots) {
            lastRestoredOverflow[container] = restoredSlots
            if (restoredSlots.isNotEmpty()) {
                DebugLog.i(TAG, "control-center overflow restored slots=$restoredSlots")
            }
        }
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
                val merged = blockedFor(surface, state.hostIgnored)
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
            val host = IconManagerHooker.pristineFor(incoming) ?: run {
                val masks = maskSlotsFor(container)
                val echoedOurOwnList = existing != null &&
                    (existing.lastApplied == incomingStrings ||
                        (masks.isNotEmpty() &&
                            (existing.lastApplied.orEmpty() + masks).distinct() == incomingStrings))
                if (echoedOurOwnList) existing.hostIgnored else incomingStrings
            }
            return (existing ?: ContainerState(host)).also {
                it.hostIgnored = host
                containerStates[container] = it
            }
        }
    }

    /** Slots currently owned by a module overlay on this container. */
    private fun maskSlotsFor(container: Any): List<String> = maskOwners.slots(container)

    private fun surfaceFor(container: Any, incoming: List<*>): IconSurface {
        IconManagerHooker.surfaceFor(incoming)?.let { return it }
        val layoutFrom = runCatching { layoutFromField?.get(container) as? Int }.getOrNull()
        return when (layoutFrom) {
            0, 1 -> IconSurface.STATUS_BAR
            4, 5, 6 -> IconSurface.CONTROL_CENTER
            else -> IconSurface.UNKNOWN
        }
    }

    private fun blockedFor(surface: IconSurface, systemSlots: List<String>): List<String> =
        if (surface == IconSurface.CONTROL_CENTER &&
            ControlCenterHeaderHooker.secondRowStatusIconsEnabled()
        ) {
            IconSlotPolicy.blockedForTwoLineControlCenter(options.policy)
        } else {
            IconSlotPolicy.blockedFor(surface, systemSlots, options.policy)
        }

    private fun restoreIgnoredSlots(container: Any, values: List<String>) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { restoreIgnoredSlots(container, values) }
            return
        }
        runCatching {
            val ignored = ignoredSlotsField?.get(container) as? MutableList<Any?> ?: return
            ignored.clear()
            ignored.addAll(maskOwners.merged(container, values))
        }.onFailure { DebugLog.w(TAG, "ignoredSlots restore failed", it) }
    }

    /** Independent, container-local owners; no shared visibility Flow is changed. */
    fun setDuoMask(container: Any, active: Boolean): Boolean =
        setContainerMask(container, if (active) duoSlots else emptySet(), IconMaskOwners.Owner.DUO)

    internal fun setCarrierMask(container: Any, mask: CarrierMask): Boolean =
        setContainerMask(container, mask.slots(), IconMaskOwners.Owner.CARRIER)

    private fun setContainerMask(
        container: Any,
        slots: Set<String>,
        owner: IconMaskOwners.Owner
    ): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        return runCatching {
            val ignored = ignoredSlotsField?.get(container) as? List<*> ?: return false
            if (maskOwners.owned(container, owner) == slots && slots.all { it in ignored }) {
                hideNativeNetworkChildren(container)
                return true
            }
            val state = synchronized(stateLock) {
                containerStates[container] ?: ContainerState(stableStrings(ignored)).also {
                    containerStates[container] = it
                }
            }
            maskOwners.set(container, owner, slots)
            val base = state.lastApplied ?: blockedFor(
                surfaceFor(container, state.hostIgnored), state.hostIgnored
            )
            restoreIgnoredSlots(container, base)
            hideNativeNetworkChildren(container)
            (container as? android.view.View)?.requestLayout()
            val result = ignoredSlotsField?.get(container) as? List<*> ?: return false
            result == maskOwners.merged(container, base)
        }.getOrElse {
            DebugLog.w(TAG, "container slot mask failed", it)
            false
        }
    }

    /** Modern mobile/Wi-Fi binders own visibility independently from ignoredSlots. */
    private fun hideNativeNetworkChildren(container: Any) {
        val group = container as? ViewGroup ?: return
        val slots = maskSlotsFor(container)
        val state = containerStates[container] ?: return
        val hostBlocked = state.lastApplied ?: blockedFor(
            surfaceFor(container, state.hostIgnored), state.hostIgnored
        )
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            val entry = networkVisibility.entries.firstOrNull { it.key.isInstance(child) } ?: continue
            val slot = networkSlotFields[entry.key]?.get(child) as? String ?: continue
            if (slot in slots) {
                if (!state.nativeVisibleStates.containsKey(child)) {
                    readNativeVisibleState(child)?.let { state.nativeVisibleStates[child] = it }
                }
                if (state.nativeVisibleStates.containsKey(child)) {
                    invokeNativeVisibleState(entry.value, child, 2)
                }
            } else if (slot in hostBlocked) {
                state.nativeVisibleStates.remove(child)
                invokeNativeVisibleState(entry.value, child, 2)
            } else {
                val previous = state.nativeVisibleStates.remove(child) ?: continue
                invokeNativeVisibleState(entry.value, child, previous)
            }
        }
    }

    private fun readNativeVisibleState(view: View): Int? = runCatching {
        findField(view.javaClass, "iconVisibleState")?.getInt(view)
    }.getOrNull()

    private fun invokeNativeVisibleState(method: Method, view: View, state: Int) {
        val previous = applyingNativeVisibility
        applyingNativeVisibility = true
        try {
            method.invoke(view, state, false)
        } catch (error: Throwable) {
            DebugLog.w(TAG, "modern network visibility restore failed", error)
        } finally {
            applyingNativeVisibility = previous
        }
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

    /**
     * Place each native status icon against the carrier block's real two-row geometry. The host has
     * both a real and a fake control-center row, and their constraint updates can arrive in either
     * order during a hot reload; deriving screen-space centers avoids depending on which row was
     * anchored first.
     */
    private fun applyControlCenterRowPlacement(container: ViewGroup) {
        if (container.javaClass.name != CONTAINER_CLASS) return
        if (!ControlCenterHeaderHooker.secondRowStatusIconsEnabled()) {
            restoreRowTranslations(container)
            return
        }
        // During a shade switch a host row can be detached/re-attached or temporarily move out of
        // the control-center header. Keep the last module placement until the host has settled;
        // restoring here makes the next frame start a second animation from the top row.
        if (!isControlCenterContainer(container) || container.height <= 0) return
        container.clipChildren = false
        container.clipToPadding = false
        var parent = container.parent as? ViewGroup
        while (parent != null) {
            parent.clipChildren = false
            parent.clipToPadding = false
            if (parent.javaClass.name == CONTROL_CENTER_ROW_CLASS ||
                parent.javaClass.name == CONTROL_CENTER_FAKE_ROW_CLASS) break
            parent = parent.parent as? ViewGroup
        }

        val rowCenters = controlCenterRowCenters(container)
        if (rowCenters == null) return
        val battery = effectiveBattery(container, rowCenters)
        val containerLocation = IntArray(2)
        container.getLocationOnScreen(containerLocation)
        val placements = ArrayList<RowPlacement>(container.childCount)
        for (index in 0 until container.childCount) {
            val child = container.getChildAt(index) ?: continue
            val slot = slotOf(child) ?: continue
            val state = rowTranslations[child] ?: TranslationState(
                hostX = child.translationX,
                appliedX = child.translationX,
                originalY = child.translationY,
                appliedY = child.translationY
            ).also { rowTranslations[child] = it }
            captureHostTranslation(child, state)
            placements += RowPlacement(child, slot, state, index)
        }
        val rowX = rowHorizontalPlacements(container, battery, placements)
        placements.forEach { placement ->
            val child = placement.view
            val slot = placement.slot
            val state = placement.state
            val baseCenter = containerLocation[1] + child.top + child.height / 2f + state.originalY
            val targetCenter = if (slot in IconSlotPolicy.CONTROL_CENTER_FIRST_ROW_SLOTS) {
                rowCenters.first
            } else {
                rowCenters.second
            }
            val desiredY = state.originalY + targetCenter - baseCenter
            val desiredX = rowX[child] ?: state.appliedX
            applyHostHorizontalState(child, desiredX)
            if (abs(child.translationY - desiredY) > .01f) child.translationY = desiredY
            state.appliedX = desiredX
            state.appliedY = desiredY
        }
    }

    /** Coalesce repeated host layouts into one final main-thread placement for this container. */
    private fun scheduleControlCenterRowPlacement(container: ViewGroup) {
        if (!pendingPlacements.add(container)) return
        mainHandler.post {
            try {
                if (!restoring && container.isAttachedToWindow) {
                    applyControlCenterRowPlacement(container)
                }
            } catch (error: Throwable) {
                DebugLog.w(TAG, "control-center row placement failed", error)
            } finally {
                pendingPlacements.remove(container)
            }
        }
    }

    private fun isControlCenterContainer(container: View): Boolean {
        var parent = container.parent as? View
        while (parent != null) {
            if (parent.javaClass.name == CONTROL_CENTER_ROW_CLASS ||
                parent.javaClass.name == CONTROL_CENTER_FAKE_ROW_CLASS
            ) return true
            parent = parent.parent as? View
        }
        return false
    }

    /** Returns first-row and second-row centers in screen coordinates when the carrier block exists. */
    private fun controlCenterRowCenters(container: ViewGroup): Pair<Float, Float>? {
        var rowRoot: ViewGroup? = container.parent as? ViewGroup
        while (rowRoot != null &&
            rowRoot.javaClass.name != CONTROL_CENTER_ROW_CLASS &&
            rowRoot.javaClass.name != CONTROL_CENTER_FAKE_ROW_CLASS
        ) {
            rowRoot = rowRoot.parent as? ViewGroup
        }
        val header = rowRoot?.parent as? ViewGroup ?: return null
        val carrierId = header.resources.getIdentifier(
            "normal_control_center_carrier_layout", "id", "com.android.systemui"
        )
        if (carrierId == 0) return null
        val carrier = header.findViewById<View>(carrierId) as? ViewGroup ?: return null
        val carrierLocation = IntArray(2)
        carrier.getLocationOnScreen(carrierLocation)
        val rowCenters = (0 until carrier.childCount).mapNotNull { index ->
            val child = carrier.getChildAt(index)
            if (child.javaClass.name !=
                "com.android.systemui.controlcenter.shade.ControlCenterCarrierText" ||
                child.visibility != View.VISIBLE || child.height <= 0
            ) {
                null
            } else {
                carrierLocation[1] + child.top + child.height / 2f
            }
        }
        if (rowCenters.isEmpty()) return null
        val first = rowCenters.first()
        val second = rowCenters.getOrNull(1) ?: (first + carrier.height.coerceAtLeast(container.height))
        return first to second
    }

    /** Current layout owner: native battery, or DuoView while Duo borrows mBattery. */
    private fun effectiveBattery(
        container: ViewGroup,
        rowCenters: Pair<Float, Float>?
    ): View? {
        if (rowCenters == null) return null
        val parent = container.parent as? ViewGroup ?: return null
        val layoutBattery = runCatching {
            batteryLayoutFields.getOrPut(parent.javaClass) {
                findField(parent.javaClass, "mBattery")
            }?.get(parent) as? View
        }.getOrNull()
        val battery = layoutBattery ?: run {
            val batteryId = parent.resources.getIdentifier(
                "battery", "id", "com.android.systemui"
            )
            if (batteryId == 0) return null
            parent.findViewById<View>(batteryId) ?: return null
        }
        if (battery.visibility != View.VISIBLE || battery.width <= 0 || battery.height <= 0) {
            return null
        }
        val batteryLocation = IntArray(2)
        battery.getLocationOnScreen(batteryLocation)
        val batteryCenter = batteryLocation[1] + battery.height / 2f
        if (abs(batteryCenter - rowCenters.first) > abs(batteryCenter - rowCenters.second)) {
            return null
        }
        return battery
    }

    /** Independently packs row 1 and row 2 so neither row reserves the other row's slots. */
    private fun rowHorizontalPlacements(
        container: ViewGroup,
        battery: View?,
        placements: List<RowPlacement>
    ): Map<View, Float> {
        battery ?: return emptyMap()
        val visible = placements.filter { isHostLayoutVisible(it.view, it.slot, container) }
        if (visible.isEmpty()) return emptyMap()
        val firstRow = visible.filter { it.slot in IconSlotPolicy.CONTROL_CENTER_FIRST_ROW_SLOTS }
        val secondRow = visible.filter { it.slot !in IconSlotPolicy.CONTROL_CENTER_FIRST_ROW_SLOTS }
        val containerLocation = IntArray(2)
        val batteryLocation = IntArray(2)
        container.getLocationOnScreen(containerLocation)
        battery.getLocationOnScreen(batteryLocation)
        val spacingId = container.resources.getIdentifier(
            "status_bar_system_icon_spacing", "dimen", "com.android.systemui"
        )
        val spacing = if (spacingId != 0) {
            container.resources.getDimensionPixelSize(spacingId).toFloat()
        } else {
            0f
        }
        val result = IdentityHashMap<View, Float>()
        placeRowFromRight(
            firstRow,
            batteryLocation[0] - containerLocation[0].toFloat(),
            spacing,
            result
        )
        placeRowFromRight(
            secondRow,
            batteryLocation[0] + battery.width - containerLocation[0].toFloat(),
            spacing,
            result
        )
        return result
    }

    private fun placeRowFromRight(
        row: List<RowPlacement>,
        rightEdge: Float,
        spacing: Float,
        result: MutableMap<View, Float>
    ) {
        var cursor = rightEdge
        val ordered = row.sortedBy { it.childIndex }
        (if (ordered.firstOrNull()?.view?.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            ordered
        } else {
            ordered.asReversed()
        })
            .forEach { placement ->
            cursor -= occupiedWidth(placement.view)
            result[placement.view] = cursor
            cursor -= spacing
        }
    }

    /** Keep the host's layout state native; only the child view receives the second-row position. */
    private fun applyHostHorizontalState(view: View, desiredX: Float) {
        val viewChanged = abs(view.translationX - desiredX) > .01f
        if (!viewChanged && !hasHostTranslationAnimation(view)) return
        snapHostTranslation(view, desiredX)
    }

    /** Read the host target after onLayout, before the module applies its visual X. */
    private fun captureHostTranslation(view: View, state: TranslationState) {
        val tagId = view.resources.getIdentifier(
            "status_bar_view_state_tag", "id", "com.android.systemui"
        )
        val hostState = if (tagId != 0) view.getTag(tagId) else null
        val hostX = hostState?.let {
            hostStateTranslationFields.getOrPut(it.javaClass) { findField(it.javaClass, "translationX") }
                ?.let { field -> runCatching { field.getFloat(hostState) }.getOrNull() }
        }
        if (hostX != null) state.hostX = hostX
    }

    /** Restore the native X before MiuiStatusIconContainer.initFrom() reads the child. */
    private fun restoreHostTranslationsForLayout(container: ViewGroup) {
        for (index in 0 until container.childCount) {
            val child = container.getChildAt(index)
            val state = rowTranslations[child] ?: continue
            if (abs(child.translationX - state.hostX) > .01f || hasHostTranslationAnimation(child)) {
                snapHostTranslation(child, state.hostX)
            }
        }
    }

    private fun hasHostTranslationAnimation(view: View): Boolean {
        val tagId = hostTranslationTagId(view)
        return tagId != 0 && view.getTag(tagId) != null
    }

    /** Cancel the host's X Folme target before applying the module-owned coordinate. */
    private fun snapHostTranslation(view: View, target: Float) {
        val current = view.translationX
        if (abs(current - target) <= .01f && !hasHostTranslationAnimation(view)) return
        runCatching {
            val reset = hostTranslationResetMethod ?: run {
                val type = classLoader.loadClass(
                    "com.android.systemui.statusbar.anim.MiuiStatusBarFolmeViewState"
                )
                type.getDeclaredMethod("resetToTranslationX", Float::class.javaPrimitiveType, View::class.java)
                    .apply { isAccessible = true }
                    .also { hostTranslationResetMethod = it }
            }
            reset.invoke(null, target - current, view)
        }.onFailure {
            DebugLog.w(TAG, "host icon translation reset failed", it)
        }
        hostTranslationTagId(view).takeIf { it != 0 }?.let { view.setTag(it, null) }
        if (abs(view.translationX - target) > .01f) view.translationX = target
    }

    private fun hostTranslationTagId(view: View): Int {
        if (hostTranslationTagId == 0) {
            hostTranslationTagId = view.resources.getIdentifier(
                "folme_translation_x_animator_tag", "id", "com.android.systemui"
            )
        }
        return hostTranslationTagId
    }

    private fun isHostLayoutVisible(view: View, slot: String, container: ViewGroup): Boolean {
        // Child alpha is animated independently during row/proxy handoffs; model visibility is the
        // stable ownership signal. Dropping an alpha-zero child here would change row packing in the
        // middle of the fade and recreate a transient empty slot.
        if (view.visibility != View.VISIBLE || view.width <= 0) return false
        val ignored = runCatching { ignoredSlotsField?.get(container) as? List<*> }.getOrNull()
        if (ignored?.contains(slot) == true) return false
        val visible = iconVisibleGetters.getOrPut(view.javaClass) {
            findNoArgMethod(view.javaClass, "isIconVisible")
        }?.let { runCatching { it.invoke(view) as? Boolean }.getOrNull() } ?: true
        val blocked = iconBlockedGetters.getOrPut(view.javaClass) {
            findNoArgMethod(view.javaClass, "isIconBlocked")
        }?.let { runCatching { it.invoke(view) as? Boolean }.getOrNull() } ?: false
        return visible && !blocked
    }

    private fun occupiedWidth(view: View): Float {
        val networkSpeedWidth = if (view.javaClass.name == NETWORK_SPEED_CLASS) {
            networkSpeedWidthGetters.getOrPut(view.javaClass) {
                findNoArgMethod(view.javaClass, "getNetworkSpeedWidth")
            }?.let { runCatching { (it.invoke(view) as? Number)?.toFloat() }.getOrNull() }
        } else {
            null
        }
        return (networkSpeedWidth ?: (
            view.width + view.paddingStart + view.paddingEnd
            ).toFloat()).coerceAtLeast(0f)
    }

    private fun findNoArgMethod(type: Class<*>, name: String): Method? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching {
                return current.getDeclaredMethod(name).apply { isAccessible = true }
            }
            runCatching {
                return current.getMethod(name).apply { isAccessible = true }
            }
            current = current.superclass
        }
        return null
    }

    private fun restoreRowTranslations(container: ViewGroup? = null) {
        val iterator = rowTranslations.entries.iterator()
        while (iterator.hasNext()) {
            val (view, state) = iterator.next()
            if (container != null && view.parent !== container) continue
            snapHostTranslation(view, state.hostX)
            if (abs(view.translationY - state.appliedY) <= .01f) view.translationY = state.originalY
            iterator.remove()
        }
    }

    private fun slotOf(view: View): String? {
        val getter = slotGetters.getOrPut(view.javaClass) {
            var current: Class<*>? = view.javaClass
            var found: Method? = null
            while (current != null && found == null) {
                found = current.interfaces.firstNotNullOfOrNull { iface ->
                    runCatching { iface.getMethod("getSlot") }.getOrNull()
                } ?: runCatching { current.getMethod("getSlot") }.getOrNull()
                current = current.superclass
            }
            found?.apply { isAccessible = true }
        }
        return getter?.let { runCatching { it.invoke(view) as? String }.getOrNull() }
    }

    private fun stableStrings(values: List<*>): List<String> {
        val result = LinkedHashSet<String>()
        values.forEach { value ->
            if (value is String && value.isNotBlank()) result += value
        }
        return result.toList()
    }
}
