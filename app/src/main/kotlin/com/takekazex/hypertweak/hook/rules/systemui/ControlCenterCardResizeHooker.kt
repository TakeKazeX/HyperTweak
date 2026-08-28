package com.takekazex.hypertweak.hook.rules.systemui

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.CompatibleMethodResolver
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.base.HotReloadMode
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/**
 * Control-center element sizes (控制中心尺寸).
 *
 * The OS4 main panel is a 4-column `GridLayoutManager`; an item's width comes solely from its
 * `getSpanSize()` (clamped to `[1, spanCount]` by the adapter's span lookup), and every item type
 * carries a fixed height in its own layout:
 *
 * - big cards (`QSCardItemView`): `qs_card_item_height` (1 row), re-applied by `updateSize()`
 *   on configuration changes;
 * - brightness/volume sliders (`ToggleSliderItemViewBinding`): `control_center_universal_2_rows_size`
 *   (2 rows);
 * - media player (`MediaPlayerPanel`): width AND height are both the fixed 2-rows dimen — the
 *   width must be forced to MATCH_PARENT whenever the span is customized;
 * - device center entry: 1 row;
 * - small tiles: fixed tile height (not resized here).
 *
 * So "resizing" = overriding `getSpanSize()` for the columns and stamping
 * `layoutParams.height` (+ MATCH_PARENT width for media) after each bind. Heights are derived at
 * runtime from the host dimens (`1_row_size`, `2_rows_size`) as `rows*unit + (rows-1)*gap`, so
 * presets stay aligned with the stock grid on every device/configuration.
 *
 * Quick switches can additionally be rendered with the big-card view: `QSRecord.getType()` is
 * forced to a module-private item type for the configured specs, which makes the adapter skip the
 * stock tile holder creation; a before-hook on `QSListController.createViewHolder` then builds a
 * real `QSCardItemView` (binding inflate + `init(QSCardItemIconView)` exactly like
 * `QSCardsController.createViewHolder`) wrapped in the stock public `QSCardViewHolder`. A distinct
 * item type means RecyclerView keeps those holders in their own recycle pool, so a card view can
 * never be rebound onto a normal tile record. Everything downstream is view-type agnostic:
 * `QSRecord` binds through the `QSItemView` interface, mode/style updates delegate through the
 * holder overrides, and long-press routes via the record itself.
 *
 * Live apply: sizes are read on every bind, but holders only rebind when the distributor
 * refreshes. An after-hook on `MainPanelAdapter.changeItemVisible` (fires on every shade expand)
 * compares a signature of all size keys and issues one `notifyChanged` after a settings change,
 * so edits apply the next time the panel opens without a SystemUI restart. The hooks themselves
 * install at plugin load, so the master switch still needs one restart to turn on.
 */
class ControlCenterCardResizeHooker : DynamicHooker() {
    override val hotReloadMode = HotReloadMode.RECREATE

    private companion object {
        const val TAG = "HyperTweak"
        const val ADAPTER_NAME =
            "miui.systemui.controlcenter.panel.main.recyclerview.MainPanelAdapter"
        const val HOLDER_NAME =
            "miui.systemui.controlcenter.panel.main.recyclerview.MainPanelItemViewHolder"
        const val RECORD_NAME = "miui.systemui.controlcenter.panel.main.qs.QSRecord"

        /**
         * Module-private adapter item type for quick switches rendered as cards. Any value outside
         * the stock types (8453 / 2273 / dividers) works: `QSListController.createViewHolder`
         * returns null for it, so our before-hook must answer it, and RecyclerView recycles these
         * holders separately from both stock pools.
         */
        const val TYPE_TILE_AS_CARD = 4242

        const val DIMEN_ROW_UNIT = "control_center_universal_1_row_size"
        const val DIMEN_TWO_ROWS = "control_center_universal_2_rows_size"
        const val CARD_HEIGHT_DIMEN = "qs_card_item_height"
    }

    /**
     * Desired card heights keyed by item view, consumed by the `QSCardItemView.updateSize`
     * after-hook (configuration changes re-run `updateSize()` directly on the view, bypassing any
     * rebind, so the override has to be restamped there). Entries are rewritten on every bind, so
     * recycled views can never keep another record's height.
     */
    private val desiredCardHeights = WeakHashMap<View, Int>()

    /** Host dimen ids resolved once from the plugin's R class (0 = not available). */
    @Volatile
    private var rowUnitDimenId: Int = -1

    @Volatile
    private var twoRowsDimenId: Int = -1

    @Volatile
    private var cardHeightDimenId: Int = -1

    /** Signature of all size prefs at the last applied refresh; null forces the next apply. */
    @Volatile
    private var lastAppliedSignature: String? = null

    override fun onHook() {
        installSpanHooks()
        installTileCardTypeHook()
        installTileCardCreateHook()
        installBindSizeHook()
        installCardUpdateSizeHook()
        installTextModeSync()
        installLiveApplyHook()
        Log.i(TAG, "ControlCenterResize: hooks installed")
    }

    private fun enabled(): Boolean =
        Preferences.getBoolean(Preferences.KEY_CC_RESIZE_ENABLED, false)

    // ─── Size parsing ──────────────────────────────────────────────────────────

    /** Parses `CxR` (columns × rows); null when absent or malformed. */
    private fun parseSize(raw: String?): Pair<Int, Int>? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.trim().lowercase().split("x")
        if (parts.size != 2) return null
        val cols = parts[0].trim().toIntOrNull() ?: return null
        val rows = parts[1].trim().toIntOrNull() ?: return null
        if (cols !in 1..4 || rows !in 1..4) return null
        return cols to rows
    }

    /** Reads the per-card map into spec → (cols, rows). */
    private fun cardSizes(): Map<String, Pair<Int, Int>> {
        val raw = Preferences.getString(Preferences.KEY_CC_CARD_SIZES, "")
        if (raw.isBlank()) return emptyMap()
        return raw.split(',').mapNotNull { entry ->
            val idx = entry.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val spec = entry.substring(0, idx).trim().lowercase()
            val size = parseSize(entry.substring(idx + 1)) ?: return@mapNotNull null
            spec to size
        }.toMap()
    }

    private fun tileCardSpecs(): Set<String> =
        Preferences.getString(Preferences.KEY_CC_TILE_CARD_SPECS, "")
            .split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    // ─── Spans ─────────────────────────────────────────────────────────────────

    /**
     * Card spans: per-spec overrides from [Preferences.KEY_CC_CARD_SIZES], plus the fixed 2-column
     * span for converted quick-switch tiles. This hooker owns card spans entirely — the legacy
     * editor hooker force-yields once this master switch is on (see
     * [ControlCenterCardsEditHooker.installCardSpanHook]).
     */
    private fun installSpanHooks() {
        val record = RECORD_NAME.toClassOrNull() ?: run {
            Log.w(TAG, "ControlCenterResize: QSRecord not found")
            return
        }
        val specGetter = CompatibleMethodResolver.find(record, "getSpec") ?: return
        val isCard = CompatibleMethodResolver.find(
            record, "isCard", returnType = Boolean::class.javaPrimitiveType
        )
        val span = CompatibleMethodResolver.find(
            record, "getSpanSize", returnType = Int::class.javaPrimitiveType
        ) ?: return
        span.hook {
            before { param ->
                if (!enabled()) return@before
                runCatching {
                    val spec = specGetter.invoke(param.thisObject)?.toString()?.lowercase()
                        ?: return@runCatching
                    val card = isCard?.invoke(param.thisObject) as? Boolean ?: false
                    if (!card) {
                        if (spec in tileCardSpecs()) param.result = 2
                        return@runCatching
                    }
                    // First component = span columns; the adapter clamps to [1, spanCount].
                    cardSizes()[spec]?.let { size -> param.result = size.first }
                }.onFailure { t -> Log.e(TAG, "ControlCenterResize: card span", t) }
            }
        }

        // Fixed contents: media player / brightness / volume / device center expose getSpanSize
        // backed by fields or style logic; the override only applies while a size is configured.
        val targets = listOf(
            "miui.systemui.controlcenter.panel.main.media.MediaPlayerController" to
                Preferences.KEY_CC_SIZE_MEDIA,
            "miui.systemui.controlcenter.panel.main.brightness.BrightnessSliderController" to
                Preferences.KEY_CC_SIZE_BRIGHTNESS,
            "miui.systemui.controlcenter.panel.main.volume.VolumeSliderController" to
                Preferences.KEY_CC_SIZE_VOLUME,
            "miui.systemui.controlcenter.panel.main.devicecenter.entry.DeviceCenterEntryController" to
                Preferences.KEY_CC_SIZE_DEVICE
        )
        var installed = 0
        targets.forEach { (name, key) ->
            val cls = name.toClassOrNull() ?: return@forEach
            val getter = CompatibleMethodResolver.find(
                cls, "getSpanSize", returnType = Int::class.javaPrimitiveType
            ) ?: return@forEach
            getter.hook {
                before { param ->
                    if (!enabled()) return@before
                    runCatching {
                        val cols = parseSize(Preferences.getString(key, ""))?.first ?: 0
                        if (cols > 0) param.result = cols
                    }
                }
            }
            installed++
        }
        Log.i(TAG, "ControlCenterResize: span hooks installed=$installed")
    }

    // ─── Quick-switch tiles rendered as big cards ──────────────────────────────

    /** Forces configured tile records onto the module-private card item type. */
    private fun installTileCardTypeHook() {
        val record = RECORD_NAME.toClassOrNull() ?: return
        val specGetter = CompatibleMethodResolver.find(record, "getSpec") ?: return
        val isCard = CompatibleMethodResolver.find(
            record, "isCard", returnType = Boolean::class.javaPrimitiveType
        )
        val getType = CompatibleMethodResolver.find(
            record, "getType", returnType = Int::class.javaPrimitiveType
        ) ?: return
        getType.hook {
            before { param ->
                if (!enabled()) return@before
                runCatching {
                    val card = isCard?.invoke(param.thisObject) as? Boolean ?: return@runCatching
                    if (card) return@runCatching
                    val spec = specGetter.invoke(param.thisObject)?.toString()?.lowercase()
                        ?: return@runCatching
                    if (spec in tileCardSpecs()) param.result = TYPE_TILE_AS_CARD
                }.onFailure { t -> Log.e(TAG, "ControlCenterResize: card type", t) }
            }
        }
    }

    /**
     * Answers the module-private card item type inside `QSListController.createViewHolder` by
     * building the exact same view/holder pair `QSCardsController.createViewHolder` builds for
     * real cards: `QsCardItemViewBinding.inflate` + `root.init(QSCardItemIconView(...))` +
     * `new QSCardViewHolder(root)`.
     */
    private fun installTileCardCreateHook() {
        val listController =
            "miui.systemui.controlcenter.panel.main.qs.QSListController".toClassOrNull() ?: return
        val create = listController.declaredMethods.firstOrNull {
            it.name == "createViewHolder" && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == ViewGroup::class.java &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType
        } ?: return
        create.hook {
            before { param ->
                if (!enabled()) return@before
                if ((param.args[1] as? Int) != TYPE_TILE_AS_CARD) return@before
                runCatching {
                    buildCardHolder(param.args[0] as ViewGroup, param.thisObject)
                }.onSuccess { holder ->
                    param.result = holder
                }.onFailure { t ->
                    Log.e(TAG, "ControlCenterResize: build card holder", t)
                }
            }
        }
    }

    private fun buildCardHolder(parent: ViewGroup, listController: Any): Any {
        val context = parent.context
        val bindingCls =
            "miui.systemui.controlcenter.databinding.QsCardItemViewBinding".toClass()
        val binding = bindingCls.getDeclaredMethod(
            "inflate",
            LayoutInflater::class.java,
            ViewGroup::class.java,
            Boolean::class.javaPrimitiveType
        ).invoke(null, LayoutInflater.from(context), parent, false)
        val root = bindingCls.getMethod("getRoot").invoke(binding) as View

        val iconCls = "miui.systemui.controlcenter.qs.tileview.QSCardItemIconView".toClass()
        val sysUIContext = findField(listController.javaClass, "sysUIContext")
            ?.get(listController) as? Context ?: context
        val icon = newCardIconView(iconCls, context, sysUIContext)
        root.javaClass.getMethod("init", iconCls).invoke(root, icon)

        val holderCls = "miui.systemui.controlcenter.panel.main.qs.QSCardViewHolder".toClass()
        val qsItemViewCls = "miui.systemui.controlcenter.qs.tileview.QSItemView".toClass()
        return holderCls.getConstructor(qsItemViewCls).newInstance(root)
    }

    /**
     * Mirrors `new QSCardItemIconView(context, sysUIContext, null, 4, null)` from
     * QSCardsController: the Kotlin synthetic-defaults constructor takes the mask int plus the
     * trailing DefaultConstructorMarker; the plain 3-arg constructor is the fallback.
     */
    private fun newCardIconView(iconCls: Class<*>, context: Context, sysUIContext: Context): Any {
        val synthetic = iconCls.declaredConstructors.firstOrNull { ctor ->
            ctor.parameterTypes.size == 5 &&
                ctor.parameterTypes[0] == Context::class.java &&
                ctor.parameterTypes[1] == Context::class.java
        }
        if (synthetic != null) {
            synthetic.isAccessible = true
            return synthetic.newInstance(context, sysUIContext, null, 4, null)
        }
        val plain = iconCls.getConstructor(
            Context::class.java, Context::class.java, android.util.AttributeSet::class.java
        )
        return plain.newInstance(context, sysUIContext, null)
    }

    /** 无字模式 sync for converted tiles (qslist only notifies `QSItemViewHolder` holders). */
    private fun installTextModeSync() {
        val listController =
            "miui.systemui.controlcenter.panel.main.qs.QSListController".toClassOrNull() ?: return
        val onBind = listController.declaredMethods.firstOrNull {
            it.name == "onBindViewHolder" && it.parameterTypes.size == 2
        } ?: return
        val textModeField = findField(listController, "textMode") ?: return
        val cardHolderCls =
            "miui.systemui.controlcenter.panel.main.qs.QSCardViewHolder".toClassOrNull() ?: return
        val textModeSetter = cardHolderCls.declaredMethods.firstOrNull {
            it.name == "onTextModeChanged" && it.parameterTypes.size == 2
        }?.apply { isAccessible = true } ?: return
        onBind.hook {
            after { param ->
                if (!enabled()) return@after
                runCatching {
                    val holder = param.args.getOrNull(0) ?: return@runCatching
                    if (!cardHolderCls.isInstance(holder)) return@runCatching
                    val textMode = textModeField.get(param.thisObject) ?: return@runCatching
                    textModeSetter.invoke(holder, textMode, false)
                }.onFailure { t -> Log.e(TAG, "ControlCenterResize: text mode", t) }
            }
        }
    }

    // ─── Height / width stamping ───────────────────────────────────────────────

    /** Applies configured heights (and the media width) after every bind of a managed holder. */
    private fun installBindSizeHook() {
        val adapter = ADAPTER_NAME.toClassOrNull() ?: return
        val holderCls = HOLDER_NAME.toClassOrNull() ?: return
        val onBind = adapter.declaredMethods.firstOrNull {
            it.name == "onBindViewHolder" && it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == holderCls
        } ?: adapter.declaredMethods.firstOrNull {
            it.name == "onBindViewHolder" && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == holderCls
        } ?: return
        val ownerMethod = holderCls.methods.firstOrNull {
            it.name == "getOwner" && it.parameterCount == 0
        } ?: return
        val itemGetter = holderCls.methods.firstOrNull {
            it.name == "getItem\$miui_controlcenter_release" && it.parameterCount == 0
        } ?: holderCls.methods.firstOrNull {
            it.name.startsWith("getItem") && it.parameterCount == 0 &&
                it.returnType != Int::class.javaPrimitiveType &&
                it.returnType.name.endsWith("MainPanelListItem")
        }
        val specGetter = RECORD_NAME.toClassOrNull()?.let {
            CompatibleMethodResolver.find(it, "getSpec")
        }
        onBind.hook {
            after { param ->
                runCatching {
                    val holder = param.args.getOrNull(0) ?: return@runCatching
                    // MainPanelItemViewHolder extends RecyclerView.ViewHolder, whose itemView is
                    // the bound root view.
                    val itemView = holder as? View ?: return@runCatching
                    val owner = ownerMethod.invoke(holder) ?: return@runCatching
                    val lp = itemView.layoutParams ?: return@runCatching
                    val spec = itemGetter?.let { runCatching { it.invoke(holder) }.getOrNull() }
                        ?.let { item -> specGetter?.invoke(item)?.toString()?.lowercase() }
                    var changed = false
                    when (contentKey(owner)) {
                        "brightness" -> changed =
                            applyConfiguredRows(itemView, lp, Preferences.KEY_CC_SIZE_BRIGHTNESS)
                        "volume" -> changed =
                            applyConfiguredRows(itemView, lp, Preferences.KEY_CC_SIZE_VOLUME)
                        "devicecenter" -> changed =
                            applyConfiguredRows(itemView, lp, Preferences.KEY_CC_SIZE_DEVICE)
                        "media" -> {
                            val size = parseSize(Preferences.getString(Preferences.KEY_CC_SIZE_MEDIA, ""))
                            if (size != null) {
                                // The XML root is a fixed-width square; a custom span must widen
                                // with the grid or the view stays narrow inside its cell.
                                if (lp.width != LayoutParams.MATCH_PARENT) {
                                    lp.width = LayoutParams.MATCH_PARENT
                                    changed = true
                                }
                                changed = setLpHeight(lp, rowHeight(itemView.resources, size.second)) || changed
                            } else {
                                changed = restoreStockSize(itemView, lp) || changed
                            }
                        }
                        "qscards" -> {
                            val rows = spec?.let { s -> cardSizes()[s]?.second } ?: 0
                            if (rows > 0) {
                                val px = rowHeight(itemView.resources, rows)
                                desiredCardHeights[itemView] = px
                                stampedViews[itemView] = true
                                changed = setLpHeight(lp, px) || changed
                            } else {
                                desiredCardHeights.remove(itemView)
                                changed = restoreStockHeight(itemView, lp, CARD_HEIGHT_DIMEN) || changed
                            }
                        }
                    }
                    if (changed) itemView.requestLayout()
                }.onFailure { t -> Log.e(TAG, "ControlCenterResize: bind sizes", t) }
            }
        }
    }

    /** Stamps the configured `CxR` height onto [lp]; returns true when it changed. */
    private fun applyConfiguredRows(view: View, lp: LayoutParams, key: String): Boolean {
        val rows = parseSize(Preferences.getString(key, ""))?.second ?: 0
        if (rows > 0) {
            stampedViews[view] = true
            return setLpHeight(lp, rowHeight(view.resources, rows))
        }
        // Back to "follow system": undo a previous stamp with the section's stock height.
        return restoreStockHeight(view, lp, stockDimenForKey(key))
    }

    private fun stockDimenForKey(key: String): String = when (key) {
        Preferences.KEY_CC_SIZE_DEVICE -> DIMEN_ROW_UNIT
        else -> DIMEN_TWO_ROWS // sliders stock at 2 rows
    }

    /**
     * Views we ever restamped, so "follow system" can restore the layout XML's fixed dimension
     * instead of leaving the previous custom size on a recycled holder forever.
     */
    private val stampedViews = WeakHashMap<View, Boolean>()

    private fun restoreStockSize(view: View, lp: LayoutParams): Boolean {
        var changed = restoreStockHeight(view, lp, DIMEN_TWO_ROWS)
        if (lp.width == LayoutParams.MATCH_PARENT) {
            val stock = dimenPx(view.resources, DIMEN_TWO_ROWS)
            if (stock > 0) {
                lp.width = stock
                changed = true
            }
        }
        return changed
    }

    /** Restores the named stock dimen when this view was previously stamped; else false. */
    private fun restoreStockHeight(view: View, lp: LayoutParams, dimenName: String?): Boolean {
        if (stampedViews.remove(view) != true) return false
        val px = dimenName?.let { dimenPx(view.resources, it) } ?: 0
        if (px <= 0 || lp.height == px) return false
        lp.height = px
        return true
    }

    private fun dimenPx(res: android.content.res.Resources, name: String): Int {
        if (rowUnitDimenId < 0 || twoRowsDimenId < 0 || cardHeightDimenId < 0) resolveDimenIds()
        val id = when (name) {
            DIMEN_ROW_UNIT -> rowUnitDimenId
            DIMEN_TWO_ROWS -> twoRowsDimenId
            CARD_HEIGHT_DIMEN -> cardHeightDimenId
            else -> -1
        }
        return if (id > 0) res.getDimensionPixelSize(id) else 0
    }

    /**
     * Configuration changes re-run `QSCardItemView.updateSize()` directly on the view without a
     * rebind, so the card height override must be restamped here.
     */
    private fun installCardUpdateSizeHook() {
        val cardViewCls = "miui.systemui.controlcenter.qs.tileview.QSCardItemView".toClassOrNull()
            ?: return
        val updateSize = CompatibleMethodResolver.find(cardViewCls, "updateSize") ?: return
        updateSize.hook {
            after { param ->
                if (!enabled()) return@after
                runCatching {
                    val view = param.thisObject as? View ?: return@runCatching
                    val desired = desiredCardHeights[view] ?: return@runCatching
                    val lp = view.layoutParams ?: return@runCatching
                    if (setLpHeight(lp, desired)) view.requestLayout()
                }.onFailure { t -> Log.e(TAG, "ControlCenterResize: updateSize", t) }
            }
        }
    }

    // ─── Live apply ────────────────────────────────────────────────────────────

    /**
     * One adapter refresh after the size config changed, issued when the panel becomes visible
     * again (changeItemVisible runs on every shade expand). Steady state costs one string compare.
     * The refresh is posted off the current call stack like the stock tile mover
     * (`distributor.notifyChanged` posts MSG_NOTIFY_CHANGED), so it never lands mid-layout.
     */
    private fun installLiveApplyHook() {
        val adapter = ADAPTER_NAME.toClassOrNull() ?: return
        val changeVisible = adapter.declaredMethods.firstOrNull {
            it.name == "changeItemVisible" && it.parameterTypes.size == 3 &&
                it.parameterTypes.all { p -> p == Boolean::class.javaPrimitiveType }
        } ?: return
        val notifyChanged = adapter.methods.firstOrNull {
            it.name == "notifyChanged" && it.parameterTypes.size == 2 &&
                it.parameterTypes.all { p -> p == Boolean::class.javaPrimitiveType }
        }?.apply { isAccessible = true } ?: return
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        changeVisible.hook {
            after { param ->
                runCatching {
                    if (!enabled()) {
                        lastAppliedSignature = null
                        return@runCatching
                    }
                    val signature = configSignature()
                    if (signature == lastAppliedSignature) return@runCatching
                    lastAppliedSignature = signature
                    val adapterInstance = param.thisObject
                    mainHandler.post {
                        runCatching { notifyChanged.invoke(adapterInstance, false, false) }
                            .onFailure { t -> Log.e(TAG, "ControlCenterResize: live refresh", t) }
                    }
                }.onFailure { t -> Log.e(TAG, "ControlCenterResize: live apply", t) }
            }
        }
    }

    private fun configSignature(): String = listOf(
        Preferences.getString(Preferences.KEY_CC_CARD_SIZES, ""),
        Preferences.getString(Preferences.KEY_CC_SIZE_MEDIA, ""),
        Preferences.getString(Preferences.KEY_CC_SIZE_BRIGHTNESS, ""),
        Preferences.getString(Preferences.KEY_CC_SIZE_VOLUME, ""),
        Preferences.getString(Preferences.KEY_CC_SIZE_DEVICE, ""),
        Preferences.getString(Preferences.KEY_CC_TILE_CARD_SPECS, "")
    ).joinToString("|")

    // ─── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Height of `rows` grid rows in px, derived from the host dimens:
     * `H(n) = n * unit + (n-1) * gap` with `gap = twoRows − 2*unit` (14dp stock), matching the
     * stock 1-row (71.5dp) and 2-row (157dp) heights exactly.
     */
    private fun rowHeight(res: android.content.res.Resources, rows: Int): Int {
        if (rowUnitDimenId < 0 || twoRowsDimenId < 0) resolveDimenIds()
        val unit = if (rowUnitDimenId > 0) res.getDimensionPixelSize(rowUnitDimenId) else 0
        val twoRows = if (twoRowsDimenId > 0) res.getDimensionPixelSize(twoRowsDimenId) else 0
        if (unit <= 0) return 0
        val gap = if (twoRows > unit * 2) twoRows - unit * 2 else unit / 5
        return unit * rows + gap * (rows - 1)
    }

    private fun resolveDimenIds() {
        runCatching {
            val dimenCls =
                "miui.systemui.controlcenter.R\$dimen".toClassOrNull() ?: return@runCatching
            rowUnitDimenId = staticInt(dimenCls, DIMEN_ROW_UNIT)
            twoRowsDimenId = staticInt(dimenCls, DIMEN_TWO_ROWS)
            cardHeightDimenId = staticInt(dimenCls, CARD_HEIGHT_DIMEN)
        }.onFailure { t -> Log.w(TAG, "ControlCenterResize: resolve dimens", t) }
    }

    private fun staticInt(cls: Class<*>, name: String): Int =
        runCatching {
            val field = cls.getDeclaredField(name)
            field.isAccessible = true
            Modifier.isStatic(field.modifiers)
            field.getInt(null)
        }.getOrDefault(-1)

    private fun setLpHeight(lp: LayoutParams, px: Int): Boolean {
        if (px <= 0 || lp.height == px) return false
        lp.height = px
        return true
    }

    private fun findField(cls: Class<*>, name: String): Field? =
        runCatching {
            var type: Class<*>? = cls
            while (type != null && type != Any::class.java) {
                try {
                    return type.getDeclaredField(name).apply { isAccessible = true }
                } catch (_: NoSuchFieldException) {
                    type = type.superclass
                }
            }
            null
        }.getOrNull()

    private fun contentKey(content: Any): String {
        val name = content.javaClass.simpleName.lowercase()
        return when {
            name.contains("qscards") -> "qscards"
            name.contains("mediaplayer") -> "media"
            name.contains("brightness") -> "brightness"
            name.contains("volume") -> "volume"
            name.contains("devicecenter") -> "devicecenter"
            name.contains("qslist") -> "qslist"
            else -> name.replace("controller", "").replace("content", "")
        }
    }
}
