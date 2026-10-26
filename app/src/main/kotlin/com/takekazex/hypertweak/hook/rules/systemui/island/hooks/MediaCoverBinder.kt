package com.takekazex.hypertweak.hook.rules.systemui.island.hooks

import android.animation.ValueAnimator
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandSlot
import com.takekazex.hypertweak.hook.rules.systemui.island.host.NativeMediaCoverContract
import com.takekazex.hypertweak.util.logging.DebugLog
import io.github.libxposed.api.XposedInterface
import java.util.IdentityHashMap

/** The native setter still resolves artwork; only its visible publication waits for onFlip. */
internal class MediaCoverBinder(private val context: IslandHookContext, private val native: NativeMediaCoverContract) {
    private class Entry(val animator: Any, var latest: Drawable, var displayed: Drawable) {
        var awaitingSwap = false
        var detach: View.OnAttachStateChangeListener? = null
    }
    private val entries = IdentityHashMap<ImageView, Entry>()

    fun install() {
        val registrar = context.registrar
        registrar.deoptimize(native.caller)
        registrar.runtime(native.bind.also(registrar::deoptimize), "three_islands_native_cover_flip",
            XposedInterface.Hooker { chain ->
                val holder = chain.thisObject ?: return@Hooker chain.proceed()
                val before = runCatching { native.image(holder) }.getOrNull()
                val previous = before?.drawable
                val result = chain.proceed()
                var published: Drawable? = null
                var target: ImageView? = null
                runCatching {
                    val image = native.image(holder) ?: return@runCatching
                    val next = image.drawable
                    target = image
                    published = next
                    if (image !== before || previous == null || next == null || !native.isAlbum(holder) ||
                        !eligible(image) || !ValueAnimator.areAnimatorsEnabled()) {
                        clear(image, restoreLatest = false)
                        return@runCatching
                    }
                    update(image, previous, next)
                }.onFailure {
                    (target ?: before)?.let { image ->
                        clear(image, restoreLatest = false)
                        if (target != null) image.setImageDrawable(published)
                    }
                    DebugLog.w(TAG, "native cover flip failed; retain native artwork", it)
                }
                result
            })
        listOf(native.hidden, native.detach).forEach { method ->
            registrar.before(method.also(registrar::deoptimize), "three_islands_cover_${method.name}") { param ->
                runCatching { native.image(param.thisObject)?.let { clear(it) } }
                    .onFailure { DebugLog.w(TAG, "release native cover flip", it) }
            }
        }
        registrar.before(context.access.method(context.access.window, "onConfigChanged"),
            "three_islands_cover_configuration") { retire() }
    }

    private fun eligible(image: ImageView): Boolean {
        if (!context.runtime.isMainThread() || context.runtime.retiring() || !image.isShown ||
            !image.isAttachedToWindow || image.width == 0 || image.height == 0) return false
        val content = generateSequence(image.parent) { it.parent }.filterIsInstance<View>()
            .firstOrNull(context.access.content::isInstance) ?: return false
        val runtime = context.runtime
        val session = runtime.sessionForView(content) ?: return false
        return runtime.enabled(session) && content in runtime.summaries(session) &&
            content !== context.host.current(session.owner, IslandSlot.EXPANDED)
    }

    private fun update(image: ImageView, previous: Drawable, next: Drawable) {
        val entry = entries.getOrPut(image) {
            Entry(native.animator(), previous, previous).also { entry ->
                entry.detach = object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) = Unit
                    override fun onViewDetachedFromWindow(view: View) { clear(image) }
                }.also(image::addOnAttachStateChangeListener)
            }
        }
        if (same(entry.latest, next)) {
            image.setImageDrawable(entry.displayed)
            return
        }
        entry.latest = next
        image.setImageDrawable(entry.displayed)
        // Rapid updates before the edge-on swap replace the pending artwork. The native
        // utility intentionally ignores new listeners during that half of a flip.
        if (entry.awaitingSwap) return
        entry.awaitingSwap = true
        native.flip(entry.animator, image) {
            if (entries[image] === entry) {
                runCatching {
                    entry.displayed = entry.latest
                    entry.awaitingSwap = false
                    image.setImageDrawable(entry.displayed)
                }.onFailure {
                    clear(image)
                    DebugLog.w(TAG, "publish native cover flip", it)
                }
            }
        }
    }

    private fun same(first: Drawable, second: Drawable): Boolean = first === second ||
        (first is BitmapDrawable && second is BitmapDrawable &&
            !first.bitmap.isRecycled && !second.bitmap.isRecycled && first.bitmap.sameAs(second.bitmap))

    private fun clear(image: ImageView, restoreLatest: Boolean = true) {
        val entry = entries.remove(image) ?: return
        entry.detach?.let(image::removeOnAttachStateChangeListener)
        runCatching { native.reset(image) }
            .onFailure { DebugLog.w(TAG, "reset native cover transform", it) }
        if (restoreLatest) image.setImageDrawable(entry.latest)
    }

    fun retire() = entries.keys.toList().forEach { clear(it) }

    private companion object { const val TAG = "ThreeIslands" }
}
