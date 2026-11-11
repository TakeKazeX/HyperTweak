package com.takekazex.hypertweak.ui.effect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import com.takekazex.hypertweak.presentation.launcher.DockPreviewBounds
import com.takekazex.hypertweak.presentation.launcher.NativeDockGlassPreviewGeometry
import com.takekazex.hypertweak.settings.contract.NativeDockGlassGeometry
import kotlin.math.roundToInt

/**
 * Native Dock glass illustration. Icons are foreground siblings: changing the panel never moves or
 * blurs the sample icons. The panel is a real MIUI View glass surface with the Bionic glass token.
 */
internal class DockPreviewView(context: Context) : FrameLayout(context) {
    private val wallpaper = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val panel = View(context).apply {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
    }
    private val icons = listOf("com.android.contacts", "com.android.mms", "com.android.browser", "com.android.camera")
        .mapIndexed { index, pkg -> ImageView(context).apply {
            setImageDrawable(runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrElse {
                context.getDrawable(intArrayOf(android.R.drawable.ic_menu_call, android.R.drawable.ic_dialog_email,
                    android.R.drawable.ic_menu_compass, android.R.drawable.ic_menu_camera)[index])!!
            })
            contentDescription = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        } }
    private val navigationBackground = GradientDrawable().apply { cornerRadius = 8f }
    private val navigation = View(context).apply { background = navigationBackground }
    private val shader by lazy { runCatching { loadGlassShader(context) }.getOrNull() }
    private var geometry = NativeDockGlassGeometry.DEFAULT
    private var bitmap: Bitmap? = null
    private var panelBounds: DockPreviewBounds? = null
    private var iconBounds: List<DockPreviewBounds> = emptyList()
    private var previewScale = 1f
    private var applied: Triple<Int, Int, Float>? = null
    var onNativeState: (Boolean) -> Unit = {}

    init {
        clipChildren = true
        addView(wallpaper, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(panel)
        icons.forEach { addView(it, LayoutParams(1, 1)) }
        addView(navigation)
    }

    fun update(value: NativeDockGlassGeometry, darkTheme: Boolean, image: Bitmap) {
        if (bitmap !== image) { wallpaper.setImageBitmap(image); bitmap = image }
        geometry = value
        navigationBackground.setColor(if (darkTheme) Color.WHITE else Color.BLACK)
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val screenWidth = context.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds.width()
        previewScale = width.toFloat() / screenWidth.coerceAtLeast(1)
        val density = resources.displayMetrics.density * previewScale
        panelBounds = NativeDockGlassPreviewGeometry.resolve(width, height, density, geometry)
        iconBounds = NativeDockGlassPreviewGeometry.icons(width, height, density, NativeDockGlassPreviewGeometry.iconCount(geometry))
        fun View.measureExact(w: Int, h: Int) = measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        wallpaper.measureExact(width, height)
        panel.measureExact(panelBounds?.width ?: 0, panelBounds?.height ?: 0)
        icons.forEachIndexed { index, icon ->
            val bounds = iconBounds.getOrNull(index)
            icon.measureExact(bounds?.width ?: 0, bounds?.height ?: 0)
        }
        navigation.measureExact((96 * density).roundToInt().coerceAtLeast(0), (4 * density).roundToInt().coerceAtLeast(1))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        wallpaper.layout(0, 0, width, height)
        val bounds = panelBounds
        if (bounds == null) panel.layout(0, 0, 0, 0)
        else panel.layout(bounds.x, bounds.y, bounds.x + bounds.width, bounds.y + bounds.height)
        icons.forEachIndexed { index, icon ->
            val it = iconBounds.getOrNull(index)
            if (it == null) icon.layout(0, 0, 0, 0) else icon.layout(it.x, it.y, it.x + it.width, it.y + it.height)
        }
        val navLeft = (width - navigation.measuredWidth) / 2
        val navTop = height - (10 * resources.displayMetrics.density * previewScale).roundToInt()
        navigation.layout(navLeft, navTop, navLeft + navigation.measuredWidth, navTop + navigation.measuredHeight)
        val ok = bounds != null && isAttachedToWindow && applyGlass(bounds)
        panel.visibility = if (ok) VISIBLE else INVISIBLE
        if (bounds != null && isAttachedToWindow) onNativeState(ok)
    }

    /** Configures the panel's own RenderNode once per size, radius and scale. */
    private fun applyGlass(bounds: DockPreviewBounds): Boolean {
        val key = Triple(bounds.width, bounds.height, bounds.radius)
        if (applied == key) return true
        val native = shader ?: return false
        return runCatching {
            panel.background = GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = bounds.radius }
            val blur = (GLASS_BLUR_PX * previewScale).roundToInt().coerceAtLeast(1)
            // Pixel lengths follow the reduced preview; ratios and directions stay native.
            val params = native.copyOf().apply { this[19] *= previewScale; this[21] *= previewScale; this[22] *= previewScale }
            val int = Int::class.javaPrimitiveType!!
            val float = Float::class.javaPrimitiveType!!
            View::class.java.getMethod("setMiBackgroundBlurMode", int).invoke(panel, 1)
            View::class.java.getMethod("setMiViewBlurMode", int).invoke(panel, 1)
            View::class.java.getMethod("setMiViewMaterialType", int).invoke(panel, 1)
            View::class.java.getMethod("setMiGlassBlurRadius", int, int).invoke(panel, blur, blur)
            View::class.java.getMethod("setMiGlass", FloatArray::class.java).invoke(panel, params)
            View::class.java.getMethod("setMiGlassSdfMaxSize", float, float).invoke(panel, bounds.width.toFloat(), bounds.height.toFloat())
            applied = key
            panel.invalidate()
        }.isSuccess
    }

    fun dispose() { applied = null; wallpaper.setImageDrawable(null); bitmap = null }

    private companion object {
        // Glass_Bionic_Medium_Normal uses 110 px for both blur textures.
        const val GLASS_BLUR_PX = 110

        fun loadGlassShader(context: Context): FloatArray {
            val plugin = context.createPackageContext("miui.systemui.plugin",
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
            val glassClass = plugin.classLoader.loadClass("miuix.theme.token.GlassToken")
            val glass = requireNotNull(glassClass.getField("Glass_Bionic_Medium_Normal").get(null))
            val shader = glassClass.getMethod("toShaderParams").invoke(glass) as FloatArray
            check(shader.size == 42 && shader.all { it.isFinite() }) { "Unknown native glass ABI" }
            return shader.copyOf()
        }
    }
}
