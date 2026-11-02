package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.takekazex.hypertweak.hook.base.BaseHooker
import com.takekazex.hypertweak.hook.base.HookComponent
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.presentation.icon.applyTo
import com.takekazex.hypertweak.util.logging.DebugLog
import java.util.WeakHashMap

/** Borrows the cellular hooker's lifecycle; saves only fields owned by typography. */
internal class NativeMobileTypeTypography(owner: BaseHooker) : HookComponent(owner) {
    private class Font(val size: Float, val face: Typeface?, val axes: String?) {
        var appliedSize = size
        var appliedFace = face
        var appliedAxes = axes
    }
    private val paints = WeakHashMap<Paint, Font>()
    private val texts = WeakHashMap<TextView, Font>()
    private val drawables = WeakHashMap<Drawable, Boolean>()
    private var active = false
    private var drawableClass: Class<*>? = null
    private fun font(paint: Paint) = Font(paint.textSize, paint.typeface, paint.fontVariationSettings)
    private fun restore(paint: Paint, saved: Font) {
        if (paint.textSize == saved.appliedSize) paint.textSize = saved.size
        if (paint.typeface == saved.appliedFace && paint.fontVariationSettings == saved.appliedAxes) {
            paint.typeface = saved.face
            paint.fontVariationSettings = saved.axes
        }
    }
    private fun apply(paint: Paint) {
        paints.remove(paint)?.let { restore(paint, it) }
        val config = MobileTypeTypographySettings.current
        if (!active || !config.enabled) return
        val saved = font(paint)
        config.applyTo(paint)
        saved.appliedSize = paint.textSize; saved.appliedFace = paint.typeface; saved.appliedAxes = paint.fontVariationSettings
        paints[paint] = saved
    }
    private fun restore(view: TextView, saved: Font) {
        if (view.textSize == saved.appliedSize) view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, saved.size)
        if (view.typeface == saved.appliedFace && view.fontVariationSettings == saved.appliedAxes) {
            view.typeface = saved.face
            view.fontVariationSettings = saved.axes
        }
    }
    private fun apply(view: TextView) {
        texts.remove(view)?.let { restore(view, it) }
        val config = MobileTypeTypographySettings.current
        if (!active || !config.enabled) return
        val saved = font(view.paint)
        val paint = Paint(view.paint)
        config.applyTo(paint)
        view.typeface = paint.typeface
        view.fontVariationSettings = paint.fontVariationSettings
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, paint.textSize)
        saved.appliedSize = view.textSize; saved.appliedFace = view.typeface; saved.appliedAxes = view.fontVariationSettings
        texts[view] = saved
    }
    private fun nativePaints(target: Any): List<Paint> = listOf("mMobileTypeTextPaint", "mMobileTypePlusPaint")
        .mapNotNull { HostAccess.read(target, it) as? Paint }

    fun install() {
        active = true
        MobileTypeTypographySettings.current
        val type = "com.miui.systemui.statusbar.views.MobileTypeDrawable".toClassOrNull() ?: return
        drawableClass = type
        val measure = HostAccess.declaredMethod(type, "measure", accessible = true) ?: return
        deoptimize(measure)
        measure.hook {
            before { param -> guarded {
                if (active) {
                    nativePaints(param.thisObject).forEach(::apply)
                    (param.thisObject as? Drawable)?.let { drawables[it] = true }
                }
            } }
        }
        val binder = "com.android.systemui.statusbar.pipeline.mobile.ui.binder.MiuiMobileIconBinder".toClassOrNull() ?: return
        binder.declaredMethods.singleOrNull { it.name == "updateMobileTypeSize" &&
            it.parameterTypes.contentEquals(arrayOf(android.content.Context::class.java, type)) }?.let { method ->
            deoptimize(method)
            method.hook {
                before { param -> guarded {
                    param.args.getOrNull(1)?.let { nativePaints(it).forEach { paint ->
                        paints.remove(paint)?.let { restore(paint, it) }
                    } }
                } }
            }
        }
        binder.declaredMethods.singleOrNull { it.name == "bind" && it.parameterTypes.size == 4 &&
            it.parameterTypes[0] == android.view.ViewGroup::class.java }?.let { method ->
            deoptimize(method)
            method.hook {
                after { param -> guarded {
                    val root = param.args.firstOrNull() as? android.view.ViewGroup ?: return@guarded
                    val views = ArrayList<View>()
                    fun visit(view: View) {
                        views += view
                        if (view is android.view.ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
                    }
                    visit(root)
                    recover(views)
                } }
            }
        }
        // This native helper reapplies TextAppearance/font before measuring either label style.
        binder.declaredMethods.singleOrNull { it.name == "access\$bind\$updateMobileLayoutParams" &&
            it.parameterTypes.size == 6 && it.parameterTypes[0] == TextView::class.java }?.let { method ->
            deoptimize(method)
            method.hook {
                before { param -> guarded {
                    (param.args.firstOrNull() as? TextView)?.let { view -> texts.remove(view)?.let { restore(view, it) } }
                } }
                after { param -> guarded { if (active) (param.args.firstOrNull() as? TextView)?.let(::apply) } }
            }
        }
    }
    fun recover(views: List<View>) = guarded {
        if (!active) return@guarded
        views.forEach { view ->
            if (view is TextView && view.id != View.NO_ID && runCatching {
                    view.resources.getResourceEntryName(view.id) == "mobile_type_single"
                }.getOrDefault(false)) apply(view)
            if (view is ImageView) view.drawable?.takeIf { drawableClass?.isInstance(it) == true }?.let { drawable ->
                HostAccess.declaredMethod(drawable.javaClass, "measure", accessible = true)?.invoke(drawable)
                view.requestLayout(); view.invalidate()
            }
        }
    }
    fun retire() {
        active = false
        paints.entries.toList().forEach { (paint, saved) -> restore(paint, saved) }; paints.clear()
        texts.entries.toList().forEach { (view, saved) -> restore(view, saved) }; texts.clear()
        drawables.keys.toList().forEach { drawable ->
            HostAccess.declaredMethod(drawable.javaClass, "measure", accessible = true)?.invoke(drawable)
        }
        drawables.clear(); drawableClass = null
    }
    private fun guarded(action: () -> Unit) {
        runCatching(action).onFailure { DebugLog.w("IconTuner", "mobile type typography failed", it) }
    }
}
