package com.takekazex.hypertweak.hook.rules.systemui.island.flashlight

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import com.takekazex.hypertweak.util.logging.DebugLog
import androidx.core.graphics.drawable.toDrawable

/**
 * The plugin's own flashlight page, inflated from its resources with its class loader:
 * `layout/mi_flashlight_layout` (`mi_seekbar_bg` track and `MiVerticalSeekBar`) with its
 * `mi_fl_view` stub replaced the way the page does it: by the OpenGL renderer
 * ([FlashlightGlSupport]) on capable devices, or by `layout/mi_flashlight_static_effect`, the
 * low-end `MiFlashlightUiFolme` layers (`mi_flash_low_close` body, `mi_flash_long_light` and
 * `mi_flash_compose_light` beam). Both are inflated, so a renderer failure falls back in place.
 */
internal class FlashlightNativeAssets private constructor(
    private val context: Context,
    private val base: Bitmap,
    private val longLight: Bitmap,
    private val composeLight: Bitmap,
    private val gl: FlashlightGlSupport?,
) {
    /** Set once a renderer fails; later panels start on the static layers. */
    @Volatile var glFailed = false

    fun inflate(withGl: Boolean): FlashlightNativeViews {
        val resources = context.resources
        val inflater = inflater()
        val root = inflater.inflate(id("mi_flashlight_layout", "layout"), null, false) as ViewGroup
        val stub = root.findViewById<ViewStub>(id("mi_fl_view", "id")) ?: error("missing id/mi_fl_view")
        val parent = stub.parent as ViewGroup
        val index = parent.indexOfChild(stub)
        parent.removeViewAt(index)
        val stage = FrameLayout(context)
        parent.addView(stage, index, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val effect = inflater.inflate(id("mi_flashlight_static_effect", "layout"), stage, false)
        stage.addView(effect)
        fun image(name: String) = effect.findViewById<ImageView>(id(name, "id")) ?: error("missing id/$name")
        image("mi_flash_layer0").setImageDrawable(base.toDrawable(resources))
        val longView = image("mi_flash_layer1").apply { setImageDrawable(longLight.toDrawable(resources)) }
        val composeView = image("mi_flash_layer2").apply { setImageDrawable(composeLight.toDrawable(resources)) }
        val container = root.findViewById<View>(id("mi_seekbar_container", "id")) ?: error("missing id/mi_seekbar_container")
        val track = root.findViewById<View>(id("mi_seekbar_bg", "id")) ?: error("missing id/mi_seekbar_bg")
        val seekbar = root.findViewById<SeekBar>(id("mi_fl_seekbar", "id")) ?: error("missing id/mi_fl_seekbar")
        // The page's own switch and full-height touch strip sit outside the island crop.
        root.findViewById<View>(id("mi_seekbar_touch_view", "id"))?.visibility = View.GONE
        root.findViewById<View>(id("mi_flashlight_lottie_view", "id"))?.visibility = View.GONE
        // The page sits on a black window; the root's own colour is only the press dim.
        root.background = null
        track.visibility = View.VISIBLE
        seekbar.visibility = View.VISIBLE
        seekbar.isEnabled = false
        seekbar.max = FlashlightSlider.MAX

        // The last frame the renderer drew, shown while a new surface warms up and in the fake copy.
        val frame = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            visibility = View.GONE
        }
        stage.addView(frame, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val renderer = if (withGl && !glFailed) gl?.let { support ->
            runCatching { support.create(context) }
                .onFailure { DebugLog.e(TAG, "native flashlight renderer creation failed", it); glFailed = true }
                .getOrNull()
        } else null
        val views = FlashlightNativeViews(root, effect, frame, longView, composeView, container, seekbar, renderer)
        if (renderer != null) {
            stage.addView(renderer.view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            effect.visibility = View.GONE
            views.coverRenderer()
            renderer.onSurfacePending = views::coverRenderer
            renderer.onFirstFrame = views::revealRenderer
            renderer.onFailure = {
                glFailed = true
                views.fallBackToStatic()
            }
        }
        return views
    }

    /**
     * The page's switch is a `LottieAnimationView` that starts loading `flashlight.json` on a
     * worker as soon as it is inflated, and Lottie's default failure listener throws on the main
     * thread, outside any boundary here. The island never shows that switch, so it is inflated as
     * a plain [View] with the same attributes and no Lottie code runs at all.
     */
    private fun inflater(): LayoutInflater = LayoutInflater.from(context).cloneInContext(context).apply {
        factory2 = object : LayoutInflater.Factory2 {
            override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? =
                if (name.endsWith(".LottieAnimationView")) View(context, attrs) else null

            override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? =
                onCreateView(null, name, context, attrs)
        }
    }

    private fun id(name: String, type: String): Int =
        context.resources.getIdentifier(name, type, PLUGIN_PACKAGE).also { require(it != 0) { "missing $type/$name" } }

    companion object {
        const val PLUGIN_PACKAGE = "miui.systemui.plugin"
        private const val TAG = "IslandFlashlight"

        /**
         * [viewContext] carries the plugin resources; [pluginLoader] resolves its custom views
         * (`MiVerticalSeekBar`, the renderer) exactly as the plugin's own inflation does.
         */
        fun load(viewContext: Context, pluginLoader: ClassLoader): FlashlightNativeAssets {
            val resourceContext = if (viewContext.resources.getIdentifier("mi_flashlight_layout", "layout", PLUGIN_PACKAGE) != 0)
                viewContext else viewContext.createPackageContext(PLUGIN_PACKAGE, 0)
            val context = object : ContextWrapper(resourceContext) {
                override fun getClassLoader(): ClassLoader = pluginLoader
                override fun getApplicationContext(): Context = resourceContext.applicationContext ?: resourceContext
            }
            fun bitmap(name: String): Bitmap {
                val id = context.resources.getIdentifier(name, "drawable", PLUGIN_PACKAGE)
                require(id != 0) { "missing drawable/$name" }
                return requireNotNull(BitmapFactory.decodeResource(context.resources, id)) { "undecodable drawable/$name" }
            }
            return FlashlightNativeAssets(context, bitmap("mi_flash_low_close"), bitmap("mi_flash_long_light"),
                bitmap("mi_flash_compose_light"), FlashlightGlSupport.resolve(pluginLoader)).also { it.inflate(withGl = false) }
        }
    }
}

internal class FlashlightNativeViews(
    val root: ViewGroup,
    private val staticEffect: View,
    private val frame: ImageView,
    val longLight: ImageView,
    val composeLight: ImageView,
    val sliderContainer: View,
    val seekbar: SeekBar,
    gl: FlashlightGl?,
) {
    var gl: FlashlightGl? = gl
        private set

    /**
     * The native page sizes the compose layer to half the display height. The renderer's view is
     * laid out at the size the page is shown at and scaled back up to fill it, so its surface
     * holds only the pixels on screen; the shader depends on the aspect ratio alone.
     */
    fun sizeFor(pageWidth: Int, pageHeight: Int, scale: Float) {
        gl?.view?.let { view ->
            val params = view.layoutParams
            val targetW = (pageWidth * scale).toInt().coerceAtLeast(1)
            val targetH = (pageHeight * scale).toInt().coerceAtLeast(1)
            if (params.width != targetW || params.height != targetH) {
                params.width = targetW
                params.height = targetH
                view.layoutParams = params
            }
            view.pivotX = 0f
            view.pivotY = 0f
            view.scaleX = 1f / scale
            view.scaleY = 1f / scale
        }
        val params = composeLight.layoutParams ?: return
        if (params.height != pageHeight / 2) {
            params.height = pageHeight / 2
            composeLight.layoutParams = params
        }
        if (root.layoutParams == null) root.layoutParams = FrameLayout.LayoutParams(0, 0)
    }

    private var lastFrame: Bitmap? = null

    /**
     * Shows [bitmap], the renderer's last frame, in place of the static layers. Without a
     * renderer (the fake copy), it stands in for the live panel; with one, it covers a new
     * surface until that has drawn.
     */
    fun showFrame(bitmap: Bitmap?) {
        lastFrame = bitmap
        if (bitmap == null) return
        frame.setImageBitmap(bitmap)
        if (gl == null) {
            frame.visibility = View.VISIBLE
            staticEffect.visibility = View.GONE
        }
    }

    /**
     * Until a new surface has drawn, the renderer is hidden; the page holds its renderer back the
     * same way. The cover is its last frame, so the swap is invisible; the first time there is
     * none, and the renderer fades in from the panel's black, the way the page opens.
     */
    fun coverRenderer() {
        val view = gl?.view ?: return
        view.animate().cancel()
        frame.animate().cancel()
        view.alpha = 0f
        staticEffect.visibility = View.GONE
        frame.alpha = 1f
        frame.visibility = if (lastFrame != null) View.VISIBLE else View.GONE
    }

    fun revealRenderer() {
        val view = gl?.view ?: return
        if (view.alpha == 1f) return
        view.animate().alpha(1f).setDuration(REVEAL_MS).withEndAction {
            if (gl != null && view.alpha == 1f) frame.visibility = View.GONE
        }.start()
    }

    fun fallBackToStatic() {
        val renderer = gl ?: return
        gl = null
        renderer.setRunning(false)
        renderer.view.animate().cancel()
        (renderer.view.parent as? ViewGroup)?.removeView(renderer.view)
        frame.visibility = View.GONE
        staticEffect.visibility = View.VISIBLE
    }

    private companion object {
        const val REVEAL_MS = 180L
    }
}
