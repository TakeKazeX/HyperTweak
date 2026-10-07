package com.takekazex.hypertweak.dock

import android.content.Context
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout

/** The renderer used both in the wallpaper preview and the windowless desktop host. */
internal class DockMaterialView(context: Context) : FrameLayout(context) {
    private val material = FrameLayout(context)
    private val tokens by lazy { Tokens.load(context) }
    private var configured = false
    private var capture = false
    private data class Spec(val style: Int, val dark: Boolean, val radius: Float, val scale: Float, val crossWindow: Boolean)
    private var pending: Spec? = null
    private var applied: Pair<Spec, Pair<Int, Int>>? = null
    private var frameEpoch = 0L
    var sourceRotation: Int? = null
    private var requireTextureAfter = 0L
    var frameCommitted = false
        private set
    var onMaterialState: (Boolean) -> Unit = {}

    init {
        setBackgroundColor(Color.TRANSPARENT)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isClickable = false
        addView(material, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) pending?.let { spec ->
                runCatching { apply(spec.style, spec.dark, spec.radius, spec.scale, spec.crossWindow) }
                    .onFailure {
                        com.takekazex.hypertweak.util.DebugLog.w("DockMaterial", "measured material configuration failed", it)
                        post { onMaterialState(false) }
                    }
            }
        }
    }

    fun apply(style: Int, dark: Boolean, radius: Float, scale: Float = 1f, crossWindow: Boolean = false) {
        val spec = Spec(style, dark, radius, scale, crossWindow)
        pending = spec
        if (width < 1 || height < 1) return // Material/SDF configuration follows the real measured size.
        if (applied == (spec to (width to height))) return
        check(isAttachedToWindow && isHardwareAccelerated) { "Native material requires an attached hardware ViewRoot" }
        DockReflection.allowOwnViewApis()
        val rootClass = Class.forName("android.view.ViewRootImpl")
        check(DockReflection.callStatic(rootClass, "getSupportedMiBlur") == true) { "Native blur unsupported" }
        if (style == DockConfig.GLASS) check(DockReflection.callStatic(rootClass, "getSupportedBionicMaterial") == true) { "Native glass unsupported" }
        val token = tokens
        val blur = (token.smallRadius * scale).toInt().coerceIn(1, 500)
        val large = (token.bigRadius * scale).toInt().coerceIn(1, 1000)
        listOf(this, material).forEach { surface ->
            surface.background = GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = radius }
            surface.outlineProvider = ViewOutlineProvider.BACKGROUND
            surface.clipToOutline = true
        }
        // A switch must remove the old glass and blends, including when a host is reused.
        if (style == DockConfig.GLASS || configured) DockReflection.call(material, "setMiGlass", FloatArray(42))
        DockReflection.call(material, "setMiViewMaterialType", 0)
        DockReflection.call(this, "setMiBackgroundBlendColors", arrayListOf<Point>())
        DockReflection.call(material, "setMiBackgroundBlendColors", arrayListOf<Point>())
        DockReflection.call(this, "setMiBackgroundBlurMode", 1)
        DockReflection.call(this, "setMiBackgroundBlurRadius", blur)
        DockReflection.call(this, "setMiViewBlurMode", 0)
        DockReflection.call(this, "setMiGlassBlurRadius", blur, large)
        val blend = if (dark) token.darkBlend else token.lightBlend
        if (style == DockConfig.GLASS) {
            DockReflection.call(this, "setMiBackgroundBlendColors", blend())
            DockReflection.call(material, "setMiBackgroundBlurMode", 0)
            DockReflection.call(material, "setMiViewBlurMode", 1)
            DockReflection.call(material, "setMiViewMaterialType", 1)
            DockReflection.call(material, "setMiGlass", token.shader.copyOf())
            DockReflection.call(material, "setMiGlassSdfMaxSize", width.toFloat(), height.toFloat())
        } else {
            // The native frosted surface owns one blur/blend pass. Its empty child contributes
            // no second Gaussian pass; the same surface also owns its pass-window source.
            DockReflection.call(this, "setMiViewBlurMode", 1)
            DockReflection.call(this, "setMiBackgroundBlendColors", blend())
            DockReflection.call(material, "setMiBackgroundBlurMode", 0)
            DockReflection.call(material, "setMiViewBlurMode", 0)
        }
        if (crossWindow) enableCapture()
        configured = true
        applied = spec to (width to height)
        frameCommitted = false
        val generation = ++frameEpoch
        viewTreeObserver.registerFrameCommitCallback { post { if (generation == frameEpoch && configured) frameCommitted = true } }
        onMaterialState(true)
        invalidate()
        material.invalidate()
    }

    private fun enableCapture() {
        if (capture) return
        val enabled = DockReflection.call(this, "setPassWindowBlurEnabled", true) == true
        if (!enabled) {
            val already = runCatching { DockReflection.field(this, "mNeedPassWindowBlur").getBoolean(this) }.getOrDefault(false)
            if (!already) {
                val root = requireNotNull(DockReflection.call(this, "getViewRootImpl"))
                val field = DockReflection.field(root, "mPassWindowBlurFilterData")
                val original = field.get(root) as? String ?: error("Native background filter unavailable")
                // Modify only OUR attached ViewRoot's filter; never a static flag or another app.
                check(context.packageName == "com.takekazex.hypertweak")
                field.set(root, "$original,${context.packageName}")
                if (DockReflection.call(this, "setPassWindowBlurEnabled", true) != true) {
                    field.set(root, original)
                    error("Native cross-window background rejected")
                }
            }
        }
        capture = true
    }

    fun textureTimestamp(): Long = if (!configured || !capture) 0 else runCatching {
        val root = requireNotNull(DockReflection.call(this, "getViewRootImpl"))
        val texture = DockReflection.field(root, "mSurTex").get(root) as? SurfaceTexture
        if (texture == null || texture.isReleased || DockReflection.field(root, "mLastSfState").getInt(root) != 1 ||
            !DockReflection.field(root, "mTextureVis").getBoolean(root)) return@runCatching 0L
        sourceRotation?.let { rotation ->
            val install = (DockReflection.call(requireNotNull(display), "getInstallOrientation") as? Int) ?: 0
            val expected = DockCaptureGeometry.rotation(rotation, install) ?: error("Unknown capture rotation")
            val rotationField = DockReflection.field(root, "mConfigRot")
            if (rotationField.getInt(root) != expected) {
                // Windowless roots do not receive the launcher's relayout. Correct ONLY our root,
                // using the framework's checked rotation/texture-size contract, before showing it.
                requireTextureAfter = texture.timestamp
                val size = DockReflection.field(root, "mSurfaceSize").get(root) as? Point ?: error("Capture size missing")
                val scale = DockReflection.field(root, "mTexScale").getFloat(root)
                val buffer = DockCaptureGeometry.buffer(size.x, size.y, expected, scale) ?: error("Invalid capture buffer")
                rotationField.setInt(root, expected)
                (DockReflection.field(root, "mDispRect").get(root) as? Rect)?.set(0, 0, buffer.first, buffer.second)
                texture.setDefaultBufferSize(buffer.first, buffer.second)
                DockReflection.call(this, "setTextureAvailable", true, expected, scale)
                postInvalidateOnAnimation()
                return@runCatching 0L
            }
        }
        texture.timestamp.takeIf { it > requireTextureAfter } ?: 0L
    }.getOrDefault(0L)

    fun stop() {
        if (capture) runCatching { DockReflection.call(this, "setPassWindowBlurEnabled", false) }
        capture = false
        configured = false
        pending = null
        applied = null
        frameCommitted = false
        frameEpoch++
        requireTextureAfter = 0
    }

    private class Tokens(val shader: FloatArray, val smallRadius: Int, val bigRadius: Int,
        val lightBlend: () -> ArrayList<Point>, val darkBlend: () -> ArrayList<Point>) {
        companion object {
            fun load(context: Context): Tokens {
                val plugin = context.createPackageContext("miui.systemui.plugin",
                    Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
                val glassClass = plugin.classLoader.loadClass("miuix.theme.token.GlassToken")
                val glass = requireNotNull(glassClass.getField("Glass_Bionic_Medium_Normal").get(null))
                val shader = glassClass.getMethod("toShaderParams").invoke(glass) as FloatArray
                check(shader.size == 42 && shader.all { it.isFinite() }) { "Unknown native glass ABI" }
                val blur = requireNotNull(glassClass.getField("blurRadius").get(glass))
                val small = blur.javaClass.getField("smallBlurRadius").getInt(blur)
                val big = blur.javaClass.getField("bigBlurRadius").getInt(blur)
                val blendClass = plugin.classLoader.loadClass("miuix.theme.token.ColorBlendToken")
                fun blend(name: String): () -> ArrayList<Point> {
                    val value = requireNotNull(blendClass.getField(name).get(null))
                    val colors = (blendClass.getField("colors").get(value) as IntArray).copyOf()
                    val modes = (blendClass.getField("blendModes").get(value) as IntArray).copyOf()
                    check(colors.size == modes.size && colors.isNotEmpty())
                    return { ArrayList(colors.indices.map { Point(colors[it], modes[it]) }) }
                }
                return Tokens(shader.copyOf(), small, big, blend("Pured_Regular_Glass_Light"), blend("Pured_Regular_Glass_Dark"))
            }
        }
    }
}
