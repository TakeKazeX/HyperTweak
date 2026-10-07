package com.takekazex.hypertweak.ui.effect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.takekazex.hypertweak.dock.DockConfig
import com.takekazex.hypertweak.dock.DockGeometry
import com.takekazex.hypertweak.dock.DockMaterialView
import com.takekazex.hypertweak.dock.DockBounds
import com.takekazex.hypertweak.dock.DockPreviewGeometry
import kotlin.math.roundToInt

/** Icons are foreground siblings: changing the panel never moves or blurs the sample icons. */
internal class DockPreviewView(context: Context) : FrameLayout(context) {
    private val wallpaper = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val panel = DockMaterialView(context)
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
    private var config = DockConfig()
    private var dark = false
    private var bitmap: Bitmap? = null
    private var panelBounds: DockBounds? = null
    private var iconBounds: List<DockBounds> = emptyList()
    private var previewScale = 1f
    var onNativeState: (Boolean) -> Unit = {}

    init {
        clipChildren = true
        addView(wallpaper, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(panel)
        panel.onMaterialState = { ok ->
            panel.visibility = if (ok) VISIBLE else INVISIBLE
            onNativeState(ok)
        }
        icons.forEach { addView(it, LayoutParams(1, 1)) }
        addView(navigation)
    }
    fun update(value: DockConfig, darkTheme: Boolean, image: Bitmap) {
        if (bitmap !== image) { wallpaper.setImageBitmap(image); bitmap = image }
        config = value
        dark = darkTheme
        navigationBackground.setColor(if (dark) Color.WHITE else Color.BLACK)
        requestLayout()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val screenWidth = context.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds.width()
        previewScale = width.toFloat() / screenWidth.coerceAtLeast(1)
        val density = resources.displayMetrics.density * previewScale
        panelBounds = DockGeometry.resolve(width, height, density, config)
        iconBounds = DockPreviewGeometry.icons(width, height, density)
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
        if (bounds == null) {
            panel.layout(0, 0, 0, 0)
            panel.visibility = INVISIBLE
        } else panel.layout(bounds.x, bounds.y, bounds.x + bounds.width, bounds.y + bounds.height)
        icons.forEachIndexed { index, icon -> iconBounds.getOrNull(index)?.let {
            icon.layout(it.x, it.y, it.x + it.width, it.y + it.height)
        } }
        val navLeft = (width - navigation.measuredWidth) / 2
        val navTop = height - (10 * resources.displayMetrics.density * previewScale).roundToInt()
        navigation.layout(navLeft, navTop, navLeft + navigation.measuredWidth, navTop + navigation.measuredHeight)
        if (bounds != null && isAttachedToWindow) {
            val ok = runCatching { panel.apply(config.style, dark, bounds.radius, previewScale) }.isSuccess
            panel.visibility = if (ok) VISIBLE else INVISIBLE
            onNativeState(ok)
        }
    }
    fun dispose() { panel.stop(); wallpaper.setImageDrawable(null); bitmap = null }
}
