package com.takekazex.hypertweak.hook.rules.systemui.volume.app

import android.content.Context
import android.view.View
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Method

/**
 * The volume plugin's own sizing and material helpers, bound by exact parameter types.
 *
 * App columns and the entry button are module views; calling the same static helpers the native
 * collapsed column ([VOLUME_COLUMN_RES]) and ringer buttons ([RINGER_BUTTON_RES]) use keeps their
 * size, radius, blur and blend tokens identical to the host. Every handle is required: a missing
 * one rejects the contract and the feature stays absent for that plugin build.
 */
internal class AppVolumeHost private constructor(
    private val advancedMaterial: Method,
    private val glassMaterial: Method,
    private val backgroundStyle: Method,
    private val backgroundStyleWithProgress: Method,
    private val blendColor: Method,
    private val blurOutline: Method,
    private val closedGlass: Any,
    private val openGlass: Any,
    private val columnWidth: Method,
    private val columnHeight: Method,
    private val columnRadius: Method,
    private val columnIconSize: Method,
    private val columnIconMargin: Method,
    private val sliderBlend: Method,
    private val progressBlend: Method,
    private val buttonWidth: Method,
    private val buttonHeight: Method,
    private val buttonRadius: Method,
    private val buttonIconSize: Method,
    private val buttonBlend: Method,
    private val buttonBackground: Method,
    private val buttonBlurBackground: Method,
    private val dialogBlend: Method,
    val ringerLayout: Int,
    val idBlur: Int,
    val idStandard: Int,
    val idIcon: Int,
    val idDivider: Int,
    val idDndLayout: Int,
    private val dimenGap: Int,
    private val drawableTrack: Int,
    private val dimenPercentText: Int,
) {
    fun advanced(context: Context) = advancedMaterial.invoke(null, context) == true
    private fun glass(context: Context) = glassMaterial.invoke(null, context) == true

    // Collapsed hardware-key dialog: needShowDialog = true, expanded = false.
    fun columnWidth(context: Context) = columnWidth.invoke(null, context, true, false, false) as Int
    fun columnHeight(context: Context) = columnHeight.invoke(null, context, true, false) as Int
    fun columnRadius(context: Context) = columnRadius.invoke(null, context, true, false) as Int
    fun columnIconSize(context: Context) = columnIconSize.invoke(null, context, true) as Int
    fun columnIconMargin(context: Context) = columnIconMargin.invoke(null, context, false, true) as Int
    fun columnGap(context: Context) = context.resources.getDimensionPixelSize(dimenGap)

    fun buttonWidth(context: Context) = buttonWidth.invoke(null, context, false, true) as Int
    fun buttonHeight(context: Context) = buttonHeight.invoke(null, context, false, true) as Int
    fun buttonRadius(context: Context) = buttonRadius.invoke(null, context, false, true) as Int
    fun buttonIconSize(context: Context) = buttonIconSize.invoke(null, context, false, true) as Int
    /** The super-volume label's native text size. */
    fun percentTextSize(context: Context) = context.resources.getDimension(dimenPercentText)

    /** VolumeColumn.initSliderStyle / setSliderBlendColor, collapsed branch. */
    fun styleTrack(track: View, radius: Float) {
        val context = track.context
        if (advanced(context)) {
            track.background = null
            backgroundStyle.invoke(null, track, 1, sliderBlend.invoke(null, false, true, glass(context)), closedGlass)
            blurOutline.invoke(null, track, radius)
        } else {
            track.setBackgroundResource(drawableTrack)
        }
    }

    /** The native progress view's blend color; false keeps the module's flat fallback fill. */
    fun styleFill(fill: View): Boolean {
        val context = fill.context
        if (!advanced(context)) return false
        blendColor.invoke(null, fill, progressBlend.invoke(null, false, true, false, glass(context)))
        return true
    }

    /** RingerButtonHelper.applyCollapsedStyle / updateStateWithDefaultMaterial, collapsed branch. */
    fun styleButton(blur: View, standard: View, on: Boolean) {
        val context = standard.context
        standard.isActivated = true
        standard.isSelected = on
        if (advanced(context)) {
            standard.background = null
            backgroundStyleWithProgress.invoke(
                null, standard, 1, dialogBlend.invoke(null, true),
                buttonBlend.invoke(null, false, true, on, glass(context)),
                if (on) 1f else 0f, closedGlass, openGlass
            )
            blurOutline.invoke(null, standard, buttonRadius(context).toFloat())
        } else {
            blur.setBackgroundResource(buttonBlurBackground.invoke(null, true, false) as Int)
            standard.setBackgroundResource(buttonBackground.invoke(null, false, true, false) as Int)
        }
    }

    companion object {
        private const val TAG = "AppVolume"
        private const val PACKAGE = "com.android.systemui.miui.volume"
        const val VOLUME_COLUMN_RES = "$PACKAGE.VolumeColumnRes"
        const val RINGER_BUTTON_RES = "$PACKAGE.RingerButtonRes"

        fun resolve(loader: ClassLoader): AppVolumeHost? = runCatching {
            val util = loader.loadClass("$PACKAGE.Util")
            HostAccess.cached(util, "appVolume.hostContract", AppVolumeHost::class.java) { build(loader, util) }
        }.onFailure { DebugLog.hookSkipped(TAG, "volume plugin material contract", it.toString()) }.getOrNull()

        private fun build(loader: ClassLoader, util: Class<*>): AppVolumeHost {
            val z = Boolean::class.javaPrimitiveType!!
            val i = Int::class.javaPrimitiveType!!
            val f = Float::class.javaPrimitiveType!!
            val ctx = Context::class.java
            val view = View::class.java
            val blendToken = loader.loadClass("miuix.theme.token.ColorBlendToken")
            val bionics = loader.loadClass("miui.systemui.util.BionicsToken")
            val style = loader.loadClass("miui.systemui.util.MiBackgroundStyle")
            val blur = loader.loadClass("miui.systemui.util.MiBlurCompat")
            val columnRes = loader.loadClass(VOLUME_COLUMN_RES)
            val ringerRes = loader.loadClass(RINGER_BUTTON_RES)
            val dialogRes = loader.loadClass("$PACKAGE.MiuiVolumeDialogRes")
            val styleInstance = HostAccess.requirePublicField(style, "INSTANCE", accessible = false).get(null)

            fun static(owner: Class<*>, name: String, returns: Class<*>, vararg parameters: Class<*>): Method {
                val method = HostAccess.requirePublicMethod(owner, name, *parameters, accessible = false)
                require(java.lang.reflect.Modifier.isStatic(method.modifiers) && method.returnType == returns) {
                    "${owner.simpleName}#$name shape"
                }
                return method
            }
            fun token(name: String): Any = requireNotNull(
                HostAccess.requirePublicMethod(style, name, accessible = false).invoke(styleInstance)
            ) { name }
            fun constant(kind: String, name: String) =
                HostAccess.requirePublicField(loader.loadClass("$PACKAGE.R\$$kind"), name, accessible = false).getInt(null)

            return AppVolumeHost(
                advancedMaterial = static(util, "isAdvancedMaterialEffective", z, ctx),
                glassMaterial = static(util, "isBionicsAdvancedMaterialEnabled", z, ctx),
                backgroundStyle = static(util, "setMiViewBackgroundStyle", Void.TYPE, view, i, blendToken, bionics),
                backgroundStyleWithProgress = static(
                    util, "setMiViewBackgroundStyleWithProgress", Void.TYPE, view, i, blendToken, blendToken, f, bionics, bionics
                ),
                blendColor = static(util, "applyBlendColor", Void.TYPE, view, blendToken),
                blurOutline = static(blur, "setBlurOutlineRoundRect", Void.TYPE, view, f),
                closedGlass = token("getVOLUMPANEL_COLLAPSED_CLOSED_GLASS_TOKEN"),
                openGlass = token("getVOLUMPANEL_COLLAPSED_OPEN_GLASS_TOKEN"),
                columnWidth = static(columnRes, "getWidth", i, ctx, z, z, z),
                columnHeight = static(columnRes, "getHeight", i, ctx, z, z),
                columnRadius = static(columnRes, "getRadius", i, ctx, z, z),
                columnIconSize = static(columnRes, "getIconSize", i, ctx, z),
                columnIconMargin = static(columnRes, "getIconMarginBottom", i, ctx, z, z),
                sliderBlend = static(columnRes, "getSliderBlendColor", blendToken, z, z, z),
                progressBlend = static(columnRes, "getProgressViewBlendColor", blendToken, z, z, z, z),
                buttonWidth = static(ringerRes, "getWidth", i, ctx, z, z),
                buttonHeight = static(ringerRes, "getHeight", i, ctx, z, z),
                buttonRadius = static(ringerRes, "getButtonRadius", i, ctx, z, z),
                buttonIconSize = static(ringerRes, "getIconSize", i, ctx, z, z),
                buttonBlend = static(ringerRes, "getButtonBgBlendColor", blendToken, z, z, z, z),
                buttonBackground = static(ringerRes, "getButtonBgId", i, z, z, z),
                buttonBlurBackground = static(ringerRes, "getBlurViewBg", i, z, z),
                dialogBlend = static(dialogRes, "getBgBlandColor", blendToken, z),
                ringerLayout = constant("layout", "miui_ringer_mode_layout"),
                idBlur = constant("id", "bg_blur"),
                idStandard = constant("id", "miui_standard_btn"),
                idIcon = constant("id", "icon"),
                idDivider = constant("id", "miui_volume_ringer_divider"),
                idDndLayout = constant("id", "dnd_layout"),
                dimenGap = constant("dimen", "miui_volume_temp_margin_active"),
                drawableTrack = constant("drawable", "o3_miui_volume_seekbar_backgroud"),
                dimenPercentText = constant("dimen", "miui_super_volume_text_size"),
            )
        }
    }
}
