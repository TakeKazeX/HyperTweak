@file:Suppress("StaticFieldLeak")

package com.takekazex.hypertweak.hook.rules.systemui.icon

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
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.presentation.icon.duo.DuoSignalRow
import com.takekazex.hypertweak.presentation.icon.duo.DuoSignalRows
import com.takekazex.hypertweak.settings.contract.IconSettingsKeys
import com.takekazex.hypertweak.settings.contract.IconSlotMode
import com.takekazex.hypertweak.settings.contract.IconSlotPolicy
import com.takekazex.hypertweak.settings.contract.SignalSvgStyle
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Method
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData

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
    private const val PANEL_CALLBACK_OWNER_MARKER =
        "com.android.systemui.controlcenter.shade.ControlCenterHeaderExpandController\$controlCenterCallback"
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "IconTuner"
    private const val SLOT_STACKED = "stacked_mobile_icon"
    private const val SLOT_STACKED_TYPE = "stacked_mobile_type"
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
    private val installed = AtomicBoolean(false)
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

    @Volatile
    private var enabled = false

    private var renderStacked = false
    private val panelMotions = IdentityHashMap<Any, StackedPanelMotion>()
    private var slotIndices: Map<Int, Int> = emptyMap()
    private var publishedSignalRows: List<DuoSignalRow> = emptyList()
    private var publishedSignalPicture: PublishedSignalPicture? = null
    private data class SignalRenderKey(
        val levels: List<Int>,
        val config: IconSvgRenderConfig
    )
    private var publishedSignalKey: SignalRenderKey? = null
    private var publishedTypeBitmap: android.graphics.Bitmap? = null
    private var publishedTypeSuppressed = false
    private var pendingRender: Runnable? = null

    /** Hide the whole module-owned stacked slot (signal + type) while WiFi is connected. */
    @Volatile
    private var hideMobileOnWifi = false

    @Volatile
    private var hostContext: Context? = null

    @Volatile
    private var svgRepository: IconSvgRepository? = null

    @Volatile
    private var iconBridge: HostIconBridge? = null

    /** Bounded retries cover the short gap between package-ready and Dagger component startup. */

    @Volatile
    private var options = IconTunerOptions.snapshot()

    @Volatile
    private var typeConfig = MobileTypeConfig()

    /**
     * Alpha crossfade for the module-owned type slot. A Wi-Fi hand-off must not collapse the slot:
     * the host lays the icon row out from the trailing edge, so removing it shifts the signal icon
     * sideways. See [BitmapAlphaFade].
     */
    private val typeFade = BitmapAlphaFade(mainHandler)

    /** True once the type has faded out and given its box back, so a re-render keeps it collapsed. */
    @Volatile
    private var typeCollapsed = false

    /** All mutable reducer state is consumed on the main looper. */

    private data class PublishedSignalPicture(val picture: Picture, val splitFraction: Float)

    private data class SignalAssets(
        val bindingGeneration: Long,
        val style: SignalSvgStyle,
        val single: IconSvgSnapshot,
        val stacked: IconSvgSnapshot
    )

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
        enabled = false
        val retiredGeneration = assetLoads.advance()
        val retiringBridge = iconBridge
        hideMobileOnWifi = false
        typeFade.cancel()
        typeCollapsed = false
        pendingRender?.let(mainHandler::removeCallbacks)
        pendingRender = null
        publishedSignalKey = null
        publishedTypeBitmap = null

        // A replacement generation may be prepared from a binder thread. StateFlow updates are
        // thread-safe; pass every exposed Pair through its original value before unregistering the
        // getter entries. Queued old-generation callbacks are rejected by the generation check.
        if (renderStacked) MobileSignalVisibility.clearForHotReload()
        removeRetiringBridge(retiredGeneration, retiringBridge)
        source.retire()
        slotIndices = emptyMap()
        publishedSignalRows = emptyList()
        publishedSignalPicture = null
        MobileTypeRenderer.clearCache()
        iconBridge = null
        installed.set(false)
    }

    override fun saveHotReloadState(): Any? = source.snapshot()
    override fun restoreHotReloadState(state: Any?) = source.restore(state)
    internal val mobilePipelineConnected get() = source.mobilePipelineConnected
    internal fun recoverAdapter(adapter: Any) = source.recoverAdapter(adapter)

    private var dataFirst = false

    override fun onHook() {
        dataFirst = Preferences.getInt(IconSettingsKeys.KEY_ICON_MOBILE_ROW_STYLE, 0) == 1
        IconTunerFlows.init(classLoader)
        options = IconTunerOptions.snapshot()
        renderStacked = Preferences.getBoolean(IconSettingsKeys.KEY_ICON_STACKED_ENABLED, false)
        hideMobileOnWifi = Preferences.getBoolean(IconSettingsKeys.KEY_ICON_HIDE_MOBILE_ON_WIFI, false)
        enabled = renderStacked || SignalHandoverEvents.shade.requiresMobileState() ||
            ControlCenterCarrierBlockHooker.requiresMobileState
        typeConfig = readTypeConfig()
        if (!enabled) {
            DebugLog.hookSkippedDebug(TAG, "StackedSignal", "disabled")
            return
        }
        if (!installed.compareAndSet(false, true)) return
        // Invalidate any cleanup runnable posted by the retiring generation before it can touch a
        // holder published by this generation.
        assetLoads.advance(SignalSvgStyle.fromStored(Preferences.iconStackedSignalStyle()))
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

    private fun orderedSignalRows(state: MobileSignalState): List<DuoSignalRow> = DuoSignalRows.ordered(
        state.rows.map { DuoSignalRow(it.subId,
            slotIndices[it.subId] ?: -1, it.renderLevel) }, state.activeDataSubId, dataFirst)

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
                        enabled && hasPublishedReplacement() && !SignalHandoverEvents.shade.requiresMobileState() &&
                            !(hideMobileOnWifi && source.signalState.wifiConnected)
                    }
                    motion.update(publishedSignalRows, drawable?.picture, param.args.firstOrNull() as? Float,
                        param.args.firstOrNull() as? Boolean, drawable?.splitFraction)
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
        SignalHandoverEvents.shade.onMobileState(state, complete)
        ControlCenterCarrierBlockHooker.onMobileState(state, complete)
        if (!renderStacked) {
            restoreNative()
            return
        }
        // The WiFi rule hides the whole mobile signal (signal strength included), not just the
        // type label that [MobileTypeConfig.hideWhenWifiAvailable] controls. It runs before the
        // completeness gate so a transient incomplete emission cannot flash the native icon back.
        if (hideMobileOnWifi && state.wifiConnected) {
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

        // An explicit hide for the custom stack is a user policy, so it is allowed to keep the
        // native rows masked. No automatic path takes this branch without a published bitmap.
        if (!customStackVisible()) {
            panelMotions.values.forEach { it.clear() }
            publishedSignalPicture = null
            iconBridge?.removeOwned(SLOT_STACKED)
            releaseTypeSlot(iconBridge)
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
                panelMotions.values.forEach { it.clear() }
                publishedSignalPicture = null
                iconBridge?.setVisible(SLOT_STACKED, false)
                releaseTypeSlot(iconBridge)
                MobileSignalVisibility.setHiddenForSubIds(emptySet())
                return
            }
            restoreNative()
            return
        }

        val assets = assetLoads.current(generation.get())?.takeIf { it.bindingGeneration == generation.get() } ?: run {
            scheduleSignalAssetsLoad(generation.get())
            if (!hasPublishedReplacement()) restoreNative()
            return
        }
        val renderRows = orderedSignalRows(state)
        val bridge = iconBridge ?: run {
            restoreNative()
            return
        }
        val config = renderConfig()
        val renderKey = SignalRenderKey(renderRows.map { it.level }, config)
        // Visibility/masking and the separate type slot still reconcile on every state update.
        // Only an intact owned holder may reuse the last successfully published artwork.
        if (renderKey != publishedSignalKey || !bridge.owns(SLOT_STACKED)) {
            val bitmap = runCatching {
                if (state.rows.size == 1) {
                    IconSvgRenderer.renderSingle(assets.single.document, renderRows[0].level, config)
                } else {
                    IconSvgRenderer.renderStacked(
                        assets.stacked.document, renderRows[0].level, renderRows[1].level, config
                    )
                }
            }.onFailure { DebugLog.w(TAG, "cellular signal SVG render failed", it) }.getOrNull()
                ?: run {
                    if (!hasPublishedReplacement()) restoreNative()
                    return
                }

            if (!enabled || generation.get() != assets.bindingGeneration) return
            val hadPublishedReplacement = bridge.owns(SLOT_STACKED)
            val published = bridge.publish(SLOT_STACKED, bitmap, "Mobile signal")
            if (!published) {
                // HostIconBridge restores an already-owned holder on update failure. Keeping the
                // native mask would be unsafe on a first publish, but is correct when the previous
                // module holder is still intact.
                if (!hadPublishedReplacement) restoreNative()
                return
            }
            publishedSignalKey = renderKey
            DebugLog.d(TAG,
                "cellular signal published rows=${state.rows.size} bitmap=${bitmap.width}x${bitmap.height}")
        }
        val replacementVisible = options.ignoreSystemHide || state.rows.any { it.originalVisible }
        if (!bridge.setVisible(SLOT_STACKED, replacementVisible)) {
            DebugLog.w(TAG, "published cellular signal holder could not be made visible")
            MobileSignalVisibility.setHiddenForSubIds(emptySet())
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
            weight = Preferences.getFloat(IconSettingsKeys.KEY_ICON_STACKED_WEIGHT, 0f)),
            assets.style.stackedSplitFraction) else null
        renderTypeSlot(state, bridge)
        if (panelMotions.isNotEmpty()) {
            val commands = publishedSignalPicture
            panelMotions.values.forEach { motion ->
                runCatching { motion.update(renderRows, commands?.picture, null, null, commands?.splitFraction) }.onFailure { motion.clear() }
            }
        }
    }

    private fun hasPublishedReplacement(): Boolean = iconBridge?.owns(SLOT_STACKED) == true

    private fun restoreNative() {
        publishedSignalRows = emptyList()
        publishedSignalPicture = null
        panelMotions.values.forEach { it.clear() }
        if (!renderStacked) return
        MobileSignalVisibility.setHiddenForSubIds(emptySet())
        iconBridge?.removeOwned(SLOT_STACKED)
        releaseTypeSlot(iconBridge)
        iconBridge?.removeOwned(SLOT_SINGLE_SIM1)
        iconBridge?.removeOwned(SLOT_SINGLE_SIM2)
    }

    private fun customStackVisible(): Boolean {
        // No separate extra-hidden set is passed: the legacy `icon_ext_blocked` list is folded into
        // `slotModes` by `IconTunerOptions.snapshot` (`IconSlotPolicy.foldExtraHiddenIntoModes`).
        val mode = IconSlotPolicy.modeFor(SLOT_STACKED, options.policy.slotModes)
        return when (mode) {
            IconSlotMode.FOLLOW_SYSTEM,
            IconSlotMode.SHOW_EVERYWHERE,
            IconSlotMode.STATUS_BAR_ONLY -> true
            IconSlotMode.CONTROL_CENTER_ONLY,
            IconSlotMode.HIDE_EVERYWHERE -> false
        }
    }

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
        return SignalAssets(ticket.generation, ticket.style, single, stacked)
    }

    private fun renderTypeSlot(state: MobileSignalState, bridge: HostIconBridge) {
        val output = MobileTypePolicy.resolve(state, typeConfig)
        if (output.text.isBlank() || !typeSlotVisible()) {
            releaseTypeSlot(bridge)
            return
        }
        // Already faded away, and it must stay away: the slot was released so the row closes up.
        if (output.suppressed && typeCollapsed) return
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
        if (bitmap == null) {
            releaseTypeSlot(bridge)
            return
        }
        if (publishedTypeBitmap === bitmap && publishedTypeSuppressed == output.suppressed &&
            bridge.owns(SLOT_STACKED_TYPE)) return
        publishedTypeBitmap = bitmap
        publishedTypeSuppressed = output.suppressed
        val publish: (Float) -> Unit = { alpha ->
            val frame = if (alpha >= 1f) bitmap else scaledAlphaBitmap(bitmap, alpha)
            if (bridge.publish(SLOT_STACKED_TYPE, frame, output.text)) {
                if (!bridge.setVisible(SLOT_STACKED_TYPE, true)) publishedTypeBitmap = null
            } else {
                publishedTypeBitmap = null
            }
        }
        if (output.suppressed) {
            // Fade the glyph out with its box still held, then release the box: a hidden type leaves
            // no empty gap. `bridge.owns` is false when it was already released.
            if (bridge.owns(SLOT_STACKED_TYPE)) {
                typeFade.animate(visible = false, apply = publish) {
                    typeCollapsed = true
                    bridge.removeOwned(SLOT_STACKED_TYPE)
                }
            } else {
                typeCollapsed = true
            }
            return
        }
        typeCollapsed = false
        if (bridge.owns(SLOT_STACKED_TYPE)) {
            typeFade.animate(visible = true, apply = publish)
        } else {
            // Coming back: take the box transparent and ramp in, so the return is a fade rather
            // than a pop. The slot is new to the host, so the first paint cannot animate.
            typeFade.snap(visible = false, apply = publish)
            typeFade.animate(visible = true, apply = publish)
        }
        DebugLog.d(TAG, "cellular type published text=${output.text} bitmap=${bitmap.width}x${bitmap.height}")
    }

    /** Drops the type slot through the fade, so no queued frame can land on a removed holder. */
    private fun releaseTypeSlot(bridge: HostIconBridge?) {
        bridge ?: return
        typeFade.cancel()
        typeCollapsed = false
        publishedTypeBitmap = null
        bridge.removeOwned(SLOT_STACKED_TYPE)
    }

    private fun typeSlotVisible(): Boolean {
        // Same as `customStackVisible`: the folded `slotModes` already carry the legacy entry.
        return when (IconSlotPolicy.modeFor(SLOT_STACKED_TYPE, options.policy.slotModes)) {
            IconSlotMode.FOLLOW_SYSTEM,
            IconSlotMode.SHOW_EVERYWHERE,
            IconSlotMode.STATUS_BAR_ONLY -> true
            IconSlotMode.CONTROL_CENTER_ONLY,
            IconSlotMode.HIDE_EVERYWHERE -> false
        }
    }

    /**
     * Only the visibility toggles stay user-configurable; every appearance field keeps
     * [MobileTypeConfig]'s built-in default because the settings page no longer exposes it.
     */
    private fun readTypeConfig(): MobileTypeConfig = MobileTypeConfig(
        hideWhenDisconnected = Preferences.getBoolean(
            IconSettingsKeys.KEY_ICON_STACKED_TYPE_HIDE_DISCONNECT,
            false
        ),
        hideWhenWifiAvailable = !Preferences.cellularTypeKeepsOnWifi(),
        showRoamingPrefix = Preferences.getBoolean(
            IconSettingsKeys.KEY_ICON_STACKED_TYPE_ROAMING,
            false
        ),
        small5GaEnabled = Preferences.getBoolean(
            IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_SMALL_5GA,
            false
        )
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
            signalWeight = Preferences.getFloat(IconSettingsKeys.KEY_ICON_STACKED_WEIGHT, 0f),
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

}
