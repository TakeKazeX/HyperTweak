package com.takekazex.hypertweak.hook.rules.ime

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Insets
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import androidx.core.view.isVisible
import com.takekazex.hypertweak.hook.base.BaseHooker
import com.takekazex.hypertweak.hook.base.HookComponent
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.dex.DexContractMembers
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/** Bridges the native bottom bar to keyboards that do not implement Xiaomi's theme/inset API.
 * It borrows the parent's hooks and never replaces the keyboard's own inset listener. */
internal class ImeNativeBottomAppearance(owner: BaseHooker) : HookComponent(owner) {
    private class Host(service: InputMethodService, input: View, area: View, bottom: View, val manager: Class<*>) {
        val service = WeakReference(service)
        val input = WeakReference(input)
        val area = WeakReference(area)
        val bottom = WeakReference(bottom)
        var listener: ViewTreeObserver.OnPreDrawListener? = null
        var pending = false
        var retired = false
        var active: Boolean? = null
        var color: Int? = null
    }
    data class InputColor(val color: Field, val insetReaders: List<Method>)
    private data class PaintFields(val fields: List<Field>)
    private val decorHosts = WeakHashMap<View, Host>()
    private val inputHosts = WeakHashMap<View, Host>()
    private val dispatchHooks = HashSet<Method>()
    private val colorFields = HashMap<Class<*>, Field>()
    private val requestedColors = HashSet<Class<*>>()
    private var manager: Class<*>? = null
    private var alive = true
    private var epoch = 0

    fun install() {
        alive = true
        val method = HostAccess.requireDeclaredMethod(View::class.java, "getRootWindowInsets", accessible = true)
        deoptimize(method)
        method.hook {
            after { param ->
                HookFailurePolicy.open("AospIme", "native root insets", Unit) {
                    val view = param.thisObject as? View ?: return@open
                    val host = decorHosts[view.rootView] ?: return@open
                    val insets = param.result as? WindowInsets ?: return@open
                    if (isActive(host)) param.result = withoutBottomNavigation(insets)
                }
            }
        }
    }

    fun bind(service: InputMethodService, manager: Class<*>) {
        this.manager = manager
        refresh(service)
    }

    fun refresh(service: InputMethodService) {
        if (!alive) return
        val decor = service.window?.window?.decorView ?: return
        if (!AospImeHooker.miuiStyleActive(service)) {
            decorHosts.remove(decor)?.let { removeHost(it); decor.requestApplyInsets() }
            return
        }
        val type = manager ?: return
        val helper = HostAccess.requireDeclaredField(type, "sBottomViewHelper", accessible = true).get(null) ?: return
        if (HostAccess.read(helper, "mInputMethodService") !== service) return
        val supported = HostAccess.requireDeclaredField(type, "sImeMinVersionSupport", accessible = true).get(null) as? Map<*, *>
            ?: return
        // Integrated keyboards already supply Xiaomi's own colors and geometry. Preserve that API.
        if (supported.containsKey(service.packageName)) return
        val input = HostAccess.read(service, "mInputView") as? View ?: return
        val area = HostAccess.read(helper, "mMiuiBottomArea") as? View ?: return
        val bottom = HostAccess.requireDeclaredField(type, "sBottomView", accessible = true).get(null) as? View ?: return
        val old = decorHosts[decor]
        if (old?.input?.get() === input && old.bottom.get() === bottom) {
            schedule(old)
            return
        }
        if (old != null) removeHost(old)
        val host = Host(service, input, area, bottom, type)
        decorHosts[decor] = host
        inputHosts[input] = host
        hookInputDispatch(input)
        if (HostAccess.declaredMethod(input.javaClass, "onDraw", Canvas::class.java, accessible = true) != null &&
            requestedColors.add(input.javaClass)) AospImeHooker.prepareNativeInputColor(input.javaClass)
        val listener = ViewTreeObserver.OnPreDrawListener {
            if (alive) schedule(host)
            true
        }
        host.listener = listener
        input.viewTreeObserver.addOnPreDrawListener(listener)
        schedule(host)
    }

    private fun hookInputDispatch(input: View) {
        val method = HostAccess.method(input, "dispatchApplyWindowInsets", WindowInsets::class.java) ?: return
        if (!dispatchHooks.add(method)) return
        deoptimize(method)
        method.hook {
            before { param ->
                HookFailurePolicy.open("AospIme", "native input insets", Unit) {
                    val view = param.thisObject as? View ?: return@open
                    val host = inputHosts[view] ?: return@open
                    val insets = param.args.firstOrNull() as? WindowInsets ?: return@open
                    if (isActive(host)) param.args[0] = withoutBottomNavigation(insets)
                }
            }
        }
    }

    /** The native sibling bar already reserves the gesture area. Only its duplicate bottom
     * navigation inset is removed; side/cutout/status/IME/gesture insets remain intact. */
    private fun withoutBottomNavigation(insets: WindowInsets): WindowInsets {
        val type = WindowInsets.Type.navigationBars()
        val visible = insets.getInsets(type)
        val stable = insets.getInsetsIgnoringVisibility(type)
        if (visible.bottom == 0 && stable.bottom == 0) return insets
        return WindowInsets.Builder(insets)
            .setInsets(type, Insets.of(visible.left, visible.top, visible.right, 0))
            .setInsetsIgnoringVisibility(type, Insets.of(stable.left, stable.top, stable.right, 0))
            .build()
    }

    private fun isActive(host: Host): Boolean {
        if (!alive || host.retired) return false
        val service = host.service.get() ?: return false
        val area = host.area.get() ?: return false
        return AospImeHooker.miuiStyleActive(service) && !service.isFullscreenMode && !service.isExtractViewShown &&
            area.isShown && area.height > 0
    }

    private fun schedule(host: Host) {
        if (!alive || host.retired || host.pending) return
        val input = host.input.get() ?: return
        host.pending = true
        val generation = epoch
        input.post {
            if (!alive || host.retired || generation != epoch) return@post
            host.pending = false
            HookFailurePolicy.open("AospIme", "native appearance", Unit) {
                val active = isActive(host)
                if (host.active != active) {
                    host.active = active
                    // Also refresh Gboard's decor-level WindowMetricsHelper, which reads root
                    // insets rather than the input view's dispatched value.
                    input.rootView.requestApplyInsets()
                    input.requestLayout()
                    DebugLog.i("AospIme", "native bottom inset owner active=$active")
                }
                if (active) syncColor(host)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun syncColor(host: Host) {
        val input = host.input.get() ?: return
        val service = host.service.get() ?: return
        val drawnColor = colorFields[input.javaClass]?.getInt(input)?.takeIf { Color.alpha(it) == 255 }
        val color = drawnColor ?: surfaceColor(input) ?: service.window?.window?.navigationBarColor?.takeIf { Color.alpha(it) == 255 }
            ?: return
        val bottom = host.bottom.get() ?: return
        // The native skin refresh resets its drawable without changing our last requested color.
        if (host.color == color && (bottom.background as? ColorDrawable)?.color == color) return
        val icon = ImeNativeBottomPolicy.iconColor(color)
        HostAccess.requireDeclaredMethod(host.manager, "setBottomColor", Boolean::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
            accessible = true).invoke(null, true, color, icon, (icon and 0x00FFFFFF) or 0x99000000.toInt())
        host.color = color
        DebugLog.i("AospIme", "native bottom theme synced color=${Integer.toHexString(color)}")
    }

    private fun surfaceColor(root: View): Int? {
        drawableColor(root.background)?.let { return it }
        var color: Int? = null
        // Follow the broad keyboard surface at its lower edge, never an individual key.
        val y = root.height - root.paddingBottom - 1
        fun visit(view: View, top: Int, depth: Int) {
            if (depth > 12 || !view.isVisible || !ImeNativeBottomPolicy.isSurface(view.width, view.height, root.width, root.height)) return
            if (top > y || top + view.height <= y) return
            drawableColor(view.background)?.let { color = it }
            if (view is ViewGroup) for (index in 0 until view.childCount) {
                val child = view.getChildAt(index)
                visit(child, top + child.top - view.scrollY, depth + 1)
            }
        }
        if (root is ViewGroup) for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            visit(child, child.top - root.scrollY, 1)
        }
        return color
    }

    private fun drawableColor(drawable: Drawable?, depth: Int = 0): Int? {
        if (drawable == null || depth > 6) return null
        val color = when (drawable) {
            is ColorDrawable -> drawable.color
            is GradientDrawable -> drawable.color?.let { it.getColorForState(drawable.state, it.defaultColor) }
            is InsetDrawable -> drawableColor(drawable.drawable, depth + 1)
            is LayerDrawable -> (drawable.numberOfLayers - 1 downTo 0).firstNotNullOfOrNull {
                drawableColor(drawable.getDrawable(it), depth + 1)
            }
            else -> {
                if (drawable.current !== drawable) return drawableColor(drawable.current, depth + 1)
                val fill = HostAccess.publicMethod(drawable.javaClass, "getFillColor", accessible = true)
                    ?.invoke(drawable) as? ColorStateList
                fill?.getColorForState(drawable.state, fill.defaultColor) ?: paintColor(drawable)
            }
        }
        return color?.takeIf { Color.alpha(it) == 255 }
    }

    /** WeType's bottom-inset Drawable exposes its color through a unique solid fill Paint.
     * Read that value without rendering the keyboard, allocating bitmaps or sampling the screen. */
    private fun paintColor(drawable: Drawable): Int? {
        val fields = HostAccess.cached(drawable.javaClass, "ime.drawable.solid-paints", PaintFields::class.java) {
            PaintFields(generateSequence(drawable.javaClass as Class<*>?) { it.superclass }
                .takeWhile { it != Drawable::class.java }.flatMap { type ->
                    type.declaredFields.asSequence().filter { it.type == Paint::class.java }.map { it.apply { isAccessible = true } }
                }.toList())
        } ?: return null
        val colors = fields.fields.mapNotNull { field ->
            val paint = field.get(drawable) as? Paint ?: return@mapNotNull null
            paint.color.takeIf { paint.style == Paint.Style.FILL && paint.shader == null && Color.alpha(it) == 255 }
        }.distinct()
        return colors.singleOrNull()
    }

    /** Gboard on API 36+ stores its themed navigation fill in InputView, without calling
     * Window.setNavigationBarColor. Resolve that carrier from the actual onDraw data flow,
     * rather than an obfuscated field name or a version-specific member table. */
    fun resolveInputColor(type: Class<*>): InputColor? {
        val apk = hookParam.appInfo?.sourceDir ?: return null
        val loader = type.classLoader ?: return null
        val draw = HostAccess.declaredMethod(type, "onDraw", Canvas::class.java, accessible = true) ?: return null
        return resolveDexContract("ime.native-bottom.color.${type.name}", apk, schema = 2,
            compute = { bridge ->
                val data = bridge.getMethodData(draw) ?: return@resolveDexContract null
                val calls = data.invokes.map { it.descriptor }
                if ("Landroid/graphics/Paint;->setColor(I)V" !in calls ||
                    calls.none { it.startsWith("Landroid/graphics/Canvas;->drawRect(") } ||
                    calls.none { it.endsWith("->getPaddingBottom()I") }) return@resolveDexContract null
                val color = data.usingFields.filter { it.usingType.isRead() }.map { it.field.getFieldInstance(loader) }
                    .distinct().singleOrNull { it.declaringClass == type && it.type == Int::class.javaPrimitiveType &&
                        !Modifier.isStatic(it.modifiers) }?.apply { isAccessible = true } ?: return@resolveDexContract null
                // Hooking the framework getter cannot repair an already inlined app call.
                // Deoptimize its actual metrics-listener callers identified in this APK.
                val readers = bridge.getMethodData("Landroid/view/View;->getRootWindowInsets()Landroid/view/WindowInsets;")
                    ?.callers.orEmpty().filter { caller ->
                        caller.isMethod && caller.methodName == "onLayoutChange" && caller.paramCount == 9
                    }.mapNotNull { runCatching { it.getMethodInstance(loader) }.getOrNull() }
                    .filter { View.OnLayoutChangeListener::class.java.isAssignableFrom(it.declaringClass) }.distinct()
                InputColor(color, readers)
            },
            encode = { DexContractMembers.encode("color" to it.color, "insetReaders" to it.insetReaders) },
            decode = { members -> members.field("color").takeIf {
                it.declaringClass == type && it.type == Int::class.javaPrimitiveType && !Modifier.isStatic(it.modifiers)
            }?.apply { isAccessible = true }?.let { InputColor(it, members.methods("insetReaders")) } }
        )
    }

    fun publishInputColor(type: Class<*>, target: InputColor) {
        if (!alive) return
        target.insetReaders.forEach { check(deoptimize(it)) { "Cannot deoptimize native IME inset reader: $it" } }
        colorFields[type] = target.color
        DebugLog.i("AospIme", "native bottom palette contract root=${type.name} insetReaders=${target.insetReaders.size}")
        decorHosts.values.filter { it.input.get()?.javaClass == type }.forEach(::schedule)
    }

    private fun removeHost(host: Host) {
        host.retired = true
        host.input.get()?.let { input ->
            inputHosts.remove(input)
            host.listener?.let { if (input.viewTreeObserver.isAlive) input.viewTreeObserver.removeOnPreDrawListener(it) }
        }
    }

    fun retire() {
        alive = false
        epoch++
        decorHosts.values.toList().forEach(::removeHost)
        decorHosts.clear()
        inputHosts.clear()
        dispatchHooks.clear()
        manager = null
        colorFields.clear()
        requestedColors.clear()
    }
}
