package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.takekazex.hypertweak.hook.Preferences
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
        val gone: Field,
        val translationX: Field,
        val layoutTranslationX: Field
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateLock = Any()
    private val containerStates = WeakHashMap<Any, ContainerState>()
    private val maskOwners = IconMaskOwners()
    private val duoSlots = setOf("airplane", "mobile", "stacked_mobile", "wifi", "demo_wifi",
        "stacked_mobile_icon", "stacked_mobile_type", "single_mobile_sim1", "single_mobile_sim2")

    @Volatile
    private var options = IconTunerOptions.snapshot()

    @Volatile
    private var restoring = false
    @Volatile private var retiring = false
    private var applyingNativeVisibility = false
    private var applyingNativeViewVisibility = false
    private val nativeViewVisibility = NativeViewVisibilityMask(View.GONE)
    private var mobileViewClass: Class<*>? = null

    private val networkVisibility = LinkedHashMap<Class<*>, Method>()
    private val networkSlotFields = HashMap<Class<*>, Field?>()
    private val networkVisibleStateFields = HashMap<Class<*>, Field?>()
    private val clipStates = WeakHashMap<ViewGroup, Pair<Boolean, Boolean>>()
    private var rowOverrides: Set<String> = emptySet()
    private val rowLayoutOwners = WeakHashMap<ViewGroup, Boolean>()
    private class LowerRowMotion(val source: View) {
        val motion = CarrierTypeMotion()
        var savedAlpha = source.alpha
        var appliedAlpha = source.alpha
        fun restore() {
            motion.clear()
            if (abs(source.alpha - appliedAlpha) < .001f) source.alpha = savedAlpha
        }
    }
    private val lowerRowMotions = IdentityHashMap<View, LowerRowMotion>()
    private val lastRestoredOverflow = WeakHashMap<ViewGroup, Set<String>>()
    private val iconVisibleGetters = HashMap<Class<*>, Method?>()
    private val iconBlockedGetters = HashMap<Class<*>, Method?>()
    private val removeFlagGetters = HashMap<Class<*>, Method?>()
    private val networkSpeedWidthGetters = HashMap<Class<*>, Method?>()
    private val batteryLayoutFields = HashMap<Class<*>, Field?>()
    private val activeHostLayouts = ThreadLocal<ArrayDeque<ViewGroup>>()
    private var originalSlotOrder = emptyList<String>()
    private var slotsField: Field? = null
    private var slotNameField: Field? = null
    private var ignoredSlotsField: Field? = null
    private var layoutFromField: Field? = null
    private var slotConstructor: Constructor<*>? = null
    private val slotGetters = HashMap<Class<*>, Method?>()

    override fun saveHotReloadState(): Any = StatusIconHostAccess.onMain {
        mapOf("order" to ArrayList(originalSlotOrder), "containers" to synchronized(stateLock) {
            containerStates.entries.map { (container, state) -> listOf(container, ArrayList(state.hostIgnored)) }
        })
    }

    override fun restoreHotReloadState(state: Any?) {
        // Accept snapshots from the previous build as well as the versioned map.
        val saved = (state as? Map<*, *>)?.get("containers") as? List<*> ?: state as? List<*> ?: return
        StatusIconHostAccess.onMain {
            originalSlotOrder = ((state as? Map<*, *>)?.get("order") as? List<*>)?.filterIsInstance<String>().orEmpty()
            saved.forEach { entry ->
                val row = entry as? List<*> ?: return@forEach
                val container = row.getOrNull(0) as? View ?: return@forEach
                val ignored = (row.getOrNull(1) as? List<*>)?.filterIsInstance<String>() ?: return@forEach
                synchronized(stateLock) { containerStates[container] = ContainerState(ignored) }
            }
        }
    }

    internal fun recoverSlotOrder(controller: Any, context: Context) {
        val list = StatusIconHostAccess.read(controller, "mStatusBarIconList") ?: error("Missing host icon list")
        if (originalSlotOrder.isEmpty()) {
            val id = context.resources.getIdentifier("config_statusBarIcons", "array", "com.android.systemui")
            check(id != 0) { "Missing native status icon order" }
            originalSlotOrder = context.resources.getStringArray(id).toList()
        }
        rewriteIconList(list)
    }

    internal fun recoverExistingContainers(views: List<View>) {
        views.filter { it.javaClass.name == CONTAINER_CLASS }.forEach { container ->
            runCatching {
                val state = synchronized(stateLock) { containerStates[container] }
                    ?: captureContainerState(container, ignoredSlotsField?.get(container) as? List<*> ?: return@runCatching)
                val merged = blockedFor(container, surfaceFor(container, state.hostIgnored), state.hostIgnored)
                state.lastApplied = merged
                restoreIgnoredSlots(container, merged)
                container.requestLayout()
            }.onFailure { DebugLog.w(TAG, "hot reload container recovery failed", it) }
        }
    }

    override fun onPrepareHotReload() {
        retiring = true
        mainHandler.removeCallbacksAndMessages(null)
        // The replacement callback runs off the UI thread. Restore only the mutable container
        // lists on the main thread. The replacement rebuilds groups before changing slot indices.
        val pending = synchronized(stateLock) { containerStates.entries.map { it.key to it.value } }
        StatusIconHostAccess.onMain {
            restoring = true
            maskOwners.clear()
            try {
                lastRestoredOverflow.clear()
                clearLowerRowHandover()
                rowLayoutOwners.keys.toList().forEach { it.requestLayout() }
                rowLayoutOwners.clear()
                clipStates.forEach { (group, flags) ->
                    if (!group.clipChildren) group.clipChildren = flags.first
                    if (!group.clipToPadding) group.clipToPadding = flags.second
                }
                clipStates.clear()
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
        rowOverrides = Preferences.getStringSet(Preferences.KEY_CC_ICON_ROW_OVERRIDES, emptySet())
        hookStatusBarIconListFactory()
        hookIgnoredSlots()
        networkVisibility.clear()
        networkSlotFields.clear()
        networkVisibleStateFields.clear()
        iconVisibleGetters.clear()
        iconBlockedGetters.clear()
        removeFlagGetters.clear()
        networkSpeedWidthGetters.clear()
        batteryLayoutFields.clear()
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
            if (name.endsWith(".ModernStatusBarMobileView")) mobileViewClass = type
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
        hookNativeMobileVisibility()
        DebugLog.i(TAG, "IconPosition hooks installed")
    }

    /** MIUI's binder writes View visibility independently of the icon container's visible state. */
    private fun hookNativeMobileVisibility() {
        val method = View::class.java.getDeclaredMethod("setVisibility", Int::class.javaPrimitiveType)
        deoptimize(method)
        method.hook {
            before { param ->
                if (applyingNativeViewVisibility || Looper.myLooper() != Looper.getMainLooper()) return@before
                val view = param.thisObject as? View ?: return@before
                val type = mobileViewClass ?: return@before
                if (!type.isInstance(view)) return@before
                runCatching {
                    val container = view.parent ?: return@runCatching
                    val slot = networkSlotFields[type]?.get(view) as? String ?: return@runCatching
                    val requested = param.args[0] as? Int ?: return@runCatching
                    param.args[0] = nativeViewVisibility.hostRequest(
                        view, requested, slot in maskSlotsFor(container)
                    )
                }.onFailure { DebugLog.w(TAG, "native mobile visibility mask failed", it) }
            }
        }
    }

    private fun reconcileNativeMobileVisibility(view: View, masked: Boolean) {
        if (mobileViewClass?.isInstance(view) != true) return
        val visibility = nativeViewVisibility.reconcile(view, view.visibility, masked)
        if (view.visibility == visibility) return
        applyingNativeViewVisibility = true
        try {
            view.visibility = visibility
        } finally {
            applyingNativeViewVisibility = false
        }
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
        if (originalSlotOrder.isEmpty()) originalSlotOrder = currentNames
        val normalizedNames = IconSlotPolicy.normalizeReloadOrder(originalSlotOrder, currentNames, options.policy)
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
        // The factory exposes an unmodifiable *view* of this same mutable list. Preserve identity.
        val viewOnly = findField(listObject.javaClass, "mViewOnlySlots")
        check(viewOnly?.get(listObject) is List<*>) { "Missing native slot view" }
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
                        rowLayoutOwners[container] = true
                    }
                }
                after { param ->
                    val container = param.thisObject as? ViewGroup ?: return@after
                    val active = activeHostLayouts.get()
                    if (active?.peekLast() === container) active.removeLast()
                    if (active?.isEmpty() == true) activeHostLayouts.remove()
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
            val translationX = findField(state, "translationX")
            val layoutTranslationX = findField(state, "layoutTranslationX")
            if (layoutStates == null || view == null || visibleState == null ||
                hiddenBySpace == null || inIslandState == null || gone == null ||
                translationX == null || layoutTranslationX == null
            ) null else HostRowStateAccess(
                layoutStates, view, visibleState, hiddenBySpace, inIslandState, gone, translationX, layoutTranslationX
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
                runCatching {
                    restoreTwoLineOverflowStates(container, access)
                    prepareControlCenterRows(container, access)
                }.onFailure { DebugLog.w(TAG, "two-line host state failed", it) }
            }
        }
        DebugLog.hookRegistered(TAG, "two-line host overflow state")
    }

    private fun ownsTwoLineLayout(container: ViewGroup): Boolean {
        if (container.javaClass.name != CONTAINER_CLASS ||
            !ControlCenterHeaderHooker.secondRowStatusIconsEnabled(container.context)
        ) return false
        val layoutFrom = runCatching { layoutFromField?.getInt(container) }.getOrNull()
        return IconSlotPolicy.ownsTwoLineControlCenterRow(layoutFrom) &&
            isControlCenterContainer(container)
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
                if (restoring || retiring) return@before
                val incoming = param.args.getOrNull(0) as? List<*> ?: return@before
                val state = captureContainerState(param.thisObject, incoming)
                val surface = surfaceFor(param.thisObject, incoming)
                val merged = blockedFor(param.thisObject, surface, state.hostIgnored)
                state.lastApplied = merged
                param.args[0] = ArrayList(merged)
            }
            after { param ->
                if (restoring || retiring) return@after
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

    private fun blockedFor(container: Any, surface: IconSurface, systemSlots: List<String>): List<String> =
        if (surface == IconSurface.CONTROL_CENTER &&
            IconSlotPolicy.ownsTwoLineControlCenterRow(
                runCatching { layoutFromField?.getInt(container) }.getOrNull()
            ) &&
            ControlCenterHeaderHooker.twoLineConfigured()
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

    internal fun duoOwnsAirplane(container: Any): Boolean =
        "airplane" in maskOwners.owned(container, IconMaskOwners.Owner.DUO)

    internal fun setCarrierMask(container: Any, mask: CarrierMask): Boolean =
        setContainerMask(container, mask.slots(), IconMaskOwners.Owner.CARRIER)

    private fun setContainerMask(
        container: Any,
        slots: Set<String>,
        owner: IconMaskOwners.Owner
    ): Boolean {
        if (retiring) return slots.isEmpty()
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
                container, surfaceFor(container, state.hostIgnored), state.hostIgnored
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
        val hostBlocked = if (restoring) state.hostIgnored else state.lastApplied ?: blockedFor(
            container, surfaceFor(container, state.hostIgnored), state.hostIgnored
        )
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            val entry = networkVisibility.entries.firstOrNull { it.key.isInstance(child) } ?: continue
            val slot = networkSlotFields[entry.key]?.get(child) as? String ?: continue
            reconcileNativeMobileVisibility(child, slot in slots)
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
        val field = if (networkVisibleStateFields.containsKey(view.javaClass)) {
            networkVisibleStateFields[view.javaClass]
        } else {
            findField(view.javaClass, "iconVisibleState").also { networkVisibleStateFields[view.javaClass] = it }
        }
        field?.getInt(view)
    }.getOrNull()

    private fun invokeNativeVisibleState(method: Method, view: View, state: Int) {
        // The host setter may schedule binding/layout work even when asked for the same state.
        if (readNativeVisibleState(view) == state) return
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

    private fun disableClipping(group: ViewGroup) {
        clipStates.putIfAbsent(group, group.clipChildren to group.clipToPadding)
        group.clipChildren = false
        group.clipToPadding = false
    }

    /** Reuse the existing carrier expansion clock and source drawing for lower status icons. */
    internal fun updateLowerRowHandover(real: ViewGroup, fake: ViewGroup, appearanceAlpha: Float, progress: Float) {
        if (retiring || !ownsTwoLineLayout(real)) { clearLowerRowHandover(); return }
        val root = real.rootView as? ViewGroup ?: return
        val sources = HashMap<String, View>()
        for (index in 0 until fake.childCount) {
            val view = fake.getChildAt(index)
            val slot = slotOf(view) ?: continue
            if (isHostLayoutVisible(view, slot, fake)) sources[slot] = view
        }
        val used = HashSet<View>()
        val leftOwned = LeftContainerHooker.homeOwnedSlots()
        for (index in 0 until real.childCount) {
            val target = real.getChildAt(index)
            val slot = slotOf(target) ?: continue
            if (!IconSlotPolicy.ownsLowerRowMotion(slot, leftOwned, rowOverrides) || !isHostLayoutVisible(target, slot, real)) continue
            val source = sources[slot] ?: continue
            if (lowerRowMotions[target]?.source !== source) lowerRowMotions.remove(target)?.restore()
            val state = lowerRowMotions.getOrPut(target) { LowerRowMotion(source) }
            if (abs(source.alpha - state.appliedAlpha) > .001f) state.savedAlpha = source.alpha
            val ready = state.motion.update(root, source, target, progress, appearanceAlpha)
            state.appliedAlpha = if (ready) 0f else state.savedAlpha
            if (source.alpha != state.appliedAlpha) source.alpha = state.appliedAlpha
            used += target
        }
        lowerRowMotions.entries.removeIf { (target, state) ->
            if (target in used) false else { state.restore(); true }
        }
    }

    internal fun clearLowerRowHandover() {
        lowerRowMotions.values.forEach { it.restore() }
        lowerRowMotions.clear()
    }

    /** Publish row targets before the host applies/animates its NewStatusIconState objects. */
    private fun prepareControlCenterRows(container: ViewGroup, access: HostRowStateAccess) {
        if (retiring || !ownsTwoLineLayout(container) || container.height <= 0) return
        val centers = controlCenterRowCenters(container) ?: return
        val battery = effectiveBattery(container, centers) ?: return
        val states = access.layoutStates.get(container) as? List<*> ?: return
        val nativeStates = IdentityHashMap<View, Any>()
        for (raw in states) {
            val state = raw ?: continue
            val view = access.view.get(state) as? View ?: continue
            if (view.parent === container) nativeStates[view] = state
        }
        val items = ArrayList<TwoRowIconLayout.Item>()
        val children = HashMap<Int, Pair<View, Any>>()
        for (index in 0 until container.childCount) {
            val view = container.getChildAt(index)
            val slot = slotOf(view) ?: continue
            val state = nativeStates[view] ?: continue
            // Use the final host visibility model, including explicit masks and remove state.
            // An appearance fade must not collapse either row's horizontal positions.
            if (access.gone.getBoolean(state) || access.visibleState.getInt(state) != 0 ||
                !isHostLayoutVisible(view, slot, container)) continue
            val removing = removeFlagGetters.getOrPut(view.javaClass) {
                findNoArgMethod(view.javaClass, "getRemoveFlag")
            }?.let { runCatching { it.invoke(view) as? Boolean }.getOrNull() } ?: false
            if (removing) continue
            items += TwoRowIconLayout.Item(index, occupiedWidth(view), IconSlotPolicy.isTwoLineUpperSlot(slot, rowOverrides))
            children[index] = view to state
        }
        val containerLocation = IntArray(2)
        val batteryLocation = IntArray(2)
        container.getLocationOnScreen(containerLocation)
        battery.getLocationOnScreen(batteryLocation)
        val spacingId = container.resources.getIdentifier("status_bar_system_icon_spacing", "dimen", "com.android.systemui")
        val spacing = if (spacingId != 0) container.resources.getDimensionPixelSize(spacingId).toFloat() else 0f
        val positions = TwoRowIconLayout.positions(items,
            upperRight = batteryLocation[0] - containerLocation[0].toFloat(),
            lowerRight = batteryLocation[0] + battery.width - containerLocation[0].toFloat(),
            spacing = spacing, rtl = container.layoutDirection == View.LAYOUT_DIRECTION_RTL)
        disableClipping(container)
        var parent = container.parent as? ViewGroup
        while (parent != null) {
            disableClipping(parent)
            if (parent.javaClass.name == CONTROL_CENTER_ROW_CLASS) break
            parent = parent.parent as? ViewGroup
        }
        for (item in items) {
            val (view, state) = children.getValue(item.index)
            val x = positions.getValue(item.index)
            // X belongs to the native Folme state. Do not snap the child or cancel its animator.
            access.translationX.setFloat(state, x)
            access.layoutTranslationX.setFloat(state, x)
            // The host state has no Y property. Use final child bounds within this layout pass;
            // QS_FAKE remains one row and native appearance/expansion still owns the movement.
            val center = if (item.upper) centers.first else centers.second
            val top = (center - containerLocation[1] - view.height / 2f).toInt()
            view.layout(view.left, top, view.right, top + view.height)
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
        val geometry = ControlCenterCarrierBlockHooker.rowGeometry(carrier) ?: return null
        return (carrierLocation[1] + geometry.firstCenter) to (carrierLocation[1] + geometry.secondCenter)
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
