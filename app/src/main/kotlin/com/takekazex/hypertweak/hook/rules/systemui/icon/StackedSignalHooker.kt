@file:Suppress("StaticFieldLeak")

package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.hook.base.HostRecoveryPhase
import com.takekazex.hypertweak.hook.rules.systemui.icon.carrier.ControlCenterCarrierBlockHooker
import com.takekazex.hypertweak.presentation.icon.IconSvgRenderConfig
import com.takekazex.hypertweak.presentation.icon.IconSvgRenderer
import com.takekazex.hypertweak.presentation.icon.IconSvgRepository
import com.takekazex.hypertweak.presentation.icon.IconSvgSnapshot
import com.takekazex.hypertweak.presentation.icon.MobileSignalState

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Picture
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.SubscriptionManager
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.presentation.icon.duo.DuoSignalRow
import com.takekazex.hypertweak.presentation.icon.duo.DuoSignalRows
import com.takekazex.hypertweak.settings.contract.IconSettingsKeys
import com.takekazex.hypertweak.settings.contract.IconSurface
import com.takekazex.hypertweak.settings.contract.IconSlotPolicy
import com.takekazex.hypertweak.settings.contract.SignalSvgStyle
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Method
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData
import com.takekazex.hypertweak.util.platform.ResourceLookup

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
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingIconMobileRowStyle by hookSetting(0) { Preferences.getInt(IconSettingsKeys.KEY_ICON_MOBILE_ROW_STYLE, 0) }
    private val settingIconStackedEnabled by hookSetting(false) { Preferences.iconBoolean(IconSettingsKeys.KEY_ICON_STACKED_ENABLED) }
    private val settingIconHideMobileOnWifi by hookSetting(false) {
        Preferences.getBoolean(IconSettingsKeys.KEY_ICON_HIDE_MOBILE_ON_WIFI, false)
    }
    private val hideNonDefaultSim by hookSetting(false) { Preferences.hideNonDefaultSim() }
    private val hideSimOne by hookSetting(false) { Preferences.getBoolean(IconSettingsKeys.KEY_ICON_HIDE_SIM_ONE, false) }
    private val hideSimTwo by hookSetting(false) { Preferences.getBoolean(IconSettingsKeys.KEY_ICON_HIDE_SIM_TWO, false) }
    private val settingIconStackedSignalStyle by hookSetting(0) { Preferences.iconStackedSignalStyle() }
    private val settingIconStackedWeight by hookSetting(0f) { Preferences.getFloat(IconSettingsKeys.KEY_ICON_STACKED_WEIGHT, 0f) }
    private val settingIconStackedTypeHideDisconnect by hookSetting(false) {
        Preferences.getBoolean(IconSettingsKeys.KEY_ICON_STACKED_TYPE_HIDE_DISCONNECT, false)
    }
    private val separateType by hookSetting(false) {
        Preferences.getBoolean(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_SINGLE, false)
    }
    private val keepSeparateTypeOnWifi by hookSetting(false) { Preferences.cellularTypeKeepsOnWifi() }
    private val keepStackedTypeOnWifi by hookSetting(false) { Preferences.legacyStackedTypeKeepsOnWifi() }
    private val settingIconStackedTypeRoaming by hookSetting(false) {
        Preferences.getBoolean(IconSettingsKeys.KEY_ICON_STACKED_TYPE_ROAMING, false)
    }
    private val settingIconCellularTypeSmall5ga by hookSetting(false) {
        Preferences.getBoolean(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_SMALL_5GA, false)
    }

    init {
        hostRecovery(HostRecoveryPhase.ADAPTER, icons = true) {
            it.adapter?.let(::recoverAdapter)
        }
    }

    private const val PANEL_CALLBACK_OWNER_MARKER =
        "com.android.systemui.controlcenter.shade.ControlCenterHeaderExpandController\$controlCenterCallback"

    private const val TAG = "IconTuner"
    private const val SLOT_STACKED = IconSlotPolicy.SLOT_STACKED_SIGNAL
    private const val SLOT_STACKED_TYPE = IconSlotPolicy.SLOT_STACKED_TYPE
    private const val SLOT_SINGLE_SIM1 = "single_mobile_sim1"
    private const val SLOT_SINGLE_SIM2 = "single_mobile_sim2"

    /** Bounded backoff before re-reading signal artwork that failed to load. */
    private const val ASSET_LOAD_RETRY_BACKOFF_MS = 5_000L

    // Signal appearance is fixed. The settings page no longer exposes opacity, scale, start/end
    // padding or any type-appearance knob (size, weight, condensed width, font, padding, offset),
    // so the renderer always uses these built-in values instead of reading a stored preference.
    private const val SIGNAL_ALPHA_FG = 1f
    private const val SIGNAL_ALPHA_BG = 0.4f
    private const val SIGNAL_ALPHA_ERROR = 0.2f
    private const val SIGNAL_SCALE = 1f
    private const val SIGNAL_PADDING_START_PX = 0
    private const val SIGNAL_PADDING_END_PX = 0

    private val mainHandler = Handler(Looper.getMainLooper())
    /** SVG parsing must not delay SystemUI application startup. */
    private val assetExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "HyperTweak-StackedSignalAssets").apply { isDaemon = true }
    }
    private val generation = AtomicLong(0L)
    private val assetLoads = SignalAssetLoadState<SignalAssets>(generation, ASSET_LOAD_RETRY_BACKOFF_MS)
    private val installed = generationResource(AtomicBoolean(false)) { it.set(false) }
    private val nativeWifiType = NativeWifiTypeVisibility(this)
    private var nativeTypeVisibilityInstalled by generationState { false }
    private val source = MobileSignalSource(
        this, generation, { enabled }, { renderStacked }, { hostContext },
        onAdapterReady = { controller, bindingGeneration ->
            hostContext = hostContext ?: (runCatching { HostAccess.read(controller, "mContext") }.getOrNull() as? Context)
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

        },
        scheduleRender = ::scheduleRender
    )

    private var enabled by generationState { false }

    private var renderStacked by generationState { false }
    private val panelMotions = generationMainResource(IdentityHashMap<Any, StackedPanelMotion>()) { it.clear() }
    private var slotIndices: Map<Int, Int> by generationState { emptyMap() }
    private var publishedSignalRows: List<DuoSignalRow> by generationState { emptyList() }
    private var publishedSignalPicture: PublishedSignalPicture? by generationState { null }
    private data class SignalRenderKey(
        val levels: List<Int>,
        val config: IconSvgRenderConfig
    )
    private var publishedSignalKey: SignalRenderKey? by generationState { null }
    private var publishedTypeBitmap: android.graphics.Bitmap? by generationState { null }
    private var pendingRender: Runnable? by generationState { null }

    /** Hide the whole module-owned stacked slot (signal + type) while WiFi is connected. */
    private var hideMobileOnWifi by generationState { false }

    private var hostContext: Context? by generationState { null }

    private var svgRepository: IconSvgRepository? by generationState { null }

    private var iconBridge: HostIconBridge? by generationState { null }

    private var options by generationState { IconTunerOptions.snapshot() }

    private var typeConfig by generationState { MobileTypeConfig() }

    private data class PublishedSignalPicture(val picture: Picture, val splitFraction: Float,
        val morphs: List<com.takekazex.hypertweak.presentation.icon.duo.SignalBarMorph>)

    private data class SignalAssets(
        val bindingGeneration: Long,
        val style: SignalSvgStyle,
        val single: IconSvgSnapshot,
        val stacked: IconSvgSnapshot,
        val destination: IconSvgSnapshot?
    ) {
        var morphKey: Pair<Int, Float>? = null
        var morphs: List<com.takekazex.hypertweak.presentation.icon.duo.SignalBarMorph> = emptyList()
        fun morphs(rows: Int, weight: Float): List<com.takekazex.hypertweak.presentation.icon.duo.SignalBarMorph> {
            val destination = destination ?: return emptyList()
            val key = rows to weight
            if (morphKey != key) {
                morphKey = key
                morphs = runCatching { (0 until rows).map { row ->
                    com.takekazex.hypertweak.presentation.icon.duo.SignalBarMorph.create(
                        if (rows > 1) stacked.document else single.document, destination.document,
                        row, rows, style.stackedSplitFraction, weight)
                } }.getOrDefault(emptyList())
            }
            return morphs
        }
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
            source.scheduleExistingAdapterDiscovery()
        }
    }

    override fun onPrepareHotReload() {
        val retiredGeneration = assetLoads.advance()
        val retiringBridge = iconBridge


        pendingRender?.let(mainHandler::removeCallbacks)

        // A replacement generation may be prepared from a binder thread. StateFlow updates are
        // thread-safe; pass every exposed Pair through its original value before unregistering the
        // getter entries. Queued old-generation callbacks are rejected by the generation check.
        if (renderStacked) MobileSignalVisibility.clearForHotReload()
        removeRetiringBridge(retiredGeneration, retiringBridge)
        nativeWifiType.retire()
        source.retire()

        MobileTypeRenderer.clearCache()

        installed.set(false)
    }

    override fun saveHotReloadState(): Any? = source.snapshot()
    override fun restoreHotReloadState(state: Any?) = source.restore(state)
    internal val mobilePipelineConnected get() = source.mobilePipelineConnected
    internal fun recoverAdapter(adapter: Any) = source.recoverAdapter(adapter)

    private var dataFirst by generationState { false }

    override fun onHook() {
        dataFirst = settingIconMobileRowStyle == 1
        IconTunerFlows.init(classLoader)
        options = IconTunerOptions.snapshot()
        renderStacked = settingIconStackedEnabled
        hideMobileOnWifi = settingIconHideMobileOnWifi
        nativeTypeVisibilityInstalled = separateType &&
            (keepSeparateTypeOnWifi || !settingIconStackedTypeHideDisconnect) && nativeWifiType.install()
        enabled = nativeTypeVisibilityInstalled || renderStacked || SignalHandoverEvents.shade.requiresMobileState() ||
            ControlCenterCarrierBlockHooker.requiresMobileState
        typeConfig = readTypeConfig()
        if (!enabled) {
            DebugLog.hookSkippedDebug(TAG, "StackedSignal", "disabled")
            return
        }
        if (!installed.compareAndSet(false, true)) return
        // Invalidate any cleanup runnable posted by the retiring generation before it can touch a
        // holder published by this generation.
        assetLoads.advance(SignalSvgStyle.fromStored(settingIconStackedSignalStyle))
        if (renderStacked && !MobileSignalVisibility.installGetter(this)) {
            DebugLog.hookSkipped(TAG, "MiuiMobileIconVMImpl#isVisible", "getter bridge unavailable")
            return
        }
        if (renderStacked && !SignalHandoverEvents.shade.requiresMobileState()) hookPanelMotion()
        source.hookCreateViewModel()
        source.hookAdapterStart()
        source.restore(source.snapshot())
        source.scheduleExistingAdapterDiscovery()
        hostContext?.let { scheduleSignalAssetsLoad(generation.get()) }
        DebugLog.hookRegistered(TAG, "model-driven stacked signal slot")
    }

    private fun orderedSignalRows(state: MobileSignalState, hiddenSimIds: Set<Int>): List<DuoSignalRow> =
        DuoSignalRows.ordered(
            state.rows.filterNot { it.subId in hiddenSimIds }.map {
                DuoSignalRow(it.subId, slotIndices[it.subId] ?: -1, it.renderLevel)
            },
            state.activeDataSubId,
            dataFirst
        )

    private fun hookPanelMotion() {
        val methods = resolvePanelMotionMethods()
        if (methods.isEmpty()) {
            DebugLog.hookSkipped(TAG, "stacked signal panel-motion callbacks", "unique DexKit targets not found")
            return
        }
        methods.forEach { method ->
            deoptimize(method)
            method.hook { after { param ->
                val owner = param.thisObject
                val motion = panelMotions.getOrPut(owner) { StackedPanelMotion(owner) }
                runCatching {
                    val drawable = publishedSignalPicture.takeIf {
                        enabled && stackHasHomeSource() && hasPublishedReplacement() &&
                            !SignalHandoverEvents.shade.requiresMobileState() &&
                            !(hideMobileOnWifi && source.signalState.wifiConnected)
                    }
                    motion.update(publishedSignalRows, drawable?.picture, param.args.firstOrNull() as? Float,
                        param.args.firstOrNull() as? Boolean, drawable?.splitFraction, drawable?.morphs.orEmpty())
                }.onFailure { motion.clear(); DebugLog.w(TAG, "stacked signal handoff unavailable", it) }
            } }
        }
    }

    private fun resolvePanelMotionMethods(): List<Method> {
        val apkPath = hookParam.appInfo?.sourceDir ?: return emptyList()
        return DexRuntime.resolveMethodMap(
            "systemui.stacked_panel_motion", apkPath, dexCacheDirectory, classLoader, hookParam.appInfo,
            validate = { key, method ->
                val type = when (key) { "expansion" -> Float::class.javaPrimitiveType
                    "visibility" -> Boolean::class.javaPrimitiveType; else -> null }
                val name = if (key == "expansion") "onExpansionChanged" else "onVisibleChanged"
                type != null && method.declaringClass.name.contains(PANEL_CALLBACK_OWNER_MARKER) &&
                    method.name == name && method.parameterTypes.contentEquals(arrayOf(type)) &&
                    method.returnType == Void.TYPE && !java.lang.reflect.Modifier.isStatic(method.modifiers)
            },
        ) { bridge ->
            val expansion = resolvePanelMotionMethod(
                bridge,
                "onExpansionChanged",
                Float::class.javaPrimitiveType!!
            )
            val visibility = resolvePanelMotionMethod(
                bridge,
                "onVisibleChanged",
                Boolean::class.javaPrimitiveType!!
            )
            mapOf("expansion" to expansion, "visibility" to visibility)
                .filterValues { it != null }.mapValues { requireNotNull(it.value) }
        }?.values?.toList().orEmpty()
    }

    private fun resolvePanelMotionMethod(
        bridge: DexKitBridge,
        methodName: String,
        parameterType: Class<*>
    ): Method? = bridge.findMethod {
        matcher {
            declaredClass { className(PANEL_CALLBACK_OWNER_MARKER, StringMatchType.Contains) }
            name(methodName)
            paramTypes(parameterType)
            returnType(Void.TYPE)
        }
    }.toList().filter { data ->
        data.className.contains(PANEL_CALLBACK_OWNER_MARKER) &&
            data.methodName == methodName &&
            data.paramTypeNames == listOf(parameterType.name) &&
            data.returnTypeName == Void.TYPE.name
    }.mapNotNull(::materializeMethod).singleOrNull()

    private fun materializeMethod(data: MethodData): Method? = runCatching {
        data.getMethodInstance(classLoader)
    }.onFailure {
        DebugLog.w(TAG, "failed to inspect ${data.className}#${data.methodName}", it)
    }.getOrNull()

    /**
     * Holder teardown must run on SystemUI's main thread. The generation check and the bridge's
     * exact-icon ownership check make a late cleanup harmless after a replacement generation has
     * already published a new holder.
     */
    private fun removeRetiringBridge(retiredGeneration: Long, bridge: HostIconBridge?) {
        val cleanup = Runnable {
            if (generation.get() == retiredGeneration) {
                panelMotions.values.forEach { it.clear() }; panelMotions.clear()
                bridge?.removeAllOwned()
            }
        }
        HostAccess.onMain { cleanup.run() }
    }

    private fun scheduleRender(bindingGeneration: Long) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { scheduleRender(bindingGeneration) }
            return
        }
        if (!enabled || generation.get() != bindingGeneration || pendingRender != null) return
        val task = Runnable {
            pendingRender = null
            if (enabled && generation.get() == bindingGeneration) renderCurrent()
        }
        pendingRender = task
        mainHandler.post(task)
    }

    /** Coalesces a burst of flow emissions before rendering the final complete state. */
    private fun renderCurrent() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            scheduleRender(generation.get())
            return
        }
        if (!enabled) return
        val state = source.signalState
        slotIndices = state.subscriptionOrder.associateWith {
            runCatching { SubscriptionManager.getSlotIndex(it) }.getOrDefault(-1)
        }
        val complete = source.complete
        if (nativeTypeVisibilityInstalled) {
            // The native binder owns the label. Bitmap label limits must not gate its text.
            val wifi = state.wifiConnected
            val allowType = if (wifi) keepSeparateTypeOnWifi && !hideMobileOnWifi
                else !settingIconStackedTypeHideDisconnect
            val activeType = state.wifiDataSubscription(wifiDefault = true)?.subId?.takeIf {
                allowType && complete && it !in state.hiddenSimIds(hideNonDefaultSim, hideSimOne, hideSimTwo) { id ->
                    slotIndices[id] ?: -1
                }
            }
            nativeWifiType.publish(activeType)
        }
        SignalHandoverEvents.shade.onMobileState(state, complete)
        ControlCenterCarrierBlockHooker.onMobileState(state, complete)
        if (!renderStacked) {
            restoreNative()
            return
        }
        // The WiFi rule hides the whole mobile signal (signal strength included), not just the
        // type label that [MobileTypeConfig.hideWhenWifiAvailable] controls. It runs before the
        // completeness gate so a transient incomplete emission cannot flash the native icon back.
        // An explicit slot restriction may also hide the replacement on both surfaces.
        if ((hideMobileOnWifi && state.wifiConnected) || !stackedSlotUsed(SLOT_STACKED)) {
            panelMotions.values.forEach { it.clear() }
            publishedSignalPicture = null
            iconBridge?.removeOwned(SLOT_STACKED)
            releaseTypeSlot(iconBridge)
            MobileSignalVisibility.setHiddenForSubIds(state.subscriptionOrder.toSet())
            return
        }
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

        val hiddenSimIds = state.hiddenSimIds(hideNonDefaultSim, hideSimOne, hideSimTwo) {
            slotIndices[it] ?: -1
        }
        if (hiddenSimIds.size == state.subscriptionOrder.size && hiddenSimIds.isNotEmpty()) {
            restoreNative(hiddenSimIds)
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
                panelMotions.values.forEach { it.clear() }
                publishedSignalPicture = null
                iconBridge?.setVisible(SLOT_STACKED, false)
                releaseTypeSlot(iconBridge)
                MobileSignalVisibility.setHiddenForSubIds(hiddenSimIds)
                return
            }
            restoreNative(hiddenSimIds)
            return
        }

        val assets = assetLoads.current(generation.get())?.takeIf { it.bindingGeneration == generation.get() } ?: run {
            scheduleSignalAssetsLoad(generation.get())
            if (!hasPublishedReplacement()) restoreNative(hiddenSimIds)
            return
        }
        val renderRows = orderedSignalRows(state, hiddenSimIds)
        val bridge = iconBridge ?: run {
            restoreNative(hiddenSimIds)
            return
        }
        val config = renderConfig()
        val renderKey = SignalRenderKey(renderRows.map { it.level }, config)
        // Visibility/masking and the separate type slot still reconcile on every state update.
        // Only an intact owned holder may reuse the last successfully published artwork.
        if (renderKey != publishedSignalKey || !bridge.owns(SLOT_STACKED)) {
            val bitmap = runCatching {
                if (renderRows.size == 1) {
                    IconSvgRenderer.renderSingle(assets.single.document, renderRows[0].level, config)
                } else {
                    IconSvgRenderer.renderStacked(
                        assets.stacked.document, renderRows[0].level, renderRows[1].level, config
                    )
                }
            }.onFailure { DebugLog.w(TAG, "cellular signal SVG render failed", it) }.getOrNull()
                ?: run {
                    if (!hasPublishedReplacement()) restoreNative(hiddenSimIds)
                    return
                }

            if (!enabled || generation.get() != assets.bindingGeneration) return
            val hadPublishedReplacement = bridge.owns(SLOT_STACKED)
            val published = bridge.publish(SLOT_STACKED, bitmap, "Mobile signal")
            if (!published) {
                // HostIconBridge restores an already-owned holder on update failure. Keeping the
                // native mask would be unsafe on a first publish, but is correct when the previous
                // module holder is still intact.
                if (!hadPublishedReplacement) restoreNative(hiddenSimIds)
                return
            }
            publishedSignalKey = renderKey
            DebugLog.d(TAG,
                "cellular signal published rows=${state.rows.size} bitmap=${bitmap.width}x${bitmap.height}")
        }
        val replacementVisible = options.ignoreSystemHide || state.rows.any { it.originalVisible }
        if (!bridge.setVisible(SLOT_STACKED, replacementVisible)) {
            DebugLog.w(TAG, "published cellular signal holder could not be made visible")
            MobileSignalVisibility.setHiddenForSubIds(hiddenSimIds)
            bridge.removeOwned(SLOT_STACKED)
            return
        }
        // The type slot is owned by `renderTypeSlot`; removing it here would cancel an in-flight
        // fade and give the box back mid-transition.
        bridge.removeOwned(SLOT_SINGLE_SIM1)
        bridge.removeOwned(SLOT_SINGLE_SIM2)
        // This is the first point at which a complete replacement exists.
        MobileSignalVisibility.setHiddenForSubIds(
            state.replacementMask(true, options.ignoreSystemHide)
        )
        publishedSignalRows = renderRows
        publishedSignalPicture = if (replacementVisible) PublishedSignalPicture(IconSvgRenderer.signalPicture(
            if (renderRows.size > 1) assets.stacked.document else assets.single.document,
            renderRows.map { it.level }, SIGNAL_ALPHA_BG, SIGNAL_ALPHA_ERROR,
            weight = settingIconStackedWeight),
            assets.style.stackedSplitFraction, assets.morphs(renderRows.size, settingIconStackedWeight)) else null
        if (state.activeDataSubId in hiddenSimIds) {
            releaseTypeSlot(bridge)
        } else {
            renderTypeSlot(state, bridge, singleRow = renderRows.size == 1)
        }
        if (panelMotions.isNotEmpty()) {
            val commands = publishedSignalPicture.takeIf { stackHasHomeSource() }
            panelMotions.values.forEach { motion ->
                runCatching { motion.update(renderRows, commands?.picture, null, null, commands?.splitFraction, commands?.morphs.orEmpty()) }.onFailure { motion.clear() }
            }
        }
    }

    private fun duoSlot(container: android.view.View, slot: String): android.widget.ImageView? {
        val found = ArrayList<android.widget.ImageView>()
        fun visit(view: android.view.View) {
            if (view.visibility != android.view.View.VISIBLE) return
            if (view is android.widget.ImageView && HostAccess.read(view, "mSlot") == slot &&
                signalEndpointVisible(view, container)) found += view
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(container)
        return found.singleOrNull()
    }
    internal fun duoSignalTarget(container: android.view.View): StackedSignalTarget? {
        val published = publishedSignalPicture ?: return null
        val rows = publishedSignalRows
        if (rows.isEmpty() || rows.size != published.morphs.size) return null
        val view = duoSlot(container, SLOT_STACKED) ?: return null
        return StackedSignalTarget(view, rows, published.splitFraction, published.morphs.map { it.source })
    }
    internal fun duoTypeTarget(container: android.view.View): android.view.View? = duoSlot(container, SLOT_STACKED_TYPE)

    private fun hasPublishedReplacement(): Boolean = iconBridge?.owns(SLOT_STACKED) == true

    private fun restoreNative(hiddenSimIds: Set<Int> = emptySet()) {
        publishedSignalRows = emptyList()
        publishedSignalPicture = null
        panelMotions.values.forEach { it.clear() }
        if (!renderStacked) return
        MobileSignalVisibility.setHiddenForSubIds(hiddenSimIds)
        iconBridge?.removeOwned(SLOT_STACKED)
        releaseTypeSlot(iconBridge)
        iconBridge?.removeOwned(SLOT_SINGLE_SIM1)
        iconBridge?.removeOwned(SLOT_SINGLE_SIM2)
    }

    private fun stackHasHomeSource(): Boolean =
        !IconSlotPolicy.blocksSlot(SLOT_STACKED, IconSurface.STATUS_BAR, options.policy)

    // Holders are published once for all managers. Each manager applies its own surface block list.
    private fun stackedSlotUsed(slot: String): Boolean =
        !IconSlotPolicy.blocksSlot(slot, IconSurface.STATUS_BAR, options.policy) ||
            !IconSlotPolicy.blocksSlot(slot, IconSurface.CONTROL_CENTER, options.policy)

    private fun scheduleSignalAssetsLoad(bindingGeneration: Long) {
        if (!enabled || !renderStacked) return
        val repository = svgRepository ?: return
        val ticket = assetLoads.begin(bindingGeneration, SystemClock.elapsedRealtime()) ?: return
        assetExecutor.execute {
            val assets = runCatching { loadSignalAssets(repository, ticket) }
                .onFailure { DebugLog.w(TAG, "asynchronous signal SVG load failed", it) }
                .getOrNull()
            mainHandler.post {
                if (!assetLoads.complete(ticket, assets, SystemClock.elapsedRealtime())) return@post
                if (assets != null) {
                    DebugLog.d(TAG, "signal SVG assets ready style=${ticket.style.name} generation=${ticket.generation}")
                }
                if (enabled && generation.get() == ticket.generation) renderCurrent()
            }
        }
    }

    private fun loadSignalAssets(repository: IconSvgRepository, ticket: SignalAssetLoadState.Ticket): SignalAssets? {
        val single = repository.loadSignalSingle(ticket.style)
            .onFailure { DebugLog.w(TAG, "single signal SVG unavailable", it) }.getOrNull()
            ?: return null
        val stacked = repository.loadSignalStacked(ticket.style)
            .onFailure { DebugLog.w(TAG, "stacked signal SVG unavailable", it) }.getOrNull()
            ?: return null
        return SignalAssets(ticket.generation, ticket.style, single, stacked,
            repository.loadSignalSingle(SignalSvgStyle.HYPEROS3).getOrNull())
    }

    private fun renderTypeSlot(state: MobileSignalState, bridge: HostIconBridge, singleRow: Boolean) {
        val output = MobileTypePolicy.resolve(state, typeConfig).copy(isSingle = singleRow)
        if (output.text.isBlank() || output.suppressed || !stackedSlotUsed(SLOT_STACKED_TYPE)) {
            releaseTypeSlot(bridge)
            return
        }
        val context = hostContext
        val hostFont = context?.let { runCatching { MobileTypeHostMetrics.font(it) }.getOrNull() }
        val hostSizePx = hostFont?.textSizePx
        // The host's own type has no side padding of its own; the icon row's spacing separates it.
        val config = if (hostSizePx != null) {
            typeConfig.safe().copy(paddingStartSp = 0.5f, paddingEndSp = 0.5f)
        } else {
            typeConfig.safe()
        }
        val iconHeight = renderIconHeight(context)
        val bitmap = runCatching {
            MobileTypeRenderer.render(
                output = output,
                config = config,
                iconHeightPx = iconHeight,
                densityDpi = context?.resources?.displayMetrics?.densityDpi ?: 0,
                fontScale = context?.resources?.configuration?.fontScale ?: 1f,
                rtl = context?.resources?.configuration?.layoutDirection == android.util.LayoutDirection.RTL,
                hostTypeface = hostFont?.typeface,
                hostTextSizePx = hostSizePx,
                hostFontVariationSettings = hostFont?.variationSettings
            )
        }.onFailure { DebugLog.w(TAG, "mobile type render failed", it) }.getOrNull()
        if (bitmap == null) {
            releaseTypeSlot(bridge)
            return
        }
        if (publishedTypeBitmap === bitmap && bridge.owns(SLOT_STACKED_TYPE)) return
        // The holder and its measured width must always describe the same drawable.
        // A transparent placeholder can survive a host drawable update during slot reattachment.
        publishedTypeBitmap = bitmap.takeIf {
            bridge.publish(SLOT_STACKED_TYPE, it, output.text) &&
                bridge.setVisible(SLOT_STACKED_TYPE, true)
        }
        DebugLog.d(TAG, "cellular type published text=${output.text} bitmap=${bitmap.width}x${bitmap.height}")
    }

    /** Drops the slot as soon as Wi-Fi takes its place. */
    private fun releaseTypeSlot(bridge: HostIconBridge?) {
        bridge ?: return
        publishedTypeBitmap = null
        bridge.removeOwned(SLOT_STACKED_TYPE)
    }

    /**
     * Only the visibility toggles stay user-configurable; every appearance field keeps
     * [MobileTypeConfig]'s built-in default because the settings page no longer exposes it.
     */
    private fun readTypeConfig(): MobileTypeConfig = MobileTypeConfig(
        hideWhenDisconnected = settingIconStackedTypeHideDisconnect,
        hideWhenWifiAvailable = !keepStackedTypeOnWifi,
        showRoamingPrefix = settingIconStackedTypeRoaming,
        typography = MobileTypeTypographySettings.current,
        small5GaEnabled = settingIconCellularTypeSmall5ga
    ).safe()

    private fun renderConfig(): IconSvgRenderConfig {
        val context = hostContext
        val height = renderIconHeight(context)
        return IconSvgRenderConfig(
            iconHeightPx = height.coerceIn(1, 512),
            scale = SIGNAL_SCALE,
            alphaFg = SIGNAL_ALPHA_FG,
            alphaBg = SIGNAL_ALPHA_BG,
            alphaError = SIGNAL_ALPHA_ERROR,
            paddingStartPx = SIGNAL_PADDING_START_PX,
            paddingEndPx = SIGNAL_PADDING_END_PX,
            densityDpi = context?.resources?.displayMetrics?.densityDpi ?: 0,
            fontScale = context?.resources?.configuration?.fontScale ?: 1f,
            signalWeight = settingIconStackedWeight,
            configVersion = 3
        )
    }

    private fun renderIconHeight(context: Context?): Int {
        return context?.let {
            val id = ResourceLookup.identifier(it.resources, 
                "status_bar_icon_height",
                "dimen",
                "com.android.systemui"
            )
            if (id != 0) runCatching { it.resources.getDimensionPixelSize(id) }.getOrNull() else null
        } ?: context?.let { (20f * it.resources.displayMetrics.density).toInt() } ?: 20
    }

}
