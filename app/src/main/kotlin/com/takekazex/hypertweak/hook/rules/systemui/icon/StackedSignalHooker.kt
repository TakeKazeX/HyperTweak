@file:Suppress("StaticFieldLeak")

package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Publishes the custom cellular signal as a normal SystemUI icon-controller slot.
 *
 * The old implementation intercepted every ImageView in SystemUI, created a second ImageView,
 * and fed fake resource ids back through the framework. OS4 already exposes the complete source
 * state through `MobileIconsViewModel.createViewModel(int)`, so this hook now stays at the model
 * boundary: it collects the host flows, renders one module-owned bitmap, publishes it through
 * [HostIconBridge], and only then masks the native per-subscription visibility pair.
 */
@SuppressLint("StaticFieldLeak")
object StackedSignalHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "IconTuner"
    private const val ADAPTER_CLASS =
        "com.android.systemui.statusbar.pipeline.mobile.ui.MobileUiAdapter"
    private const val SYSUI_COMPONENT_FIELD = "mSysUIComponent"
    private const val STARTABLES_METHOD = "getStartables"
    private const val PER_USER_STARTABLES_METHOD = "getPerUserStartables"
    private const val ICONS_VM_CLASS =
        "com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MobileIconsViewModel"
    private const val SLOT_STACKED = "stacked_mobile_icon"
    private const val SLOT_STACKED_TYPE = "stacked_mobile_type"
    private const val SLOT_SINGLE_SIM1 = "single_mobile_sim1"
    private const val SLOT_SINGLE_SIM2 = "single_mobile_sim2"

    /** Bounded backoff before re-reading signal artwork that failed to load. */
    private const val ASSET_LOAD_RETRY_BACKOFF_MS = 5_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    /** SVG parsing and remote-file reads must not delay SystemUI application startup. */
    private val assetExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "HyperTweak-StackedSignalAssets").apply { isDaemon = true }
    }
    private val assetLoadLock = Any()
    private val generation = AtomicLong(0L)
    private val installed = AtomicBoolean(false)
    private val adapterFlowInstalled = AtomicBoolean(false)
    private val flowHandles = CopyOnWriteArrayList<HostFlowCollector.Handle>()
    private val bindings = LinkedHashMap<Int, SubBinding>()
    private val pendingBindings = IdentityHashMap<Any, Boolean>()
    private val pendingEvents = LinkedHashMap<Int, PendingSubEvents>()

    @Volatile
    private var enabled = false

    @Volatile
    private var scale = 1f

    @Volatile
    private var singleSvgStyle = 0

    @Volatile
    private var stackedSvgStyle = 0

    @Volatile
    private var signalAlphaFg = 1f

    @Volatile
    private var signalAlphaBg = 0.4f

    @Volatile
    private var signalAlphaError = 0.2f

    @Volatile
    private var signalPaddingStart = 0f

    @Volatile
    private var signalPaddingEnd = 0f

    @Volatile
    private var adapterFlowsReady = false

    private var factoryViewModelIds: Set<Int> = emptySet()

    @Volatile
    private var hostContext: Context? = null

    @Volatile
    private var svgRepository: IconSvgRepository? = null

    @Volatile
    private var signalAssets: SignalAssets? = null

    /** Generation whose assets are already resolved (and therefore never re-read). */
    @Volatile
    private var assetLoadGeneration = Long.MIN_VALUE

    /** Generation with an asset read currently in flight; prevents duplicate concurrent reads. */
    @Volatile
    private var assetLoadInFlightGeneration = Long.MIN_VALUE

    /**
     * Uptime before which a failed asset read must not be retried.
     *
     * Without it, a failed read would either retry on every state-flow event (repeated I/O) or —
     * as the previous implementation did — never retry at all for the rest of the binding
     * generation, leaving the custom signal icons missing until a hot reload or process restart.
     */
    @Volatile
    private var assetLoadRetryAfterMs = 0L

    @Volatile
    private var iconBridge: HostIconBridge? = null

    /** MobileUiAdapter.start() runs once per SystemUI lifetime; carry the adapter across reloads. */
    @Volatile
    private var adapterReference: WeakReference<Any>? = null

    /** MobileIconsViewModel.reuseCache retains the full triples behind the public VM list Flow. */
    @Volatile
    private var mobileIconsViewModelReference: WeakReference<Any>? = null

    /** Bounded retries cover the short gap between package-ready and Dagger component startup. */
    private var adapterDiscoveryGeneration = 0L
    private var adapterDiscoveryAttempt = 0

    @Volatile
    private var options = IconTunerOptions.snapshot()

    @Volatile
    private var typeConfig = MobileTypeConfig()

    /** All mutable reducer state is consumed on the main looper. */
    private var signalState = MobileSignalState()

    private data class SignalAssets(
        val single: IconSvgSnapshot,
        val stacked: IconSvgSnapshot
    )

    private data class SubBinding(
        val subId: Int,
        val miuiViewModel: Any,
        val visibility: MobileSignalVisibility.Registration,
        val handles: List<HostFlowCollector.Handle>,
        val bindingGeneration: Long
    )

    /** Flow values can arrive before the subscription-id StateFlow's first emission. */
    private data class PendingSubEvents(
        var signal: MobileSignalEvent.SignalModel? = null,
        var dataConnected: MobileSignalEvent.DataConnected? = null,
        var inService: MobileSignalEvent.InService? = null,
        var roaming: MobileSignalEvent.Roaming? = null,
        var nonTerrestrial: MobileSignalEvent.NonTerrestrial? = null,
        var networkType: MobileSignalEvent.NetworkType? = null,
        var wifiAvailable: MobileSignalEvent.WifiAvailable? = null,
        var visibility: MobileSignalEvent.OriginalVisibility? = null
    ) {
        fun put(event: MobileSignalEvent) {
            when (event) {
                is MobileSignalEvent.SignalModel -> signal = event
                is MobileSignalEvent.DataConnected -> dataConnected = event
                is MobileSignalEvent.InService -> inService = event
                is MobileSignalEvent.Roaming -> roaming = event
                is MobileSignalEvent.NonTerrestrial -> nonTerrestrial = event
                is MobileSignalEvent.NetworkType -> networkType = event
                is MobileSignalEvent.WifiAvailable -> wifiAvailable = event
                is MobileSignalEvent.OriginalVisibility -> visibility = event
                else -> Unit
            }
        }

        fun ordered(): List<MobileSignalEvent> = listOfNotNull(
            signal,
            dataConnected,
            inService,
            roaming,
            nonTerrestrial,
            networkType,
            wifiAvailable,
            visibility
        )
    }

    /** Called by HookEntry once SystemUI's application context is available. */
    fun onPackageReady(context: Context) {
        hostContext = context
        val moduleContext = runCatching {
            context.createPackageContext(
                HostIconBridge.MODULE_PACKAGE,
                Context.CONTEXT_IGNORE_SECURITY
            )
        }.getOrNull()
        svgRepository = moduleContext?.let(::IconSvgRepository)
        if (enabled) {
            scheduleSignalAssetsLoad(generation.get())
            scheduleExistingAdapterDiscovery()
        }
    }

    override fun onPrepareHotReload() {
        val retiredGeneration = generation.incrementAndGet()
        val retiringBridge = iconBridge
        enabled = false
        adapterFlowsReady = false

        // A replacement generation may be prepared from a binder thread. StateFlow updates are
        // thread-safe; pass every exposed Pair through its original value before unregistering the
        // getter entries. Queued old-generation callbacks are rejected by the generation check.
        MobileSignalVisibility.clearForHotReload()
        removeRetiringBridge(retiredGeneration, retiringBridge)
        flowHandles.forEach { it.cancel() }
        flowHandles.clear()
        bindings.values.toList().forEach { binding ->
            binding.handles.forEach { it.cancel() }
            MobileSignalVisibility.unregister(binding.miuiViewModel)
        }
        bindings.clear()
        pendingBindings.clear()
        pendingEvents.clear()
        factoryViewModelIds = emptySet()
        HostFlowCollector.resetForReload()
        signalState = MobileSignalState()
        signalAssets = null
        synchronized(assetLoadLock) {
            assetLoadGeneration = Long.MIN_VALUE
            assetLoadInFlightGeneration = Long.MIN_VALUE
            assetLoadRetryAfterMs = 0L
        }
        MobileTypeRenderer.clearCache()
        iconBridge = null
        adapterReference = null
        mobileIconsViewModelReference = null
        adapterDiscoveryGeneration = generation.get()
        adapterDiscoveryAttempt = 0
        adapterFlowInstalled.set(false)
        installed.set(false)
    }

    override fun saveHotReloadState(): Any? = adapterReference?.get()

    override fun restoreHotReloadState(state: Any?) {
        val adapter = state ?: return
        adapterReference = WeakReference(adapter)
        scheduleAdapterRestore(adapter, generation.get())
    }

    override fun onHook() {
        IconTunerFlows.init(classLoader)
        options = IconTunerOptions.snapshot()
        enabled = Preferences.getBoolean(Preferences.KEY_ICON_STACKED_ENABLED, false)
        scale = Preferences.getFloat(Preferences.KEY_ICON_STACKED_SCALE, 1f)
            .takeIf { it.isFinite() }
            ?.coerceIn(0.5f, 1.5f)
            ?: 1f
        readSignalSvgConfig()
        typeConfig = readTypeConfig()
        if (!enabled) {
            DebugLog.hookSkipped(TAG, "StackedSignal", "disabled")
            return
        }
        if (!installed.compareAndSet(false, true)) return
        // Invalidate any cleanup runnable posted by the retiring generation before it can touch a
        // holder published by this generation.
        generation.incrementAndGet()
        if (!MobileSignalVisibility.installGetter(this)) {
            DebugLog.hookSkipped(TAG, "MiuiMobileIconVMImpl#isVisible", "getter bridge unavailable")
            return
        }
        hookCreateViewModel()
        hookAdapterStart()
        adapterReference?.get()?.let { scheduleAdapterRestore(it, generation.get()) }
        scheduleExistingAdapterDiscovery()
        hostContext?.let { scheduleSignalAssetsLoad(generation.get()) }
        DebugLog.hookRegistered(TAG, "model-driven stacked signal slot (scale=$scale)")
    }

    private fun hookCreateViewModel() {
        val vmClass = ICONS_VM_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, ICONS_VM_CLASS, "class not found")
            return
        }
        val method = vmClass.findMethodOrNull {
            name("createViewModel")
            paramCount(1)
        } ?: run {
            DebugLog.hookSkipped(TAG, "$ICONS_VM_CLASS#createViewModel", "method not found")
            return
        }
        method.hook {
            after { param ->
                val subId = (param.args.getOrNull(0) as? Number)?.toInt() ?: return@after
                val result = param.result ?: return@after
                val bindingGeneration = generation.get()
                // Factory work is launched from the host pipeline scope. Registration and all
                // reducer updates are serialized with icon-controller operations on main.
                mainHandler.post {
                    if (!enabled || generation.get() != bindingGeneration) return@post
                    registerSubscription(subId, result, bindingGeneration)
                }
            }
        }
    }

    private fun hookAdapterStart() {
        val adapterClass = ADAPTER_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, ADAPTER_CLASS, "class not found")
            return
        }
        val method = adapterClass.findMethodOrNull { name("start"); noParams() } ?: run {
            DebugLog.hookSkipped(TAG, "$ADAPTER_CLASS#start", "method not found")
            return
        }
        method.hook {
            after { param ->
                runCatching { setupAdapter(param.thisObject) }
                    .onFailure { DebugLog.w(TAG, "stacked adapter setup failed", it) }
            }
        }
    }

    private fun setupAdapter(adapter: Any) {
        adapterReference = WeakReference(adapter)
        if (!adapterFlowInstalled.compareAndSet(false, true)) return
        val bindingGeneration = generation.get()
        val scope = readField(adapter, "scope")
        val controller = readField(adapter, "iconController")
        val iconsLazy = readField(adapter, "mobileIconsViewModel")
        val iconsVm = iconsLazy?.let(::unwrapLazy) ?: iconsLazy
        if (scope == null || controller == null || iconsVm == null) {
            adapterFlowInstalled.set(false)
            DebugLog.hookSkipped(TAG, "MobileUiAdapter fields", "scope/controller/mobileIconsViewModel missing")
            return
        }
        mobileIconsViewModelReference = WeakReference(iconsVm)
        val activeDataFlow = readField(iconsVm, "activeMobileDataSubscriptionId")
        val subscriptionIdsFlow = readField(iconsVm, "subscriptionIdsFlow")
        val mobileSubViewModelsFlow = readField(iconsVm, "mobileSubViewModels")
        val airplaneInteractor = readField(iconsVm, "airplaneModeInteractor")
        val airplaneFlow = airplaneInteractor?.let { readField(it, "isAirplaneMode") }
        if (activeDataFlow == null || subscriptionIdsFlow == null ||
            mobileSubViewModelsFlow == null || airplaneFlow == null
        ) {
            adapterFlowInstalled.set(false)
            DebugLog.hookSkipped(TAG, "MobileIconsViewModel flows", "required source flow missing")
            return
        }

        hostContext = hostContext ?: (readField(controller, "mContext") as? Context)
        if (svgRepository == null) {
            val context = hostContext
            val moduleContext = context?.let {
                runCatching {
                    it.createPackageContext(
                        HostIconBridge.MODULE_PACKAGE,
                        Context.CONTEXT_IGNORE_SECURITY
                    )
                }.getOrNull()
            }
            svgRepository = moduleContext?.let(::IconSvgRepository)
        }
        iconBridge = HostIconBridge(controller, classLoader)
        scheduleSignalAssetsLoad(bindingGeneration)

        val handles = ArrayList<HostFlowCollector.Handle>(4)
        fun required(flow: Any, consumer: (Any?) -> Unit): Boolean {
            val handle = HostFlowCollector.collect(
                scope = scope,
                flow = flow,
                isCurrent = { enabled && generation.get() == bindingGeneration },
                consumer = consumer
            ) ?: return false
            handles += handle
            flowHandles += handle
            IconTunerFlows.readFlowValue(flow)?.let { initial ->
                mainHandler.post {
                    if (enabled && generation.get() == bindingGeneration) consumer(initial)
                }
            }
            return true
        }

        if (!required(activeDataFlow) { value ->
                val subId = (value as? Number)?.toInt() ?: MobileSignalState.INVALID_SUB_ID
                reduceOnMain(MobileSignalEvent.ActiveDataSubId(subId), bindingGeneration)
            } || !required(subscriptionIdsFlow) { value ->
                reduceSubscriptionsOnMain(extractSubscriptionIds(value), bindingGeneration)
            } || !required(mobileSubViewModelsFlow) { value ->
                // The public flow exposes AOSP VM entries; restoreFactoryViewModelsOnMain joins
                // them with MobileIconsViewModel.reuseCache to recover the full MIUI triples.
                restoreFactoryViewModelsOnMain(value, bindingGeneration)
            } || !required(airplaneFlow) { value ->
                reduceOnMain(MobileSignalEvent.AirplaneMode(value as? Boolean == true), bindingGeneration)
            }
        ) {
            handles.forEach { it.cancel() }
            flowHandles.removeAll(handles.toSet())
            adapterFlowInstalled.set(false)
            DebugLog.w(TAG, "stacked signal not ready: one adapter flow did not bind")
            return
        }
        adapterFlowsReady = true
        DebugLog.i(TAG, "stacked signal adapter flows bound")
        scheduleRender(bindingGeneration)
    }

    /** Replays setup for an adapter whose start() callback ran before module hot reload. */
    private fun scheduleAdapterRestore(adapter: Any, bindingGeneration: Long) {
        mainHandler.post {
            if (!enabled || generation.get() != bindingGeneration || adapterFlowInstalled.get()) return@post
            runCatching { setupAdapter(adapter) }
                .onFailure { DebugLog.w(TAG, "restored stacked adapter setup failed", it) }
        }
    }

    /**
     * Recovers the already-started MobileUiAdapter when the replacement generation has no saved
     * adapter reference. Hot reload normally carries the host object across generations, but the
     * first upgrade from an older module build cannot do that. SystemUI's Dagger component keeps
     * the same singleton in its startable-provider map, so resolving only this verified entry is
     * enough to reconnect without constructing or restarting any host component.
     */
    private fun scheduleExistingAdapterDiscovery() {
        val bindingGeneration = generation.get()
        adapterDiscoveryGeneration = bindingGeneration
        adapterDiscoveryAttempt = 0
        mainHandler.post { discoverExistingAdapterOnMain(bindingGeneration) }
    }

    private fun discoverExistingAdapterOnMain(bindingGeneration: Long) {
        if (!enabled || generation.get() != bindingGeneration || adapterFlowInstalled.get()) return
        // Give SystemUI's already-started service array a chance to become visible first. Calling
        // the Dagger Provider too early can eagerly initialize unrelated startables during boot.
        val adapter = findExistingAdapter(allowProvider = adapterDiscoveryAttempt >= 3)
        if (adapter != null) {
            DebugLog.i(TAG, "recovered existing MobileUiAdapter from SysUI component")
            runCatching { setupAdapter(adapter) }
                .onFailure { DebugLog.w(TAG, "existing stacked adapter setup failed", it) }
            return
        }

        if (adapterDiscoveryGeneration != bindingGeneration) {
            adapterDiscoveryGeneration = bindingGeneration
            adapterDiscoveryAttempt = 0
        }
        adapterDiscoveryAttempt++
        if (adapterDiscoveryAttempt >= 30) {
            DebugLog.w(TAG, "existing MobileUiAdapter discovery timed out")
            return
        }
        mainHandler.postDelayed(
            { discoverExistingAdapterOnMain(bindingGeneration) },
            500L
        )
    }

    private fun findExistingAdapter(allowProvider: Boolean): Any? {
        val context = hostContext?.applicationContext ?: hostContext ?: return null
        val services = readField(context, "mServices")?.let { invokeNoArg(it, "get") }
        findAdapterInCollection(services)?.let { return it }

        if (!allowProvider) return null

        val component = readField(context, SYSUI_COMPONENT_FIELD)
            ?: readField(context, "mInitializer")?.let { invokeNoArg(it, "getSysUIComponent") }
            ?: return null
        val maps = listOf(STARTABLES_METHOD, PER_USER_STARTABLES_METHOD)
        maps.forEach { methodName ->
            val startables = invokeNoArg(component, methodName) as? Map<*, *> ?: return@forEach
            val provider = startables.entries.firstOrNull { (key, _) ->
                (key as? Class<*>)?.name == ADAPTER_CLASS
            }?.value ?: return@forEach
            val adapter = invokeNoArg(provider, "get")
            if (adapter != null && adapter.javaClass.name == ADAPTER_CLASS) return adapter
        }
        return null
    }

    private fun findAdapterInCollection(value: Any?): Any? {
        val entries: Iterable<*> = when (value) {
            is Iterable<*> -> value
            is Array<*> -> value.asIterable()
            else -> return null
        }
        return entries.firstOrNull { it?.javaClass?.name == ADAPTER_CLASS }
    }

    /**
     * Holder teardown must run on SystemUI's main thread. The generation check and the bridge's
     * exact-icon ownership check make a late cleanup harmless after a replacement generation has
     * already published a new holder.
     */
    private fun removeRetiringBridge(retiredGeneration: Long, bridge: HostIconBridge?) {
        if (bridge == null) return
        val cleanup = Runnable {
            if (generation.get() == retiredGeneration) bridge.removeAllOwned()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cleanup.run()
            return
        }
        val completed = CountDownLatch(1)
        mainHandler.post {
            try {
                cleanup.run()
            } finally {
                completed.countDown()
            }
        }
        runCatching { completed.await(1L, TimeUnit.SECONDS) }
    }

    private fun registerSubscription(subId: Int, triple: Any, bindingGeneration: Long) {
        val aospViewModel = triplePart(triple, "getFirst")
        val scope = triplePart(triple, "getSecond")
        val miuiViewModel = triplePart(triple, "getThird")
        if (aospViewModel == null || scope == null || miuiViewModel == null) {
            DebugLog.w(TAG, "createViewModel returned incomplete Triple subId=$subId")
            return
        }
        val originInteractor = invokeNoArg(miuiViewModel, "getOriginIconInteractor")
        val originalVisibleFlow = invokeNoArg(miuiViewModel, "isVisible")
        val signalFlow = originInteractor?.let { invokeNoArg(it, "getSignalLevelIcon") }
        val dataConnectedFlow = originInteractor?.let { invokeNoArg(it, "isDataConnected") }
        val inServiceFlow = originInteractor?.let { invokeNoArg(it, "isInService") }
        val roamingFlow = originInteractor?.let { invokeNoArg(it, "isRoaming") }
        val nonTerrestrialFlow = originInteractor?.let { invokeNoArg(it, "isNonTerrestrial") }
        val networkTypeFlow = invokeNoArg(miuiViewModel, "getShowName")
        val miuiInteractor = readField(miuiViewModel, "iconInteractor")
        val wifiAvailableFlow = miuiInteractor?.let { readField(it, "wifiAvailable") }
        if (originInteractor == null || originalVisibleFlow == null || signalFlow == null ||
            dataConnectedFlow == null || inServiceFlow == null || roamingFlow == null ||
            nonTerrestrialFlow == null
        ) {
            DebugLog.w(TAG, "createViewModel flow set incomplete subId=$subId")
            return
        }

        bindings.remove(subId)?.let { old ->
            old.handles.forEach { it.cancel() }
            MobileSignalVisibility.unregister(old.miuiViewModel)
        }
        val visibility = MobileSignalVisibility.register(miuiViewModel, subId, originalVisibleFlow)
            ?: return
        pendingBindings[miuiViewModel] = true
        val handles = ArrayList<HostFlowCollector.Handle>(6)
        val isCurrent = {
            enabled && generation.get() == bindingGeneration &&
                (bindings[subId]?.miuiViewModel === miuiViewModel || pendingBindings.containsKey(miuiViewModel))
        }

        fun required(flow: Any, consumer: (Any?) -> Unit): Boolean {
            val handle = HostFlowCollector.collect(
                scope = scope,
                flow = flow,
                isCurrent = isCurrent,
                consumer = consumer
            ) ?: return false
            handles += handle
            flowHandles += handle
            IconTunerFlows.readFlowValue(flow)?.let { initial ->
                mainHandler.post {
                    if (isCurrent()) consumer(initial)
                }
            }
            return true
        }

        fun optional(flow: Any?, consumer: (Any?) -> Unit) {
            if (flow == null) return
            val handle = HostFlowCollector.collect(
                scope = scope,
                flow = flow,
                isCurrent = isCurrent,
                consumer = consumer
            )
            if (handle == null) {
                DebugLog.w(TAG, "optional type flow not collectable subId=$subId")
                return
            }
            handles += handle
            flowHandles += handle
            IconTunerFlows.readFlowValue(flow)?.let { initial ->
                mainHandler.post {
                    if (isCurrent()) consumer(initial)
                }
            }
        }

        // Register the host visibility flow with the same cancellation/generation gate as the
        // signal flows. It remains a Pair so the binder's detail chain keeps its second value.
        val allBound = required(originalVisibleFlow) { value ->
            val pair = MobileSignalVisibility.pairValueOf(value) ?: return@required
            reduceOnMain(
                MobileSignalEvent.OriginalVisibility(subId, pair.first, pair.second),
                bindingGeneration
            )
        } && required(signalFlow) { value ->
            reduceOnMain(
                MobileSignalEvent.SignalModel(subId, parseSignalModel(value)),
                bindingGeneration
            )
        } && required(dataConnectedFlow) { value ->
            reduceOnMain(MobileSignalEvent.DataConnected(subId, value as? Boolean == true), bindingGeneration)
        } && required(inServiceFlow) { value ->
            reduceOnMain(MobileSignalEvent.InService(subId, value as? Boolean == true), bindingGeneration)
        } && required(roamingFlow) { value ->
            reduceOnMain(MobileSignalEvent.Roaming(subId, value as? Boolean == true), bindingGeneration)
        } && required(nonTerrestrialFlow) { value ->
            reduceOnMain(MobileSignalEvent.NonTerrestrial(subId, value as? Boolean == true), bindingGeneration)
        }
        if (!allBound) {
            handles.forEach { it.cancel() }
            flowHandles.removeAll(handles.toSet())
            MobileSignalVisibility.unregister(miuiViewModel)
            pendingBindings.remove(miuiViewModel)
            DebugLog.w(TAG, "subscription flow set not ready subId=$subId")
            return
        }
        optional(networkTypeFlow) { value ->
            reduceOnMain(MobileSignalEvent.NetworkType(subId, value?.toString()), bindingGeneration)
        }
        optional(wifiAvailableFlow) { value ->
            reduceOnMain(MobileSignalEvent.WifiAvailable(subId, value as? Boolean == true), bindingGeneration)
        }
        bindings[subId] = SubBinding(
            subId = subId,
            miuiViewModel = miuiViewModel,
            visibility = visibility,
            handles = handles.toList(),
            bindingGeneration = bindingGeneration
        )
        pendingBindings.remove(miuiViewModel)
        scheduleRender(bindingGeneration)
    }

    private fun reduceSubscriptionsOnMain(ids: List<Int>, bindingGeneration: Long) {
        if (generation.get() != bindingGeneration || !enabled) return
        val current = ids.toSet()
        bindings.values.filter { it.subId !in current }.toList().forEach { binding ->
            bindings.remove(binding.subId)
            binding.handles.forEach { it.cancel() }
            flowHandles.removeAll(binding.handles.toSet())
            MobileSignalVisibility.unregister(binding.miuiViewModel)
        }
        signalState = signalState.reduce(MobileSignalEvent.Subscriptions(ids))
        pendingEvents.keys.toList().filter { it !in current }.forEach(pendingEvents::remove)
        ids.forEach { id ->
            val pending = pendingEvents.remove(id) ?: return@forEach
            pending.ordered().forEach { signalState = signalState.reduce(it) }
        }
        renderCurrent()
    }

    /** Re-registers existing `(AOSP VM, scope, MIUI VM)` triples after hot reload. */
    private fun restoreFactoryViewModelsOnMain(value: Any?, bindingGeneration: Long) {
        if (generation.get() != bindingGeneration || !enabled) return
        val entries: Iterable<*> = when (value) {
            is Map<*, *> -> value.values
            is Iterable<*> -> value
            else -> emptyList<Any?>()
        }
        val cachedTriples = LinkedHashMap<Int, Any>()
        (mobileIconsViewModelReference?.get()?.let { readField(it, "reuseCache") } as? Map<*, *>)
            ?.forEach { (key, cached) ->
                val triple = cached ?: return@forEach
                val subId = (key as? Number)?.toInt() ?: subscriptionIdOfTriple(triple)
                if (subId != null && triplePart(triple, "getFirst") != null &&
                    triplePart(triple, "getThird") != null
                ) {
                    cachedTriples[subId] = triple
                }
            }
        entries.forEach { entry ->
            val item = entry ?: return@forEach
            val directTriple = item.takeIf {
                triplePart(it, "getFirst") != null && triplePart(it, "getThird") != null
            }
            val subId = directTriple?.let(::subscriptionIdOfTriple) ?: subscriptionIdOf(item)
                ?: return@forEach
            val triple = directTriple ?: cachedTriples[subId] ?: return@forEach
            val miuiViewModel = triplePart(triple, "getThird")
            if (miuiViewModel != null && bindings[subId]?.miuiViewModel === miuiViewModel) {
                return@forEach
            }
            registerSubscription(subId, triple, bindingGeneration)
        }
        cachedTriples.forEach { (subId, triple) ->
            if (bindings[subId]?.miuiViewModel !== triplePart(triple, "getThird")) {
                registerSubscription(subId, triple, bindingGeneration)
            }
        }
        factoryViewModelIds = (extractSubscriptionIds(value) + cachedTriples.keys).toSet()
        renderCurrent()
    }

    private fun reduceOnMain(event: MobileSignalEvent, bindingGeneration: Long) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { reduceOnMain(event, bindingGeneration) }
            return
        }
        if (!enabled || generation.get() != bindingGeneration) return
        val subId = when (event) {
            is MobileSignalEvent.SignalModel -> event.subId
            is MobileSignalEvent.DataConnected -> event.subId
            is MobileSignalEvent.InService -> event.subId
            is MobileSignalEvent.Roaming -> event.subId
            is MobileSignalEvent.NonTerrestrial -> event.subId
            is MobileSignalEvent.NetworkType -> event.subId
            is MobileSignalEvent.WifiAvailable -> event.subId
            is MobileSignalEvent.OriginalVisibility -> event.subId
            else -> null
        }
        if (subId != null && subId !in signalState.subscriptions) {
            pendingEvents.getOrPut(subId) { PendingSubEvents() }.put(event)
            return
        }
        signalState = signalState.reduce(event)
        renderCurrent()
    }

    private fun scheduleRender(bindingGeneration: Long) {
        mainHandler.post {
            if (enabled && generation.get() == bindingGeneration) renderCurrent()
        }
    }

    /** Renders only after the adapter and every current subscription have complete flow sets. */
    private fun renderCurrent() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::renderCurrent)
            return
        }
        val state = signalState
        val complete = adapterFlowsReady && state.subscriptionOrder.isNotEmpty() &&
            state.subscriptionOrder.size <= MobileSignalState.MAX_RENDER_ROWS &&
            state.subscriptionOrder.all { it in factoryViewModelIds } &&
            state.subscriptionOrder.all { bindings[it]?.bindingGeneration == generation.get() }
        if (!complete) {
            // The mobile VM briefly emits an incomplete factory/cache state while its two
            // subscription rows are being rebound. Once a module holder is already published,
            // removing it here leaves the native `stacked_mobile` bindable (which is false on
            // this ROM when isStackable=false) as the only path, so the signal disappears
            // permanently until the next full SystemUI restart. Keep the last verified bitmap
            // and its native mask through that transient gap; the next complete emission updates
            // it in place.
            if (!hasPublishedReplacement()) restoreNative()
            return
        }

        // An explicit hide for the custom stack is a user policy, so it is allowed to keep the
        // native rows masked. No automatic path takes this branch without a published bitmap.
        if (!customStackVisible()) {
            iconBridge?.removeOwned(SLOT_STACKED)
            iconBridge?.removeOwned(SLOT_STACKED_TYPE)
            MobileSignalVisibility.setHiddenForSubIds(state.subscriptionOrder.toSet())
            return
        }
        if (!state.canRenderReplacement(options.ignoreSystemHide)) {
            // Visibility briefly goes false while the status-bar host changes between lockscreen
            // and home. Keep an already verified module holder as an invisible, unmasked fallback
            // instead of deleting it; the next original-visibility=true emission can then publish
            // it again without waiting for a new subscription or adapter lifecycle.
            if (!state.airplaneMode && state.rows.size == state.subscriptionOrder.size &&
                state.rows.isNotEmpty() && state.rows.all { it.supportsReplacement } &&
                hasPublishedReplacement()
            ) {
                iconBridge?.setVisible(SLOT_STACKED, false)
                iconBridge?.removeOwned(SLOT_STACKED_TYPE)
                MobileSignalVisibility.setHiddenForSubIds(emptySet())
                return
            }
            restoreNative()
            return
        }

        val assets = signalAssets ?: run {
            scheduleSignalAssetsLoad(generation.get())
            if (!hasPublishedReplacement()) restoreNative()
            return
        }
        val bitmap = runCatching {
            val config = renderConfig()
            val typeOutput = MobileTypePolicy.resolve(state, typeConfig)
            val badge = renderInternalTypeBadge(typeOutput, state, config)
            if (state.rows.size == 1) {
                if (badge == null) {
                    IconSvgRenderer.renderSingle(assets.single.document, state.rows[0].renderLevel, config)
                } else {
                    IconSvgRenderer.renderSingleWithBadge(
                        assets.single.document,
                        state.rows[0].renderLevel,
                        config,
                        badge
                    )
                }
            } else {
                if (badge == null) {
                    IconSvgRenderer.renderStacked(
                        assets.stacked.document,
                        state.rows[0].renderLevel,
                        state.rows[1].renderLevel,
                        config
                    )
                } else {
                    IconSvgRenderer.renderStackedWithBadge(
                        assets.stacked.document,
                        state.rows[0].renderLevel,
                        state.rows[1].renderLevel,
                        config,
                        badge
                    )
                }
            }
        }.onFailure { DebugLog.w(TAG, "cellular signal SVG render failed", it) }.getOrNull()
            ?: run {
                if (!hasPublishedReplacement()) restoreNative()
                return
            }

        val bridge = iconBridge ?: run {
            restoreNative()
            return
        }
        val hadPublishedReplacement = bridge.owns(SLOT_STACKED)
        val published = bridge.publish(SLOT_STACKED, bitmap, "Mobile signal")
        if (!published) {
            // HostIconBridge restores an already-owned holder on update failure. Keeping the
            // native mask would be unsafe on a first publish, but is correct when the previous
            // module holder is still intact.
            if (!hadPublishedReplacement) restoreNative()
            return
        }
        val replacementVisible = options.ignoreSystemHide || state.rows.any { it.originalVisible }
        if (!bridge.setVisible(SLOT_STACKED, replacementVisible)) {
            DebugLog.w(TAG, "published cellular signal holder could not be made visible")
            MobileSignalVisibility.setHiddenForSubIds(emptySet())
            bridge.removeOwned(SLOT_STACKED)
            return
        }
        bridge.removeOwned(SLOT_STACKED_TYPE)
        bridge.removeOwned(SLOT_SINGLE_SIM1)
        bridge.removeOwned(SLOT_SINGLE_SIM2)
        // This is the first point at which a complete replacement exists.
        MobileSignalVisibility.setHiddenForSubIds(
            state.replacementMask(published, options.ignoreSystemHide)
        )
        DebugLog.i(
            TAG,
            "cellular signal published rows=${state.rows.size} bitmap=${bitmap.width}x${bitmap.height}"
        )
        renderTypeSlot(state, bridge)
    }

    private fun hasPublishedReplacement(): Boolean = iconBridge?.owns(SLOT_STACKED) == true

    private fun renderInternalTypeBadge(
        output: MobileTypeOutput,
        state: MobileSignalState,
        signalConfig: IconSvgRenderConfig
    ): android.graphics.Bitmap? {
        if (output.text.isBlank() || !MobileTypePolicy.showInternalBadge(state, typeConfig)) return null
        val badgeConfig = typeConfig.safe().copy(
            textSizeSp = typeConfig.safe().badgeTextSizeSp,
            weight = typeConfig.safe().badgeWeight,
            singleWeight = typeConfig.safe().badgeWeight,
            paddingStartSp = 0f,
            paddingEndSp = 0f,
            verticalOffsetSp = 0f
        )
        return MobileTypeRenderer.render(
            output = output,
            config = badgeConfig,
            iconHeightPx = (signalConfig.iconHeightPx * 0.6f).toInt().coerceAtLeast(1),
            densityDpi = signalConfig.densityDpi,
            fontScale = signalConfig.fontScale,
            rtl = signalConfig.rtl
        )
    }

    private fun restoreNative() {
        MobileSignalVisibility.setHiddenForSubIds(emptySet())
        iconBridge?.removeOwned(SLOT_STACKED)
        iconBridge?.removeOwned(SLOT_STACKED_TYPE)
        iconBridge?.removeOwned(SLOT_SINGLE_SIM1)
        iconBridge?.removeOwned(SLOT_SINGLE_SIM2)
    }

    private fun customStackVisible(): Boolean {
        val mode = IconSlotPolicy.modeFor(
            SLOT_STACKED,
            options.policy.slotModes,
            options.policy.extraHiddenSlots
        )
        return when (mode) {
            IconSlotMode.FOLLOW_SYSTEM,
            IconSlotMode.SHOW_EVERYWHERE,
            IconSlotMode.STATUS_BAR_ONLY -> true
            IconSlotMode.CONTROL_CENTER_ONLY,
            IconSlotMode.HIDE_EVERYWHERE -> false
        }
    }

    private fun scheduleSignalAssetsLoad(bindingGeneration: Long) {
        if (!enabled || signalAssets != null || svgRepository == null) return
        synchronized(assetLoadLock) {
            if (assetLoadGeneration == bindingGeneration) return
            if (assetLoadInFlightGeneration == bindingGeneration) return
            if (assetLoadRetryAfterMs > SystemClock.elapsedRealtime()) return
            assetLoadInFlightGeneration = bindingGeneration
        }
        assetExecutor.execute {
            val assets = runCatching { loadSignalAssets() }
                .onFailure { DebugLog.w(TAG, "asynchronous signal SVG load failed", it) }
                .getOrNull()
            mainHandler.post {
                val stillCurrent = generation.get() == bindingGeneration
                synchronized(assetLoadLock) {
                    if (assetLoadInFlightGeneration == bindingGeneration) {
                        assetLoadInFlightGeneration = Long.MIN_VALUE
                    }
                    if (assets == null) {
                        // A transient failure (module service not ready yet, one failed read) must
                        // not pin this generation to "nothing to load": back off briefly, then let
                        // the next state event retry instead of waiting for a hot reload.
                        assetLoadRetryAfterMs = SystemClock.elapsedRealtime() + ASSET_LOAD_RETRY_BACKOFF_MS
                    } else if (stillCurrent) {
                        assetLoadGeneration = bindingGeneration
                    }
                }
                if (!stillCurrent) return@post
                if (assets != null) {
                    signalAssets = assets
                    DebugLog.i(
                        TAG,
                        "signal SVG assets ready singleStyle=$singleSvgStyle stackedStyle=$stackedSvgStyle"
                    )
                }
                if (enabled) renderCurrent()
            }
        }
    }

    private fun loadSignalAssets(): SignalAssets? {
        val repository = svgRepository ?: return null
        val single = repository.loadSignalSingle(singleSvgStyle) { module.openRemoteFile(it) }
            .onFailure { DebugLog.w(TAG, "single signal SVG unavailable", it) }.getOrNull()
            ?: return null
        val stacked = repository.loadSignalStacked(stackedSvgStyle) { module.openRemoteFile(it) }
            .onFailure { DebugLog.w(TAG, "stacked signal SVG unavailable", it) }.getOrNull()
            ?: return null
        return SignalAssets(single, stacked)
    }

    /** Reads Hyper Helper's independent t32 single/stacked SVG configuration. */
    private fun readSignalSvgConfig() {
        singleSvgStyle = Preferences.getInt(Preferences.KEY_ICON_STACKED_SVG_SINGLE, 0)
            .coerceIn(0, 3)
        stackedSvgStyle = Preferences.getInt(Preferences.KEY_ICON_STACKED_SVG_STACKED, 0)
            .coerceIn(0, 3)
        signalAlphaFg = Preferences.getFloat(Preferences.KEY_ICON_STACKED_ALPHA_FG, 1f)
            .takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 1f
        signalAlphaBg = Preferences.getFloat(Preferences.KEY_ICON_STACKED_ALPHA_BG, 0.4f)
            .takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0.4f
        signalAlphaError = Preferences.getFloat(Preferences.KEY_ICON_STACKED_ALPHA_ERROR, 0.2f)
            .takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0.2f
        signalPaddingStart = Preferences.getFloat(Preferences.KEY_ICON_STACKED_PADDING_START, 0f)
            .takeIf(Float::isFinite)?.coerceIn(0f, 48f) ?: 0f
        signalPaddingEnd = Preferences.getFloat(Preferences.KEY_ICON_STACKED_PADDING_END, 0f)
            .takeIf(Float::isFinite)?.coerceIn(0f, 48f) ?: 0f
    }

    private fun renderTypeSlot(state: MobileSignalState, bridge: HostIconBridge) {
        val output = MobileTypePolicy.resolve(state, typeConfig)
        if (output.text.isBlank() || !typeSlotVisible()) {
            bridge.removeOwned(SLOT_STACKED_TYPE)
            return
        }
        val context = hostContext
        val config = typeConfig.safe()
        val iconHeight = renderIconHeight(context)
        val bitmap = runCatching {
            MobileTypeRenderer.render(
                output = output,
                config = config,
                iconHeightPx = iconHeight,
                densityDpi = context?.resources?.displayMetrics?.densityDpi ?: 0,
                fontScale = context?.resources?.configuration?.fontScale ?: 1f,
                rtl = context?.resources?.configuration?.layoutDirection == android.util.LayoutDirection.RTL
            )
        }.onFailure { DebugLog.w(TAG, "mobile type render failed", it) }.getOrNull()
        if (bitmap == null || !bridge.publish(SLOT_STACKED_TYPE, bitmap, output.text)) {
            bridge.removeOwned(SLOT_STACKED_TYPE)
            return
        }
        bridge.setVisible(SLOT_STACKED_TYPE, true)
        DebugLog.i(TAG, "cellular type published text=${output.text} bitmap=${bitmap.width}x${bitmap.height}")
    }

    private fun typeSlotVisible(): Boolean {
        return when (IconSlotPolicy.modeFor(
            SLOT_STACKED_TYPE,
            options.policy.slotModes,
            options.policy.extraHiddenSlots
        )) {
            IconSlotMode.FOLLOW_SYSTEM,
            IconSlotMode.SHOW_EVERYWHERE,
            IconSlotMode.STATUS_BAR_ONLY -> true
            IconSlotMode.CONTROL_CENTER_ONLY,
            IconSlotMode.HIDE_EVERYWHERE -> false
        }
    }

    private fun readTypeConfig(): MobileTypeConfig = MobileTypeConfig(
        hideWhenDisconnected = Preferences.getBoolean(
            Preferences.KEY_ICON_STACKED_TYPE_HIDE_DISCONNECT,
            false
        ),
        hideWhenWifiAvailable = Preferences.getBoolean(
            Preferences.KEY_ICON_STACKED_TYPE_HIDE_WIFI,
            false
        ),
        showSingleBadge = Preferences.getBoolean(
            Preferences.KEY_ICON_STACKED_TYPE_SHOW_SINGLE,
            false
        ),
        showStackedBadge = Preferences.getBoolean(
            Preferences.KEY_ICON_STACKED_TYPE_SHOW_STACKED,
            false
        ),
        showRoamingPrefix = Preferences.getBoolean(
            Preferences.KEY_ICON_STACKED_TYPE_ROAMING,
            false
        ),
        textSizeSp = Preferences.getFloat(Preferences.KEY_ICON_STACKED_TYPE_SIZE, 14f),
        weight = Preferences.getInt(Preferences.KEY_ICON_STACKED_TYPE_WEIGHT, 630),
        singleWeight = Preferences.getInt(Preferences.KEY_ICON_STACKED_TYPE_SINGLE_WEIGHT, 400),
        badgeTextSizeSp = Preferences.getFloat(Preferences.KEY_ICON_STACKED_TYPE_BADGE_SIZE, 7.16f),
        badgeWeight = Preferences.getInt(Preferences.KEY_ICON_STACKED_TYPE_WEIGHT, 630),
        condensedWidthPercent = Preferences.getInt(
            Preferences.KEY_ICON_STACKED_TYPE_WIDTH_CONDENSED,
            80
        ),
        paddingStartSp = Preferences.getFloat(
            Preferences.KEY_ICON_STACKED_TYPE_PADDING_START,
            2f
        ),
        paddingEndSp = Preferences.getFloat(
            Preferences.KEY_ICON_STACKED_TYPE_PADDING_END,
            2f
        ),
        verticalOffsetSp = Preferences.getFloat(
            Preferences.KEY_ICON_STACKED_TYPE_VERTICAL_OFFSET,
            0f
        ),
        fontMode = Preferences.getInt(Preferences.KEY_ICON_STACKED_TYPE_FONT, 0)
    ).safe()

    private fun renderConfig(): IconSvgRenderConfig {
        val context = hostContext
        val height = renderIconHeight(context)
        val density = context?.resources?.displayMetrics?.density
            ?.takeIf { it.isFinite() && it > 0f } ?: 1f
        return IconSvgRenderConfig(
            iconHeightPx = height.coerceIn(1, 512),
            scale = scale,
            alphaFg = signalAlphaFg,
            alphaBg = signalAlphaBg,
            alphaError = signalAlphaError,
            paddingStartPx = (signalPaddingStart * density).roundToInt().coerceIn(0, 512),
            paddingEndPx = (signalPaddingEnd * density).roundToInt().coerceIn(0, 512),
            densityDpi = context?.resources?.displayMetrics?.densityDpi ?: 0,
            fontScale = context?.resources?.configuration?.fontScale ?: 1f,
            configVersion = 3
        )
    }

    private fun renderIconHeight(context: Context?): Int {
        return context?.let {
            val id = it.resources.getIdentifier(
                "status_bar_icon_height",
                "dimen",
                "com.android.systemui"
            )
            if (id != 0) runCatching { it.resources.getDimensionPixelSize(id) }.getOrNull() else null
        } ?: context?.let { (20f * it.resources.displayMetrics.density).toInt() } ?: 20
    }

    private fun parseSignalModel(value: Any?): MobileSignalModel {
        if (value == null) return MobileSignalModel.unknown()
        val name = value.javaClass.name
        if (name.contains("SignalIconModel\$CellularTypeIconModel\$Cellular")) {
            return MobileSignalModel.cellular(
                level = readInt(value, "level", 0),
                numberOfLevels = readInt(value, "numberOfLevels", 5),
                showExclamationMark = readBoolean(value, "showExclamationMark"),
                carrierNetworkChange = readBoolean(value, "carrierNetworkChange")
            )
        }
        if (name.contains("Satellite")) {
            return MobileSignalModel.satellite(
                level = readInt(value, "level", 0),
                numberOfLevels = readInt(value, "numberOfLevels", 5)
            )
        }
        return MobileSignalModel.unknown()
    }

    private fun extractSubscriptionIds(value: Any?): List<Int> {
        val list: Iterable<*> = when (value) {
            is Map<*, *> -> value.values
            is Iterable<*> -> value
            else -> return emptyList()
        }
        return list.mapNotNull { item ->
            when (item) {
                is Number -> item.toInt()
                null -> null
                else -> subscriptionIdOf(item)
            }
        }.filter { it != MobileSignalState.INVALID_SUB_ID }.distinct()
    }

    private fun subscriptionIdOfTriple(triple: Any): Int? =
        triplePart(triple, "getThird")?.let(::subscriptionIdOf)
            ?: triplePart(triple, "getFirst")?.let(::subscriptionIdOf)

    private fun subscriptionIdOf(value: Any): Int? =
        readIntOrNull(value, "subscriptionId")
            ?: (invokeNoArg(value, "getSubscriptionId") as? Number)?.toInt()
            ?: readIntOrNull(value, "subId")
            ?: (invokeNoArg(value, "getSubId") as? Number)?.toInt()
            // MiuiMobileIconVMImpl keeps the subscription id in its interactor rather than on
            // the VM itself. This path is needed when replaying existing triples after reload;
            // the createViewModel(int) hook supplies the id on a cold start.
            ?: readField(value, "originIconInteractor")?.let(::subscriptionIdOf)
            ?: readField(value, "iconInteractor")?.let(::subscriptionIdOf)

    private fun triplePart(triple: Any, getter: String): Any? = invokeNoArg(triple, getter)

    private fun unwrapLazy(value: Any): Any? = invokeNoArg(value, "get")

    private fun invokeNoArg(target: Any, name: String): Any? = runCatching {
        findMethod(target.javaClass, name, 0)?.invoke(target)
    }.getOrNull()

    private fun readField(target: Any, name: String): Any? = runCatching {
        findField(target.javaClass, name)?.get(target)
    }.getOrNull()

    private fun readInt(target: Any, name: String, default: Int): Int =
        readIntOrNull(target, name) ?: default

    private fun readIntOrNull(target: Any, name: String): Int? = runCatching {
        val field = findField(target.javaClass, name) ?: return@runCatching null
        val value = if (field.type == Int::class.javaPrimitiveType) field.getInt(target) else field.get(target)
        (value as? Number)?.toInt()
    }.getOrNull()

    private fun readBoolean(target: Any, name: String): Boolean = runCatching {
        val field = findField(target.javaClass, name) ?: return@runCatching false
        if (field.type == Boolean::class.javaPrimitiveType) field.getBoolean(target)
        else field.get(target) as? Boolean ?: false
    }.getOrDefault(false)

    private fun findField(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching { return current.getDeclaredField(name).apply { isAccessible = true } }
            current = current.superclass
        }
        return null
    }

    private fun findMethod(type: Class<*>, name: String, parameterCount: Int): Method? {
        var current: Class<*>? = type
        while (current != null) {
            current.declaredMethods.firstOrNull {
                it.name == name && it.parameterTypes.size == parameterCount
            }?.let { return it.apply { isAccessible = true } }
            current = current.superclass
        }
        return type.methods.firstOrNull {
            it.name == name && it.parameterTypes.size == parameterCount
        }?.apply { isAccessible = true }
    }
}
