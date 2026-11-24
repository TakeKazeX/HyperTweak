package com.takekazex.hypertweak.hook.rules.systemui.lockscreen

import android.animation.ValueAnimator
import android.graphics.Outline
import android.graphics.Point
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.HostRecoveryPhase
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap
import kotlin.math.min

/**
 * Gives every lock-screen PIN key the glass circle of the emergency dialer keypad.
 *
 * Emergency dialer keys (`com.android.phone.DialpadButton`, TeleService `a4d6176a…`) are miuix
 * `CallOvalImageButton`s with `MaterialUtils.Glass_Colored_Extra_Thin_Dark`. With bionic glass on,
 * only its glass config is applied: `setMiViewBlurMode(1)`, `setMiViewMaterialType(1)`,
 * `setMiGlassBlurRadius(120, 120)` and `setMiGlass(Glass_Bionic_Medium_Bold_Motion)`. A press
 * interpolates the 42 shader params toward `..._Press` (`PressLightAnimator`). Params below are
 * `GlassToken.toShaderParams()` of those tokens, which flattens the `create()` arguments in order.
 *
 * SystemUI's `NumPadKey` (`6c6c1121…`) draws nothing at rest; its press ring's full radius is
 * `min(w, h) / 2`. A tagged disc view of that diameter is added as child 0 of each key and
 * centred after the host's `onLayout` (which only positions its two text views). The disc is not
 * clickable, so touches still reach the key. The glass shape follows its round-rect outline, the
 * same way SystemUI shapes its own keyguard glass buttons (`NotificationUtil.setRoundRect`).
 * The disc needs a (transparent) background: MiGlass is drawn in the background pass.
 *
 * MiBlur glass is a container/element pair: an element (`setMiViewBlurMode(1)` + `setMiGlass`)
 * samples the backdrop captured by an ancestor container (`setMiBackgroundBlurMode(1)`). The
 * dialer's container is `R.id.emergency_dialer` (`setBackgroundBlur(60dp)`). The bouncer has none,
 * so the enclosing `KeyguardSecurityContainer` becomes one, matching the in-call dialpad
 * (InCallUI `258b1856…`: button container `setBackgroundBlur(60dp)`, root `Mask_Glass_Light`):
 * pass-window blur on (the wallpaper is another window), radius 60 dp, and the mask blend layers
 * SOFT_LIGHT `0xFF999999` + LAB `0xFF0D8000`. SystemUI never puts MiBlur state on that view (its
 * editor-mode blur is on the parent), so clearing restores it.
 *
 * Like the dialers, glass keys show no stroke: the key's `NumPadRippleDrawable` press ring is
 * taken off and kept in a view tag, so any generation can put it back. The host only animates
 * that ring through `background is NumPadRippleDrawable` checks, which then do nothing. The disc
 * keeps the native ring diameter; PIN rows are packed tight to clear the fingerprint sensor.
 *
 * Live: enabling adds discs to living keys, disabling and retirement remove them. Discs are
 * found by tag, so the next generation also removes or reuses discs left by any older build.
 */
object LockscreenPinGlassKeysHooker : StaticHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingEnabled by hookSetting(false) {
        Preferences.getBoolean(SystemUiSettingsKeys.KEY_LOCKSCREEN_PIN_GLASS_KEYS, false)
    }

    private const val TAG = "LockscreenPinGlassKeys"
    private const val NUM_PAD_KEY = "com.android.keyguard.NumPadKey"
    private const val SECURITY_CONTAINER = "com.android.keyguard.KeyguardSecurityContainer"
    private const val CONTAINER_RADIUS_DP = 60f
    private const val BLEND_SOFT_LIGHT = 21
    private const val BLEND_LAB = 106
    /** View tag key (non-resource, high byte ≥ 2) holding a key's native background. */
    private const val NATIVE_BACKGROUND_KEY = 0x5a71c0de
    /** Boot-class String, so discs from another generation's classloader still match. */
    private const val DISC_TAG = "hypertweak:pin_glass_disc"
    private const val GLASS_BLUR_RADIUS = 120
    private const val PRESS_IN_MS = 180L
    private const val PRESS_OUT_MS = 320L

    private val NORMAL = floatArrayOf(
        0f, 0f, 0.09f, 0.8f, 0.24f, 1.4f, 0.14f, 0.14f, 0.6f, 1f,
        0.03f, 1f, 1f, 1f, 0.6f, 0.2f, 0.3f, 1f, 1f,
        35f, 2.4f, 80f, 1200f, 0.6f, 0.8f,
        -0.4f, 0.6f, -0.8f, 1f, 0.8f, 1.4f, 1f,
        4f, 2.6f, 0f, 0f, 0f,
        0f, 0f, 0f, 0f, 0f
    )
    private val PRESSED = NORMAL.copyOf().also {
        it[37] = 1.3f; it[38] = 1.4f; it[39] = 0.08f; it[40] = 0.08f; it[41] = 0.08f
    }

    /** MIUI `android.view.View` glass API, resolved by parameter list; several return boolean. */
    private class GlassApi(
        val viewMode: Method, val materialType: Method, val blurRadius: Method, val glass: Method,
        val passWindow: Method, val bgMode: Method, val bgRadius: Method,
        val blendColors: Method, val clearBlend: Method, val blurType: Method
    )

    private class Disc(val view: View) {
        val params = NORMAL.copyOf()
        var progress = 0f
        var target = 0f
        var animator: ValueAnimator? = null
    }

    init {
        hostRecovery(HostRecoveryPhase.WINDOWS) { scope -> recover(scope.views) }
    }

    private var keyClass: Class<*>? by generationState { null }
    private var api: GlassApi? by generationState { null }
    private var containerClass: Class<*>? by generationState { null }
    @Volatile private var enabled = false
    @Volatile private var retiring = false

    /** Keys seen by this generation mapped to their disc, null when the key carries none. */
    private val keys = generationMainResource(WeakHashMap<ViewGroup, Disc?>()) { it.clear() }
    /** Security containers this generation turned into blur containers. */
    private val containers = generationMainResource(WeakHashMap<View, Boolean>()) { it.clear() }
    private val mainHandler = generationMainHandler()

    private val discOutline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, min(view.width, view.height) / 2f)
        }
    }

    override fun onHook() {
        if (!isMainProcess) return
        retiring = false
        enabled = settingEnabled
        val key = NUM_PAD_KEY.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, NUM_PAD_KEY, "class not found")
            return
        }
        fun declared(name: String, vararg types: Class<*>) = key.declaredMethods.singleOrNull {
            it.name == name && it.parameterTypes.contentEquals(types) && it.returnType == Void.TYPE && !Modifier.isStatic(it.modifiers)
        }
        val int = Int::class.javaPrimitiveType!!
        val layout = declared("onLayout", Boolean::class.javaPrimitiveType!!, int, int, int, int)
        val stateChanged = declared("drawableStateChanged")
        if (!ViewGroup::class.java.isAssignableFrom(key) || layout == null || stateChanged == null) {
            DebugLog.hookSkipped(TAG, "$NUM_PAD_KEY contract", "group=${ViewGroup::class.java.isAssignableFrom(key)} onLayout=${layout != null} drawableStateChanged=${stateChanged != null}")
            return
        }
        val resolved = resolveApi() ?: return
        val container = SECURITY_CONTAINER.toClassOrNull()?.takeIf { View::class.java.isAssignableFrom(it) } ?: run {
            DebugLog.hookSkipped(TAG, SECURITY_CONTAINER, "class not found")
            return
        }
        containerClass = container
        keyClass = key
        api = resolved
        key.hookAllConstructors {
            after { param ->
                HookFailurePolicy.open(TAG, "constructed PIN key", Unit) {
                    val group = param.thisObject as? ViewGroup ?: return@open
                    if (retiring || !enabled || !glassEnabled(group)) return@open
                    // Constructors may run on an async inflation thread; the key is not attached yet.
                    attach(resolved, group)
                }
            }
        }
        layout.hook {
            after { param ->
                HookFailurePolicy.open(TAG, "PIN key layout", Unit) {
                    val group = param.thisObject as? ViewGroup ?: return@open
                    keys[group]?.let {
                        place(group, it.view)
                        ensureContainer(resolved, group)
                    }
                }
            }
        }
        stateChanged.hook {
            after { param ->
                HookFailurePolicy.open(TAG, "PIN key pressed", Unit) {
                    val group = param.thisObject as? ViewGroup ?: return@open
                    val disc = keys[group] ?: return@open
                    animatePress(resolved, disc, group.isPressed)
                }
            }
        }
        DebugLog.d(TAG, "hooked ${key.simpleName} enabled=$enabled")
    }

    private fun resolveApi(): GlassApi? = runCatching {
        val int = Int::class.javaPrimitiveType!!
        fun method(name: String, vararg types: Class<*>) = View::class.java.getMethod(name, *types).also {
            require(!Modifier.isStatic(it.modifiers)) { "$name is static" }
        }
        GlassApi(
            viewMode = method("setMiViewBlurMode", int),
            materialType = method("setMiViewMaterialType", int),
            blurRadius = method("setMiGlassBlurRadius", int, int),
            glass = method("setMiGlass", FloatArray::class.java),
            passWindow = method("setPassWindowBlurEnabled", Boolean::class.javaPrimitiveType!!),
            blendColors = method("setMiBackgroundBlendColors", ArrayList::class.java),
            clearBlend = method("clearMiBackgroundBlendColor"),
            blurType = method("setMiBackgroundBlurType", int),
            bgMode = method("setMiBackgroundBlurMode", int),
            bgRadius = method("setMiBackgroundBlurRadius", int)
        )
    }.onFailure { DebugLog.hookSkipped(TAG, "View glass API", it.message ?: it.javaClass.simpleName) }.getOrNull()

    private fun booleanProperty(key: String): Boolean = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
            .invoke(null, key, false) as Boolean
    }.getOrDefault(false)

    /** Same gate as `CallOvalImageButton.isGlassEffectActive()` plus `HyperMaterialUtils.isFeatureEnable()`. */
    private fun glassEnabled(view: View): Boolean {
        val resolver = view.context.contentResolver
        return booleanProperty("persist.sys.bionic_material_supported") &&
            Settings.Secure.getInt(resolver, "material_style", -1) == 1 &&
            Settings.Secure.getInt(resolver, "background_blur_enable", 0) == 1
    }

    private fun findDisc(group: ViewGroup): View? =
        (0 until group.childCount).map(group::getChildAt).firstOrNull { it.tag == DISC_TAG }

    private fun attach(api: GlassApi, group: ViewGroup) {
        val view = findDisc(group) ?: View(group.context).also {
            it.tag = DISC_TAG
            it.isClickable = false
            it.isFocusable = false
            it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            group.addView(it, 0, ViewGroup.LayoutParams(0, 0))
        }
        // MiGlass renders while drawing the view background, and View.drawBackground returns
        // early without one. CallOvalImageButton likewise keeps its background at alpha 0.
        if (view.background == null) {
            view.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0) }
        }
        view.outlineProvider = discOutline
        view.clipToOutline = false
        val disc = Disc(view)
        keys[group] = disc
        if (group.getTag(NATIVE_BACKGROUND_KEY) == null && group.background != null) {
            group.setTag(NATIVE_BACKGROUND_KEY, group.background)
            group.background = null
        }
        api.viewMode.invoke(view, 1)
        api.materialType.invoke(view, 1)
        api.blurRadius.invoke(view, GLASS_BLUR_RADIUS, GLASS_BLUR_RADIUS)
        api.glass.invoke(view, disc.params)
        if (group.width > 0) place(group, view)
        DebugLog.d(TAG, "attached disc key=${group.width}x${group.height}")
    }

    /** Centred square whose diameter matches the native press ring. */
    private fun place(group: ViewGroup, view: View) {
        val size = min(group.width, group.height)
        if (size <= 0) return
        val left = (group.width - size) / 2
        val top = (group.height - size) / 2
        if (view.width != size || view.height != size) {
            val exact = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
            view.measure(exact, exact)
        }
        view.layout(left, top, left + size, top + size)
        view.invalidateOutline()
    }

    private fun ensureContainer(api: GlassApi, key: View) {
        val owner = containerClass ?: return
        var parent = key.parent
        while (parent is View && !owner.isInstance(parent)) parent = parent.parent
        val container = parent as? View ?: return
        if (containers[container] == true) return
        containers[container] = true
        api.passWindow.invoke(container, true)
        api.bgMode.invoke(container, 1)
        api.bgRadius.invoke(container, (CONTAINER_RADIUS_DP * container.resources.displayMetrics.density + 0.5f).toInt())
        api.viewMode.invoke(container, 0)
        api.blendColors.invoke(container, arrayListOf(Point(0xFF999999.toInt(), BLEND_SOFT_LIGHT), Point(0xFF0D8000.toInt(), BLEND_LAB)))
        api.blurType.invoke(container, 0)
        container.invalidate()
        DebugLog.d(TAG, "blur container ready")
    }

    private fun clearContainers(api: GlassApi, extra: List<View> = emptyList()) {
        (containers.keys + extra).toSet().forEach { container ->
            api.passWindow.invoke(container, false)
            api.bgMode.invoke(container, 0)
            api.clearBlend.invoke(container)
            container.invalidate()
        }
        containers.clear()
    }

    /** Interpolates normal → press params like the dialer's `PressLightAnimator`. */
    private fun animatePress(api: GlassApi, disc: Disc, pressed: Boolean) {
        val target = if (pressed) 1f else 0f
        if (disc.target == target) return
        disc.target = target
        disc.animator?.cancel()
        disc.animator = ValueAnimator.ofFloat(disc.progress, target).apply {
            duration = if (pressed) PRESS_IN_MS else PRESS_OUT_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                HookFailurePolicy.open(TAG, "press light frame", Unit) {
                    val t = anim.animatedValue as Float
                    disc.progress = t
                    for (i in disc.params.indices) disc.params[i] = NORMAL[i] + (PRESSED[i] - NORMAL[i]) * t
                    api.glass.invoke(disc.view, disc.params)
                }
            }
            start()
        }
    }

    private fun detach(group: ViewGroup) {
        keys[group]?.animator?.cancel()
        keys[group] = null
        findDisc(group)?.let(group::removeView)
        (group.getTag(NATIVE_BACKGROUND_KEY) as? Drawable)?.let {
            if (group.background == null) group.background = it
            group.setTag(NATIVE_BACKGROUND_KEY, null)
        }
    }

    private fun recover(views: List<View>) {
        val owner = keyClass ?: return
        val resolved = api ?: return
        if (retiring) return
        views.filter { owner.isInstance(it) }.forEach { if (it !in keys) keys[it as ViewGroup] = null }
        // A container left by an older build carries no record here; clear it when disabled.
        val staleContainers = containerClass?.let { c -> views.filter { c.isInstance(it) } }.orEmpty()
        keys.keys.toList().forEach { group ->
            HookFailurePolicy.open(TAG, "recover PIN key", Unit) {
                if (enabled && glassEnabled(group)) {
                    if (keys[group] == null) attach(resolved, group)
                } else {
                    detach(group)
                }
            }
        }
        if (!enabled) HookFailurePolicy.open(TAG, "clear blur containers", Unit) { clearContainers(resolved, staleContainers) }
        else keys.keys.toList().forEach { group -> HookFailurePolicy.open(TAG, "blur container", Unit) { if (keys[group] != null && group.isAttachedToWindow) ensureContainer(resolved, group) } }
    }

    /** Accepted-snapshot refresh: add discs to living keys on enable, remove them on disable. */
    fun refreshSettings() {
        val next = settingEnabled
        val changed = next != enabled
        enabled = next
        if (changed && !retiring && api != null) mainHandler.post { recover(emptyList()) }
        DebugLog.i(TAG, "settings applied enabled=$next keys=${keys.size}")
    }

    override fun onPrepareHotReload() {
        retiring = true
        val resolved = api
        HostAccess.onMain {
            keys.keys.toList().forEach { group ->
                runCatching { detach(group) }.onFailure { DebugLog.w(TAG, "remove PIN glass failed", it) }
            }
            if (resolved != null) runCatching { clearContainers(resolved) }.onFailure { DebugLog.w(TAG, "clear blur container failed", it) }
        }
    }
}
