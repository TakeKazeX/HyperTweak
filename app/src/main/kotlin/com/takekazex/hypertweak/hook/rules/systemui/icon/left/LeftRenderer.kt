package com.takekazex.hypertweak.hook.rules.systemui.icon.left

import com.takekazex.hypertweak.hook.base.orSkip
import com.takekazex.hypertweak.hook.base.HostRecoveryPhase
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
import com.takekazex.hypertweak.hook.base.ViewVisibilityRouter
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconRenderer
import com.takekazex.hypertweak.hook.rules.systemui.icon.PanelHub
import com.takekazex.hypertweak.hook.rules.systemui.icon.ShadeSwitchMotionPolicy
import com.takekazex.hypertweak.settings.contract.IconSlotPolicy
import com.takekazex.hypertweak.settings.contract.IconTunerContract
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap
import com.takekazex.hypertweak.util.platform.ResourceLookup

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
 *   LinearLayout inserted right after the clock (stock: `R.id.phone_status_bar_left_container`,
 *   status_bar.xml: clock → [left icons] → chips → notification area). Custom ROMs wrap or move the
 *   clock, so the row is the nearest horizontal ancestor of the clock that does not hold the system
 *   icons ([LeftAnchorPolicy]), with the start of the stock row as fallback. Its `layoutParams` are
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
internal object LeftRenderer : IconRenderer() {
    override val hostCallbackOrder = 50
    init {
        hostRecovery(HostRecoveryPhase.PRESENTATION, icons = true) {
            recoverExistingViews(it.views, it.progress, it.visible); logStateSummary("recovery")
        }
    }

    internal val handover = LeftHandoverController(object : LeftHandoverController.Host {
        override val retiring get() = LeftRenderer.retiring
        override val active get() = LeftRenderer.active
        override val shadeSwitchSettledToControlCenter get() = LeftRenderer.shadeSwitchSettledToControlCenter
        override val panelVisible get() = LeftRenderer.panelVisible
        override val panelProgress get() = LeftRenderer.panelProgress
        override fun sourceClones() = synchronized(states) {
            states.values.firstOrNull { !it.isKeyguard && LeftVisibilityPolicy.handoverSource(
                visibility(it), panelVisible || panelProgress > 0f, sourceHostDrawn(it), keyguardShowing(), it.islandShowing)
            }?.clones
        }
        override fun restoreSources() = synchronized(states) {
            states.values.forEach { state ->
                state.clones.values.forEach { clone -> if (clone.alpha != 1f) clone.alpha = 1f }
                state.leftContainer?.let { container -> if (container.alpha != 1f) container.alpha = 1f }
            }
        }
        override fun rows() = liveControlCenterRows()
        override fun slotOf(view: View) = LeftRenderer.slotOf(view)
    })
    internal val cloner = LeftIconCloner(this)
    internal val presentation = LeftPresentation(object : LeftPresentation.Host {
        override val activeSlots get() = LeftRenderer.activeSlots
        override fun cancelSlot(slot: String) = handover.cancelSlot(slot)
        override fun slotOf(view: View) = LeftRenderer.slotOf(view)
        override fun presentationApplied(state: LeftState) = carrierSwap.update(state)
        override fun releaseCarrierSwap(state: LeftState) = carrierSwap.release(state)
    }, cloner)
    // Holds only the application context for its screen-on receiver, released per generation.
    @android.annotation.SuppressLint("StaticFieldLeak")
    internal val carrierSwap = LeftKeyguardCarrierSwap(object : LeftKeyguardCarrierSwap.Host {
        override val retiring get() = LeftRenderer.retiring
        override val handler get() = mainHandler
        override val mode get() = keyguardCarrierMode
        // Placement switched off entirely, or a left-placed airplane: the left-side label keeps the
        // glyph. Otherwise a replacing glyph moves to the native right slot after the intro.
        override val labelKeepsAirplane get() = leftMode == IconTunerContract.LEFT_MODE_DISABLED ||
            IconSlotPolicy.SLOT_AIRPLANE in activeSlots
        // A hidden bar (screen-on reveal, unlock) keeps the icons' claim so waking does not flash
        // the carrier; the island hides only the icons, so the carrier must take the corner back.
        override fun iconsShown(state: LeftState) = state.presentation.retain &&
            state.presentation != LeftVisibilityPolicy.Result.ISLAND && state.clones.values.any(cloner::shows)
        override fun keyguardStates() = synchronized(states) { states.values.filter { it.isKeyguard } }
        override fun slotOf(view: View) = LeftRenderer.slotOf(view)
    })
    init { generationCleanup { HostAccess.onMain { carrierSwap.reset() } } }

    @Volatile
    internal var retiring = false
    init { generationCleanup { retiring = true } }
    internal const val TAG = "IconTuner"
    internal const val RECONCILE_INTERVAL_MS = 1500L

    internal const val STATUS_BAR_VIEW_CLASS =
        "com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView"
    internal const val ICON_MANAGER_CLASS =
        "com.android.systemui.statusbar.phone.ui.DarkIconManager"
    internal const val ICON_MANAGER_BASE_CLASS =
        "com.android.systemui.statusbar.phone.ui.IconManager"
    internal const val KEYGUARD_ICON_MANAGER_CLASS =
        "com.android.systemui.statusbar.phone.MiuiLightDarkIconManager"
    internal const val KEYGUARD_VIEW_CONTROLLER_CLASS =
        "com.android.systemui.statusbar.phone.KeyguardStatusBarViewController"
    internal const val KEYGUARD_VIEW_CLASS =
        "com.android.systemui.statusbar.phone.MiuiKeyguardStatusBarView"
    internal const val ISLAND_HANDLER_CLASS =
        "com.android.systemui.statusbar.StatusBarIslandControllerImpl\$IslandStateHandler"
    internal const val THEME_CONTROLLER_CLASS =
        "com.android.systemui.statusbar.phone.ui.StatusBarIconControllerImpl"

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
     * behind it is the fallback (`DuoRenderer` hooks that one for the same value).
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
    internal val CC_ROW_CLASSES = listOf(
        CC_STATUS_ROW_CLASS, CC_QS_HEADER_CLASS, CC_FAKE_STATUS_ROW_CLASS
    )

    // The per-toggle slot groups live in `IconTunerContract.slotsForLeftPreference` only: an earlier
    // copy here was never read (the live path is `snapshot().policy.leftSlots`) and would have
    // drifted from the slots the hook actually moves.

    // ── Live snapshot (refreshed by the ticker; read by the per-event hooks) ──
    internal var active by generationState { false }

    internal var activeSlots: Set<String> by generationState { emptySet() }

    internal var leftMode by generationState { IconTunerContract.LEFT_MODE_DISABLED }

    internal var keyguardCarrierMode by generationState { IconTunerContract.LEFT_KEYGUARD_CARRIER_REPLACE }

    internal val mainHandler = Handler(Looper.getMainLooper())
    internal val reconcileRunnable = Runnable { reconcileTick() }

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

    internal var islandShowingField: Field? by generationState { null }
    private var slotGetter: Method? by generationState { null }
    private var appContext: Context? by generationState { null }

    /** Home DarkIconManager -> bookkeeping. Weak so stale managers vanish. */
    internal val states: WeakHashMap<Any, LeftState> = generationMainResource(WeakHashMap<Any, LeftState>()) { states ->
        states.values.filter { it.isKeyguard }.forEach(carrierSwap::releaseAirplane)
        states.clear()
    }

    private fun masterEnabled(): Boolean = leftMode != IconTunerContract.LEFT_MODE_DISABLED

    /** Owned slots as of the last tick, so a change can refresh the merged block lists. */
    internal var publishedOwnedSlots: Set<String> by generationState { emptySet() }
    internal var publishedKeyguardOwnedSlots: Set<String> by generationState { emptySet() }

    /**
     * Panel expansion of the control center: 0 while the bar is alone, 1 once the shade owns the top
     * of the screen. Written per frame by the host's expand callback and re-read on every tick.
     */
    internal val panelProgress get() = PanelHub.state.progress

    /** True while the control-center window is still visible, including a gesture resting at 0. */
    internal val panelVisible get() = PanelHub.state.visible

    /** At this endpoint the host copies are ready; no home-to-QS overlay should replay. */
    internal val shadeSwitchSettledToControlCenter get() = PanelHub.state.settledToControlCenter

    /** True once the host expansion callback is hooked; only then the hand-over is authoritative. */
    internal var panelProgressHooked by generationState { false }

    /** The shade owns the status-bar band. Read on every tick as the coarser fallback signal. */
    internal var shadeFullyOpen by generationState { false }

    /**
     * The live control-center rows, in [CC_ROW_CLASSES] order. Weak: the shade views are recreated on
     * configuration changes and the old ones must not be kept alive. Read and written on the main
     * thread only.
     */
    internal val ccRows = generationMainResource(LinkedHashMap<String, WeakReference<ViewGroup>>()) { it.clear() }
    internal val ccRowsScratch = generationMainResource(ArrayList<ViewGroup>(CC_ROW_CLASSES.size)) { it.clear() }

    /** A secondary host copy temporarily hidden while the overlay carries the icon. */
    internal fun reloadSnapshot() {
        val snapshot = IconTunerOptions.snapshot()
        leftMode = snapshot.leftMode
        keyguardCarrierMode = snapshot.leftKeyguardCarrier
        val master = masterEnabled()
        val slots = if (master) snapshot.policy.leftSlots else emptySet()
        active = master && slots.isNotEmpty()
        activeSlots = slots
    }

    internal fun keyguardShowing(): Boolean = IconHostPorts.keyguard.showing() ?: runCatching {
        appContext?.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
    }.getOrDefault(false)

    internal fun visibility(state: LeftState): LeftVisibilityPolicy.Result = LeftVisibilityPolicy.resolve(
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

    internal fun shouldRender(state: LeftState): Boolean = visibility(state).visible

    internal fun <T> onMainBlocking(action: () -> T): T = HostAccess.onMain(action)

    private val recovery = LeftRecovery()
    override fun saveHotReloadState(): Any = recovery.saveHotReloadState()
    override fun restoreHotReloadState(state: Any?) = recovery.restoreHotReloadState(state)
    internal fun recoverExistingViews(views: List<View>, progress: Float?, visible: Boolean?) =
        recovery.recoverExistingViews(views, progress, visible)
    internal fun logStateSummary(stage: String) = recovery.logStateSummary(stage)

    override fun onPrepareHotReload() {
        retiring = true
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.removeCallbacks(reconcileRunnable)
        onMainBlocking {
            handover.restoreHandover()
            states.values.forEach { state ->
                carrierSwap.releaseAirplane(state)
                presentation.teardownState(state)
            }
            states.clear()
            ccRows.clear()
            ownershipRefreshPending = false
            resetReflectionCaches()
            publishOwnedChange()
        }
    }

    private val hostSubscriptions = LeftHostSubscriptions(this)
    override fun onHook() = hostSubscriptions.install()

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
    internal var shadeSwitchObserved by generationState { false }
    private val panelBinding = LeftPanelBinding(this)
    internal fun hookControlCenterHandover() = panelBinding.hookControlCenterHandover()
    internal fun captureShadeRow(row: ViewGroup) = panelBinding.captureShadeRow(row)
    private fun liveControlCenterRows(): List<ViewGroup> = panelBinding.liveControlCenterRows()
    internal fun onShadeSwitchStarted(controlCenterVisible: Boolean) = panelBinding.onShadeSwitchStarted(controlCenterVisible)
    internal fun onShadeSwitchFinished(controlCenterVisible: Boolean) = panelBinding.onShadeSwitchFinished(controlCenterVisible)

    internal fun hookManagerEvents(managerClass: Class<*>) {
        managerClass.findMethodOrNull { name("onIconAdded"); paramCount(4) }?.let { method ->
            deoptimize(method)
            method.observe {
                after { param ->
                    if (!active) return@after
                    syncClonesFor(synchronized(states) { states[param.thisObject] })
                }
            }
        }
        managerClass.findMethodOrNull { name("onSetIcon"); paramCount(2) }?.let { method ->
            deoptimize(method)
            method.observe {
                after { param ->
                    if (!active) return@after
                    syncClonesFor(synchronized(states) { states[param.thisObject] })
                }
            }
        }
        managerClass.findMethodOrNull { name("onRemoveIcon"); paramCount(1) }?.let { method ->
            deoptimize(method)
            method.observe {
                after { param ->
                    if (!active) return@after
                    syncClonesFor(synchronized(states) { states[param.thisObject] })
                }
            }
        }
    }

    internal val keyguardCapture = LeftKeyguardCapture(object : LeftKeyguardCapture.Host {
        override val retiring get() = LeftRenderer.retiring
        override val handler get() = mainHandler
        override val states get() = LeftRenderer.states
        override fun teardown(state: LeftState) {
            carrierSwap.releaseAirplane(state)
            presentation.teardownState(state)
        }
        override fun resolveIslandHandler(view: Any) = LeftRenderer.resolveIslandHandler(view)
        override fun readIslandShowing(handler: Any?) = LeftRenderer.readIslandShowing(handler)
        override fun reject(reason: String) = rejectCapture(reason)
        override fun viewName(view: View?) = LeftRenderer.viewName(view)
        override fun detached(state: LeftState) = runOnMain {
            carrierSwap.releaseAirplane(state)
            presentation.teardownState(state)
            publishOwnedChange()
            handover.applyHandoverGuarded(panelProgress)
        }
    })

    internal fun detachHomeState(viewObject: Any?) {
        val view = viewObject as? ViewGroup ?: return
        val manager = hierarchyField(view.javaClass, "mDarkIconManager")?.get(view) ?: return
        val state = synchronized(states) { states[manager] } ?: return
        runOnMain {
            presentation.teardownState(state)
            publishOwnedChange()
            handover.applyHandoverGuarded(panelProgress)
        }
    }

    internal fun runOnMain(block: () -> Unit) {
        if (retiring) return
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post { if (!retiring) runCatching(block).onFailure { DebugLog.w(TAG, "main cleanup failed", it) } }
    }

    /** The home status bar's dependency object, which holds the island and shade controllers. */
    private fun statusBarDependency(view: Any): Any? = runCatching {
        hierarchyField(view.javaClass, "mDependence")?.get(view)
            ?: hierarchyField(view.javaClass, "mDep")?.get(view)
    }.getOrNull()

    internal fun resolveIslandHandler(view: Any): Any? = runCatching {
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
    internal fun readShadeFullyOpen(view: Any?): Boolean = runCatching {
        val root = view as? View ?: return false
        val dependency = statusBarDependency(root) ?: return false
        val shadeController = hierarchyField(dependency.javaClass, "shadeController")
            ?.get(dependency) ?: return false
        val method = shadeController.javaClass.methods.firstOrNull {
            it.name == "isShadeFullyOpen" && it.parameterTypes.isEmpty()
        } ?: return false
        method.invoke(shadeController) as? Boolean == true
    }.getOrDefault(false)

    internal fun readIslandShowing(handler: Any?): Boolean = handler?.let { value ->
        runCatching { islandShowingField?.getBoolean(value) == true }.getOrDefault(false)
    } ?: false

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
     * this tick (LeftRenderer via the 1.5 s timer), with the shade closed.
     */
    internal var ownershipRefreshPending by generationState { false }
    internal var reconciling by generationState { false }
    private val reconciler = LeftReconciler()
    private fun reconcileTick() = reconciler.reconcileTick()
    internal fun requestReconcile() = reconciler.requestReconcile()

    /**
     * Forwards a host theme change to every living clone, then re-syncs payload and layout from
     * the right-cluster sources and drops sampled handover overlays. Main thread.
     */
    internal fun onThemeChanged(force: Boolean) {
        val clones = synchronized(states) { states.values.flatMap { it.clones.values } }
        val refreshed = clones.count { cloner.applyThemeChange(it, force) }
        handover.resampleMotions()
        reconcileAll()
        handover.applyHandoverGuarded(panelProgress)
        DebugLog.i(TAG, "left clones theme refresh force=$force refreshed=$refreshed/${clones.size}")
    }
    internal fun scheduleReconcile() = reconciler.scheduleReconcile()
    internal fun onDuoOwnershipChanged() = reconciler.onDuoOwnershipChanged()
    internal fun reconcileAll() = reconciler.reconcileAll()

    // ─── Capture / container ─────────────────────────────────────────────────────

    internal fun captureManager(root: ViewGroup, manager: Any, groupField: Field): LeftState? {
        val right = groupField.get(manager) as? ViewGroup ?: return null
        val res = root.resources
        fun id(name: String): Int = ResourceLookup.identifier(res, name, "id", "com.android.systemui")
        val statusIconsId = id("statusIcons")
        if (statusIconsId == 0 || right.id != statusIconsId) {
            // Not the home status-bar manager (e.g. secondary display variant).
            rejectCapture("icon group is ${viewName(right)}, not statusIcons")
            return null
        }
        val anchor = homeAnchor.resolveHomeAnchor(root, right) ?: return null

        // The pristine host block list is captured by `IconManagerHooker` from the static OS4 lists
        // it registers (`RIGHT_BLOCK_LIST` / `CONTROL_CENTER_BLOCK_LIST`); this hook no longer keeps
        // its own copy, which is what let the two disagree.

        // A re-capture for the same manager must not leave the previous container in the tree.
        states.remove(manager)?.let(presentation::teardownState)
        val state = LeftState(anchor.host, anchor.after, right, manager, barRoot = root)
        state.islandHandler = resolveIslandHandler(root)
        state.islandShowing = readIslandShowing(state.islandHandler)
        states[manager] = state
        DebugLog.i(TAG, "left container captured home root=${System.identityHashCode(root)} " +
            "row=${viewName(anchor.host)} ${anchor.description}")
        return state
    }

    private val homeAnchor = LeftHomeAnchor(::viewName, ::rejectCapture)

    /** Capture rejections are layout facts that repeat on every attach, so each is logged once. */
    private val loggedRejections = generationMainResource(HashSet<String>()) { it.clear() }

    private fun rejectCapture(reason: String) {
        if (loggedRejections.add(reason)) DebugLog.w(TAG, "left container disabled: $reason")
    }

    private fun viewName(view: View?): String {
        if (view == null) return "none"
        val id = if (view.id == View.NO_ID) "no-id" else
            runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("0x${Integer.toHexString(view.id)}")
        return "${view.javaClass.name}#$id"
    }

    /** Re-capture/reconcile the home manager after its view has re-entered the window. */
    internal fun rebindHomeState(root: ViewGroup, groupField: Field) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { if (!retiring) rebindHomeState(root, groupField) }
            return
        }
        val manager = hierarchyField(root.javaClass, "mDarkIconManager")?.get(root) ?: return
        synchronized(states) {
            val state = states[manager]
            val right = groupField.get(manager) as? ViewGroup
            val anchor = right?.let { homeAnchor.resolveHomeAnchor(root, it) }
            if (state == null || anchor == null || state.leftHost !== anchor.host || state.anchor !== anchor.after) {
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
        IconHostPorts.republish()
    }

    // ─── Left clones ─────────────────────────────────────────────────────────────

    internal fun syncClonesFor(state: LeftState?) {
        if (state == null) return
        requestReconcile()
    }

    /** Island events update policy inputs; they never independently reveal a container. */
    internal fun applyIslandVisibility(handler: Any?, islandShowing: Boolean) = runOnMain {
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
                    runCatching { HostAccess.requirePublicMethod(iface, "getSlot", accessible = false) }.getOrNull()
                } ?: runCatching { HostAccess.requirePublicMethod(current, "getSlot", accessible = false) }.getOrNull()
                if (found != null) slotGetter = found
                c = c.superclass
            }
        }
        val getter = slotGetter ?: return null
        return runCatching { getter.invoke(view) as? String }.getOrNull()
    }

    internal fun hierarchyField(clazz: Class<*>, name: String): Field? = HostAccess.field(clazz, name)

    /** Marker on our own left container so teardown never touches system views. */
}
