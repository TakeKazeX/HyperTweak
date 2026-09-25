package com.takekazex.hypertweak.ui.effect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.rules.systemui.glass.scaleBlendArrayColors
import com.takekazex.hypertweak.hook.rules.systemui.glass.scaleGlassParams

/** A real MIUI View glass surface, using GlassToken/ColorBlendToken from the installed plugin. */
internal class NativeGlassPreviewView(context: Context) : FrameLayout(context) {
    private val backdrop = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        scaleX = 1.1f
        scaleY = 1.4f
        contentDescription = null
    }
    private val glassCard = NativeSurface(context, dp(18f))
    private val cardTitle = TextView(context).apply {
        text = context.getString(R.string.glass_preview_notification_title)
        textSize = 14f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    private val cardDescription = TextView(context).apply {
        text = context.getString(R.string.glass_preview_notification_body)
        textSize = 12f
        maxLines = 2
    }
    private val unavailable = TextView(context).apply {
        setText(R.string.glass_preview_native_unavailable)
        setTextColor(Color.WHITE)
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
        background = GradientDrawable().apply {
            setColor(0xCC202124.toInt())
            cornerRadius = dp(12f).toFloat()
        }
        visibility = View.GONE
    }
    private val cardContent = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(15f), dp(12f), dp(15f), dp(12f))
        addView(cardTitle, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(cardDescription, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(5f)
        })
    }

    private val tokens = runCatching { NativeMaterialTokens.load(context) }.getOrNull()
    private var lastConfig: GlassPreviewConfig? = null
    private var lastBitmap: Bitmap? = null
    private var nativeReady = false

    init {
        clipChildren = true
        clipToPadding = true
        background = transparentRoundRect(dp(20f))
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true

        addView(backdrop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(glassCard, LayoutParams(LayoutParams.MATCH_PARENT, dp(104f), Gravity.CENTER).apply {
            setMargins(dp(28f), dp(28f), dp(28f), dp(28f))
        })
        glassCard.addView(cardContent, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(unavailable, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    fun update(config: GlassPreviewConfig, bitmap: Bitmap) {
        if (lastConfig == config && lastBitmap === bitmap) return
        lastConfig = config
        if (lastBitmap !== bitmap) {
            backdrop.setImageBitmap(bitmap)
            backdrop.translationY = 0f
            lastBitmap = bitmap
        }
        post { applyNativeStyle(config) }
    }

    fun panBackground(deltaY: Float) {
        val maxOffset = height * 0.16f
        backdrop.translationY = (backdrop.translationY + deltaY).coerceIn(-maxOffset, maxOffset)
    }

    private fun applyNativeStyle(config: GlassPreviewConfig) {
        val materialTokens = tokens
        if (materialTokens == null || !isAttachedToWindow || width == 0 || height == 0) {
            nativeReady = false
            updateStatus()
            return
        }

        val enabled = config.tunerEnabled
        val blendAlpha = if (enabled) config.blendAlpha else 1f
        val blendLightness = if (enabled) config.blendLightness else 1f
        val radiusScale = if (enabled) config.radiusScale else 1f
        val opacity = if (enabled) config.glassOpacity else 1f
        val tone = if (enabled) config.glassTone else 1f
        val blendToken = if (config.darkTheme) materialTokens.darkBlend else materialTokens.lightBlend
        val baseRadius = materialTokens.smallBlurRadius
        val nativeType = if (config.materialStyle == MATERIAL_STYLE_BIONICS) 1 else 0

        val cardColors = nativeBlendPoints(blendToken, blendAlpha, blendLightness)
        val shader = if (nativeType == 1) scaleGlassParams(materialTokens.shaderParams, opacity, tone)
        else FloatArray(0)
        val scaledRadius = (baseRadius * radiusScale).toInt().coerceIn(0, 400)

        val cardOk = applySurface(
            glassCard,
            nativeType,
            cardColors,
            shader,
            scaledRadius,
        )
        val contentColor = if (config.darkTheme) Color.WHITE else 0xFF202124.toInt()
        cardTitle.setTextColor(contentColor)
        cardDescription.setTextColor(if (config.darkTheme) 0xCCFFFFFF.toInt() else 0xCC202124.toInt())
        nativeReady = cardOk
        updateStatus()
        requestLayout()
    }

    private fun applySurface(
        view: View,
        materialType: Int,
        blendColors: ArrayList<Point>,
        shaderParams: FloatArray,
        blurRadius: Int,
    ): Boolean {
        val modeOk = invokeInts(view, "setMiBackgroundBlurMode", 1) &&
            invokeInts(view, "setMiViewBlurMode", 1) &&
            invokeInts(view, "setMiViewMaterialType", materialType)
        val radiusOk = invokeInts(view, "setMiBackgroundBlurRadius", blurRadius) &&
            (materialType != MATERIAL_STYLE_BIONICS ||
                invokeInts(view, "setMiGlassBlurRadius", blurRadius, blurRadius))
        val materialOk = if (materialType == MATERIAL_STYLE_BIONICS) {
            invokeObject(view, "setMiGlass", FloatArray::class.java, shaderParams).also {
                setSdfSize(view)
            }
        } else {
            invokeObject(view, "setMiBackgroundBlendColors", ArrayList::class.java, blendColors)
        }
        return modeOk && radiusOk && materialOk
    }

    private fun setSdfSize(view: View) {
        runCatching {
            val method = View::class.java.getMethod(
                "setMiGlassSdfMaxSize",
                Float::class.javaPrimitiveType!!,
                Float::class.javaPrimitiveType!!,
            )
            method.invoke(view, view.width.toFloat(), view.height.toFloat())
        }
    }

    private fun invokeInts(view: View, name: String, vararg values: Int): Boolean = runCatching {
        val signature = Array(values.size) { Int::class.javaPrimitiveType!! }
        val args = Array<Any>(values.size) { values[it] }
        View::class.java.getMethod(name, *signature).invoke(view, *args)
        true
    }.getOrDefault(false)

    private fun invokeObject(view: View, name: String, parameterType: Class<*>, value: Any): Boolean = runCatching {
        View::class.java.getMethod(name, parameterType).invoke(view, value)
        true
    }.getOrDefault(false)

    private fun nativeBlendPoints(token: NativeBlendToken, alpha: Float, lightness: Float): ArrayList<Point> {
        val interleaved = IntArray(token.colors.size * 2)
        token.colors.indices.forEach { index ->
            interleaved[index * 2] = token.colors[index]
            interleaved[index * 2 + 1] = token.blendModes.getOrElse(index) { 0 }
        }
        val scaled = scaleBlendArrayColors(interleaved, alpha, lightness)
        return ArrayList<Point>(token.colors.size).apply {
            token.colors.indices.forEach { index ->
                add(Point(scaled[index * 2], scaled[index * 2 + 1]))
            }
        }
    }

    private fun updateStatus() {
        unavailable.visibility = if (nativeReady) View.GONE else View.VISIBLE
        glassCard.visibility = if (nativeReady) View.VISIBLE else View.INVISIBLE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            post { lastConfig?.let(::applyNativeStyle) }
        }
    }

    private fun transparentRoundRect(radiusPx: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx.toFloat()
        setColor(Color.TRANSPARENT)
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private data class NativeBlendToken(val colors: IntArray, val blendModes: IntArray)

    private data class NativeMaterialTokens(
        val shaderParams: FloatArray,
        val smallBlurRadius: Int,
        val lightBlend: NativeBlendToken,
        val darkBlend: NativeBlendToken,
    ) {
        companion object {
            fun load(context: Context): NativeMaterialTokens {
                val pluginContext = context.createPackageContext(
                    "miui.systemui.plugin",
                    Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
                )
                val loader = pluginContext.classLoader
                val glassClass = loader.loadClass("miuix.theme.token.GlassToken")
                val glass = glassClass.getField("Glass_Bionic_Medium_Normal").get(null)
                val shaderParams = glassClass.getMethod("toShaderParams").invoke(glass) as FloatArray
                val blurRadius = glassClass.getField("blurRadius").get(glass)
                val smallRadius = blurRadius.javaClass.getField("smallBlurRadius").getInt(blurRadius)
                val blendClass = loader.loadClass("miuix.theme.token.ColorBlendToken")

                fun token(fieldName: String): NativeBlendToken {
                    val value = blendClass.getField(fieldName).get(null)
                    return NativeBlendToken(
                        colors = blendClass.getField("colors").get(value) as IntArray,
                        blendModes = blendClass.getField("blendModes").get(value) as IntArray,
                    )
                }

                return NativeMaterialTokens(
                    shaderParams = shaderParams,
                    smallBlurRadius = smallRadius,
                    lightBlend = token("Pured_Regular_Glass_Light"),
                    darkBlend = token("Pured_Regular_Glass_Dark"),
                )
            }
        }
    }

    private class NativeSurface(context: Context, radiusPx: Int) : FrameLayout(context) {
        init {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radiusPx.toFloat()
                setColor(Color.TRANSPARENT)
            }
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            clipChildren = true
        }
    }

    private companion object {
        const val MATERIAL_STYLE_CLASSIC = 0
        const val MATERIAL_STYLE_BIONICS = 1
    }
}

internal data class GlassPreviewConfig(
    val darkTheme: Boolean,
    val materialStyle: Int,
    val tunerEnabled: Boolean,
    val blendAlpha: Float,
    val blendLightness: Float,
    val radiusScale: Float,
    val glassOpacity: Float,
    val glassTone: Float,
)
