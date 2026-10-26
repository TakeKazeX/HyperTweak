package com.takekazex.hypertweak.hook.rules.systemui.icon.left

import com.takekazex.hypertweak.hook.rules.systemui.icon.IconManagerHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconTunerFlows
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconTunerOptions
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconHostPorts
import android.app.KeyguardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.settings.contract.IconSlotPolicy
import com.takekazex.hypertweak.settings.contract.IconTunerContract
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.ref.WeakReference
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
 * mutate views directly. State capture and cleanup finish on the main thread before the old
 * generation retires; framework view references and panel progress rebind the replacement.
 */
object LeftContainerHooker : StaticHooker() {
    private val handover = LeftHandoverController(object : LeftHandoverController.Host {
        override val retiring get() = LeftContainerHooker.retiring
        override val active get() = LeftContainerHooker.active
        override val shadeSwitchSettledToControlCenter get() = LeftContainerHooker.shadeSwitchSettledToControlCenter
        override val panelVisible get() = LeftContainerHooker.panelVisible
        override val panelProgress get() = LeftContainerHooker.panelProgress
        override fun sourceClones() = synchronized(states) {
            states.values.firstOrNull { !it.isKeyguard && visibility(it).visible && sourceHostDrawn(it) }?.clones
        }
        override fun restoreSources() = synchronized(states) {
            states.values.forEach { state ->
                state.clones.values.forEach { clone -> if (clone.alpha != 1f) clone.alpha = 1f }
                state.leftContainer?.let { container -> if (container.alpha != 1f) container.alpha = 1f }
            }
        }
        override fun rows() = liveControlCenterRows()
        override fun slotOf(view: View) = LeftContainerHooker.slotOf(view)
    })
    private val cloner = LeftIconCloner(this)
    private val presentation = LeftPresentation(object : LeftPresentation.Host {
        override val activeSlots get() = LeftContainerHooker.activeSlots
        override fun cancelSlot(slot: String) = handover.cancelSlot(slot)
        override fun slotOf(view: View) = LeftContainerHooker.slotOf(view)
    }, cloner)
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    @Volatile private var retiring = false
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

    /**
     * Per-frame panel expansion of the control center, in resolution order. Both carry the same
     * `float` the host uses to slide its own rows between the status bar and the control-center
     * header, which makes it the hand-over's follow-finger signal (the header alpha used before is a
     * *blur* ramp: it only falls near the end of the drag, so the icons were drawn twice until
     * release).
     *
     * The delegate is preferred because it is a named class and because its callback list — which
     * includes the header controller that translates the control-center rows — has already run when
     * its own method returns, so the frame's destination geometry is current. The panel callback
     * behind it is the fallback (`DuoSignalHooker` hooks that one for the same value).
     */
    private val CC_EXPAND_CLASSES = listOf(
        "com.miui.systemui.controlcenter.container.ControlCenterExpandControllerDelegate",
        "com.android.systemui.controlcenter.shade.ControlCenterHeaderExpandController\$controlCenterCallback\$1"
    )

    /** The control-center header's status bar: the row that owns the moved slots once it is open. */
    private const val CC_STATUS_ROW_CLASS =
        "com.android.systemui.controlcenter.phone.widget.ControlCenterStatusBarIcon"

    /**
     * The QS header's own status row. On OS4 the control center header (`QS` above) is the visible
     * one, but the pre-OS4 "old" control-center style keeps its icons here instead
     * (`MiuiQSHeaderView.onAttachedToWindow` destroys its manager in the new style), and it carries
     * the same slots either way.
     */
    private const val CC_QS_HEADER_CLASS = "com.android.systemui.qs.MiuiQSHeaderView"

    /** The shade header's mirror of the home bar, drawn over it for the whole drag. */
    private const val CC_FAKE_STATUS_ROW_CLASS =
        "com.android.systemui.controlcenter.phone.widget.ControlCenterFakeStatusIcons"

    /** Candidate rows for the real endpoint and for suppressing duplicate host copies. */
    private val CC_ROW_CLASSES = listOf(
        CC_STATUS_ROW_CLASS, CC_QS_HEADER_CLASS, CC_FAKE_STATUS_ROW_CLASS
    )

    // The per-toggle slot groups live in `IconTunerContract.slotsForLeftPreference` only: an earlier
    // copy here was never read (the live path is `snapshot().policy.leftSlots`) and would have
    // drifted from the slots the hook actually moves.

    // ── Live snapshot (refreshed by the ticker; read by the per-event hooks) ──
    @Volatile
    private var active = false

    @Volatile
    private var activeSlots: Set<String> = emptySet()

    @Volatile
    private var leftMode = IconTunerContract.LEFT_MODE_DISABLED

    private val mainHandler = Handler(Looper.getMainLooper())
    private val reconcileRunnable = Runnable { reconcileTick() }

    fun onPackageReady(context: Context) { appContext = context }

    /**
     * Slots the home status bar's left container actually renders, i.e. the ones the HOME row must
     * stop drawing. Empty while the feature is off.
     *
     * This is the home state's [LeftState.migratedSlots] — the slots whose clone was really created
     * — and deliberately not the requested `activeSlots`: a slot whose clone could not be built must
     * stay visible on the right. Read by [IconManagerHooker.applyPolicy] when it merges a host
     * block-list emission, so the change reaches an already-attached row.
     */
    fun homeOwnedSlots(): Set<String> = synchronized(states) {
        states.values.firstOrNull { !it.isKeyguard }?.migratedSlots ?: emptySet()
    }

    /**
     * Same for the lockscreen row, from the keyguard state. Non-empty only in
     * [IconTunerContract.LEFT_MODE_HOME_AND_LOCKSCREEN] (its own left container exists), which is why
     * the per-location rule in [IconSlotPolicy.ownedSlotsFor] can hand the keyguard its own set
     * instead of the home bar's.
     */
    fun keyguardOwnedSlots(): Set<String> = synchronized(states) {
        states.values.firstOrNull { it.isKeyguard }?.migratedSlots ?: emptySet()
    }

    private var islandShowingField: Field? = null
    private var slotGetter: Method? = null
    @Volatile private var appContext: Context? = null

    /** Home DarkIconManager -> bookkeeping. Weak so stale managers vanish. */
    private val states = WeakHashMap<Any, LeftState>()

    private fun masterEnabled(): Boolean = leftMode != IconTunerContract.LEFT_MODE_DISABLED

    /** Owned slots as of the last tick, so a change can refresh the merged block lists. */
    private var publishedOwnedSlots: Set<String> = emptySet()
    private var publishedKeyguardOwnedSlots: Set<String> = emptySet()

    /**
     * Panel expansion of the control center: 0 while the bar is alone, 1 once the shade owns the top
     * of the screen. Written per frame by the host's expand callback and re-read on every tick.
     */
    @Volatile
    private var panelProgress = 0f

    /** True while the control-center window is still visible, including a gesture resting at 0. */
    @Volatile
    private var panelVisible = false

    /** At this endpoint the host copies are ready; no home-to-QS overlay should replay. */
    private var shadeSwitchSettledToControlCenter = false

    /** True once the host expansion callback is hooked; only then the hand-over is authoritative. */
    @Volatile
    private var panelProgressHooked = false

    /** The shade owns the status-bar band. Read on every tick as the coarser fallback signal. */
    @Volatile
    private var shadeFullyOpen = false

    /**
     * The live control-center rows, in [CC_ROW_CLASSES] order. Weak: the shade views are recreated on
     * configuration changes and the old ones must not be kept alive. Read and written on the main
     * thread only.
     */
    private val ccRows = LinkedHashMap<String, WeakReference<ViewGroup>>()
    private val ccRowsScratch = ArrayList<ViewGroup>(CC_ROW_CLASSES.size)

    /** A secondary host copy temporarily hidden while the overlay carries the icon. */
    private fun reloadSnapshot() {
        val snapshot = IconTunerOptions.snapshot()
        leftMode = snapshot.leftMode
        val master = masterEnabled()
        val slots = if (master) snapshot.policy.leftSlots else emptySet()
        active = master && slots.isNotEmpty()
        activeSlots = slots
    }

    private fun keyguardShowing(): Boolean = IconHostPorts.keyguard.showing() ?: runCatching {
        appContext?.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
    }.getOrDefault(false)

    private fun visibility(state: LeftState): LeftVisibilityPolicy.Result = LeftVisibilityPolicy.resolve(
        configured = active && if (state.isKeyguard) leftMode == IconTunerContract.LEFT_MODE_HOME_AND_LOCKSCREEN
            else leftMode >= IconTunerContract.LEFT_MODE_HOME,
        attached = state.leftHost.isAttachedToWindow,
        hostShown = state.leftHost.isShown,
        keyguard = state.isKeyguard,
        keyguardShowing = keyguardShowing(),
        hideLockscreen = IconHostPorts.keyguard.suppressed(),
        island = state.islandShowing
    )

    // Clone alpha belongs to the handover itself. Only host ancestors determine source eligibility.
    private fun sourceHostDrawn(state: LeftState): Boolean {
        var view: View? = state.leftHost
        while (view != null) {
            if (view.alpha <= 0f || view.visibility != View.VISIBLE) return false
            view = view.parent as? View
        }
        return true
    }

    private fun shouldRender(state: LeftState): Boolean = visibility(state).visible

    private fun <T> onMainBlocking(action: () -> T): T = HostAccess.onMain(action)

    // Only framework objects and collections cross the module class-loader boundary.
    override fun saveHotReloadState(): Any = onMainBlocking {
        listOf(states.values.map { state ->
            listOf(state.leftHost, state.clock, state.rightContainer, state.manager,
                state.isKeyguard, state.barRoot, state.anchorEntries.map {
                    listOf(it.view, it.parent, it.index, it.layoutParams)
                }, state.islandHandler)
        }, ccRows.values.mapNotNull { it.get() }, panelProgress, panelVisible,
            shadeSwitchSettledToControlCenter)
    }

    override fun restoreHotReloadState(state: Any?) {
        val saved = state as? List<*> ?: return
        mainHandler.post {
            if (retiring) return@post
            panelProgress = saved.getOrNull(2) as? Float ?: 0f
            panelVisible = saved.getOrNull(3) as? Boolean ?: false
            shadeSwitchSettledToControlCenter = saved.getOrNull(4) as? Boolean ?: false
            (saved.getOrNull(0) as? List<*>)?.forEach { item ->
                val row = item as? List<*> ?: return@forEach
                val host = row.getOrNull(0) as? ViewGroup ?: return@forEach
                val clock = row.getOrNull(1) as? View ?: return@forEach
                val right = row.getOrNull(2) as? ViewGroup ?: return@forEach
                val manager = row.getOrNull(3) ?: return@forEach
                val restored = LeftState(host, clock, right, manager,
                    row.getOrNull(4) == true, row.getOrNull(5) as? View)
                (row.getOrNull(6) as? List<*>)?.forEach anchor@{ value ->
                    val entry = value as? List<*> ?: return@anchor
                    restored.anchorEntries.add(AnchorEntry(
                        entry.getOrNull(0) as? View ?: return@anchor,
                        entry.getOrNull(1) as? ViewGroup ?: return@anchor,
                        entry.getOrNull(2) as? Int ?: return@anchor,
                        entry.getOrNull(3) as? ViewGroup.LayoutParams))
                }
                restored.islandHandler = row.getOrNull(7)
                restored.islandShowing = readIslandShowing(restored.islandHandler)
                states[manager] = restored
            }
            (saved.getOrNull(1) as? List<*>)?.filterIsInstance<ViewGroup>()
                ?.filter { it.isAttachedToWindow }?.forEach(::captureShadeRow)
            runCatching { reconcileAll() }.onFailure { DebugLog.w(TAG, "restore left icons failed", it) }
        }
    }

    internal fun recoverExistingViews(views: List<View>, progress: Float?, visible: Boolean?) {
        progress?.let { panelProgress = it.coerceIn(0f, 1f) }
        visible?.let { panelVisible = it }
        val managerClass = ICON_MANAGER_BASE_CLASS.toClassOrNull() ?: return
        val groupField = hierarchyField(managerClass, "mGroup") ?: return
        views.forEach { view ->
            when (view.javaClass.name) {
                STATUS_BAR_VIEW_CLASS -> (view as? ViewGroup)?.let { rebindHomeState(it, groupField) }
                KEYGUARD_VIEW_CLASS -> (view as? ViewGroup)?.let(::captureKeyguardView)
                in CC_ROW_CLASSES -> (view as? ViewGroup)?.let(::captureShadeRow)
            }
        }
        reconcileAll()
        // Recovery runs once; a timer only continues if the shade is genuinely open.
        if (panelVisible || panelProgress > 0f) scheduleReconcile()
        DebugLog.i(TAG, "hot reload left roots=${states.size} rows=${ccRows.size}")
    }

    /**
     * Snapshot left-icon hosts after recovery, including visibility and render eligibility.
     * Like the host state itself, this must be read on the main thread.
     */
    internal fun logStateSummary(stage: String) {
        runCatching {
            val keyguard = keyguardShowing()
            val summary = synchronized(states) {
                states.values.map { state ->
                    val left = state.leftContainer
                    val parent = left?.parent as? ViewGroup
                    buildString {
                        append(if (state.isKeyguard) "keyguard" else "home")
                        append(" attached=").append(state.leftHost.isAttachedToWindow)
                        append(" hostShown=").append(state.leftHost.isShown)
                        append(" root=").append(System.identityHashCode(state.barRoot))
                        append(" rootVis=").append(state.barRoot?.visibility)
                        append(" rootAlpha=").append(state.barRoot?.alpha)
                        append(" policy=").append(state.presentation)
                        append(" container=").append(
                            when {
                                left == null -> "none"
                                parent == null -> "detached"
                                else -> "index=${parent.indexOfChild(left)}/${parent.childCount}"
                            }
                        )
                        left?.let {
                            append(" vis=").append(visibilityName(it.visibility))
                            append(" alpha=").append(it.alpha)
                            append(" w=").append(it.width).append(" h=").append(it.height)
                        }
                        append(" render=").append(shouldRender(state))
                        append(" clones=").append(state.clones.keys.sorted())
                    }
                }
            }
            DebugLog.i(
                TAG,
                "left state stage=$stage mode=$leftMode active=$active keyguard=$keyguard " +
                    "suppressed=${IconHostPorts.keyguard.suppressed()} " +
                    "wanted=${activeSlots.sorted()} rows=${summary.ifEmpty { listOf("none") }.joinToString(" | ")}"
            )
        }.onFailure { DebugLog.w(TAG, "left state summary failed", it) }
    }

    private fun visibilityName(value: Int): String = when (value) {
        View.VISIBLE -> "V"
        View.INVISIBLE -> "I"
        View.GONE -> "G"
        else -> value.toString()
    }

    override fun onPrepareHotReload() {
        retiring = true
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.removeCallbacks(reconcileRunnable)
        onMainBlocking {
            handover.restoreHandover()
            shadeSwitchSettledToControlCenter = false
            states.values.forEach(presentation::teardownState)
            states.clear()
            ccRows.clear()
            ownershipRefreshPending = false
            resetReflectionCaches()
            publishOwnedChange()
        }
    }

    override fun onHook() {
        retiring = false
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

        if (!cloner.prepare()) return

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
        // The block list is no longer written from here: `IconManagerHooker` owns that single merge
        // path (slot modes first, then this hooker's owned slots per host location). This hook only
        // had to observe the emission to remember the pristine list, which the policy hook already
        // tracks itself — writing it from here used to bypass the slot modes entirely.

        // 3. Re-sync the left clones whenever the bar changes (slot scan, no indices).
        managerClass.findMethodOrNull {
            name("onIconAdded"); paramCount(4)
        }?.let { method ->
            deoptimize(method)
            method.hook {
                after { param ->
                    if (!active) return@after
                    syncClonesFor(synchronized(states) { states[param.thisObject] })
                }
            }
        } ?: DebugLog.hookSkipped(TAG, "$ICON_MANAGER_CLASS#onIconAdded", "method not found")

        managerClass.findMethodOrNull { name("onSetIcon"); paramCount(2) }?.let { method ->
            deoptimize(method)
            method.hook {
                after { param ->
                    if (!active) return@after
                    syncClonesFor(synchronized(states) { states[param.thisObject] })
                }
            }
        } ?: DebugLog.hookSkipped(TAG, "$ICON_MANAGER_CLASS#onSetIcon", "method not found")

        managerClass.findMethodOrNull { name("onRemoveIcon"); paramCount(1) }?.let { method ->
            deoptimize(method)
            method.hook {
                after { param ->
                    if (!active) return@after
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
                        if (retiring) return@post
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
                    if (retiring) return@post
                    runCatching { rebindHomeState(root, groupField) }
                        .onFailure { DebugLog.w(TAG, "home left-container rebind failed", it) }
                }
            }
        } ?: DebugLog.hookSkipped(TAG, "$STATUS_BAR_VIEW_CLASS#onAttachedToWindow", "method not found")

        // A host root can hide/reveal without attachment or an icon payload change.
        View::class.java.getMethod("setVisibility", Int::class.javaPrimitiveType).hook {
            after { param ->
                if (retiring || reconciling) return@after
                val root = param.thisObject as? View ?: return@after
                if (synchronized(states) { states.values.any { it.barRoot === root } }) requestReconcile()
            }
        }

        View::class.java.getMethod("setAlpha", Float::class.javaPrimitiveType).hook {
            after { param ->
                if (retiring || reconciling) return@after
                val view = param.thisObject as? View ?: return@after
                if (synchronized(states) { states.values.any { it.barRoot === view || it.leftHost === view } }) {
                    runOnMain { handover.applyHandoverGuarded(panelProgress) }
                }
            }
        }

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

        // 5. Hand the moved icons over to the control center while the shade is dragged.
        hookControlCenterHandover()

        reloadSnapshot()
        // One repair pass after installation; the timer only continues while the shade is open.
        scheduleReconcile()
        DebugLog.i(
            TAG,
            "LeftContainer hooks installed (clone + policy merge, panelProgress=$panelProgressHooked, " +
                "snapshot=$activeSlots)"
        )
    }

    /**
     * Hooks the host signal that drives the hand-over, and captures the rows that draw the moved
     * slots while the shade is open.
     *
     * `ControlCenterHeaderExpandController`'s panel callback is called with the panel expansion on
     * every frame of the drag, and the host uses the same value to slide its own rows from the
     * status-bar position to the control-center header (`onExpansionChanged`:
     * `controlCenterStatusBarIcon.setTranslationX/Y(offset * (1 - progress))`). Following it is
     * therefore exactly as "跟手" as the system's own icons.
     *
     * Every row that draws a copy of the home cluster is captured from its own `onAttachedToWindow`
     * ([CC_ROW_CLASSES]): the control center's own row, the QS header's row of the old control-center
     * style, and the shade header's mirror of the bar. Each of them draws the same moved slots, so
     * each of them has to join the hand-over or the icon is on screen twice.
     *
     * Fail-open: an unresolved target leaves the previous behavior (the icons stay in the left
     * container) — and the ticker's `isShadeFullyOpen` fallback still hides them once the shade is
     * open, so a missing hook cannot leave the duplicate behind permanently.
     */
    private fun hookControlCenterHandover() {
        CC_ROW_CLASSES.forEach { name ->
            val rowClass = name.toClassOrNull()
            if (rowClass == null) {
                DebugLog.hookSkipped(TAG, name, "class not found")
                return@forEach
            }
            rowClass.findMethodOrNull { name("onAttachedToWindow"); noParams() }?.let { method ->
                method.hook {
                    after { param -> (param.thisObject as? ViewGroup)?.let(::captureShadeRow) }
                }
            } ?: DebugLog.hookSkipped(TAG, "$name#onAttachedToWindow", "method not found")
        }

        val source = CC_EXPAND_CLASSES.firstNotNullOfOrNull { name ->
            val type = name.toClassOrNull()
            if (type == null) {
                DebugLog.hookSkipped(TAG, name, "class not found")
                return@firstNotNullOfOrNull null
            }
            type.findMethodOrNull { name("onExpansionChanged"); paramCount(1) }?.let { method ->
                deoptimize(method)
                method.hook {
                    after { param ->
                        val progress = (param.args.getOrNull(0) as? Number)?.toFloat() ?: return@after
                        if (progress < 0f || progress > 1f) return@after
                        onPanelProgress(progress)
                    }
                }
                DebugLog.hookRegistered(TAG, "${type.name}#onExpansionChanged")
                type
            } ?: run {
                DebugLog.hookSkipped(TAG, "$name#onExpansionChanged", "method not found")
                null
            }
        }
        if (source == null) return
        panelProgressHooked = true
        // Visibility, rather than progress alone, tells us whether the panel has actually been
        // dismissed. The panel can rest at progress=0 while the finger is still down; restoring the
        // endpoints there would show the right copy beside the left clone.
        source.findMethodOrNull { name("onVisibleChanged"); paramCount(1) }?.let { method ->
            method.hook {
                after { param ->
                    val visible = param.args.getOrNull(0) as? Boolean ?: return@after
                    panelVisible = visible
                    if (!visible) {
                        panelProgress = 0f
                        shadeSwitchSettledToControlCenter = false
                    }
                    handover.applyHandoverGuarded(if (visible) panelProgress else 0f)
                }
            }
        }
        source.findMethodOrNull { name("onAppearanceChanged"); paramCount(2) }?.let { method ->
            method.hook {
                after { param ->
                    // Appearance changes swap the fake and real rows, but do not necessarily mean
                    // that the panel window has been dismissed. Keep the current hand-over fraction
                    // and let onVisibleChanged(false) perform the final restore.
                    if (param.args.getOrNull(0) == true) panelVisible = true
                    handover.applyHandoverGuarded(if (panelVisible) panelProgress else 0f)
                }
            }
        }
    }

    /** Remembers a control-center row that draws the moved slots (weak: the shade is recreated). */
    private fun captureShadeRow(row: ViewGroup) {
        if (row.javaClass.name !in CC_ROW_CLASSES) return
        ccRows[row.javaClass.name] = WeakReference(row)
        // Install the parked copies immediately: a row that turns visible before the first expansion
        // callback (or attaches mid-gesture) must not draw the icon on the right.
        mainHandler.post { if (!retiring) handover.applyHandoverGuarded(panelProgress) }
    }

    /** The live rows that draw a copy of the home cluster, in hand-over preference order. */
    private fun liveControlCenterRows(): List<ViewGroup> {
        ccRowsScratch.clear()
        CC_ROW_CLASSES.forEach { name ->
            val row = ccRows[name]?.get() ?: return@forEach
            if (row.isAttachedToWindow) ccRowsScratch.add(row)
        }
        return ccRowsScratch
    }

    private fun onPanelProgress(progress: Float) {
        panelProgress = progress.coerceIn(0f, 1f)
        if (panelProgress < 0.999f) shadeSwitchSettledToControlCenter = false
        if (panelProgress > 0f && !panelVisible) {
            // The first expansion callback can arrive after the fake row has already been made
            // visible. Park its copies before creating the overlay so that frame cannot flash the
            // original icon on the right.
            panelVisible = true
            handover.parkHandover()
        } else if (panelProgress > 0f) {
            panelVisible = true
        }
        // The host drives this callback per frame while the shade moves, which is exactly when a
        // dropped frame can leave a clone behind. Arm the repair tick for the duration.
        if (panelVisible || panelProgress > 0f) scheduleReconcile()
        handover.applyHandoverGuarded(panelProgress)
    }

    /**
     * Carries every selected icon through the same hand-over as Duo's middle Wi-Fi glyph.
     *
     * The host rows remain untouched geometrically. One overlay per slot draws the already tinted
     * left clone from its real screen position to the real expanded endpoint; duplicate fake-row
     * copies are alpha-suppressed only after the overlay has produced a frame. The last quarter fades
     * the overlay out while the endpoint fades in, exactly like `DuoPanelMotion`.
     */
    internal fun onShadeSwitchStarted(controlCenterVisible: Boolean) {
        if (!active) return
        runCatching {
            shadeSwitchSettledToControlCenter = false
            handover.suspendHandoverForShadeSwitch()
            val sourceAlpha = if (controlCenterVisible) 0f else 1f
            synchronized(states) {
                states.values.forEach { state ->
                    if (state.isKeyguard) return@forEach
                    state.clones.values.forEach { clone -> clone.alpha = sourceAlpha }
                }
            }
        }
            .onFailure { DebugLog.w(TAG, "LeftContainer shade-switch suspend failed", it) }
    }

    internal fun onShadeSwitchFinished(controlCenterVisible: Boolean) {
        if (!active) return
        panelVisible = controlCenterVisible
        panelProgress = if (controlCenterVisible) 1f else 0f
        shadeSwitchSettledToControlCenter = controlCenterVisible
        if (controlCenterVisible) scheduleReconcile() else requestReconcile()
        handover.applyHandoverGuarded(panelProgress)
    }

    private fun hookManagerEvents(managerClass: Class<*>) {
        managerClass.findMethodOrNull { name("onIconAdded"); paramCount(4) }?.let { method ->
            deoptimize(method)
            method.hook {
                after { param ->
                    if (!active) return@after
                    syncClonesFor(synchronized(states) { states[param.thisObject] })
                }
            }
        }
        managerClass.findMethodOrNull { name("onSetIcon"); paramCount(2) }?.let { method ->
            deoptimize(method)
            method.hook {
                after { param ->
                    if (!active) return@after
                    syncClonesFor(synchronized(states) { states[param.thisObject] })
                }
            }
        }
        managerClass.findMethodOrNull { name("onRemoveIcon"); paramCount(1) }?.let { method ->
            deoptimize(method)
            method.hook {
                after { param ->
                    if (!active) return@after
                    syncClonesFor(synchronized(states) { states[param.thisObject] })
                }
            }
        }
    }

    private fun captureKeyguardState(controller: Any) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { if (!retiring) captureKeyguardState(controller) }
            return
        }
        val view = hierarchyField(controller.javaClass, "mView")?.get(controller) as? ViewGroup
            ?: return
        captureKeyguardView(view)
    }

    private fun captureKeyguardView(view: ViewGroup) {
        if (view.javaClass.name != KEYGUARD_VIEW_CLASS) return
        val manager = hierarchyField(view.javaClass, "mTintedIconManager")?.get(view) ?: return
        val right = hierarchyField(manager.javaClass, "mGroup")?.get(manager) as? ViewGroup ?: return
        val resourceId = { name: String -> view.resources.getIdentifier(name, "id", "com.android.systemui") }
        val leftFrame = view.findViewById(resourceId("keyguard_left_frame")) as? ViewGroup ?: return
        val clock = view.findViewById<View>(resourceId("keyguard_clock")) ?: return
        val alarm = view.findViewById<View>(resourceId("ll_alarm_container")) ?: return
        val anchors = listOf(clock, alarm)
        synchronized(states) {
            val existing = states[manager]
            if (existing != null && existing.leftHost === leftFrame && existing.isKeyguard) return
            if (anchors.any { it.parent !== leftFrame }) return
            existing?.let {
                presentation.teardownState(it)
                states.remove(manager)
            }
            val state = LeftState(leftFrame, clock, right, manager, isKeyguard = true, barRoot = view)
            state.islandHandler = resolveIslandHandler(view)
            state.islandShowing = readIslandShowing(state.islandHandler)
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
            presentation.teardownState(state)
            publishOwnedChange()
            // No clones left: hand the control-center copies back instead of leaving them parked.
            handover.applyHandoverGuarded(panelProgress)
        }
    }

    private fun detachHomeState(viewObject: Any?) {
        val view = viewObject as? ViewGroup ?: return
        val manager = hierarchyField(view.javaClass, "mDarkIconManager")?.get(view) ?: return
        val state = synchronized(states) { states[manager] } ?: return
        runOnMain {
            presentation.teardownState(state)
            publishOwnedChange()
            handover.applyHandoverGuarded(panelProgress)
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (retiring) return
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post { if (!retiring) runCatching(block).onFailure { DebugLog.w(TAG, "main cleanup failed", it) } }
    }

    /** The home status bar's dependency object, which holds the island and shade controllers. */
    private fun statusBarDependency(view: Any): Any? = runCatching {
        hierarchyField(view.javaClass, "mDependence")?.get(view)
            ?: hierarchyField(view.javaClass, "mDep")?.get(view)
    }.getOrNull()

    private fun resolveIslandHandler(view: Any): Any? = runCatching {
        val dependency = statusBarDependency(view) ?: return null
        val islandController = hierarchyField(dependency.javaClass, "islandController")?.get(dependency)
            ?: return null
        hierarchyField(islandController.javaClass, "islandStateHandler")?.get(islandController)
    }.getOrNull()

    /**
     * True once the shade owns the whole top of the screen. Read from the same dependency object the
     * island lookup uses (`ShadeController.isShadeFullyOpen`). The per-frame panel progress drives
     * the hand-over; this coarse flag is only the ticker's fallback for a build where that callback
     * could not be hooked.
     */
    private fun readShadeFullyOpen(view: Any?): Boolean = runCatching {
        val root = view as? View ?: return false
        val dependency = statusBarDependency(root) ?: return false
        val shadeController = hierarchyField(dependency.javaClass, "shadeController")
            ?.get(dependency) ?: return false
        val method = shadeController.javaClass.methods.firstOrNull {
            it.name == "isShadeFullyOpen" && it.parameterTypes.isEmpty()
        } ?: return false
        method.invoke(shadeController) as? Boolean == true
    }.getOrDefault(false)

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
        cloner.reset()
        islandShowingField = null
        slotGetter = null
    }

    // ─── Event-driven reconciliation (main thread only) ─────────────────────────

    /**
     * Repairs what a dropped frame left behind. It is only worth running while the shade owns the
     * screen: that is the one path driven per frame by the host, so that is the only place a frame
     * can be lost. When the shade is closed the same work is triggered by the events that can
     * actually change the result (settings commit, clone attach, Duo ownership, hot reload), so an
     * unconditional timer would only re-read the whole icon snapshot forever.
     *
     * Device sampling on 2026-10-20 attributed the module's only visible CPU samples in SystemUI to
     * this tick (LeftContainerHooker via the 1.5 s timer), with the shade closed.
     */
    private fun reconcileTick() {
        if (retiring) return
        runCatching { reconcileAll() }.onFailure { t ->
            DebugLog.w(TAG, "LeftContainer reconcile failed", t)
        }
        // Keep repairing while the shade is on screen or still moving; stop once it settles.
        if (panelVisible || panelProgress > 0f) scheduleReconcile()
    }

    /**
     * Reconciles now, for an event that changed the outcome. This is what replaces the old
     * unconditional timer when the shade is closed: settings commits, clone attach and Duo
     * ownership all land here.
     */
    internal fun requestReconcile() {
        if (retiring) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { if (!retiring) requestReconcile() }
            return
        }
        mainHandler.removeCallbacks(reconcileRunnable)
        runCatching { reconcileAll() }
            .onFailure { DebugLog.w(TAG, "LeftContainer reconcile failed", it) }
        if (panelVisible || panelProgress > 0f) scheduleReconcile()
    }

    private fun scheduleReconcile() {
        mainHandler.removeCallbacks(reconcileRunnable)
        mainHandler.postDelayed(reconcileRunnable, RECONCILE_INTERVAL_MS)
    }

    private var ownershipRefreshPending = false
    internal fun onDuoOwnershipChanged() {
        if (ownershipRefreshPending) return
        ownershipRefreshPending = true
        mainHandler.post {
            if (retiring) return@post
            ownershipRefreshPending = false
            reconcileAll()
        }
    }

    private var reconciling = false
    private fun reconcileAll() {
        if (retiring || reconciling) return
        reconciling = true
        try { reconcilePresentation() } finally { reconciling = false }
    }

    private fun reconcilePresentation() {
        reloadSnapshot()
        var needsOverlayRefresh = false
        synchronized(states) {
            states.values.forEach { state ->
                val next = visibility(state)
                if (state.presentation != next) {
                    DebugLog.i(TAG, "left visibility surface=${if (state.isKeyguard) "keyguard" else "home"} " +
                        "root=${System.identityHashCode(state.barRoot)} reason=$next clones=${state.clones.size}")
                }
                state.presentation = next
                if (next.retain) {
                    // Content and migration ownership remain current even while temporarily hidden.
                    presentation.applyVisibility(state)
                    presentation.syncClones(state)
                } else {
                    presentation.teardownState(state)
                }
            }
            // A merged list only changes when the owned slots do, so react to that instead of
            // re-publishing on every tick (the host asserts the main thread and re-measures).
            val home = homeOwnedSlots()
            val keyguard = keyguardOwnedSlots()
            if (home != publishedOwnedSlots || keyguard != publishedKeyguardOwnedSlots) {
                publishedOwnedSlots = home
                publishedKeyguardOwnedSlots = keyguard
                needsOverlayRefresh = true
            }
        }
        if (needsOverlayRefresh) IconManagerHooker.republishMergedLists()
        // The hand-over runs per frame from the host's expansion callback; re-applying it here
        // repairs whatever a dropped frame left behind. While the shade owns the top of the screen
        // the hand-over is forced to its open endpoint, which is also all the fallback there is on a
        // build where the per-frame callback could not be resolved.
        shadeFullyOpen = readShadeFullyOpen(
            synchronized(states) { states.values.firstOrNull { !it.isKeyguard }?.barRoot }
        )
        handover.applyHandoverGuarded(if (shadeFullyOpen) 1f else panelProgress)
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

        // The pristine host block list is captured by `IconManagerHooker` from the static OS4 lists
        // it registers (`RIGHT_BLOCK_LIST` / `CONTROL_CENTER_BLOCK_LIST`); this hook no longer keeps
        // its own copy, which is what let the two disagree.

        val state = LeftState(leftHost, clock, right, manager, barRoot = root)
        state.islandHandler = resolveIslandHandler(root)
        state.islandShowing = readIslandShowing(state.islandHandler)
        states[manager] = state
        return state
    }

    /** Re-capture/reconcile the home manager after its view has re-entered the window. */
    private fun rebindHomeState(root: ViewGroup, groupField: Field) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { if (!retiring) rebindHomeState(root, groupField) }
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

    // ─── Right-cluster block list ────────────────────────────────────────────────

    /**
     * Tells the policy hook to re-merge every manager's block list, which is where the owned slots
     * are applied and where the slot modes win. This hooker deliberately does not write the list
     * itself any more: doing so bypassed `IconSlotPolicy.blockedFor` for the row it wrote.
     *
     * Called when the owned set changes and on teardown, so a removed container gives its slots back
     * to the right cluster (the merge then has nothing to add).
     */
    private fun publishOwnedChange() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { if (!retiring) publishOwnedChange() }
            return
        }
        publishedOwnedSlots = homeOwnedSlots()
        publishedKeyguardOwnedSlots = keyguardOwnedSlots()
        IconManagerHooker.republishMergedLists()
    }

    // ─── Left clones ─────────────────────────────────────────────────────────────

    private fun syncClonesFor(state: LeftState?) {
        if (state == null) return
        requestReconcile()
    }

    /** Island events update policy inputs; they never independently reveal a container. */
    private fun applyIslandVisibility(handler: Any?, islandShowing: Boolean) = runOnMain {
        synchronized(states) {
            states.values.forEach { state ->
                if (handler != null && state.islandHandler === handler) state.islandShowing = islandShowing
            }
        }
        requestReconcile()
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

    private fun hierarchyField(clazz: Class<*>, name: String): Field? = HostAccess.field(clazz, name)

    /** Marker on our own left container so teardown never touches system views. */
}
