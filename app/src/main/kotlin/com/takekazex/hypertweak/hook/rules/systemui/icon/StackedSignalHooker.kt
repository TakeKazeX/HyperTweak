package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.content.Context
import android.graphics.drawable.Icon
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Custom stacked mobile signal, ported from Hyper Helper's `StackedMobileIcon` + render engine.
 *
 * The hook takes over the mobile icon pipeline instead of fighting OS4's Compose stacked renderer:
 * `MobileIconsViewModel.isStackable` is forced true so SystemUI clears its ordinary mobile icons,
 * and each real `MobileIconViewModel.getIcon()` returns a module-drawn ALPHA_8 icon instead of the
 * system one. Signal level and type text are derived from the original system `Icon` resource ids
 * (`stat_sys_signal_N`, `data_connection_*`), so no system state streams need to be understood.
 * In stacked mode the default-SIM icon composites both SIM rows from a stacked SVG.
 */
object StackedSignalHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "IconTuner"
    private const val ICONS_VM_CLASS = "com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MobileIconsViewModel"
    private const val VM_CLASS = "com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MobileIconViewModel"
    private const val SVG_DIR = "svg"

    private class SignalState {
        @Volatile var level = 0
        @Volatile var type = ""
    }

    private val states = ConcurrentHashMap<Int, SignalState>()
    private val defaultSubId = AtomicInteger(Int.MIN_VALUE)

    @Volatile private var enabled = false
    @Volatile private var singleStyle = 0
    @Volatile private var stackedStyle = 0
    @Volatile private var scale = 1f
    @Volatile private var paddingStart = 0f
    @Volatile private var paddingEnd = 0f
    @Volatile private var alphaFg = 1f
    @Volatile private var alphaBg = 1f
    @Volatile private var alphaError = 1f
    @Volatile private var typeSizeDp = 11f
    @Volatile private var typeWeight = 400
    @Volatile private var typePosition = 0
    @Volatile private var showSingle = true
    @Volatile private var showStacked = false
    @Volatile private var showRoaming = false
    @Volatile private var rtl = false

    private var iconHeightPx = 0

    private var singleSvgs = mutableMapOf<Int, StackedSignalRender.Doc>()
    private var stackedSvgs = mutableMapOf<Int, StackedSignalRender.Doc>()

    private var subscriptionField: Field? = null
    private var isStackableField: Field? = null
    private val hooksInstalled = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Cached `ActivityThread.currentApplication()` reflection; resolves once, never per call. */
    private val activityThreadCurrentApplicationMethod by lazy {
        runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .apply { isAccessible = true }
        }.getOrNull()
    }

    @Volatile
    private var appContext: android.content.Context? = null

    private fun appResources(): android.content.res.Resources? {
        appContext?.let { return it.resources }
        val method = activityThreadCurrentApplicationMethod ?: return null
        val resolved = runCatching { method.invoke(null) as? android.content.Context }.getOrNull()
        if (resolved != null) appContext = resolved
        return resolved?.resources
    }

    /** Resource-name lookups are per-icon-request hot; cache by resource id (small, bounded set). */
    private val resourceNameCache = ConcurrentHashMap<Int, String>()

    private fun resourceNameOf(resId: Int): String? {
        resourceNameCache[resId]?.let { return it }
        val name = runCatching { appResources()?.getResourceEntryName(resId) }.getOrNull()
        if (name != null) resourceNameCache[resId] = name
        return name
    }

    /**
     * The composed icon only changes when the signal level, type text or a render parameter
     * changes, but `getIcon()` is called on every status-bar refresh. Cache the composed bitmap
     * by those inputs instead of re-rendering (3-5 ALPHA_8 bitmaps plus Pictures) per request.
     * [renderEpoch] is bumped whenever a parameter outside the key changes (style, scale, alphas,
     * weight, paddings, rtl, icon height), which only happens on (re)install.
     */
    private class RenderKey(
        val epoch: Int,
        val stacked: Boolean,
        val row1Level: Int,
        val row2Level: Int,
        val typeText: String
    ) {
        override fun equals(other: Any?): Boolean =
            other is RenderKey && other.epoch == epoch && other.stacked == stacked &&
                other.row1Level == row1Level && other.row2Level == row2Level &&
                other.typeText == typeText

        override fun hashCode(): Int {
            var result = epoch
            result = 31 * result + if (stacked) 1 else 0
            result = 31 * result + row1Level
            result = 31 * result + row2Level
            result = 31 * result + typeText.hashCode()
            return result
        }
    }

    private const val MAX_RENDER_CACHE_ENTRIES = 16

    @Volatile
    private var renderEpoch = 0
    private val renderedCache = ConcurrentHashMap<RenderKey, android.graphics.Bitmap>()

    /** Bundled style files; index matches the style dropdown (2 = custom → falls back to 0). */
    private val bundledStyles = listOf(
        "Signal-HyperOS" to "Signal-HyperOS",
        "Signal-iOS" to "Signal-iOS",
        null to null,
        "Signal-iOS27" to "Signal-iOS27"
    )

    /** Loads built-in SVGs and resolves the icon height; called from HookEntry.onPackageReady. */
    fun onPackageReady(context: Context) {
        rtl = context.resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL
        val density = context.resources.displayMetrics.density
        val heightRes = context.resources.getIdentifier(
            "status_bar_icon_height", "dimen", "com.android.systemui"
        )
        iconHeightPx = if (heightRes != 0) {
            runCatching { context.resources.getDimensionPixelSize(heightRes) }.getOrDefault(0)
        } else 0
        if (iconHeightPx <= 0) iconHeightPx = (20 * density).toInt()
        val moduleContext = runCatching {
            context.createPackageContext("com.takekazex.hypertweak", Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull()
        if (moduleContext != null) {
            singleSvgs.clear()
            stackedSvgs.clear()
            bundledStyles.forEachIndexed { index, (singleName, stackedName) ->
                if (singleName != null) {
                    readSvg(moduleContext, "$singleName-Single.svg")?.let { singleSvgs[index] = it }
                }
                if (stackedName != null) {
                    readSvg(moduleContext, "$stackedName-Stacked.svg")?.let { stackedSvgs[index] = it }
                }
            }
        }
        DebugLog.i(
            TAG,
            "StackedSignal ready: h=${iconHeightPx}px rtl=$rtl single=${singleSvgs.keys} stacked=${stackedSvgs.keys}"
        )
        // Render parameters (height, rtl) changed; any cached icon is stale.
        renderEpoch++
        // onHook may have run before package ready (SVGs missing then); install now.
        if (enabled && singleSvgs.isNotEmpty() && hooksInstalled.compareAndSet(false, true)) {
            runCatching { installHooks() }.onFailure { t ->
                hooksInstalled.set(false)
                DebugLog.e(TAG, "StackedSignal late install failed", t)
            }
        }
    }

    private fun readSvg(context: Context, name: String): StackedSignalRender.Doc? {
        val text = runCatching {
            context.assets.open("$SVG_DIR/$name").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return null
        return StackedSignalRender.parse(text)
    }

    override fun onPrepareHotReload() {
        enabled = false
        states.clear()
        defaultSubId.set(Int.MIN_VALUE)
        singleSvgs.clear()
        stackedSvgs.clear()
        subscriptionField = null
        isStackableField = null
        hooksInstalled.set(false)
        appContext = null
        resourceNameCache.clear()
        renderedCache.clear()
    }

    override fun onHook() {
        IconTunerFlows.init(classLoader)
        enabled = Preferences.getBoolean(Preferences.KEY_ICON_STACKED_ENABLED, false)
        singleStyle = Preferences.getInt(Preferences.KEY_ICON_STACKED_SVG_SINGLE, 0)
        stackedStyle = Preferences.getInt(Preferences.KEY_ICON_STACKED_SVG_STACKED, 0)
        scale = Preferences.getFloat(Preferences.KEY_ICON_STACKED_SCALE, 1f)
        paddingStart = Preferences.getFloat(Preferences.KEY_ICON_STACKED_PADDING_START, 0f)
        paddingEnd = Preferences.getFloat(Preferences.KEY_ICON_STACKED_PADDING_END, 0f)
        alphaFg = Preferences.getFloat(Preferences.KEY_ICON_STACKED_ALPHA_FG, 1f)
        alphaBg = Preferences.getFloat(Preferences.KEY_ICON_STACKED_ALPHA_BG, 1f)
        alphaError = Preferences.getFloat(Preferences.KEY_ICON_STACKED_ALPHA_ERROR, 1f)
        typeSizeDp = Preferences.getFloat(Preferences.KEY_ICON_STACKED_TYPE_SIZE, 11f)
        typeWeight = Preferences.getInt(Preferences.KEY_ICON_STACKED_TYPE_WEIGHT, 400)
        typePosition = Preferences.getInt(Preferences.KEY_ICON_STACKED_TYPE_POSITION, 0)
        showSingle = Preferences.getBoolean(Preferences.KEY_ICON_STACKED_SHOW_SINGLE, true)
        showStacked = Preferences.getBoolean(Preferences.KEY_ICON_STACKED_SHOW_STACKED, false)
        showRoaming = Preferences.getBoolean(Preferences.KEY_ICON_STACKED_SHOW_ROAMING, false)
        // Render parameters changed; any cached icon is stale.
        renderEpoch++
        if (!enabled) {
            DebugLog.hookSkipped(TAG, "StackedSignal", "disabled")
            return
        }
        if (singleSvgs.isEmpty() && stackedSvgs.isEmpty()) {
            DebugLog.w(TAG, "StackedSignal deferred: SVGs not loaded yet (package ready pending)")
            return
        }
        if (hooksInstalled.compareAndSet(false, true)) {
            runCatching { installHooks() }.onFailure { t ->
                hooksInstalled.set(false)
                DebugLog.e(TAG, "StackedSignal install failed", t)
            }
        }
    }

    private fun installHooks() {
        val iconsVmClass = ICONS_VM_CLASS.toClassOrNull()
        if (iconsVmClass == null) {
            DebugLog.hookSkipped(TAG, ICONS_VM_CLASS, "class not found")
            return
        }
        isStackableField = iconsVmClass.fieldOrNull("isStackable")

        val vmClass = VM_CLASS.toClassOrNull()
        if (vmClass == null) {
            DebugLog.hookSkipped(TAG, VM_CLASS, "class not found")
            return
        }
        subscriptionField = vmClass.fieldOrNull("subscriptionId")

        // 1. Force the stacked pipeline so SystemUI clears the ordinary mobile icons.
        if (isStackableField != null) {
            iconsVmClass.hookAllConstructors {
                after { param ->
                    val vm = param.thisObject ?: return@after
                    runCatching { IconTunerFlows.writeField(vm, isStackableField!!, IconTunerFlows.trueFlow) }
                        .onFailure { DebugLog.w(TAG, "isStackable write failed", it) }
                }
            }
        } else {
            DebugLog.hookSkipped(TAG, "$ICONS_VM_CLASS#isStackable", "field not found")
        }

        // 2. Replace each ViewModel's icon output with the module-drawn one.
        vmClass.findMethodOrNull { name("getIcon") }?.hook {
            after { param ->
                val vm = param.thisObject ?: return@after
                val subId: Int = runCatching { subscriptionField?.getInt(vm) }.getOrDefault(-1) ?: -1
                val sysIcon = param.result as? Icon ?: return@after
                val level = levelFromSystemIcon(sysIcon) ?: return@after
                if (defaultSubId.get() == Int.MIN_VALUE) defaultSubId.set(subId)
                val state = states.getOrPut(subId) { SignalState() }
                state.level = level
                param.result = buildIcon(subId, state)
            }
        } ?: DebugLog.hookSkipped(TAG, "$VM_CLASS#getIcon", "method not found")

        // 3. Capture the network type text from the system type icon resource.
        vmClass.findMethodOrNull { name("getNetworkTypeIcon") }?.hook {
            after { param ->
                val vm = param.thisObject ?: return@after
                val subId: Int = runCatching { subscriptionField?.getInt(vm) }.getOrDefault(-1) ?: -1
                val sysIcon = param.result as? Icon ?: return@after
                val type = typeFromSystemIcon(sysIcon) ?: return@after
                val state = states.getOrPut(subId) { SignalState() }
                state.type = type
            }
        } ?: DebugLog.hookSkipped(TAG, "$VM_CLASS#getNetworkTypeIcon", "method not found")

        // 4. The module-drawn icon replaces the whole signal, so the separate data-activity
        //    arrows (in/out) bound next to it are suppressed.
        val cellClass = "com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MiuiCellularIconVM"
            .toClassOrNull()
        if (cellClass != null) {
            cellClass.findMethodOrNull { name("getInOutVisible") }?.hook {
                before { param -> param.result = IconTunerFlows.falseFlow }
            } ?: DebugLog.hookSkipped(TAG, "$cellClass#getInOutVisible", "method not found")
        } else {
            DebugLog.hookSkipped(TAG, "MiuiCellularIconVM", "class not found")
        }
    }

    private fun levelFromSystemIcon(icon: Icon): Int? {
        val resId = runCatching { icon.resId }.getOrDefault(0)
        if (resId == 0) return null
        val name = resourceNameOf(resId) ?: return null
        val prefix = "stat_sys_signal_"
        if (!name.startsWith(prefix)) return null
        val digits = name.removePrefix(prefix).takeWhile { it.isDigit() }
        return digits.toIntOrNull()
    }

    private fun typeFromSystemIcon(icon: Icon): String? {
        val resId = runCatching { icon.resId }.getOrDefault(0)
        if (resId == 0) return null
        val name = resourceNameOf(resId) ?: return null
        return when {
            "5ga" in name || "5g_a" in name -> "5GA"
            "5g" in name -> if ("plus" in name) "5G+" else "5G"
            "4g" in name || "lte" in name -> if ("plus" in name) "4G+" else "4G"
            "3g" in name -> "3G"
            else -> null
        }
    }

    private fun buildIcon(subId: Int, state: SignalState): Icon? {
        val stacked = showStacked && states.size > 1
        val doc = if (stacked) {
            stackedSvgs[stackedStyle] ?: stackedSvgs[0] ?: return null
        } else {
            if (!showSingle) return null
            singleSvgs[singleStyle] ?: singleSvgs[0] ?: return null
        }
        val height = (iconHeightPx * scale).toInt().coerceAtLeast(1)
        val other = if (stacked) states.keys.firstOrNull { it != defaultSubId.get() } else null
        val row1Level = state.level
        val row2Level = other?.let { states[it]?.level } ?: 0
        val typeText = if (subId == defaultSubId.get()) state.type else ""

        val key = RenderKey(renderEpoch, stacked, row1Level, row2Level, typeText)
        renderedCache[key]?.let { return Icon.createWithBitmap(it) }
        val composed = renderIcon(doc, stacked, row1Level, row2Level, height, typeText) ?: return null
        if (renderedCache.size >= MAX_RENDER_CACHE_ENTRIES) renderedCache.clear()
        renderedCache[key] = composed
        return Icon.createWithBitmap(composed)
    }

    private fun renderIcon(
        doc: StackedSignalRender.Doc,
        stacked: Boolean,
        row1Level: Int,
        row2Level: Int,
        height: Int,
        typeText: String
    ): android.graphics.Bitmap? {
        val bars: android.graphics.Bitmap = if (stacked) {
            // Default SIM on row 1, the other SIM on row 2.
            val b1 = StackedSignalRender.renderBars(doc, 1, row1Level, height / 2, alphaFg) ?: return null
            val b2 = StackedSignalRender.renderBars(doc, 2, row2Level, height / 2, alphaBg) ?: return null
            val out = android.graphics.Bitmap.createBitmap(
                maxOf(b1.width, b2.width), height, android.graphics.Bitmap.Config.ALPHA_8
            )
            val canvas = android.graphics.Canvas(out)
            canvas.drawBitmap(b1, 0f, 0f, null)
            canvas.drawBitmap(b2, 0f, height / 2f, null)
            out
        } else {
            StackedSignalRender.renderBars(doc, 0, row1Level, height, alphaFg) ?: return null
        }
        val density = appResources()?.displayMetrics?.density ?: 1f
        val typeBmp = StackedSignalRender.renderTypeText(
            typeText, (typeSizeDp * density).toInt().coerceAtLeast(1), typeWeight, alphaFg
        )
        val position = StackedSignalRender.TypePosition.entries[typePosition.coerceIn(0, 2)]
        return StackedSignalRender.compose(
            bars, typeBmp, position, doc.anchor, doc.width, rtl,
            (paddingStart * density).toInt(), (paddingEnd * density).toInt()
        )
    }

    private fun Class<*>.fieldOrNull(name: String): Field? =
        runCatching { getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
}
