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
    private val tokens by lazy { Tokens.load(context) }
    private var configured = false
    private var capture = false
    private data class Spec(val config: DockConfig, val dark: Boolean, val radius: Float, val scale: Float, val crossWindow: Boolean)
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
        addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) pending?.let { spec ->
                runCatching { apply(spec.config, spec.dark, spec.radius, spec.scale, spec.crossWindow) }
                    .onFailure {
                        com.takekazex.hypertweak.util.DebugLog.w("DockMaterial", "measured material configuration failed", it)
                        post { onMaterialState(false) }
                    }
            }
        }
    }

    fun apply(config: DockConfig, dark: Boolean, radius: Float, scale: Float = 1f, crossWindow: Boolean = false) {
        val style = config.style
        val spec = Spec(config, dark, radius, scale, crossWindow)
        pending = spec
        if (width < 1 || height < 1) return // Material/SDF configuration follows the real measured size.
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
        if (style == DockConfig.GLASS || configured) DockReflection.call(this, "setMiGlass", FloatArray(42))
        DockReflection.call(this, "setMiViewMaterialType", 0)
        DockReflection.call(this, "setMiBackgroundBlendColors", arrayListOf<Point>())
        DockReflection.call(this, "setMiBackgroundBlurMode", 1)
        DockReflection.call(this, "setMiViewBlurMode", 1)
        if (style == DockConfig.GLASS) {
            DockReflection.call(this, "setMiBackgroundBlurRadius", 0)
            DockReflection.call(this, "setMiViewMaterialType", 1)
            DockReflection.call(this, "setMiGlassBlurRadius", blur, blur)
            DockReflection.call(this, "setMiGlass", config.glassShader(token.shader, scale))
            DockReflection.call(this, "setMiGlassSdfMaxSize", width.toFloat(), height.toFloat())
        } else {
            if (configured) DockReflection.call(this, "setMiGlassBlurRadius", 0, 0)
            DockReflection.call(this, "setMiBackgroundBlurRadius", blur)
            val nativeBlend = if (dark) token.darkBlend else token.lightBlend
            val blend = ArrayList(nativeBlend().map { point ->
                val alpha = ((point.x ushr 24) * config.tint / 100f).toInt().coerceIn(0, 255)
                Point((point.x and 0x00ffffff) or (alpha shl 24), point.y)
            })
            DockReflection.call(this, "setMiBackgroundBlendColors", blend)
        }
        if (crossWindow) enableCapture()
        configured = true
        applied = spec to (width to height)
        frameCommitted = false
        val generation = ++frameEpoch
        viewTreeObserver.registerFrameCommitCallback { post { if (generation == frameEpoch && configured) frameCommitted = true } }
        onMaterialState(true)
        invalidate()
    }

    private var ownCaptureBridge = false

    private fun enableCapture() {
        if (capture) return
        if (DockReflection.call(this, "setPassWindowBlurEnabled", true) != true &&
            !DockReflection.field(this, "mNeedPassWindowBlur").getBoolean(this)) {
            // OS4 stable moved admission into ViewRootImplStub. Its global policy is not
            // ours to change. Execute the same native View operations on OUR renderer only.
            check(context.packageName == "com.takekazex.hypertweak")
            val root = requireNotNull(DockReflection.call(this, "getViewRootImpl"))
            val node = requireNotNull(DockReflection.field(this, "mRenderNode").get(this))
            val enabled = DockReflection.field(this, "mNeedPassWindowBlur")
            try {
                DockReflection.call(root, "addTextureView", this)
                DockReflection.call(node, "setPassWindowBlurEnabled", true)
                enabled.setBoolean(this, true)
                ownCaptureBridge = true
            } catch (error: Throwable) {
                runCatching { DockReflection.call(root, "clearTextureView", this) }.exceptionOrNull()?.let(error::addSuppressed)
                runCatching { DockReflection.call(node, "setPassWindowBlurEnabled", false) }.exceptionOrNull()?.let(error::addSuppressed)
                enabled.setBoolean(this, false)
                throw error
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
        if (capture) runCatching {
            if (ownCaptureBridge) {
                val root = requireNotNull(DockReflection.call(this, "getViewRootImpl"))
                val node = requireNotNull(DockReflection.field(this, "mRenderNode").get(this))
                try { DockReflection.call(root, "clearTextureView", this) } finally {
                    DockReflection.call(node, "setPassWindowBlurEnabled", false)
                    DockReflection.field(this, "mNeedPassWindowBlur").setBoolean(this, false)
                }
            } else DockReflection.call(this, "setPassWindowBlurEnabled", false)
        }.onFailure { com.takekazex.hypertweak.util.DebugLog.w("DockMaterial", "native capture cleanup failed", it) }
        ownCaptureBridge = false
        capture = false
        configured = false
        pending = null
        applied = null
        frameCommitted = false
        frameEpoch++
        requireTextureAfter = 0
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
