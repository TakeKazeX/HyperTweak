package com.takekazex.hypertweak.hook.rules.systemui.island.flashlight

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import android.view.View
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import androidx.core.graphics.createBitmap

/**
 * The plugin's OpenGL flashlight (`MiFlashlightUiOpenGl`): `FlashlightRender`, an `MglRenderer`
 * whose shader draws the body shadow, beams and metal ring, on a `miuix.mgl.android.GLTextureView`.
 *
 * The full-screen page runs it in the plugin's `:flashlight` process; here it runs inside
 * SystemUI, so the contract is checked up front and the native library is loaded on the main
 * thread. `GLTextureView`'s render thread has no exception guard, so every thread it starts gets
 * an uncaught-exception handler that turns a renderer failure into [FlashlightGl.onFailure]
 * instead of a SystemUI crash.
 */
internal class FlashlightGlSupport private constructor(
    private val viewConstructor: Constructor<*>,
    private val renderConstructor: Constructor<*>,
    private val attach: Method,
    private val setProgress: Method,
    private val pause: Method,
    private val resume: Method,
    private val renderThread: Field,
    private val firstFrameListener: Method,
    private val firstFrameType: Class<*>,
    private val firstFramePending: Field,
) {
    fun create(context: Context): FlashlightGl {
        val view = viewConstructor.newInstance(context) as View
        val renderer = renderConstructor.newInstance(context)
        attach.invoke(renderer, view)
        // Nothing renders until the panel is on screen.
        pause.invoke(renderer)
        val gl = FlashlightGl(view, renderer, this)
        // The page reveals its renderer only once it has drawn (`OnFirstFrameDrawListener`); the
        // renderer posts the call to the main thread itself.
        val listener = Proxy.newProxyInstance(firstFrameType.classLoader, arrayOf(firstFrameType)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "HyperTweakFirstFrameListener"
                else -> { gl.onFirstFrameDrawn(); null }
            }
        }
        firstFrameListener.invoke(renderer, listener)
        return gl
    }

    internal fun setProgress(renderer: Any, progress: Float) = setProgress.invoke(renderer, progress)
    internal fun pause(renderer: Any) = pause.invoke(renderer)
    internal fun resume(renderer: Any) = resume.invoke(renderer)
    internal fun renderThread(view: View): Thread? = renderThread.get(view) as? Thread
    /** A new surface starts with a raw first frame, so each one is announced again. */
    internal fun armFirstFrame(renderer: Any) = firstFramePending.setBoolean(renderer, true)

    companion object {
        private const val TAG = "IslandFlashlight"
        private const val RENDER = "miui.systemui.flashlight.effect.FlashlightRender"
        private const val MGL_RENDERER = "miuix.mgl.android.MglRenderer"
        private const val GL_VIEW = "miuix.mgl.android.GLTextureView"
        /** Its static initializer runs `MglNative.init()`, the `libmglnative` load. */
        private const val NATIVE_OBJECT = "miuix.mgl.utils.NativeObject"

        /** Null when any part of the contract is missing; the island then uses the static layers. */
        fun resolve(loader: ClassLoader): FlashlightGlSupport? = runCatching {
            val render = Class.forName(RENDER, false, loader)
            val base = Class.forName(MGL_RENDERER, false, loader)
            val view = Class.forName(GL_VIEW, false, loader)
            check(base.isAssignableFrom(render)) { "$RENDER is not an MglRenderer" }
            check(TextureView::class.java.isAssignableFrom(view)) { "$GL_VIEW is not a TextureView" }
            fun method(owner: Class<*>, name: String, vararg params: Class<*>) =
                owner.getMethod(name, *params).also { check(it.returnType == Void.TYPE) { "$name returns ${it.returnType}" } }
            val float = Float::class.javaPrimitiveType!!
            val thread = view.declaredFields.single { Thread::class.java.isAssignableFrom(it.type) }
                .apply { isAccessible = true }
            // The listener setter takes a one-method interface nested in the renderer.
            val listenerSetter = render.declaredMethods.single { m ->
                m.returnType == Void.TYPE && m.parameterTypes.size == 1 && m.parameterTypes[0].isInterface &&
                    m.parameterTypes[0].declaringClass == render
            }
            val listenerType = listenerSetter.parameterTypes[0]
            check(listenerType.methods.count { it.declaringClass == listenerType } == 1) { "unexpected first-frame listener" }
            val pending = render.declaredFields.single { it.type == Boolean::class.javaPrimitiveType }
                .apply { isAccessible = true }
            val support = FlashlightGlSupport(
                view.getConstructor(Context::class.java),
                render.getConstructor(Context::class.java),
                method(render, "attachToView", View::class.java),
                method(render, "setProgress", float),
                method(render, "pause"),
                method(render, "resume"),
                thread,
                listenerSetter.apply { isAccessible = true },
                listenerType,
                pending,
            )
            Class.forName(NATIVE_OBJECT, true, loader)
            support
        }.onFailure { DebugLog.w(TAG, "native OpenGL flashlight unavailable; using the static layers", it) }.getOrNull()
    }
}

/** One renderer and its view. Main thread only, except the render thread's failure report. */
internal class FlashlightGl(
    val view: View,
    private val renderer: Any,
    private val support: FlashlightGlSupport,
) {
    private val main = Handler(Looper.getMainLooper())
    private var running = false
    @Volatile var failed = false
        private set
    var onFailure: (() -> Unit)? = null
    /** A surface was (re)created; its first frame is not drawn yet. */
    var onSurfacePending: (() -> Unit)? = null
    var onFirstFrame: (() -> Unit)? = null

    init {
        guardRenderThread()
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            // `onAttachedToWindow` starts a new render thread, with a new surface, after a detach.
            override fun onViewAttachedToWindow(v: View) {
                guardRenderThread()
                if (failed) return
                runCatching { support.armFirstFrame(renderer) }
                onSurfacePending?.invoke()
            }

            override fun onViewDetachedFromWindow(v: View) = Unit
        })
    }

    private fun guardRenderThread() {
        val thread = runCatching { support.renderThread(view) }.getOrNull() ?: return
        thread.setUncaughtExceptionHandler { _, error ->
            DebugLog.e(TAG, "native flashlight renderer failed; switching to the static layers", error)
            failed = true
            main.post {
                running = false
                runCatching { support.pause(renderer) }
                onFailure?.invoke()
            }
        }
    }

    /**
     * The frame on screen now, or null without a live surface. Each attach destroys the
     * `TextureView` surface, so this is taken before the host detaches the panel.
     *
     * The read-back is synchronous on the main thread, right when the host starts a transition,
     * so it is taken at half size into [reuse] when that fits: the frame only stands in, scaled,
     * for the moments the live renderer cannot show.
     */
    fun capture(reuse: Bitmap?): Bitmap? {
        val texture = view as? TextureView ?: return null
        if (failed || !texture.isAvailable || texture.width == 0 || texture.height == 0) return null
        val w = (texture.width / 2).coerceAtLeast(1)
        val h = (texture.height / 2).coerceAtLeast(1)
        return runCatching {
            val target = reuse?.takeIf { !it.isRecycled && it.isMutable && it.width == w && it.height == h }
                ?: createBitmap(w, h)
            texture.getBitmap(target)
        }.onFailure { DebugLog.w(TAG, "flashlight frame capture failed", it) }.getOrNull()
    }

    internal fun onFirstFrameDrawn() {
        if (!failed) onFirstFrame?.invoke()
    }

    /** `MiFlashlightController.updateFlashlightUi` progress, 0..1. */
    fun setProgress(progress: Float) {
        if (failed) return
        runCatching { support.setProgress(renderer, progress) }
    }

    fun setRunning(run: Boolean) {
        if (failed || run == running) return
        running = run
        runCatching { if (run) support.resume(renderer) else support.pause(renderer) }
            .onFailure { DebugLog.e(TAG, "flashlight renderer ${if (run) "resume" else "pause"} failed", it) }
    }

    private companion object {
        const val TAG = "IslandFlashlight"
    }
}
