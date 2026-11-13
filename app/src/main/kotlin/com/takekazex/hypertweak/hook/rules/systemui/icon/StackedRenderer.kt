@file:Suppress("StaticFieldLeak")

package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Picture
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.SubscriptionManager
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.RetiringWorker
import com.takekazex.hypertweak.hook.base.HostRecoveryPhase
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.presentation.icon.IconSvgRenderConfig
import com.takekazex.hypertweak.presentation.icon.IconSvgRenderer
import com.takekazex.hypertweak.presentation.icon.IconSvgRepository
import com.takekazex.hypertweak.presentation.icon.IconSvgSnapshot
import com.takekazex.hypertweak.presentation.icon.MobileSignalState
import com.takekazex.hypertweak.presentation.icon.NativeMobileMask
import com.takekazex.hypertweak.presentation.icon.NetworkIconVisibility
import com.takekazex.hypertweak.presentation.icon.duo.DuoSignalRow
import com.takekazex.hypertweak.presentation.icon.duo.DuoSignalRows
import com.takekazex.hypertweak.presentation.icon.duo.SignalBarMorph
import com.takekazex.hypertweak.settings.contract.IconSettingsKeys
import com.takekazex.hypertweak.settings.contract.IconSlotPolicy
import com.takekazex.hypertweak.settings.contract.IconSurface
import com.takekazex.hypertweak.settings.contract.SignalSvgStyle
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.util.platform.ResourceLookup
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.IdentityHashMap
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
 *
 * It is also the single owner of the native cellular rows: SIM and on-Wi-Fi hiding apply through
 * the same visibility registrations whether or not the stacked bars are enabled. The stacked slot
 * draws the bars only; the 5G text is the host's standalone label in the active data row, which
 * [NativeTypeOnlyMask] reduces to that label, so typography, 5GA, custom names and the keep-on-Wi-Fi
 * rule are the native ones.
 */
@SuppressLint("StaticFieldLeak")
internal object StackedRenderer : IconRenderer() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingIconMobileRowStyle by hookSetting(0) { Preferences.iconConfig().stacked.mobileRowStyle }
    private val settingIconStackedEnabled by hookSetting(false) { Preferences.iconBoolean(IconSettingsKeys.KEY_ICON_STACKED_ENABLED) }
    private val networkVisibility by hookSetting(NetworkIconVisibility()) { IconTunerOptions.networkVisibility() }
    private val settingIconStackedSignalStyle by hookSetting(0) { Preferences.iconStackedSignalStyle() }
    private val settingIconStackedWeight by hookSetting(0f) { Preferences.iconConfig().stacked.stackedWeight }


    private const val PANEL_CALLBACK_OWNER_MARKER =
        "com.android.systemui.controlcenter.shade.ControlCenterHeaderExpandController\$controlCenterCallback"

    private const val TAG = "IconTuner"
    private const val SLOT_STACKED = IconSlotPolicy.SLOT_STACKED_SIGNAL
    // Old module publications can survive an APK upgrade without generation ownership records.
    private val RETIRED_SLOTS = listOf(IconSlotPolicy.LEGACY_STACKED_SIGNAL,
        "stacked_mobile_type", "single_mobile_sim1", "single_mobile_sim2")

    /** Bounded backoff before re-reading signal artwork that failed to load. */
    private const val ASSET_LOAD_RETRY_BACKOFF_MS = 5_000L

    // Signal appearance is fixed. The settings page no longer exposes opacity, scale or start/end
    // padding, so the renderer always uses these built-in values instead of a stored preference.
    private const val SIGNAL_ALPHA_FG = 1f
    private const val SIGNAL_ALPHA_BG = 0.4f
    private const val SIGNAL_ALPHA_ERROR = 0.2f
    private const val SIGNAL_SCALE = 1f
    private const val SIGNAL_PADDING_START_PX = 0
    private const val SIGNAL_PADDING_END_PX = 0

    private val mainHandler = Handler(Looper.getMainLooper())
    /** SVG parsing must not delay SystemUI application startup. */
    private var assetExecutor = RetiringWorker("HyperTweak-StackedSignalAssets")
    @Volatile private var assetsRetired = false
    private val generation = AtomicLong(0L)
    private val assetLoads = SignalAssetLoadState<SignalAssets>(generation, ASSET_LOAD_RETRY_BACKOFF_MS)
    private val installed = generationResource(AtomicBoolean(false)) { it.set(false) }
    private var bridgeController: WeakReference<Any>? by generationState { null }

    private fun onMobileState() {
        val controller = MobileSignalHub.controller
        if (bridgeController?.get() !== controller) {
            iconBridge?.removeAllOwned()
            iconBridge = null
            bridgeController = null
        }
        if (controller != null && bridgeController?.get() !== controller) {
            hostContext = hostContext ?: (runCatching { HostAccess.read(controller, "mContext") }.getOrNull() as? Context)
            if (svgRepository == null) {
                val moduleContext = hostContext?.let { context ->
                    runCatching { context.createPackageContext(HostIconBridge.MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY) }.getOrNull()
                }
                svgRepository = moduleContext?.let(::IconSvgRepository)
            }
            bridgeController = WeakReference(controller)
            iconBridge = HostIconBridge(controller, classLoader)
            retiredSlotsCleared = false
            // Independent of the stacked setting and of asset readiness: the retired holder is
            // outside every current mask, so it must go even when this generation stays native.
            clearRetiredSlots()
            scheduleSignalAssetsLoad(generation.get())
        }
        scheduleRender(generation.get())
    }

    private var enabled by generationState { false }

    private var renderStacked by generationState { false }
    private var currentStyle by generationState { -1 }
    private val panelMotions = generationMainResource(IdentityHashMap<Any, StackedPanelMotion>()) { it.clear() }
    private var slotIndices: Map<Int, Int> by generationState { emptyMap() }
    private var publishedSignalRows: List<DuoSignalRow> by generationState { emptyList() }
    private var publishedSignalPicture: PublishedSignalPicture? by generationState { null }
    private data class SignalRenderKey(
        val artworkHash: String,
        val levels: List<Int>,
        val config: IconSvgRenderConfig
    )
    private var publishedSignalKey: SignalRenderKey? by generationState { null }
    private var pendingRender: Runnable? by generationState { null }

    /** What the stacked pass leaves the native rows: the mask follows it, or stays put. */
    private enum class StackedOutcome { NATIVE, PUBLISHED, SUPPRESSED, KEEP }

    private var hostContext: Context? by generationState { null }

    private var svgRepository: IconSvgRepository? by generationState { null }

    private var iconBridge: HostIconBridge? by generationState { null }
    /** True once the controller was read back without any retired module holders. */
    private var retiredSlotsCleared by generationState { false }

    private var options by generationState { IconTunerOptions.snapshot() }

    private data class PublishedSignalPicture(val picture: Picture, val splitFraction: Float,
        val morphs: List<SignalBarMorph>)

    private data class SignalAssets(
        val bindingGeneration: Long,
        val style: SignalSvgStyle,
        val single: IconSvgSnapshot,
        val stacked: IconSvgSnapshot,
        val destination: IconSvgSnapshot?
    ) {
        var morphKey: Pair<Int, Float>? = null
        var morphs: List<SignalBarMorph> = emptyList()
        fun morphs(rows: Int, weight: Float): List<SignalBarMorph> {
            val destination = destination ?: return emptyList()
            val key = rows to weight
            if (morphKey != key) {
                morphKey = key
                morphs = runCatching { (0 until rows).map { row ->
                    SignalBarMorph.create(
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
        }
    }

    override fun onPrepareHotReload() {
        assetsRetired = true
        val retiredGeneration = assetLoads.advance()
        assetExecutor.close()
        val retiringBridge = iconBridge


        pendingRender?.let(mainHandler::removeCallbacks)

        // A replacement generation may be prepared from a binder thread. StateFlow updates are
        // thread-safe; pass every exposed Pair through its original value before unregistering the
        // getter entries. Queued old-generation callbacks are rejected by the generation check.
        HostAccess.onMain { SlotMaskApplier.releaseStacked() }
        removeRetiringBridge(retiredGeneration, retiringBridge)

        installed.set(false)
    }

    private var dataFirst by generationState { false }

    override fun onHook() {
        if (assetsRetired) {
            assetExecutor = RetiringWorker("HyperTweak-StackedSignalAssets")
            assetsRetired = false
        }
        dataFirst = settingIconMobileRowStyle == 1
        IconTunerFlows.init(classLoader)
        options = IconTunerOptions.snapshot()
        renderStacked = settingIconStackedEnabled
        currentStyle = settingIconStackedSignalStyle
        // Installed while every option is off too: the exposed flows are then already bound, so a
        // SIM-hiding toggle only re-applies the mask and the host binder animates the row in place.
        enabled = true
        if (!installed.compareAndSet(false, true)) return
        // Invalidate any cleanup runnable posted by the retiring generation before it can touch a
        // holder published by this generation.
        assetLoads.advance(SignalSvgStyle.fromStored(settingIconStackedSignalStyle))
        // Without the type-only row the stacked bars would stand alone, losing the 5G text.
        if (renderStacked && !SlotMaskApplier.typeOnlyInstalled) renderStacked = false
        hookPanelMotion()
        HandoverCoordinator.registerStacked(this, ::duoSignalTarget)
        MobileSignalHub.subscribe(this, ::onMobileState)
        hostContext?.let { scheduleSignalAssetsLoad(generation.get()) }
        DebugLog.hookRegistered(TAG, "stacked signal renderer enabled=$renderStacked")
    }

    /**
     * Re-applies changed weight, row-order and SIM/Wi-Fi hiding options in place. The module slot
     * keeps its holder, so only its drawable changes, and the native rows are re-masked. The
     * loaded artwork identity is part of the bitmap key, so SVG switches republish in place.
     */
    fun refreshSettings() {
        HostAccess.onMain {
            if (!enabled) return@onMain
            dataFirst = settingIconMobileRowStyle == 1
            options = IconTunerOptions.snapshot()
            renderStacked = settingIconStackedEnabled && SlotMaskApplier.typeOnlyInstalled
            if (currentStyle != settingIconStackedSignalStyle) {
                currentStyle = settingIconStackedSignalStyle
                assetLoads.advance(SignalSvgStyle.fromStored(currentStyle))
                scheduleSignalAssetsLoad(generation.get())
            }
            if (!renderStacked) restoreNative()
            renderCurrent()
            DebugLog.i(TAG, "mobile signal settings refreshed render=$renderStacked dataFirst=$dataFirst " +
                "visibility=$networkVisibility")
        }
    }

    private fun slotOf(subId: Int): Int = slotIndices[subId] ?: -1

    private fun orderedSignalRows(state: MobileSignalState, hiddenSimIds: Set<Int>): List<DuoSignalRow> =
        DuoSignalRows.ordered(
            state.rows.filterNot { it.subId in hiddenSimIds }.map {
                DuoSignalRow(it.subId, slotIndices[it.subId] ?: -1, it.renderLevel)
            },
            state.activeDataSubId,
            dataFirst
        )

    private fun hookPanelMotion() {
        PanelHub.subscribe(this, changed = {
            val owner = PanelHub.motionHost ?: return@subscribe
            val motion = panelMotions.getOrPut(owner) { StackedPanelMotion(owner) }
            runCatching {
                val drawable = publishedSignalPicture.takeIf {
                    enabled && renderStacked && stackHasHomeSource() && hasPublishedReplacement() &&
                        !HandoverCoordinator.shade.ownsSignalHandover() && !PanelHub.state.shadeSwitching &&
                        !(networkVisibility.hideMobileOnWifi && NetworkHub.state.wifiDefault == true)
                }
                motion.update(publishedSignalRows, drawable?.picture, PanelHub.state.progress,
                    PanelHub.state.visible, drawable?.splitFraction, drawable?.morphs.orEmpty())
            }.onFailure { motion.clear(); DebugLog.w(TAG, "stacked signal handoff unavailable", it) }
        })
    }

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
        val state = MobileSignalHub.state
        slotIndices = state.subscriptionOrder.associateWith {
            runCatching { SubscriptionManager.getSlotIndex(it) }.getOrDefault(-1)
        }
        val complete = MobileSignalHub.complete
        val outcome = if (renderStacked) renderStackedSlot(state, complete) else StackedOutcome.NATIVE
        applyNativeMask(state, outcome)
    }

    /**
     * One mask for every mode: SIM and on-Wi-Fi hiding, plus, while the stacked bars stand in for
     * the rows, everything but the active data row's standalone type label.
     */
    private fun applyNativeMask(state: MobileSignalState, outcome: StackedOutcome) {
        if (outcome == StackedOutcome.KEEP) return
        SlotMaskApplier.stackedReady(outcome == StackedOutcome.PUBLISHED)
    }

    private fun renderStackedSlot(state: MobileSignalState, complete: Boolean): StackedOutcome {
        // The WiFi rule hides the whole mobile signal (signal strength included). It runs before
        // the completeness gate so a transient incomplete emission cannot flash the native icon
        // back. An explicit slot restriction may also hide the replacement on both surfaces.
        if ((networkVisibility.hideMobileOnWifi && state.wifiConnected) || !stackedSlotUsed(SLOT_STACKED)) {
            restoreNative()
            return StackedOutcome.SUPPRESSED
        }
        if (!complete) {
            // The mobile VM briefly emits an incomplete factory/cache state while its two
            // subscription rows are being rebound. Once a module holder is already published,
            // removing it here leaves the native `stacked_mobile` bindable (which is false on
            // this ROM when isStackable=false) as the only path, so the signal disappears
            // permanently until the next full SystemUI restart. Keep the last verified bitmap
            // and its native mask through that transient gap; the next complete emission updates
            // it in place.
            if (hasPublishedReplacement()) return StackedOutcome.KEEP
            restoreNative()
            return StackedOutcome.NATIVE
        }

        val hiddenSimIds = networkVisibility.hiddenSimIds(state, state.wifiConnected, ::slotOf)
        if (hiddenSimIds.size == state.subscriptionOrder.size && hiddenSimIds.isNotEmpty()) {
            restoreNative()
            return StackedOutcome.NATIVE
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
            } else {
                restoreNative()
            }
            return StackedOutcome.NATIVE
        }

        val assets = assetLoads.current(generation.get())?.takeIf { it.bindingGeneration == generation.get() } ?: run {
            scheduleSignalAssetsLoad(generation.get())
            return keepOrRestore()
        }
        val renderRows = orderedSignalRows(state, hiddenSimIds)
        val bridge = iconBridge ?: run {
            restoreNative()
            return StackedOutcome.NATIVE
        }
        val config = renderConfig()
        // Key the bitmap by the loaded source, not just levels/geometry: a live SVG switch
        // must republish even while every SIM stays at the same strength.
        val artwork = if (renderRows.size == 1) assets.single else assets.stacked
        val renderKey = SignalRenderKey(artwork.sourceHash, renderRows.map { it.level }, config)
        // Visibility and masking still reconcile on every state update. Only an intact owned
        // holder may reuse the last successfully published artwork.
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
                ?: return keepOrRestore()

            if (!enabled || generation.get() != assets.bindingGeneration) return StackedOutcome.KEEP
            // HostIconBridge restores an already-owned holder on update failure. Keeping the
            // native mask would be unsafe on a first publish, but is correct when the previous
            // module holder is still intact.
            if (!bridge.publish(SLOT_STACKED, bitmap, "Mobile signal")) return keepOrRestore()
            publishedSignalKey = renderKey
            DebugLog.d(TAG) {
                "cellular signal published rows=${state.rows.size} bitmap=${bitmap.width}x${bitmap.height}"
            }
        }
        val replacementVisible = options.ignoreSystemHide || state.rows.any { it.originalVisible }
        if (!bridge.setVisible(SLOT_STACKED, replacementVisible)) {
            DebugLog.w(TAG, "published cellular signal holder could not be made visible")
            restoreNative()
            return StackedOutcome.NATIVE
        }
        clearRetiredSlots()
        publishedSignalRows = renderRows
        publishedSignalPicture = if (replacementVisible) PublishedSignalPicture(IconSvgRenderer.signalPicture(
            if (renderRows.size > 1) assets.stacked.document else assets.single.document,
            renderRows.map { it.level }, SIGNAL_ALPHA_BG, SIGNAL_ALPHA_ERROR,
            weight = settingIconStackedWeight),
            assets.style.stackedSplitFraction, assets.morphs(renderRows.size, settingIconStackedWeight)) else null
        if (panelMotions.isNotEmpty()) {
            val commands = publishedSignalPicture.takeIf { stackHasHomeSource() }
            panelMotions.values.forEach { motion ->
                runCatching {
                    motion.update(
                        renderRows, commands?.picture, null, null,
                        commands?.splitFraction, commands?.morphs.orEmpty()
                    )
                }.onFailure { motion.clear() }
            }
        }
        // This is the first point at which a complete replacement exists.
        return StackedOutcome.PUBLISHED
    }

    /** A transient failure keeps an intact published holder and its mask; otherwise go native. */
    private fun keepOrRestore(): StackedOutcome {
        if (hasPublishedReplacement()) return StackedOutcome.KEEP
        restoreNative()
        return StackedOutcome.NATIVE
    }

    private fun duoSlot(container: View, slot: String): ImageView? {
        val found = ArrayList<ImageView>()
        fun visit(view: View) {
            if (view.visibility != View.VISIBLE) return
            if (view is ImageView && HostAccess.read(view, "mSlot") == slot &&
                signalEndpointVisible(view, container)) found += view
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(container)
        return found.singleOrNull()
    }
    internal fun duoSignalTarget(container: View): StackedSignalTarget? {
        val published = publishedSignalPicture ?: return null
        val rows = publishedSignalRows
        if (rows.isEmpty() || rows.size != published.morphs.size) return null
        val view = duoSlot(container, SLOT_STACKED) ?: return null
        return StackedSignalTarget(view, rows, published.splitFraction, published.morphs.map { it.source })
    }

    private fun hasPublishedReplacement(): Boolean = iconBridge?.owns(SLOT_STACKED) == true

    /** Drops the module holder; the caller's outcome decides the native mask. */
    private fun restoreNative() {
        publishedSignalRows = emptyList()
        publishedSignalPicture = null
        panelMotions.values.forEach { it.clear() }
        val bridge = iconBridge ?: return
        bridge.removeOwned(SLOT_STACKED)
        clearRetiredSlots()
    }

    /**
     * Upgrade cleanup for retired signal/type holders. Only ordinary holders naming this module
     * are removed. Foreign publications with these old shared names are never managed here.
     */
    private fun clearRetiredSlots() {
        if (retiredSlotsCleared || Looper.myLooper() != Looper.getMainLooper()) return
        val bridge = iconBridge ?: return
        retiredSlotsCleared = RETIRED_SLOTS.map(bridge::removeRetiredModuleHolder).all { it }
    }

    private fun stackHasHomeSource(): Boolean =
        !IconSlotPolicy.blocksSlot(SLOT_STACKED, IconSurface.STATUS_BAR, options.policy)

    // Holders are published once for all managers. Each manager applies its own surface block list.
    private fun stackedSlotUsed(slot: String): Boolean =
        !IconSlotPolicy.blocksSlot(slot, IconSurface.STATUS_BAR, options.policy) ||
            !IconSlotPolicy.blocksSlot(slot, IconSurface.CONTROL_CENTER, options.policy)

    private fun scheduleSignalAssetsLoad(bindingGeneration: Long) {
        if (assetsRetired || !enabled || !renderStacked) return
        val repository = svgRepository ?: return
        val ticket = assetLoads.begin(bindingGeneration, SystemClock.elapsedRealtime()) ?: return
        val accepted = assetExecutor.execute {
            if (assetsRetired || generation.get() != ticket.generation) return@execute
            val assets = runCatching { loadSignalAssets(repository, ticket) }
                .onFailure { DebugLog.w(TAG, "asynchronous signal SVG load failed", it) }
                .getOrNull()
            if (assetsRetired || generation.get() != ticket.generation) return@execute
            mainHandler.post {
                if (!assetLoads.complete(ticket, assets, SystemClock.elapsedRealtime())) return@post
                if (assets != null) {
                    DebugLog.d(TAG, "signal SVG assets ready style=${ticket.style.name} generation=${ticket.generation}")
                }
                if (enabled && generation.get() == ticket.generation) renderCurrent()
            }
        }
        if (!accepted) assetLoads.complete(ticket, null, SystemClock.elapsedRealtime())
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
