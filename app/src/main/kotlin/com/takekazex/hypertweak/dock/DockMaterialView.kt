package com.takekazex.hypertweak.dock

import com.takekazex.hypertweak.settings.contract.DockConfig

import android.content.Context
import android.graphics.Color
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout

/** The renderer used both in the wallpaper preview and the windowless desktop host. */
internal class DockMaterialView(context: Context) : FrameLayout(context) {
    private val tokens by lazy { Tokens.load(context) }
    private var configured = false
    private val captureSession = DockCaptureSession(this) { if (configured) awaitFrame() }
    private data class Spec(val config: DockConfig, val dark: Boolean, val radius: Float, val scale: Float)
    private var pending: Spec? = null
    private var applied: Pair<Spec, Pair<Int, Int>>? = null
    private var frameEpoch = 0L
    var frameCommitted = false
        private set
    var onMaterialState: (Boolean) -> Unit = {}

    init {
        setBackgroundColor(Color.TRANSPARENT)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isClickable = false
        addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) pending?.let { spec ->
                runCatching { apply(spec.config, spec.dark, spec.radius, spec.scale) }
                    .onFailure {
                        com.takekazex.hypertweak.util.logging.DebugLog.w("DockMaterial", "measured material configuration failed", it)
                        post { onMaterialState(false) }
                    }
            }
        }
    }

    fun apply(config: DockConfig, dark: Boolean, radius: Float, scale: Float = 1f, crossWindow: Boolean = false) {
        val style = config.style
        val spec = Spec(config, dark, radius, scale)
        if (crossWindow) captureSession.request()
        pending = spec
        if (width < 1 || height < 1) return // Material/SDF configuration follows the real measured size.
        captureSession.reconcile()
        if (applied == (spec to (width to height))) return
        check(isAttachedToWindow && isHardwareAccelerated) { "Native material requires an attached hardware ViewRoot" }
        DockReflection.allowOwnViewApis()
        val rootClass = Class.forName("android.view.ViewRootImpl")
        check(DockReflection.callStatic(rootClass, "getSupportedMiBlur") == true) { "Native blur unsupported" }
        if (style == DockConfig.GLASS) check(DockReflection.callStatic(rootClass, "getSupportedBionicMaterial") == true) { "Native glass unsupported" }
        val token = tokens
        val blur = config.blurPixels(scale)
        background = GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = radius }
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
        // One RenderNode owns both texture sampling and its material, as in
        // HyperMaterialUtils.applyContainerWithGlass. No parent Gaussian pass or child sampler.
        val styleChanged = !configured || applied?.first?.config?.style != style
        if (!configured) {
            DockReflection.call(this, "setMiBackgroundBlurMode", 1)
            DockReflection.call(this, "setMiViewBlurMode", 1)
        }
        if (style == DockConfig.GLASS) {
            if (styleChanged) {
                DockReflection.call(this, "setMiBackgroundBlurRadius", 0)
                DockReflection.call(this, "setMiBackgroundBlendColors", arrayListOf<Point>())
                DockReflection.call(this, "setMiViewMaterialType", 1)
            }
            DockReflection.call(this, "setMiGlassBlurRadius", blur, blur)
            DockReflection.call(this, "setMiGlass", config.glassShader(token.shader, scale))
            DockReflection.call(this, "setMiGlassSdfMaxSize", width.toFloat(), height.toFloat())
        } else {
            if (styleChanged) {
                if (configured) DockReflection.call(this, "setMiGlass", FloatArray(42))
                DockReflection.call(this, "setMiGlassBlurRadius", 0, 0)
                DockReflection.call(this, "setMiViewMaterialType", 0)
            }
            DockReflection.call(this, "setMiBackgroundBlurRadius", blur)
            val nativeBlend = if (dark) token.darkBlend else token.lightBlend
            val blend = ArrayList(nativeBlend().map { point ->
                val alpha = ((point.x ushr 24) * config.tint / 100f).toInt().coerceIn(0, 255)
                Point((point.x and 0x00ffffff) or (alpha shl 24), point.y)
            })
            DockReflection.call(this, "setMiBackgroundBlendColors", blend)
        }
        configured = true
        applied = spec to (width to height)
        awaitFrame()
        onMaterialState(true)
    }

    fun startCapture() {
        captureSession.request()
        pending?.let { apply(it.config, it.dark, it.radius, it.scale) }
    }

    private fun awaitFrame() {
        frameCommitted = false
        val generation = ++frameEpoch
        viewTreeObserver.registerFrameCommitCallback {
            post { if (generation == frameEpoch && configured) frameCommitted = true }
        }
        invalidate()
    }

    fun textureTimestamp(): Long = if (!configured) 0 else captureSession.textureTimestamp()

    fun stop() {
        captureSession.close()
        configured = false
        pending = null
        applied = null
        frameCommitted = false
        frameEpoch++
    }

    private class Tokens(val shader: FloatArray,
        val lightBlend: () -> ArrayList<Point>, val darkBlend: () -> ArrayList<Point>) {
        companion object {
            fun load(context: Context): Tokens {
                val plugin = context.createPackageContext("miui.systemui.plugin",
                    Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
                val glassClass = plugin.classLoader.loadClass("miuix.theme.token.GlassToken")
                val glass = requireNotNull(glassClass.getField("Glass_Bionic_Medium_Normal").get(null))
                val shader = glassClass.getMethod("toShaderParams").invoke(glass) as FloatArray
                check(shader.size == 42 && shader.all { it.isFinite() }) { "Unknown native glass ABI" }
                val blendClass = plugin.classLoader.loadClass("miuix.theme.token.ColorBlendToken")
                fun blend(name: String): () -> ArrayList<Point> {
                    val value = requireNotNull(blendClass.getField(name).get(null))
                    val colors = (blendClass.getField("colors").get(value) as IntArray).copyOf()
                    val modes = (blendClass.getField("blendModes").get(value) as IntArray).copyOf()
                    check(colors.size == modes.size && colors.isNotEmpty())
                    return { ArrayList(colors.indices.map { Point(colors[it], modes[it]) }) }
                }
                return Tokens(shader.copyOf(), blend("Pured_Regular_Glass_Light"), blend("Pured_Regular_Glass_Dark"))
            }
        }
    }
}
