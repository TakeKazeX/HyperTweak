package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.content.Context
import android.app.KeyguardManager
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
 *   and the glyph (`set(mIcon)` plus the native `adjustViewBounds` behavior) match the right
 *   cluster exactly — this avoids the sizing drift the earlier view-relocation version had. The clones are small boxes
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
    private const val KEYGUARD_ICON_MANAGER_CLASS =
        "com.android.systemui.statusbar.phone.MiuiLightDarkIconManager"
    private const val KEYGUARD_VIEW_CONTROLLER_CLASS =
        "com.android.systemui.statusbar.phone.KeyguardStatusBarViewController"
    private const val KEYGUARD_VIEW_CLASS =
        "com.android.systemui.statusbar.phone.MiuiKeyguardStatusBarView"
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
        Preferences.KEY_ICON_LEFT_HEADSET to listOf("headset", "wireless_headset"),
        Preferences.KEY_ICON_LEFT_COMPOUND to listOf(
            "compound_location", "compound_alarm_clock", "compound_zen",
            "compound_volume_vibrate", "compound_volume_mute"
        )
    )

    // ── Live snapshot (refreshed by the ticker; read by the per-event hooks) ──
    @Volatile
    private var active = false

    @Volatile
    private var activeSlots: Set<String> = emptySet()

    @Volatile
    private var leftMode = IconTunerOptions.LEFT_MODE_DISABLED

    /** Set on the LSPosed binder thread; consumed (and cleared) by the main-thread ticker. */
    @Volatile
    private var resetPending = false

    /** Re-entrancy guard: our own setBlockList re-apply must not re-record the merged list as the
     *  pristine system list (that would pollute the snapshot permanently). */
    @Volatile
    private var inApplyBlocked = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val reconcileRunnable = Runnable { reconcileTick() }

    fun onPackageReady(context: Context) { appContext = context }

    private var iconViewConstructor: java.lang.reflect.Constructor<*>? = null
    private var iconViewMIconField: Field? = null
    private var iconPayloadCloneMethod: Method? = null
    private var isIconVisibleMethod: Method? = null
    private var iconViewSetMethod: Method? = null
    private var iconViewSetResolved = false
    private var darkDispatcherField: Field? = null
    private var setBlockListMethod: Method? = null
    private var islandShowingField: Field? = null
    private var slotGetter: Method? = null
    @Volatile private var appContext: Context? = null

    /** Home DarkIconManager -> bookkeeping. Weak so stale managers vanish. */
    private val states = WeakHashMap<Any, LeftState>()

    private class LeftState(
        val leftHost: ViewGroup,
        val clock: View,
        val rightContainer: ViewGroup,
        val manager: Any,
        val isKeyguard: Boolean = false,
        var islandHandler: Any? = null
    ) {
        /** Pristine system block list (last value the binder fed us), never polluted. */
        var systemBlocked: List<String> = emptyList()
        /** Effective list we last applied to the manager (to avoid churn). */
        var lastApplied: List<String>? = null
        var leftContainer: LinearLayout? = null
        /** slot -> clone view (our own, sized from the system view of the same slot). */
        val clones = HashMap<String, View>()
        /** Slots with a valid clone; only these may be hidden in the right cluster. */
        var migratedSlots: Set<String> = emptySet()
        /** Original keyguard clock/alarm placement, used to restore the host tree exactly. */
        val anchorEntries = ArrayList<AnchorEntry>()
        var anchorWrapper: LinearLayout? = null
        var islandShowing: Boolean = false
    }

    private data class AnchorEntry(
        val view: View,
        val parent: ViewGroup,
        val index: Int,
        val layoutParams: ViewGroup.LayoutParams?
    )

    private fun masterEnabled(): Boolean = leftMode != IconTunerOptions.LEFT_MODE_DISABLED

    private fun selectedSlots(): Set<String> = IconTunerOptions.snapshot().policy.leftSlots

    private fun reloadSnapshot() {
        val snapshot = IconTunerOptions.snapshot()
        leftMode = snapshot.leftMode
        val master = masterEnabled()
        val slots = if (master) selectedSlots() else emptySet()
        active = master && slots.isNotEmpty()
        activeSlots = slots
    }

    private fun keyguardShowing(): Boolean = runCatching {
        appContext?.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
    }.getOrDefault(false)

    private fun shouldRender(state: LeftState): Boolean {
        if (!active || state.leftHost.visibility != View.VISIBLE ||
            !state.leftHost.isAttachedToWindow || !state.leftHost.isShown
        ) return false
        if (state.isKeyguard) return leftMode == IconTunerOptions.LEFT_MODE_HOME_AND_LOCKSCREEN &&
            keyguardShowing()
        return leftMode >= IconTunerOptions.LEFT_MODE_HOME && !keyguardShowing()
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
                            captureManager(root, manager, groupField)?.also {
                                it.islandHandler = resolveIslandHandler(root)
                                it.islandShowing = readIslandShowing(it.islandHandler)
                            } ?: return@after
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
                    val system = IconManagerHooker.pristineForManager(param.thisObject)
                        ?: list.filterIsInstance<String>().toList()
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

        // The lockscreen uses MiuiLightDarkIconManager, which overrides the event callbacks on a
        // different concrete class from the home DarkIconManager. Hook those overrides as well so
        // the clone lifecycle is independent for the two icon groups.
        KEYGUARD_ICON_MANAGER_CLASS.toClassOrNull()?.let { keyguardManagerClass ->
            hookManagerEvents(keyguardManagerClass)
        }

        // Capture the lockscreen manager only after KeyguardStatusBarViewController has completed
        // onViewAttached(): that is when mTintedIconManager and its group are initialized.
        KEYGUARD_VIEW_CONTROLLER_CLASS.toClassOrNull()?.let { controllerClass ->
            controllerClass.findMethodOrNull { name("onViewAttached"); noParams() }?.hook {
                after { param ->
                    runCatching { captureKeyguardState(param.thisObject) }
                        .onFailure { DebugLog.w(TAG, "lockscreen state capture failed", it) }
                    reconcileAll()
                }
            } ?: DebugLog.hookSkipped(TAG, "$KEYGUARD_VIEW_CONTROLLER_CLASS#onViewAttached", "method not found")
            controllerClass.findMethodOrNull { name("onViewDetached"); noParams() }?.hook {
                before { param ->
                    runCatching { detachKeyguardState(param.thisObject) }
                        .onFailure { DebugLog.w(TAG, "lockscreen teardown failed", it) }
                }
                after {
                    // Unlock can only detach the keyguard layer while leaving the home root
                    // attached. Give the home clone state an immediate hand-off instead of
                    // waiting for the periodic ticker to notice the changed keyguard state.
                    mainHandler.post {
                        runCatching { reconcileAll() }
                            .onFailure { DebugLog.w(TAG, "unlock left-container reconcile failed", it) }
                    }
                }
            } ?: DebugLog.hookSkipped(TAG, "$KEYGUARD_VIEW_CONTROLLER_CLASS#onViewDetached", "method not found")
        } ?: DebugLog.hookSkipped(TAG, KEYGUARD_VIEW_CONTROLLER_CLASS, "class not found")

        // The home view can detach/re-attach without recreating its manager. Teardown is kept
        // reversible and the state object remains available for the next attachment.
        statusBarViewClass.findMethodOrNull { name("onDetachedFromWindow"); noParams() }?.hook {
            before { param ->
                runCatching { detachHomeState(param.thisObject) }
                    .onFailure { DebugLog.w(TAG, "home left-container teardown failed", it) }
            }
        } ?: DebugLog.hookSkipped(TAG, "$STATUS_BAR_VIEW_CLASS#onDetachedFromWindow", "method not found")

        // The Compose home status-bar root is detached while the lockscreen is shown and can be
        // re-attached with the same manager (or a freshly-created one). The manager is assigned
        // before the root is attached, so setDarkIconManager() may capture a state that is
        // immediately torn down by shouldRender(). Reconcile again after the root is attached;
        // otherwise unlock has no icon event left to trigger clone creation.
        statusBarViewClass.findMethodOrNull { name("onAttachedToWindow"); noParams() }?.hook {
            after { param ->
                val root = param.thisObject as? ViewGroup ?: return@after
                mainHandler.post {
                    runCatching { rebindHomeState(root, groupField) }
                        .onFailure { DebugLog.w(TAG, "home left-container rebind failed", it) }
                }
            }
        } ?: DebugLog.hookSkipped(TAG, "$STATUS_BAR_VIEW_CLASS#onAttachedToWindow", "method not found")

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
                            applyIslandVisibility(param.thisObject, showing)
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

    private fun hookManagerEvents(managerClass: Class<*>) {
        managerClass.findMethodOrNull { name("onIconAdded"); paramCount(4) }?.let { method ->
            deoptimize(method)
            method.hook {
                after { param ->
                    if (!active && !resetPending) return@after
                    syncClonesFor(synchronized(states) { states[param.thisObject] })
                }
            }
        }
        managerClass.findMethodOrNull { name("onSetIcon"); paramCount(2) }?.let { method ->
            deoptimize(method)
            method.hook {
                after { param ->
                    if (!active && !resetPending) return@after
                    syncClonesFor(synchronized(states) { states[param.thisObject] })
                }
            }
        }
        managerClass.findMethodOrNull { name("onRemoveIcon"); paramCount(1) }?.let { method ->
            deoptimize(method)
            method.hook {
                after { param ->
                    if (!active && !resetPending) return@after
                    syncClonesFor(synchronized(states) { states[param.thisObject] })
                }
            }
        }
    }

    private fun captureKeyguardState(controller: Any) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { captureKeyguardState(controller) }
            return
        }
        val view = hierarchyField(controller.javaClass, "mView")?.get(controller) as? ViewGroup
            ?: return
        if (view.javaClass.name != KEYGUARD_VIEW_CLASS) return
        val manager = hierarchyField(view.javaClass, "mTintedIconManager")?.get(view) ?: return
        val right = hierarchyField(manager.javaClass, "mGroup")?.get(manager) as? ViewGroup ?: return
        val resourceId = { name: String -> view.resources.getIdentifier(name, "id", "com.android.systemui") }
        val leftFrame = view.findViewById(resourceId("keyguard_left_frame")) as? ViewGroup ?: return
        val clock = view.findViewById<View>(resourceId("keyguard_clock")) ?: return
        val alarm = view.findViewById<View>(resourceId("ll_alarm_container")) ?: return
        val anchors = listOf(clock, alarm)
        if (anchors.any { it.parent !== leftFrame }) return

        synchronized(states) {
            val existing = states[manager]
            if (existing != null && existing.leftHost === leftFrame && existing.isKeyguard) return
            existing?.let {
                teardownState(it)
                states.remove(manager)
            }
            val state = LeftState(leftFrame, clock, right, manager, isKeyguard = true)
            state.islandHandler = resolveIslandHandler(view)
            state.islandShowing = readIslandShowing(state.islandHandler)
            state.systemBlocked = IconManagerHooker.pristineForManager(manager)
                ?: readRightBlockSeed()
            anchors.forEach { anchor ->
                val parent = anchor.parent as? ViewGroup ?: return@forEach
                state.anchorEntries += AnchorEntry(
                    view = anchor,
                    parent = parent,
                    index = parent.indexOfChild(anchor),
                    layoutParams = copyLayoutParams(anchor.layoutParams)
                )
            }
            if (state.anchorEntries.size == anchors.size) states[manager] = state
        }
    }

    private fun detachKeyguardState(controller: Any) {
        val view = hierarchyField(controller.javaClass, "mView")?.get(controller) as? ViewGroup ?: return
        val manager = hierarchyField(view.javaClass, "mTintedIconManager")?.get(view) ?: return
        val state = synchronized(states) { states.remove(manager) } ?: return
        runOnMain {
            teardownState(state)
            applyBlocked(state)
        }
    }

    private fun detachHomeState(viewObject: Any?) {
        val view = viewObject as? ViewGroup ?: return
        val manager = hierarchyField(view.javaClass, "mDarkIconManager")?.get(view) ?: return
        val state = synchronized(states) { states[manager] } ?: return
        runOnMain {
            teardownState(state)
            applyBlocked(state)
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post { runCatching(block).onFailure { DebugLog.w(TAG, "main cleanup failed", it) } }
    }

    private fun readRightBlockSeed(): List<String> = runCatching {
        val utils = Class.forName("com.android.systemui.statusbar.phone.MiuiIconManagerUtils")
        val field = utils.getDeclaredField("RIGHT_BLOCK_LIST").apply { isAccessible = true }
        (field.get(null) as? List<*>)?.filterIsInstance<String>() ?: emptyList()
    }.getOrDefault(emptyList())

    private fun resolveIslandHandler(view: Any): Any? = runCatching {
        val dependency = hierarchyField(view.javaClass, "mDependence")?.get(view)
            ?: hierarchyField(view.javaClass, "mDep")?.get(view)
            ?: return null
        val islandController = hierarchyField(dependency.javaClass, "islandController")?.get(dependency)
            ?: return null
        hierarchyField(islandController.javaClass, "islandStateHandler")?.get(islandController)
    }.getOrNull()

    private fun readIslandShowing(handler: Any?): Boolean = handler?.let { value ->
        runCatching { islandShowingField?.getBoolean(value) == true }.getOrDefault(false)
    } ?: false

    private fun copyLayoutParams(params: ViewGroup.LayoutParams?): ViewGroup.LayoutParams? {
        return when (params) {
            is ViewGroup.MarginLayoutParams -> ViewGroup.MarginLayoutParams(params)
            null -> null
            else -> ViewGroup.LayoutParams(params)
        }
    }

    private fun resetReflectionCaches() {
        iconViewConstructor = null
        iconViewMIconField = null
        iconPayloadCloneMethod = null
        isIconVisibleMethod = null
        iconViewSetMethod = null
        iconViewSetResolved = false
        darkDispatcherField = null
        setBlockListMethod = null
        islandShowingField = null
        slotGetter = null
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
                resetReflectionCaches()
            }
            DebugLog.i(TAG, "LeftContainer teardown after hot reload")
        }
        reloadSnapshot()
        synchronized(states) {
            states.values.forEach { state ->
                if (shouldRender(state)) {
                    syncClones(state)
                    applyBlocked(state)
                } else {
                    if (active && !state.isKeyguard && keyguardShowing()) {
                        state.leftContainer?.visibility = View.GONE
                    } else {
                        teardownState(state)
                    }
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
                if (state.isKeyguard) return@forEach
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

    /** Re-capture/reconcile the home manager after its view has re-entered the window. */
    private fun rebindHomeState(root: ViewGroup, groupField: Field) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { rebindHomeState(root, groupField) }
            return
        }
        val manager = hierarchyField(root.javaClass, "mDarkIconManager")?.get(root) ?: return
        synchronized(states) {
            val state = states[manager]
            if (state == null || state.leftHost !== root.findViewById(
                    root.resources.getIdentifier(
                        "phone_status_bar_left_container", "id", "com.android.systemui"
                    )
                )
            ) {
                captureManager(root, manager, groupField)
            }
        }
        DebugLog.i(TAG, "Home status-bar reattached; left-container reconciliation requested")
        reconcileAll()
    }

    private fun ensureContainer(state: LeftState): LinearLayout? {
        state.leftContainer?.let { return it }
        if (state.isKeyguard) ensureKeyguardAnchorWrapper(state) ?: return null
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
        left.visibility = if (state.islandShowing) View.GONE else View.VISIBLE
        val parent = if (state.isKeyguard) state.anchorWrapper else state.leftHost
        val index = if (state.isKeyguard) {
            parent?.childCount ?: return null
        } else {
            state.leftHost.indexOfChild(state.clock) + 1
        }
        parent?.addView(left, index)
        state.leftContainer = left
        DebugLog.i(TAG, "LeftContainer attached")
        return left
    }

    /**
     * The lockscreen's clock and alarm are FrameLayout children, not a horizontal status-bar
     * container. Keep their original parent/index/layout params and wrap only those host views,
     * then append the module clone container after them.
     */
    private fun ensureKeyguardAnchorWrapper(state: LeftState): LinearLayout? {
        state.anchorWrapper?.let { return it }
        val entries = state.anchorEntries
        if (entries.isEmpty() || entries.any { it.view.parent !== it.parent }) return null
        val parent = entries.first().parent
        val insertAt = entries.minOf { it.index }.coerceIn(0, parent.childCount)
        val wrapper = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_INHERIT
            clipChildren = false
            clipToPadding = false
            tag = WRAPPER_TAG
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        entries.sortedBy { it.index }.forEach { entry ->
            parent.removeView(entry.view)
        }
        parent.addView(wrapper, insertAt)
        entries.sortedBy { it.index }.forEach { entry ->
            wrapper.addView(entry.view, entry.layoutParams)
        }
        state.anchorWrapper = wrapper
        return wrapper
    }

    private fun teardownState(state: LeftState) {
        removeClones(state)
        state.leftContainer?.let { left ->
            runCatching { (left.parent as? ViewGroup)?.removeView(left) }
            state.leftContainer = null
        }
        state.clones.clear()
        state.migratedSlots = emptySet()
        restoreKeyguardAnchors(state)
        state.lastApplied = null
    }

    private fun restoreKeyguardAnchors(state: LeftState) {
        val wrapper = state.anchorWrapper ?: return
        val parent = state.anchorEntries.firstOrNull()?.parent ?: return
        runCatching {
            state.anchorEntries.forEach { entry ->
                if (entry.view.parent === wrapper) wrapper.removeView(entry.view)
            }
            if (wrapper.parent === parent) parent.removeView(wrapper)
            state.anchorEntries.sortedBy { it.index }.forEach { entry ->
                if (entry.view.parent == null) {
                    parent.addView(entry.view, entry.index.coerceIn(0, parent.childCount), entry.layoutParams)
                }
            }
        }.onFailure { DebugLog.w(TAG, "failed to restore keyguard clock/alarm placement", it) }
        state.anchorWrapper = null
    }

    // ─── Right-cluster block list ────────────────────────────────────────────────

    /** Re-apply system ∪ selected to the manager if it differs from what we last applied. */
    private fun applyBlocked(state: LeftState) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { applyBlocked(state) }
            return
        }
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
        if (state.migratedSlots.isEmpty()) return state.systemBlocked
        val effective = ArrayList<String>(state.systemBlocked.size + activeSlots.size)
        effective.addAll(state.systemBlocked)
        for (slot in state.migratedSlots) {
            if (!effective.contains(slot)) effective.add(slot)
        }
        return effective
    }

    // ─── Left clones ─────────────────────────────────────────────────────────────

    private fun syncClonesFor(state: LeftState?) {
        if (state == null || !shouldRender(state)) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { syncClonesFor(state) }
            return
        }
        runCatching {
            synchronized(states) {
                syncClones(state)
                applyBlocked(state)
            }
        }
            .onFailure { t -> DebugLog.w(TAG, "LeftContainer sync failed", t) }
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
        var targetIndex = 0
        for (i in 0 until right.childCount) {
            val child = right.getChildAt(i)
            if (child == null) continue
            val slot = slotOf(child) ?: continue
            if (slot !in slots) continue
            val existing = state.clones[slot]
            val clone = existing ?: createClone(state, child, slot) ?: continue
            if (!updateClone(state, clone, child)) {
                state.clones.remove(slot)
                runCatching { (clone.parent as? ViewGroup)?.removeView(clone) }
                unregisterDarkReceiver(state, clone)
                continue
            }
            if (existing == null) {
                state.clones[slot] = clone
                registerDarkReceiver(state, clone)
            }
            val container = state.leftContainer ?: continue
            if (container.indexOfChild(clone) != targetIndex) {
                container.removeView(clone)
                container.addView(clone, targetIndex.coerceAtMost(container.childCount))
            }
            targetIndex++
        }
        if (state.clones.isEmpty()) {
            // Do not leave an empty marked container (or a keyguard anchor wrapper) behind while
            // the source icon is still unavailable. The next host icon event can retry cleanly.
            teardownState(state)
            return
        }
        state.migratedSlots = state.clones.keys.toSet()
        // Home is intentionally hidden while the keyguard owns the status bar. A normal unlock
        // reaches this path with the same state and clones, so restore the container explicitly;
        // otherwise the clones remain permanently GONE until SystemUI is recreated.
        state.leftContainer?.let { left ->
            left.visibility = if (state.islandShowing) View.GONE else View.VISIBLE
        }
    }

    private fun createClone(state: LeftState, child: View, slot: String): View? {
        val ctor = iconViewConstructor ?: return null
        if (iconViewSetMethod == null) return null
        val clone = runCatching {
            ctor.newInstance(state.leftHost.context, slot, null, false)
        }.getOrNull() as? View ?: return null
        // IconManager enables this on every native StatusBarIconView. Without it, the special
        // 58x56dp airplane drawable measures at its intrinsic width instead of fitting the
        // 20dp status-bar slot, producing an abnormally wide clone.
        (clone as? android.widget.ImageView)?.setAdjustViewBounds(true)
        val container = ensureContainer(state) ?: return null
        // Add before payload/tint registration; DarkIconDispatcher immediately sends a callback.
        container.addView(clone)
        return clone
    }

    private fun updateClone(state: LeftState, clone: View, child: View): Boolean {
        // Size: copy the system view's own layout params so the box matches the right cluster
        // exactly (this is what fixes the misplaced height of the earlier view-move version).
        runCatching {
                    val src = child.layoutParams
                    if (src != null) {
                        val cur = clone.layoutParams
                        val marginsDiffer = when {
                            cur is ViewGroup.MarginLayoutParams && src is ViewGroup.MarginLayoutParams ->
                                cur.leftMargin != src.leftMargin || cur.topMargin != src.topMargin ||
                                    cur.rightMargin != src.rightMargin || cur.bottomMargin != src.bottomMargin
                            else -> (cur is ViewGroup.MarginLayoutParams) !=
                                (src is ViewGroup.MarginLayoutParams)
                        }
                        if (cur == null || cur.width != src.width || cur.height != src.height || marginsDiffer) {
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
        val payloadSet = runCatching {
            val icon = iconViewMIconField?.get(child)
            val clonedIcon = icon?.let { cloneIconPayload(it) } ?: return@runCatching false
            val setter = iconViewSetMethod ?: return@runCatching false
            setter.invoke(clone, clonedIcon)
            true
        }.getOrDefault(false)
        if (!payloadSet) return false
        // Visibility mirrors the system (icon logically active).
        val visible = isIconVisibleMethod?.let { m ->
            runCatching { m.invoke(child) as? Boolean }.getOrNull()
        } ?: false
        clone.visibility = if (visible) View.VISIBLE else View.GONE
        return true
    }

    /** StatusBarIcon is mutable; use its host clone implementation before StatusBarIconView.set(). */
    private fun cloneIconPayload(icon: Any): Any? {
        val method = iconPayloadCloneMethod ?: findReflectiveMethod(icon.javaClass) { candidate ->
            candidate.name == "clone" && candidate.parameterTypes.isEmpty()
        }?.also { iconPayloadCloneMethod = it } ?: return null
        return runCatching { method.invoke(icon) }.getOrNull()
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
                    c.getDeclaredField("mDarkIconDispatcher").apply { isAccessible = true }
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
            val method = dispatcher.javaClass.methods.firstOrNull {
                it.name == "addDarkReceiver" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0].isAssignableFrom(clone.javaClass)
            } ?: dispatcher.javaClass.methods.firstOrNull {
                it.name == "addDarkReceiver" && it.parameterTypes.size == 1
            } ?: return
            method.invoke(dispatcher, clone)
        }
    }

    private fun unregisterDarkReceiver(state: LeftState, clone: View) {
        val dispatcher = managerDarkDispatcher(state.manager) ?: return
        runCatching {
            val method = dispatcher.javaClass.methods.firstOrNull {
                it.name == "removeDarkReceiver" && it.parameterTypes.size == 1
            } ?: return
            method.invoke(dispatcher, clone)
        }
    }

    // ─── Island ──────────────────────────────────────────────────────────────────

    private fun applyIslandVisibility(handler: Any?, islandShowing: Boolean) {
        synchronized(states) {
            states.values.forEach { state ->
                if (handler == null || state.islandHandler !== handler) return@forEach
                state.islandShowing = islandShowing
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
                return c.getDeclaredField(name).apply { isAccessible = true }
            }
            c = c.superclass
        }
        return null
    }

    private fun findReflectiveMethod(clazz: Class<*>, predicate: (Method) -> Boolean): Method? {
        var c: Class<*>? = clazz
        while (c != null) {
            c.declaredMethods.firstOrNull(predicate)?.let { return it.apply { isAccessible = true } }
            c = c.superclass
        }
        return clazz.methods.firstOrNull(predicate)?.apply { isAccessible = true }
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
                c.getDeclaredMethod("set", iconCls).apply { isAccessible = true }
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
    private const val WRAPPER_TAG = "hypertweak_keyguard_left_wrapper"
}
