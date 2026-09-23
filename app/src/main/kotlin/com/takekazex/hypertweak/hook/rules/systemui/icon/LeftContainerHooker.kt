package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.content.Context
import android.app.KeyguardManager
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.isVisible
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap
import kotlin.math.abs

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

    // The per-toggle slot groups live in `IconTunerOptions.slotsForLeftPreference` only: an earlier
    // copy here was never read (the live path is `snapshot().policy.leftSlots`) and would have
    // drifted from the slots the hook actually moves.

    // ── Live snapshot (refreshed by the ticker; read by the per-event hooks) ──
    @Volatile
    private var active = false

    @Volatile
    private var activeSlots: Set<String> = emptySet()

    @Volatile
    private var leftMode = IconTunerOptions.LEFT_MODE_DISABLED

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
     * [IconTunerOptions.LEFT_MODE_HOME_AND_LOCKSCREEN] (its own left container exists), which is why
     * the per-location rule in [IconSlotPolicy.ownedSlotsFor] can hand the keyguard its own set
     * instead of the home bar's.
     */
    fun keyguardOwnedSlots(): Set<String> = synchronized(states) {
        states.values.firstOrNull { it.isKeyguard }?.migratedSlots ?: emptySet()
    }

    private var iconViewConstructor: java.lang.reflect.Constructor<*>? = null
    private var iconViewMIconField: Field? = null
    private var iconPayloadCloneMethod: Method? = null
    private var isIconVisibleMethod: Method? = null
    private var iconPayloadVisibleField: Field? = null
    private var iconViewSetMethod: Method? = null
    private var iconViewSetResolved = false
    private var darkDispatcherField: Field? = null
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
        /**
         * The status-bar view that owns this row's dependency object (`mDependence`), which is where
         * the island and shade controllers hang. Not [leftHost]: that is a plain container.
         */
        val barRoot: View? = null
    ) {
        var leftContainer: LinearLayout? = null
        /** slot -> clone view (our own, sized from the system view of the same slot). */
        val clones = HashMap<String, View>()
        /** Slots with a valid clone; only these may be hidden in the right cluster. */
        var migratedSlots: Set<String> = emptySet()
        /** Original keyguard clock/alarm placement, used to restore the host tree exactly. */
        val anchorEntries = ArrayList<AnchorEntry>()
        var anchorWrapper: LinearLayout? = null
        var islandShowing: Boolean = false
        var islandHandler: Any? = null
    }

    private data class AnchorEntry(
        val view: View,
        val parent: ViewGroup,
        val index: Int,
        val layoutParams: ViewGroup.LayoutParams?
    )

    private fun masterEnabled(): Boolean = leftMode != IconTunerOptions.LEFT_MODE_DISABLED

    private fun selectedSlots(): Set<String> = IconTunerOptions.snapshot().policy.leftSlots

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
    private class EndpointState(
        var savedAlpha: Float,
        var appliedAlpha: Float,
        var holdUntilReady: Boolean = false
    )

    /** One Duo-style overlay per moved slot; all operations are main-thread-only. */
    private val panelMotions = HashMap<String, LeftPanelMotion>()

    /** Duplicate fake/QS copies are alpha-owned only while the overlay is ready. */
    private val endpointStates = WeakHashMap<View, EndpointState>()

    private var statusIconsId = 0

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

    private fun <T> onMainBlocking(action: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return action()
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: Result<T>? = null
        mainHandler.post { try { result = runCatching(action) } finally { latch.countDown() } }
        check(latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "Left icon cleanup timed out" }
        return checkNotNull(result).getOrThrow()
    }

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
        DebugLog.i(TAG, "hot reload left roots=${states.size} rows=${ccRows.size}")
    }

    override fun onPrepareHotReload() {
        mainHandler.removeCallbacks(reconcileRunnable)
        onMainBlocking {
            restoreHandover()
            shadeSwitchSettledToControlCenter = false
            states.values.forEach(::teardownState)
            states.clear()
            ccRows.clear()
            resetReflectionCaches()
            publishOwnedChange()
        }
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

        // 5. Hand the moved icons over to the control center while the shade is dragged.
        hookControlCenterHandover()

        reloadSnapshot()
        mainHandler.removeCallbacks(reconcileRunnable)
        mainHandler.postDelayed(reconcileRunnable, RECONCILE_INTERVAL_MS)
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
                    applyHandoverGuarded(if (visible) panelProgress else 0f)
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
                    applyHandoverGuarded(if (panelVisible) panelProgress else 0f)
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
        mainHandler.post { applyHandoverGuarded(panelProgress) }
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
            parkHandover()
        } else if (panelProgress > 0f) {
            panelVisible = true
        }
        applyHandoverGuarded(panelProgress)
    }

    /**
     * Carries every selected icon through the same hand-over as Duo's middle Wi-Fi glyph.
     *
     * The host rows remain untouched geometrically. One overlay per slot draws the already tinted
     * left clone from its real screen position to the real expanded endpoint; duplicate fake-row
     * copies are alpha-suppressed only after the overlay has produced a frame. The last quarter fades
     * the overlay out while the endpoint fades in, exactly like `DuoPanelMotion`.
     */
    private fun applyHandover(progress: Float) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { applyHandoverGuarded(progress) }
            return
        }
        if (ControlCenterCarrierBlockHooker.isShadeSwitching()) {
            suspendHandoverForShadeSwitch()
            return
        }
        val clamped = progress.coerceIn(0f, 1f)
        if (shadeSwitchSettledToControlCenter && clamped >= 0.999f) {
            settleNativeControlCenterEndpoint()
            return
        }
        if (clamped <= 0f) {
            if (panelVisible) parkHandover() else restoreHandover()
            return
        }

        val state = synchronized(states) { states.values.firstOrNull { !it.isKeyguard } }
        val rows = liveControlCenterRows()
        if (!active || state == null || state.clones.isEmpty() || rows.isEmpty()) {
            restoreHandover()
            return
        }

        val usedSlots = HashSet<String>()
        val liveSecondaryEndpoints = HashSet<View>()
        state.clones.forEach { (slot, clone) ->
            val targets = rows.mapNotNull { statusRowIcon(it, slot) }
                .filter(::isUsableDestination)
                .distinct()
            // Prefer the real expanded control-center row. The fake/QS rows are only fallbacks for
            // layouts where that endpoint is not inflated yet.
            val target = targets.firstOrNull { it.parentRowClass() == CC_STATUS_ROW_CLASS }
                ?: targets.firstOrNull { it.parentRowClass() == CC_QS_HEADER_CLASS }
                ?: targets.firstOrNull()
            if (!clone.isAttachedToWindow || !clone.isShown || clone.width <= 0 || clone.height <= 0 ||
                target == null
            ) {
                if (clone.alpha != 1f) clone.alpha = 1f
                panelMotions.remove(slot)?.clear()
                return@forEach
            }

            // A view can have been parked while the panel was visible at progress=0. Carry its saved
            // host alpha into the motion, but keep its currently hidden value until the overlay draws.
            val parkedTargetAlpha = takeEndpointForMotion(target)
            val root = target.rootView as? ViewGroup
            if (root == null || !root.isAttachedToWindow) {
                if (clone.alpha != 1f) clone.alpha = 1f
                panelMotions.remove(slot)?.clear()
                return@forEach
            }

            val motion = panelMotions[slot] ?: LeftPanelMotion().also { panelMotions[slot] = it }
            val ready = motion.update(root, clone, target, clamped, parkedTargetAlpha)
            usedSlots += slot

            // Do not translate host children. Only duplicate copies are hidden after the overlay's
            // first successful draw; the selected target is owned by LeftPanelMotion.
            if (clamped < 1f) {
                targets.filterNot { it === target }.forEach { duplicate ->
                    liveSecondaryEndpoints += duplicate
                    suppressEndpoint(duplicate, ready)
                }
            }
            clone.alpha = LeftHandover.sourceAlpha(clamped, ready)
        }

        panelMotions.entries.removeIf { (slot, motion) ->
            if (slot in usedSlots) false else { motion.clear(); true }
        }
        releaseEndpointStates(liveSecondaryEndpoints)
        if (clamped >= 1f) releaseEndpointStates(emptySet())
    }

    /** The host callback runs inside a frame; never let a failure in here reach SystemUI. */
    private fun applyHandoverGuarded(progress: Float) {
        runCatching { applyHandover(progress) }
            .onFailure { DebugLog.w(TAG, "LeftContainer hand-over failed", it) }
    }

    /** Keep native QS/QS_FAKE copies as the only moving glyphs during a shade switch. */
    internal fun onShadeSwitchStarted(controlCenterVisible: Boolean) {
        if (!active) return
        runCatching {
            shadeSwitchSettledToControlCenter = false
            suspendHandoverForShadeSwitch()
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
        applyHandoverGuarded(panelProgress)
    }

    private fun suspendHandoverForShadeSwitch() {
        if (panelMotions.isNotEmpty()) {
            panelMotions.values.forEach(LeftPanelMotion::clear)
            panelMotions.clear()
        }
        releaseEndpointStates(emptySet())
    }

    private fun settleNativeControlCenterEndpoint() {
        suspendHandoverForShadeSwitch()
        val state = synchronized(states) { states.values.firstOrNull { !it.isKeyguard } } ?: return
        if (!active) return
        val rows = liveControlCenterRows()
        state.clones.forEach { (slot, clone) ->
            val ready = rows.any { row -> statusRowIcon(row, slot)?.let(::isUsableDestination) == true }
            val alpha = if (ready) 0f else 1f
            if (clone.alpha != alpha) clone.alpha = alpha
        }
    }

    /** Candidate endpoint must be attached, visible, and measured. */
    private fun isUsableDestination(view: View): Boolean =
        view.isAttachedToWindow && view.isVisible && view.width > 0 && view.height > 0

    /** The row class is resolved from the captured ancestor rather than from an icon name. */
    private fun View.parentRowClass(): String? =
        CC_ROW_CLASSES.firstOrNull { name -> ancestorOf(name) != null }

    private fun View.ancestorOf(name: String): View? {
        var node: View? = parent as? View
        while (node != null) {
            if (node.javaClass.name == name) return node
            node = node.parent as? View
        }
        return null
    }

    private fun suppressEndpoint(view: View, ready: Boolean, holdUntilReady: Boolean = false) {
        val entry = endpointStates[view] ?: EndpointState(view.alpha, view.alpha)
            .also { endpointStates[view] = it }
        if (holdUntilReady) entry.holdUntilReady = true
        if (abs(view.alpha - entry.appliedAlpha) > .001f) entry.savedAlpha = view.alpha
        val alpha = if (ready || entry.holdUntilReady) 0f else entry.savedAlpha
        if (view.alpha != alpha) view.alpha = alpha
        entry.appliedAlpha = alpha
    }

    /** Removes a parked duplicate without revealing it; [LeftPanelMotion] owns it next. */
    private fun takeEndpointForMotion(view: View): Float? {
        val entry = endpointStates.remove(view) ?: return null
        if (abs(view.alpha - entry.appliedAlpha) > .001f) entry.savedAlpha = view.alpha
        if (view.alpha != entry.appliedAlpha) view.alpha = entry.appliedAlpha
        return entry.savedAlpha
    }

    /** Restore only values still owned by us, matching DuoPanelMotion's fail-open cleanup. */
    private fun releaseEndpointStates(live: Set<View>) {
        if (endpointStates.isEmpty()) return
        val iterator = endpointStates.entries.iterator()
        while (iterator.hasNext()) {
            val (view, entry) = iterator.next()
            if (view in live) continue
            if (abs(view.alpha - entry.appliedAlpha) < .001f) view.alpha = entry.savedAlpha
            iterator.remove()
        }
    }

    /** Gives every moved slot back to the left container (closed shade, feature off, teardown). */
    private fun restoreHandover() {
        panelMotions.values.forEach(LeftPanelMotion::clear)
        panelMotions.clear()
        releaseEndpointStates(emptySet())
        synchronized(states) {
            states.values.forEach { state ->
                state.clones.values.forEach { clone -> if (clone.alpha != 1f) clone.alpha = 1f }
                state.leftContainer?.let { container -> if (container.alpha != 1f) container.alpha = 1f }
            }
        }
    }

    /**
     * Progress can reach zero before the user lifts the finger. Keep the left clone as the visible
     * owner and park every currently inflated control-center copy until the host reports dismissal.
     */
    private fun parkHandover() {
        panelMotions.values.forEach(LeftPanelMotion::clear)
        panelMotions.clear()
        releaseEndpointStates(emptySet())
        synchronized(states) {
            states.values.forEach { state ->
                state.clones.values.forEach { clone -> if (clone.alpha != 1f) clone.alpha = 1f }
                state.leftContainer?.let { container -> if (container.alpha != 1f) container.alpha = 1f }
            }
        }
        val state = synchronized(states) { states.values.firstOrNull { !it.isKeyguard } } ?: return
        if (!active || state.clones.isEmpty()) return
        liveControlCenterRows().forEach { row ->
            state.clones.keys.forEach { slot ->
                statusRowIcon(row, slot)?.takeIf(::isUsableDestination)?.let { duplicate ->
                    suppressEndpoint(duplicate, ready = false, holdUntilReady = true)
                }
            }
        }
    }

    private fun statusRowIcon(row: ViewGroup?, slot: String): View? {
        val icons = row?.let(::statusIconsContainer) ?: return null
        for (index in 0 until icons.childCount) {
            val child = icons.getChildAt(index)
            if (child != null && slotOf(child) == slot) return child
        }
        return null
    }

    /** `R.id.statusIcons` of a control-center row; resolved through the row's own resources. */
    private fun statusIconsContainer(row: ViewGroup): ViewGroup? {
        if (statusIconsId == 0) {
            statusIconsId = row.resources.getIdentifier("statusIcons", "id", "com.android.systemui")
        }
        val id = statusIconsId
        if (id == 0) return null
        return row.findViewById<View>(id) as? ViewGroup
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
            mainHandler.post { captureKeyguardState(controller) }
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
        if (anchors.any { it.parent !== leftFrame }) return

        synchronized(states) {
            val existing = states[manager]
            if (existing != null && existing.leftHost === leftFrame && existing.isKeyguard) return
            existing?.let {
                teardownState(it)
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
            teardownState(state)
            publishOwnedChange()
            // No clones left: hand the control-center copies back instead of leaving them parked.
            applyHandoverGuarded(panelProgress)
        }
    }

    private fun detachHomeState(viewObject: Any?) {
        val view = viewObject as? ViewGroup ?: return
        val manager = hierarchyField(view.javaClass, "mDarkIconManager")?.get(view) ?: return
        val state = synchronized(states) { states[manager] } ?: return
        runOnMain {
            teardownState(state)
            publishOwnedChange()
            applyHandoverGuarded(panelProgress)
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
        iconViewConstructor = null
        iconViewMIconField = null
        iconPayloadCloneMethod = null
        isIconVisibleMethod = null
        iconPayloadVisibleField = null
        iconViewSetMethod = null
        iconViewSetResolved = false
        darkDispatcherField = null
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
        reloadSnapshot()
        var needsOverlayRefresh = false
        synchronized(states) {
            states.values.forEach { state ->
                if (shouldRender(state)) {
                    syncClones(state)
                } else {
                    if (active && !state.isKeyguard && keyguardShowing()) {
                        state.leftContainer?.visibility = View.GONE
                    } else if (!active && state.leftHost.isShown) {
                        state.clones.values.forEach { animateCloneVisibility(it, false) }
                        if (state.clones.values.none { cloneFades[it]?.animator != null }) teardownState(state)
                    } else {
                        teardownState(state)
                    }
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
        applyHandoverGuarded(if (shadeFullyOpen) 1f else panelProgress)
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
            mainHandler.post { publishOwnedChange() }
            return
        }
        publishedOwnedSlots = homeOwnedSlots()
        publishedKeyguardOwnedSlots = keyguardOwnedSlots()
        IconManagerHooker.republishMergedLists()
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
            }
            // A new clone needs its control-center copy parked before the next drag frame.
            applyHandoverGuarded(panelProgress)
        }
            .onFailure { t -> DebugLog.w(TAG, "LeftContainer sync failed", t) }
    }

    /** Idempotent: sees the current right-cluster children and mirrors them into the left. */
    private fun syncClones(state: LeftState) {
        val right = state.rightContainer
        val slots = activeSlots
        // 1. Drop clones whose slot is no longer selected or has no live view on the right.
        val it = state.clones.entries.iterator()
        while (it.hasNext()) {
            val (slot, clone) = it.next()
            val child = rightChildForSlot(state, slot)
            if (slot !in slots || child == null) {
                animateCloneVisibility(clone, false)
                if (cloneFades[clone]?.animator != null) continue
                it.remove()
                runCatching { (clone.parent as? ViewGroup)?.removeView(clone) }
                cloneFades.remove(clone)?.animator?.cancel()
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
                cloneFades.remove(clone)?.animator?.cancel()
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
            val visibleField = iconPayloadVisibleField ?: hierarchyField(clonedIcon.javaClass, "visible")
                ?.also { iconPayloadVisibleField = it } ?: return@runCatching false
            visibleField.setBoolean(clonedIcon, true)
            setter.invoke(clone, clonedIcon)
            true
        }.getOrDefault(false)
        if (!payloadSet) return false
        // Visibility mirrors the system (icon logically active).
        val visible = isIconVisibleMethod?.let { m ->
            runCatching { m.invoke(child) as? Boolean }.getOrNull()
        } ?: false
        animateCloneVisibility(clone, visible)
        return true
    }

    private class CloneFade(var visible: Boolean? = null, var animator: android.animation.ValueAnimator? = null)
    private val cloneFades = WeakHashMap<View, CloneFade>()

    private fun animateCloneVisibility(clone: View, visible: Boolean) {
        val image = clone as? android.widget.ImageView ?: run {
            clone.visibility = if (visible) View.VISIBLE else View.GONE
            return
        }
        val fade = cloneFades.getOrPut(clone) { CloneFade() }
        if (fade.visible == visible) {
            if (!visible && fade.animator == null) clone.visibility = View.GONE
            return
        }
        val initial = fade.visible == null
        fade.animator?.cancel()
        fade.animator = null
        fade.visible = visible
        if (initial) image.imageAlpha = 0
        clone.visibility = View.VISIBLE
        val target = if (visible) 255 else 0
        if (!android.animation.ValueAnimator.areAnimatorsEnabled() || (!visible && initial)) {
            image.imageAlpha = target
            clone.visibility = if (visible) View.VISIBLE else View.GONE
            return
        }
        fade.animator = android.animation.ValueAnimator.ofInt(image.imageAlpha, target).apply {
            duration = 200L
            addUpdateListener { image.imageAlpha = it.animatedValue as Int }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (fade.animator !== animation) return
                    fade.animator = null
                    if (fade.visible == false) clone.visibility = View.GONE
                }
            })
            start()
        }
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
            cloneFades.remove(clone)?.animator?.cancel()
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
