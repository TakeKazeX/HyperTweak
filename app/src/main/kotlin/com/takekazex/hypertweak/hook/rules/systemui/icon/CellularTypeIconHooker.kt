package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.TextView
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Field
import java.util.IdentityHashMap
import kotlin.math.ceil

/**
 * Cellular type display, ported from Hyper Helper's `CellularTypeIcon` (OS4_ADAPTATION_PLAN.md
 * T4a). The operator config hook preserves the original 15-entry mapping contract; the binder
 * hook covers single-row order/size, while the native drawable and single-label paths handle their
 * own 5GA suffix formatting alongside the optional font overrides.
 */
object CellularTypeIconHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "IconTuner"
    private const val POLICY_CLASS = "com.android.systemui.MiuiOperatorCustomizedPolicy"
    private const val CONFIG_CLASS = "com.miui.interfaces.IOperatorCustomizedPolicy\$OperatorConfig"
    private const val DRAWABLE_CLASS = "com.miui.systemui.statusbar.views.MobileTypeDrawable"
    private const val BINDER_CLASS = "com.android.systemui.statusbar.pipeline.mobile.ui.binder.MiuiMobileIconBinder"

    @Volatile private var forceSingle = false
    @Volatile private var swapSingle = false
    @Volatile private var singleSizeEnabled = false
    @Volatile private var singleSize = 14f
    @Volatile private var customType = ""
    @Volatile private var useCustom = false
    @Volatile private var small5GaEnabled = false
    @Volatile private var mobileTypeSingleViewId = 0
    @Volatile private var drawableFontEnabled = false
    @Volatile private var drawableWeight = 660
    @Volatile private var singleFontEnabled = false
    @Volatile private var singleWeight = 400

    private var typeface: Typeface? = null
    private var singleTypeface: Typeface? = null
    private val nativeDrawStates = ThreadLocal.withInitial {
        IdentityHashMap<Any, NativeDrawState>()
    }
    private val nativeSmallSuffixPaint = ThreadLocal.withInitial {
        Paint(Paint.ANTI_ALIAS_FLAG)
    }

    private data class NativeDrawState(
        val originalLabel: String,
        val parts: SmallMobileTypeLabel
    )

    private data class NativeDrawableFields(
        val label: Field,
        val mainPaint: Field,
        val suffixPaint: Field,
        val width: Field,
        val height: Field,
        val extraWidth: Field,
        val doublePlus: Field
    )

    override fun onPrepareHotReload() {
        forceSingle = false
        swapSingle = false
        singleSizeEnabled = false
        singleSize = 14f
        customType = ""
        useCustom = false
        small5GaEnabled = false
        mobileTypeSingleViewId = 0
        drawableFontEnabled = false
        drawableWeight = 660
        singleFontEnabled = false
        singleWeight = 400
        typeface = null
        singleTypeface = null
        nativeDrawStates.remove()
        nativeSmallSuffixPaint.remove()
    }

    override fun onHook() {
        forceSingle = Preferences.getBoolean(Preferences.KEY_ICON_CELLULAR_TYPE_SINGLE, false)
        swapSingle = Preferences.getBoolean(Preferences.KEY_ICON_CELLULAR_TYPE_SINGLE_SWAP, false)
        singleSizeEnabled = Preferences.getBoolean(Preferences.KEY_ICON_CELLULAR_TYPE_SINGLE_SIZE, false)
        singleSize = Preferences.getFloat(
            Preferences.KEY_ICON_CELLULAR_TYPE_SINGLE_SIZE_VAL,
            14f
        ).takeIf(Float::isFinite)?.coerceIn(1f, 48f) ?: 14f
        useCustom = Preferences.getBoolean(Preferences.KEY_ICON_CELLULAR_TYPE_CUSTOM, false)
        customType = Preferences.getString(Preferences.KEY_ICON_CELLULAR_TYPE_CUSTOM_VAL, "").trim()
        small5GaEnabled = Preferences.getBoolean(
            Preferences.KEY_ICON_CELLULAR_TYPE_SMALL_5GA,
            false
        )
        drawableFontEnabled = Preferences.getBoolean(Preferences.KEY_ICON_FONT_MOBILE_TYPE, false)
        drawableWeight = Preferences.getInt(
            Preferences.KEY_ICON_FONT_MOBILE_TYPE_WEIGHT,
            660
        ).coerceIn(100, 900)
        singleFontEnabled = Preferences.getBoolean(
            Preferences.KEY_ICON_FONT_MOBILE_TYPE_SINGLE,
            false
        )
        singleWeight = Preferences.getInt(
            Preferences.KEY_ICON_FONT_MOBILE_TYPE_SINGLE_WEIGHT,
            400
        ).coerceIn(100, 900)

        val configEnabled = forceSingle || useCustom
        val binderEnabled = swapSingle || singleSizeEnabled || singleFontEnabled
        if (!configEnabled && !binderEnabled && !drawableFontEnabled && !small5GaEnabled) {
            DebugLog.hookSkippedDebug(TAG, "CellularTypeIcon", "disabled")
            return
        }
        if (configEnabled) hookOperatorConfig()
        if (binderEnabled) hookBinder()
        if (drawableFontEnabled) hookDrawableFont()
        if (small5GaEnabled) {
            hookSmall5GaTextView()
            hookSmall5GaDrawable()
        }
        DebugLog.hookRegistered(
            TAG,
            "CellularType: single=$forceSingle swap=$swapSingle size=$singleSizeEnabled font=$drawableFontEnabled small5GA=$small5GaEnabled"
        )
    }

    /** Styles the separate single-SIM label, scoped to the host's mobile_type_single view id. */
    private fun hookSmall5GaTextView() {
        val setText = runCatching {
            TextView::class.java.getMethod(
                "setText",
                CharSequence::class.java,
                TextView.BufferType::class.java
            )
        }.getOrElse {
            DebugLog.hookSkipped(TAG, "TextView#setText(CharSequence,BufferType)", "method not found")
            return
        }
        setText.hook {
            before { param ->
                runCatching {
                    if (!small5GaEnabled) return@runCatching
                    val view = param.thisObject as? TextView ?: return@runCatching
                    val viewId = resolveSingleTypeViewId(view)
                    if (viewId == 0 || view.id != viewId) return@runCatching
                    val label = param.args.getOrNull(0) as? CharSequence ?: return@runCatching
                    val parts = MobileTypeLabelStyle.small5GaParts(label.toString())
                        ?: return@runCatching
                    val styled = SpannableString(parts.baseText + parts.suffixText)
                    styled.setSpan(
                        RelativeSizeSpan(MobileTypeLabelStyle.SMALL_5GA_SUFFIX_SCALE),
                        parts.baseText.length,
                        parts.baseText.length + parts.suffixText.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    param.args[0] = styled
                }.onFailure { DebugLog.w(TAG, "single 5GA label styling failed", it) }
            }
        }
    }

    private fun resolveSingleTypeViewId(view: TextView): Int {
        mobileTypeSingleViewId.takeIf { it != 0 }?.let { return it }
        val id = runCatching {
            view.resources.getIdentifier("mobile_type_single", "id", "com.android.systemui")
        }.getOrDefault(0)
        if (id != 0) mobileTypeSingleViewId = id
        return id
    }

    /** Splits the native drawable label during draw and adjusts its intrinsic width after measure. */
    private fun hookSmall5GaDrawable() {
        val drawableClass = DRAWABLE_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, DRAWABLE_CLASS, "class not found")
            return
        }
        fun requiredField(name: String): Field? = hierarchyField(drawableClass, name).also {
            if (it == null) DebugLog.hookSkipped(TAG, "$DRAWABLE_CLASS#$name", "field not found")
        }
        val fields = NativeDrawableFields(
            label = requiredField("mMobileType") ?: return,
            mainPaint = requiredField("mMobileTypeTextPaint") ?: return,
            suffixPaint = requiredField("mMobileTypePlusPaint") ?: return,
            width = requiredField("mWidth") ?: return,
            height = requiredField("mHeight") ?: return,
            extraWidth = requiredField("mExtraWidth") ?: return,
            doublePlus = requiredField("mShowMobileTypeDoublePlus") ?: return
        )
        val measure = drawableClass.findMethodOrNull { name("measure"); paramCount(0) }
        val draw = drawableClass.findMethodOrNull { name("draw"); paramCount(1) }
        if (measure == null || draw == null) {
            DebugLog.hookSkipped(TAG, DRAWABLE_CLASS, "measure/draw methods not found")
            return
        }
        measure.hook {
            after { param ->
                runCatching {
                    if (small5GaEnabled) applySmall5GaMetrics(param.thisObject, fields)
                }.onFailure { DebugLog.w(TAG, "native 5GA measurement update failed", it) }
            }
        }
        draw.hook {
            before { param ->
                runCatching {
                    if (!small5GaEnabled) return@runCatching
                    val target = param.thisObject
                    val raw = fields.label.get(target) as? String ?: return@runCatching
                    val parts = MobileTypeLabelStyle.small5GaParts(raw) ?: return@runCatching
                    if (fields.doublePlus.getBoolean(target)) return@runCatching
                    fields.label.set(target, parts.baseText)
                    nativeDrawStateMap()[target] = NativeDrawState(raw, parts)
                }.onFailure { DebugLog.w(TAG, "native 5GA draw preparation failed", it) }
            }
            after { param ->
                val target = param.thisObject
                val state = nativeDrawStateMap().remove(target) ?: return@after
                runCatching {
                    fields.label.set(target, state.originalLabel)
                    val canvas = param.args.getOrNull(0) as? Canvas ?: return@runCatching
                    val mainPaint = fields.mainPaint.get(target) as? Paint ?: return@runCatching
                    val suffixPaint = fields.suffixPaint.get(target) as? Paint ?: return@runCatching
                    val suffix = prepareNativeSmallSuffixPaint(mainPaint, suffixPaint)
                    canvas.drawText(
                        state.parts.suffixText,
                        fields.width.getInt(target).toFloat(),
                        fields.height.getInt(target).toFloat(),
                        suffix
                    )
                }.onFailure { DebugLog.w(TAG, "native 5GA suffix draw failed", it) }
            }
        }
    }

    private fun applySmall5GaMetrics(target: Any, fields: NativeDrawableFields) {
        val raw = fields.label.get(target) as? String ?: return
        val parts = MobileTypeLabelStyle.small5GaParts(raw) ?: return
        if (fields.doublePlus.getBoolean(target)) return
        val mainPaint = fields.mainPaint.get(target) as? Paint ?: return
        val suffixPaint = fields.suffixPaint.get(target) as? Paint ?: return
        val suffix = prepareNativeSmallSuffixPaint(mainPaint, suffixPaint)
        val bounds = Rect()
        mainPaint.getTextBounds(parts.baseText, 0, parts.baseText.length, bounds)
        fields.width.setInt(target, ceil(mainPaint.measureText(parts.baseText).toDouble()).toInt())
        fields.height.setInt(target, bounds.height() + bounds.bottom)
        fields.extraWidth.setInt(
            target,
            ceil(suffix.measureText(parts.suffixText).toDouble()).toInt()
        )
        (target as? Drawable)?.invalidateSelf()
    }

    private fun prepareNativeSmallSuffixPaint(mainPaint: Paint, hostSuffixPaint: Paint): Paint =
        currentNativeSmallSuffixPaint().apply {
            set(hostSuffixPaint)
            textSize = mainPaint.textSize * MobileTypeLabelStyle.SMALL_5GA_SUFFIX_SCALE
        }

    private fun nativeDrawStateMap(): IdentityHashMap<Any, NativeDrawState> =
        nativeDrawStates.get() ?: IdentityHashMap<Any, NativeDrawState>().also(nativeDrawStates::set)

    private fun currentNativeSmallSuffixPaint(): Paint =
        nativeSmallSuffixPaint.get() ?: Paint(Paint.ANTI_ALIAS_FLAG).also(nativeSmallSuffixPaint::set)

    private fun hookOperatorConfig() {
        val policyClass = POLICY_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, POLICY_CLASS, "class not found")
            return
        }
        val configClass = CONFIG_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, CONFIG_CLASS, "class not found")
            return
        }
        val singleField = hierarchyField(configClass, "showMobileDataTypeSingle")
        val nameField = hierarchyField(configClass, "mobileTypeName")
        if (singleField == null && nameField == null) {
            DebugLog.hookSkipped(TAG, CONFIG_CLASS, "fields not found")
            return
        }
        policyClass.findMethodOrNull { name("getMiuiOperatorConfig"); paramCount(1) }?.hook {
            after { param ->
                val config = param.result ?: return@after
                if (!configClass.isInstance(config)) return@after
                if (forceSingle && singleField != null) {
                    runCatching { singleField.setBoolean(config, true) }
                        .onFailure { DebugLog.w(TAG, "showMobileDataTypeSingle write failed", it) }
                }
                if (useCustom && nameField != null && customType.isNotEmpty()) {
                    // A single value fills all 15 entries; exactly 15 values map one-to-one.
                    val parts = customType.split(',').map(String::trim)
                    val names = when {
                        parts.size == 1 && parts[0].isNotEmpty() -> List(15) { parts[0] }
                        parts.size == 15 -> parts
                        else -> null
                    }
                    if (names != null) {
                        runCatching { nameField.set(config, names) }
                            .onFailure { DebugLog.w(TAG, "mobileTypeName write failed", it) }
                    }
                }
            }
        } ?: DebugLog.hookSkipped(TAG, "$POLICY_CLASS#getMiuiOperatorConfig", "method not found")
    }

    /** Applies single-row layout changes after the host has created the complete view hierarchy. */
    private fun hookBinder() {
        val binderClass = BINDER_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, BINDER_CLASS, "class not found")
            return
        }
        val bind = binderClass.findMethodOrNull { name("bind"); paramCount(4) } ?: run {
            DebugLog.hookSkipped(TAG, "$BINDER_CLASS#bind", "method not found")
            return
        }
        bind.hook {
            after { param ->
                runCatching {
                    val root = param.args.getOrNull(0) as? ViewGroup ?: return@runCatching
                    val typeSingle = findView(root, "mobile_type_single") as? TextView
                    if (typeSingle != null) {
                        if (singleSizeEnabled) {
                            typeSingle.setTextSize(TypedValue.COMPLEX_UNIT_SP, singleSize)
                        }
                        if (singleFontEnabled) {
                            typeSingle.typeface = singleTypeface()
                        }
                    }
                    if (swapSingle) swapSingleAndStack(root, typeSingle)
                }.onFailure { DebugLog.w(TAG, "cellular single type layout update failed", it) }
            }
        }
    }

    private fun swapSingleAndStack(root: ViewGroup, typeSingle: TextView?) {
        val stacked = findView(root, "mobile_signal_container") ?: return
        val singleParent = typeSingle?.parent as? ViewGroup ?: return
        val stackedParent = stacked.parent as? ViewGroup ?: return
        if (singleParent !== stackedParent) return
        val first = singleParent.indexOfChild(typeSingle)
        val second = singleParent.indexOfChild(stacked)
        if (first < 0 || second < 0 || first == second) return
        singleParent.removeView(typeSingle)
        singleParent.removeView(stacked)
        singleParent.addView(stacked, first)
        singleParent.addView(typeSingle, second)
    }

    /** The constructor and static helper both touch the drawable's two paints. */
    private fun hookDrawableFont() {
        val drawableClass = DRAWABLE_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, DRAWABLE_CLASS, "class not found")
            return
        }
        drawableClass.hookAllConstructors {
            after { param -> applyDrawablePaints(param.thisObject) }
        }
        drawableClass.findMethodOrNull {
            name("setMiuiStatusBarTypeface")
            isStatic()
            paramCount(1)
        }?.hook {
            after { param ->
                val paints = param.args.getOrNull(0) as? Array<*> ?: return@after
                val resolved = drawableTypeface()
                paints.filterIsInstance<Paint>().forEach { it.typeface = resolved }
            }
        } ?: DebugLog.hookSkipped(TAG, "$DRAWABLE_CLASS#setMiuiStatusBarTypeface", "method not found")
    }

    private fun applyDrawablePaints(target: Any) {
        val resolved = drawableTypeface()
        listOf("mMobileTypeTextPaint", "mMobileTypePlusPaint").forEach { name ->
            (readField(target, name) as? Paint)?.typeface = resolved
        }
        runCatching { findMethod(target.javaClass, "measure", 0)?.invoke(target) }
    }

    private fun drawableTypeface(): Typeface = typeface ?: IconFontResolver.resolve(
        module = module,
        remoteFileName = "status_bar_font",
        weight = drawableWeight
    ).also { typeface = it }

    private fun singleTypeface(): Typeface = singleTypeface ?: IconFontResolver.resolve(
        module = module,
        remoteFileName = "status_bar_font",
        weight = singleWeight
    ).also { singleTypeface = it }

    private fun findView(root: ViewGroup, name: String): android.view.View? {
        val id = root.resources.getIdentifier(name, "id", "com.android.systemui")
        return id.takeIf { it != 0 }?.let(root::findViewById)
    }

    private fun readField(target: Any?, name: String): Any? {
        var type = target?.javaClass
        while (type != null) {
            val field = hierarchyField(type, name)
            if (field != null) return runCatching { field.get(target) }.getOrNull()
            type = type.superclass
        }
        return null
    }

    private fun findMethod(type: Class<*>, name: String, count: Int) =
        type.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.size == count }

    private fun hierarchyField(clazz: Class<*>, name: String): Field? {
        var current: Class<*>? = clazz
        while (current != null) {
            runCatching {
                return current.getDeclaredField(name).apply { isAccessible = true }
            }
            current = current.superclass
        }
        return null
    }
}
