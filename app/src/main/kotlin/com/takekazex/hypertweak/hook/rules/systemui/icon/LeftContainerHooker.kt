package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap

/**
 * 图标左置 — shows selected status-bar slots (勿扰 zen, 静音/振动 volume+mute, 热点 hotspot,
 * 闹钟 alarm_clock, 定位 location+gps, 蓝牙 bluetooth, NFC, VPN, 飞行模式 airplane,
 * 耳机 headset+wireless_headset) in a container right after the home-screen clock, hidden from
 * the right cluster. Ported from Hyper Helper's `LeftContainer` (OS4_ADAPTATION_PLAN.md T2,
 * `icon_tuner_left_container`) and rebuilt for the OS4 home status bar.
 *
 * OS4 reality (all targets verified on OS4.0.0.15.XPMCNXM): upstream's OS3 `LeftContainer`
 * registered a SECOND icon group via `StatusBarIconControllerImpl.addIconGroup` and hid the slots
 * from the right cluster through `RIGHT_BLOCK_LIST`. A second `DarkIconManager` cannot be built on
 * OS4 without the Dagger factory and would double-bind the Kairos cellular/WiFi pipeline into the
 * left container, so this port keeps the single home manager as source of truth and does NOT move
 * any view or touch the index scheme at all:
 *
 * - **Right-cluster hiding**: the home `DarkIconManager.setBlockList(List)` is hooked to append
 *   the currently selected slots to the system block list (which the OS4
 *   `HomeStatusBarIconBlockListBinder`/`HomeStatusBarIconBlockListInteractor` machinery applies).
 *   Blocked holders are still added by `addHolder` (z flag) but are not measured
 *   (`MiuiStatusIconContainer.onMeasure` skips `isIconBlocked()` children) and not visible —
 *   exactly the system's own icon_blacklist behavior. The hook remembers the pristine system list
 *   on every emission and re-applies system ∪ selected; the 1.5 s main-thread ticker re-applies it
 *   when the selection changes, so toggling the master or a single slot takes effect live in both
 *   directions (the right cluster shows the icon again within ~1.5 s when switched off).
 * - **Left rendering**: for each selected slot a fresh `StatusBarIconView` clone is created in a
 *   LinearLayout inserted right after the clock (`R.id.phone_status_bar_left_container`,
 *   status_bar.xml: clock → [left icons] → chips → notification area). Its `layoutParams` are
 *   copied from the system's own right-cluster view of the same slot on every sync, so the box
 *   and the glyph (`set(mIcon)` → identical `mIconScale`) match the right cluster exactly — this
 *   avoids the sizing drift the earlier view-relocation version had. The clones are small boxes
 *   (classic slots: WRAP_CONTENT × `status_bar_icon_height` 20dp) that the right cluster's
 *   `MiuiStatusIconContainer.onLayout` centers vertically itself; in our container the clones
 *   are centered by the container's own `gravity = CENTER_VERTICAL` (plus a per-clone
 *   `layoutParams.gravity` override) — without it LinearLayout's default TOP gravity parks the
 *   boxes against the top of the full-height container and the icons render raised (被抬高).
 *   Dark tint comes from the manager's `DarkIconDispatcher` (the clones are registered as dark
 *   receivers).
 * - **Sync**: `onIconAdded` / `onSetIcon` / `onRemoveIcon` after-hooks re-run the idempotent
 *   clone sync (slot scan, no indices) whenever the bar changes; the ticker reconciles anything
 *   missed and drives on/off transitions.
 *
 * **Threading / hot reload**: `onPrepareHotReload` runs on the LSPosed binder thread and must not
 * touch views (the earlier version did and threw `CalledFromWrongThreadException`, which left the
 * relocated icons stuck — root cause of "关掉之后图标不会消失"). It only flags a pending reset;
 * the main-thread ticker performs the teardown (remove clones/container, re-apply the pristine
 * system list) on its next tick. All view mutations happen on the main thread.
 */
object LeftContainerHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "IconTuner"
    private const val RECONCILE_INTERVAL_MS = 1500L

    private const val STATUS_BAR_VIEW_CLASS =
        "com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView"
    private const val ICON_MANAGER_CLASS =
        "com.android.systemui.statusbar.phone.ui.DarkIconManager"
    private const val ICON_MANAGER_BASE_CLASS =
        "com.android.systemui.statusbar.phone.ui.IconManager"
    private const val ISLAND_HANDLER_CLASS =
        "com.android.systemui.statusbar.StatusBarIslandControllerImpl\$IslandStateHandler"
    private const val STATUS_BAR_ICON_VIEW_CLASS =
        "com.android.systemui.statusbar.StatusBarIconView"

    /** Per-slot toggle -> the status-bar slot names it moves. */
    private val slotGroups: Map<String, List<String>> = mapOf(
        Preferences.KEY_ICON_LEFT_ZEN to listOf("zen"),
        // MIUI splits the ringer state across two slots depending on mode.
        Preferences.KEY_ICON_LEFT_VOLUME to listOf("volume", "mute"),
        Preferences.KEY_ICON_LEFT_HOTSPOT to listOf("hotspot"),
        Preferences.KEY_ICON_LEFT_ALARM_CLOCK to listOf("alarm_clock"),
        // The satellite "gps" dot and the privacy-style "location" dot are separate slots.
        Preferences.KEY_ICON_LEFT_LOCATION to listOf("location", "gps"),
        Preferences.KEY_ICON_LEFT_BLUETOOTH to listOf("bluetooth"),
        Preferences.KEY_ICON_LEFT_NFC to listOf("nfc"),
        Preferences.KEY_ICON_LEFT_VPN to listOf("vpn"),
        Preferences.KEY_ICON_LEFT_AIRPLANE to listOf("airplane"),
        Preferences.KEY_ICON_LEFT_HEADSET to listOf("headset", "wireless_headset")
    )

    // ── Live snapshot (refreshed by the ticker; read by the per-event hooks) ──
    @Volatile
    private var active = false

    @Volatile
    private var activeSlots: Set<String> = emptySet()

    /** Set on the LSPosed binder thread; consumed (and cleared) by the main-thread ticker. */
    @Volatile
    private var resetPending = false

    /** Re-entrancy guard: our own setBlockList re-apply must not re-record the merged list as the
     *  pristine system list (that would pollute the snapshot permanently). */
    @Volatile
    private var inApplyBlocked = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val reconcileRunnable = Runnable { reconcileTick() }

    private var iconViewConstructor: java.lang.reflect.Constructor<*>? = null
    private var iconViewMIconField: Field? = null
    private var isIconVisibleMethod: Method? = null
    private var iconViewSetMethod: Method? = null
    private var iconViewSetResolved = false
    private var darkDispatcherField: Field? = null
    private var setBlockListMethod: Method? = null
    private var islandShowingField: Field? = null
    private var slotGetter: Method? = null

    /** Home DarkIconManager -> bookkeeping. Weak so stale managers vanish. */
    private val states = WeakHashMap<Any, LeftState>()

    private class LeftState(
        val leftHost: ViewGroup,
        val clock: View,
        val rightContainer: ViewGroup,
        val manager: Any
    ) {
        /** Pristine system block list (last value the binder fed us), never polluted. */
        var systemBlocked: List<String> = emptyList()
        /** Effective list we last applied to the manager (to avoid churn). */
        var lastApplied: List<String>? = null
        var leftContainer: LinearLayout? = null
        /** slot -> clone view (our own, sized from the system view of the same slot). */
        val clones = HashMap<String, View>()
    }

    private fun masterEnabled(): Boolean =
        Preferences.getBoolean(Preferences.KEY_ICON_LEFT_CONTAINER_ENABLED, false)

    private fun selectedSlots(): Set<String> =
        slotGroups
            .filter { (key, _) -> Preferences.getBoolean(key, false) }
            .flatMap { (_, slots) -> slots }
            .toSet()

    private fun reloadSnapshot() {
        val master = masterEnabled()
        val slots = if (master) selectedSlots() else emptySet()
        active = master && slots.isNotEmpty()
        activeSlots = slots
    }

    override fun onPrepareHotReload() {
        // Binder thread: never touch views here. Flag a full teardown; the main-thread ticker
        // performs it (and re-applies the pristine system block list).
        resetPending = true
        mainHandler.removeCallbacks(reconcileRunnable)
        mainHandler.post(reconcileRunnable)
    }

    override fun onHook() {
        IconTunerFlows.init(classLoader)

        val managerBaseClass = ICON_MANAGER_BASE_CLASS.toClassOrNull()
        if (managerBaseClass == null) {
            DebugLog.hookSkipped(TAG, ICON_MANAGER_BASE_CLASS, "class not found")
            return
        }
        val groupField = hierarchyField(managerBaseClass, "mGroup")
        if (groupField == null) {
            DebugLog.hookSkipped(TAG, "$ICON_MANAGER_BASE_CLASS#mGroup", "field not found")
            return
        }

        // Clone class + members, resolved once.
        val iconViewClass = STATUS_BAR_ICON_VIEW_CLASS.toClassOrNull()
        if (iconViewClass == null) {
            DebugLog.hookSkipped(TAG, STATUS_BAR_ICON_VIEW_CLASS, "class not found")
            return
        }
        iconViewConstructor = runCatching {
            iconViewClass.getConstructor(
                android.content.Context::class.java,
                String::class.java,
                Class.forName("android.service.notification.StatusBarNotification"),
                Boolean::class.javaPrimitiveType
            )
        }.getOrNull()
        if (iconViewConstructor == null) {
            DebugLog.hookSkipped(TAG, "$STATUS_BAR_ICON_VIEW_CLASS#<init>", "constructor not found")
            return
        }
        resolveIconViewSetter()
        iconViewMIconField = runCatching {
            iconViewClass.getDeclaredField("mIcon").apply { isAccessible = true }
        }.getOrNull()
        isIconVisibleMethod = runCatching {
            iconViewClass.getMethod("isIconVisible")
        }.getOrNull()

        // 1. Capture the home manager when the status bar view receives it.
        val statusBarViewClass = STATUS_BAR_VIEW_CLASS.toClassOrNull()
        if (statusBarViewClass == null) {
            DebugLog.hookSkipped(TAG, STATUS_BAR_VIEW_CLASS, "class not found")
            return
        }
        statusBarViewClass.findMethodOrNull { name("setDarkIconManager"); paramCount(1) }
            ?.let { method ->
                method.hook {
                    after { param ->
                        val root = param.thisObject as? ViewGroup ?: return@after
                        val manager = param.args.getOrNull(0) ?: return@after
                        synchronized(states) {
                            if (states.containsKey(manager)) return@after
                            captureManager(root, manager, groupField) ?: return@after
                        }
                        reconcileAll()
                    }
                }
            }
            ?: DebugLog.hookSkipped(TAG, "$STATUS_BAR_VIEW_CLASS#setDarkIconManager", "method not found")

        // 2. Remember the pristine system block list on every binder emission, and immediately
        //    re-apply system ∪ selected so the right cluster hides the selected slots.
        //    (setBlockList is declared as final on the BASE IconManager; resolve it there.)
        val managerClass = ICON_MANAGER_CLASS.toClassOrNull()
        if (managerClass == null) {
            DebugLog.hookSkipped(TAG, ICON_MANAGER_CLASS, "class not found")
            return
        }
        val blockListOwner = ICON_MANAGER_BASE_CLASS.toClassOrNull()
        if (blockListOwner == null) {
            DebugLog.hookSkipped(TAG, ICON_MANAGER_BASE_CLASS, "class not found")
            return
        }
        setBlockListMethod = blockListOwner.findMethodOrNull {
            name("setBlockList"); paramCount(1)
        }
        blockListOwner.findMethodOrNull { name("setBlockList"); paramCount(1) }?.let { method ->
            method.hook {
                after { param ->
                    if (inApplyBlocked) return@after
                    val state = synchronized(states) { states[param.thisObject] }
                        ?: return@after
                    val list = param.args.getOrNull(0) as? List<*> ?: return@after
                    val system = list.filterIsInstance<String>().toList()
                    synchronized(states) {
                        state.systemBlocked = system
                    }
                    applyBlocked(state)
                }
            }
        } ?: DebugLog.hookSkipped(TAG, "$ICON_MANAGER_BASE_CLASS#setBlockList", "method not found")

        // 3. Re-sync the left clones whenever the bar changes (slot scan, no indices).
        managerClass.findMethodOrNull {
            name("onIconAdded"); paramCount(4)
        }?.let { method ->
            deoptimize(method)
            method.hook {
                after { param ->
                    if (!active && !resetPending) return@after
                    syncClonesFor(synchronized(states) { states[param.thisObject] })
                }
            }
        } ?: DebugLog.hookSkipped(TAG, "$ICON_MANAGER_CLASS#onIconAdded", "method not found")

        managerClass.findMethodOrNull { name("onSetIcon"); paramCount(2) }?.let { method ->
            deoptimize(method)
            method.hook {
                after { param ->
                    if (!active && !resetPending) return@after
                    syncClonesFor(synchronized(states) { states[param.thisObject] })
                }
            }
        } ?: DebugLog.hookSkipped(TAG, "$ICON_MANAGER_CLASS#onSetIcon", "method not found")

        managerClass.findMethodOrNull { name("onRemoveIcon"); paramCount(1) }?.let { method ->
            deoptimize(method)
            method.hook {
                after { param ->
                    if (!active && !resetPending) return@after
                    syncClonesFor(synchronized(states) { states[param.thisObject] })
                }
            }
        } ?: DebugLog.hookSkipped(TAG, "$ICON_MANAGER_CLASS#onRemoveIcon", "method not found")

        // 4. Hide the left container while the status-bar island is showing.
        val islandHandlerClass = ISLAND_HANDLER_CLASS.toClassOrNull()
        if (islandHandlerClass == null) {
            DebugLog.hookSkipped(TAG, ISLAND_HANDLER_CLASS, "class not found")
        } else {
            islandShowingField = runCatching {
                islandHandlerClass.getDeclaredField("islandShowing").apply { isAccessible = true }
            }.getOrNull()
            islandHandlerClass.findMethodOrNull { name("islandUpdate"); paramCount(2) }
                ?.let { method ->
                    method.hook {
                        after { param ->
                            val showing = islandShowingField?.let { field ->
                                runCatching { field.getBoolean(param.thisObject) }.getOrNull()
                            } ?: return@after
                            applyIslandVisibility(showing)
                        }
                    }
                }
                ?: DebugLog.hookSkipped(
                    TAG, "$ISLAND_HANDLER_CLASS#islandUpdate", "method not found"
                )
        }

        reloadSnapshot()
        mainHandler.removeCallbacks(reconcileRunnable)
        mainHandler.postDelayed(reconcileRunnable, RECONCILE_INTERVAL_MS)
        DebugLog.i(TAG, "LeftContainer hooks installed (block-hide + clone, snapshot=$activeSlots)")
    }

    // ─── Periodic reconciliation (main thread only) ──────────────────────────────

    private fun reconcileTick() {
        runCatching { reconcileAll() }.onFailure { t ->
            DebugLog.w(TAG, "LeftContainer reconcile failed", t)
        }
        mainHandler.postDelayed(reconcileRunnable, RECONCILE_INTERVAL_MS)
    }

    private fun reconcileAll() {
        if (resetPending) {
            resetPending = false
            synchronized(states) {
                states.values.forEach { state ->
                    teardownState(state)
                }
                sweepLegacyLeftContainers()
                states.values.forEach { state ->
                    applyBlocked(state)
                }
                states.clear()
            }
            DebugLog.i(TAG, "LeftContainer teardown after hot reload")
        }
        reloadSnapshot()
        synchronized(states) {
            states.values.forEach { state ->
                if (active) {
                    applyBlocked(state)
                    syncClones(state)
                } else {
                    teardownState(state)
                    applyBlocked(state) // restore pristine system list
                }
            }
        }
    }

    /**
     * Hot-reload cleanup for leftovers of the earlier view-relocation builds: raw
     * `android.widget.LinearLayout` children of the left host with no resource id (their
     * containers carried no tag). System children of `phone_status_bar_left_container` (clock,
     * stubs, chips, notification area) all have ids or custom classes, so this is safe.
     */
    private fun sweepLegacyLeftContainers() {
        synchronized(states) {
            states.values.forEach { state ->
                runCatching {
                    val host = state.leftHost
                    for (i in host.childCount - 1 downTo 0) {
                        val child = host.getChildAt(i)
                        if (child.tag == CONTAINER_TAG) continue
                        if (child.id == View.NO_ID &&
                            child.javaClass.name == "android.widget.LinearLayout"
                        ) {
                            host.removeViewAt(i)
                        }
                    }
                }
            }
        }
    }

    // ─── Capture / container ─────────────────────────────────────────────────────

    private fun captureManager(root: ViewGroup, manager: Any, groupField: Field): LeftState? {
        val right = groupField.get(manager) as? ViewGroup ?: return null
        val res = root.resources
        fun id(name: String): Int = res.getIdentifier(name, "id", "com.android.systemui")
        val statusIconsId = id("statusIcons")
        if (statusIconsId == 0 || right.id != statusIconsId) {
            return null // not the home status-bar manager (e.g. secondary display variant)
        }
        val leftHost = root.findViewById<View>(id("phone_status_bar_left_container")) as? ViewGroup
        val clock = root.findViewById<View>(id("clock"))
        if (leftHost == null || clock == null) {
            DebugLog.hookSkipped(TAG, "phone_status_bar_left_container", "view not found")
            return null
        }
        if (leftHost.indexOfChild(clock) < 0) return null

        // Seed the pristine system block list from the static lists the OS4 interactor seeds
        // from (`HomeStatusBarIconBlockListInteractor.defaultBlockedIcons`).
        val systemSeed = runCatching {
            val utils = Class.forName("com.android.systemui.statusbar.phone.MiuiIconManagerUtils")
            val f = utils.getDeclaredField("RIGHT_BLOCK_LIST").apply { isAccessible = true }
            (f.get(null) as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        }.getOrDefault(emptyList())

        val state = LeftState(leftHost, clock, right, manager)
        state.systemBlocked = systemSeed
        states[manager] = state
        return state
    }

    private fun ensureContainer(state: LeftState): LinearLayout? {
        state.leftContainer?.let { return it }
        val left = LinearLayout(state.leftHost.context)
        left.orientation = LinearLayout.HORIZONTAL
        // The container's OWN gravity aligns its children vertically. This must be
        // CENTER_VERTICAL: LinearLayout defaults to TOP, which hugged the icon clones
        // against the top of the full-height container — the "被抬高" (raised) look.
        // (`layoutParams.gravity` below is only how the HOST positions this container,
        // and is irrelevant because the container already fills the host height.)
        left.gravity = android.view.Gravity.CENTER_VERTICAL
        left.clipChildren = false
        left.clipToPadding = false
        left.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        )
        left.tag = CONTAINER_TAG
        state.leftHost.addView(left, state.leftHost.indexOfChild(state.clock) + 1)
        state.leftContainer = left
        DebugLog.i(TAG, "LeftContainer attached")
        return left
    }

    private fun teardownState(state: LeftState) {
        removeClones(state)
        state.leftContainer?.let { left ->
            runCatching { (left.parent as? ViewGroup)?.removeView(left) }
            state.leftContainer = null
        }
        state.clones.clear()
    }

    // ─── Right-cluster block list ────────────────────────────────────────────────

    /** Re-apply system ∪ selected to the manager if it differs from what we last applied. */
    private fun applyBlocked(state: LeftState) {
        val effective = buildEffectiveList(state)
        val last = state.lastApplied
        if (last != null && last == effective) return
        state.lastApplied = effective
        val method = setBlockListMethod ?: return
        runCatching {
            inApplyBlocked = true
            try {
                method.invoke(state.manager, effective)
            } finally {
                inApplyBlocked = false
            }
        }.onFailure { t ->
            DebugLog.w(TAG, "LeftContainer setBlockList failed", t)
        }
    }

    private fun buildEffectiveList(state: LeftState): List<String> {
        if (!active) return state.systemBlocked
        val effective = ArrayList<String>(state.systemBlocked.size + activeSlots.size)
        effective.addAll(state.systemBlocked)
        for (slot in activeSlots) {
            if (!effective.contains(slot)) effective.add(slot)
        }
        return effective
    }

    // ─── Left clones ─────────────────────────────────────────────────────────────

    private fun syncClonesFor(state: LeftState?) {
        if (state == null || !active) return
        runCatching {
            synchronized(states) { syncClones(state) }
        }.onFailure { t ->
            DebugLog.w(TAG, "LeftContainer sync failed", t)
        }
    }

    /** Idempotent: sees the current right-cluster children and mirrors them into the left. */
    private fun syncClones(state: LeftState) {
        val right = state.rightContainer
        val slots = activeSlots
        if (slots.isEmpty()) {
            teardownState(state)
            return
        }

        // 1. Drop clones whose slot is no longer selected or has no live view on the right.
        val it = state.clones.entries.iterator()
        while (it.hasNext()) {
            val (slot, clone) = it.next()
            val child = rightChildForSlot(state, slot)
            if (slot !in slots || child == null) {
                it.remove()
                runCatching { (clone.parent as? ViewGroup)?.removeView(clone) }
                unregisterDarkReceiver(state, clone)
            }
        }

        // 2. Create / refresh clones in right-container order.
        for (i in 0 until right.childCount) {
            val child = right.getChildAt(i)
            if (child == null) continue
            val slot = slotOf(child) ?: continue
            if (slot !in slots) continue
            val existing = state.clones[slot]
            val clone = existing ?: createClone(state, child, slot) ?: continue
            if (existing == null) state.clones[slot] = clone
            updateClone(state, clone, child)
        }
    }

    private fun createClone(state: LeftState, child: View, slot: String): View? {
        val ctor = iconViewConstructor ?: return null
        if (iconViewSetMethod == null) return null
        val clone = runCatching {
            ctor.newInstance(state.leftHost.context, slot, null, false)
        }.getOrNull() as? View ?: return null
        val container = ensureContainer(state) ?: return null
        // Order: follow the right container's child order by appending in scan order.
        container.addView(clone)
        registerDarkReceiver(state, clone)
        return clone
    }

    private fun updateClone(state: LeftState, clone: View, child: View) {
        // Size: copy the system view's own layout params so the box matches the right cluster
        // exactly (this is what fixes the misplaced height of the earlier view-move version).
        runCatching {
            val src = child.layoutParams
            if (src != null) {
                val cur = clone.layoutParams
                if (cur == null || cur.width != src.width || cur.height != src.height) {
                    val copy = when (src) {
                        is ViewGroup.MarginLayoutParams -> LinearLayout.LayoutParams(src)
                        else -> LinearLayout.LayoutParams(src)
                    }
                    // Per-child gravity overrides the container's, so the clone is always
                    // vertically centered even if some slot's source params carry a stray
                    // gravity (the right cluster centers ~20dp boxes inside the full-height
                    // bar; TOP alignment made the clones render raised).
                    copy.gravity = android.view.Gravity.CENTER_VERTICAL
                    clone.layoutParams = copy
                }
            }
        }
        // Icon payload: mirror the child's StatusBarIcon so scale/desc/colors are identical.
        runCatching {
            val icon = iconViewMIconField?.get(child)
            if (icon != null && iconViewSetMethod != null) {
                iconViewSetMethod!!.invoke(clone, icon)
            }
        }
        // Visibility mirrors the system (icon logically active).
        val visible = isIconVisibleMethod?.let { m ->
            runCatching { m.invoke(child) as? Boolean }.getOrNull()
        } ?: false
        clone.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun rightChildForSlot(state: LeftState, slot: String): View? {
        val right = state.rightContainer
        for (i in 0 until right.childCount) {
            val child = right.getChildAt(i)
            if (slotOf(child) == slot) return child
        }
        return null
    }

    private fun removeClones(state: LeftState) {
        state.clones.values.forEach { clone ->
            runCatching { (clone.parent as? ViewGroup)?.removeView(clone) }
            unregisterDarkReceiver(state, clone)
        }
        state.clones.clear()
    }

    // ─── Dark tint ───────────────────────────────────────────────────────────────

    private fun managerDarkDispatcher(manager: Any): Any? {
        if (darkDispatcherField == null) {
            var c: Class<*>? = manager.javaClass
            while (c != null) {
                val f = runCatching {
                    c!!.getDeclaredField("mDarkIconDispatcher").apply { isAccessible = true }
                }.getOrNull()
                if (f != null) {
                    darkDispatcherField = f
                    break
                }
                c = c.superclass
            }
        }
        val f = darkDispatcherField ?: return null
        return runCatching { f.get(manager) }.getOrNull()
    }

    private fun registerDarkReceiver(state: LeftState, clone: View) {
        val dispatcher = managerDarkDispatcher(state.manager) ?: return
        runCatching {
            dispatcher.javaClass.getMethod("addDarkReceiver", View::class.java)
                .invoke(dispatcher, clone)
        }
    }

    private fun unregisterDarkReceiver(state: LeftState, clone: View) {
        val dispatcher = managerDarkDispatcher(state.manager) ?: return
        runCatching {
            dispatcher.javaClass.getMethod("removeDarkReceiver", View::class.java)
                .invoke(dispatcher, clone)
        }
    }

    // ─── Island ──────────────────────────────────────────────────────────────────

    private fun applyIslandVisibility(islandShowing: Boolean) {
        synchronized(states) {
            states.values.forEach { state ->
                state.leftContainer?.let { left ->
                    runCatching {
                        left.visibility = if (islandShowing) View.GONE else View.VISIBLE
                    }
                }
            }
        }
    }

    // ─── Reflection helpers ──────────────────────────────────────────────────────

    private fun slotOf(view: View): String? {
        if (slotGetter == null) {
            var c: Class<*>? = view.javaClass
            while (c != null && slotGetter == null) {
                val current = c
                val found = current.interfaces.firstNotNullOfOrNull { iface ->
                    runCatching { iface.getMethod("getSlot") }.getOrNull()
                } ?: runCatching { current.getMethod("getSlot") }.getOrNull()
                if (found != null) slotGetter = found
                c = c.superclass
            }
        }
        val getter = slotGetter ?: return null
        return runCatching { getter.invoke(view) as? String }.getOrNull()
    }

    private fun hierarchyField(clazz: Class<*>, name: String): Field? {
        var c: Class<*>? = clazz
        while (c != null) {
            runCatching {
                return c!!.getDeclaredField(name).apply { isAccessible = true }
            }
            c = c.superclass
        }
        return null
    }

    private fun resolveIconViewSetter(): Method? {
        iconViewSetMethod?.let { return it }
        if (iconViewSetResolved) return null
        iconViewSetResolved = true
        val cls = STATUS_BAR_ICON_VIEW_CLASS.toClassOrNull() ?: return null
        val iconCls = runCatching {
            Class.forName("com.android.internal.statusbar.StatusBarIcon")
        }.getOrNull() ?: return null
        var c: Class<*>? = cls
        while (c != null) {
            runCatching {
                c!!.getDeclaredMethod("set", iconCls).apply { isAccessible = true }
            }.getOrNull()?.let { method ->
                iconViewSetMethod = method
                return method
            }
            c = c.superclass
        }
        return null
    }

    /** Marker on our own left container so teardown never touches system views. */
    private const val CONTAINER_TAG = "hypertweak_left_container"
}