package com.takekazex.hypertweak.hook.rules.systemui.media

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.util.logging.DebugLog
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/** The notification modal blur operates on its own window and background views. */
internal class HyperOsMediaModalEffects(
    private val loader: ClassLoader?,
    private val manager: Class<*>,
) {
    private fun type(name: String) = Class.forName(name, false, loader)

    private val root = checkNotNull(HostAccess.field(manager, "mWindowView"))
    private val background = checkNotNull(HostAccess.field(manager, "mBlurBackground"))
    private val blurUtils = checkNotNull(HostAccess.field(manager, "mBlurUtils"))
    private val windows = checkNotNull(HostAccess.field(manager, "mWindowManager"))
    private val added = checkNotNull(HostAccess.field(manager, "added"))
    private val float = Float::class.javaPrimitiveType!!
    private val setBlurRatio = checkNotNull(HostAccess.method(manager, "setBlurRatio", float, Boolean::class.javaPrimitiveType!!))
    private val applyContainerBlur = checkNotNull(HostAccess.method(blurUtils.type, "applyModalContainerBlur", float, View::class.java))
    private val modalWindowType = checkNotNull(HostAccess.field(
        WindowManager.LayoutParams::class.java, "TYPE_STATUS_BAR_SUB_PANEL",
    )).getInt(null)

    private val folme = type("miuix.animation.Folme")
    private val useAt = checkNotNull(HostAccess.method(folme, "useAt", Array<View>::class.java))
    private val clean = checkNotNull(HostAccess.method(folme, "clean", View::class.java))
    private val state = checkNotNull(HostAccess.method(useAt.returnType, "state"))
    private val to = checkNotNull(HostAccess.method(type("miuix.animation.FolmeStyle"), "to", Array<Any>::class.java))
    private val config = type("miuix.animation.base.AnimConfig")
    private val newConfig = config.getConstructor()
    private val ease = checkNotNull(HostAccess.method(config, "setEase", Int::class.javaPrimitiveType!!, FloatArray::class.java))
    private val special = type("miuix.animation.base.AnimSpecialConfig")
    private val newSpecial = special.getConstructor()
    private val setSpecial = checkNotNull(HostAccess.method(
        config, "setSpecial", type("miuix.animation.property.FloatProperty"), special,
    ))
    private val properties = type("miuix.animation.property.ViewProperty")
    private val translationX = checkNotNull(HostAccess.field(properties, "TRANSLATION_X")).get(null)
    private val translationY = checkNotNull(HostAccess.field(properties, "TRANSLATION_Y")).get(null)
    private val scaleX = checkNotNull(HostAccess.field(properties, "SCALE_X")).get(null)
    private val scaleY = checkNotNull(HostAccess.field(properties, "SCALE_Y")).get(null)
    private val alpha = checkNotNull(HostAccess.field(properties, "ALPHA")).get(null)
    // SystemUI inlines construction of CubicEaseInOutInterpolator on the host.
    val blurInterpolator = TimeInterpolator { fraction ->
        val t = 2f * fraction
        if (t < 1f) {
            0.5f * t * t * t
        } else {
            val tail = t - 2f
            0.5f * (tail * tail * tail + 2f)
        }
    }

    init {
        check(root.type == View::class.java && background.type == View::class.java)
        check(windows.type == WindowManager::class.java && added.type == Boolean::class.javaPrimitiveType)
    }

    fun windowManager(instance: Any): WindowManager = windows.get(instance) as WindowManager

    @Suppress("DEPRECATION")
    fun layoutParams(): WindowManager.LayoutParams = WindowManager.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
        modalWindowType,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_SPLIT_TOUCH or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        setTitle("HyperTweakMediaModal")
        setFitInsetsTypes(0)
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    }

    fun backdrop(instance: Any, rootView: View, blurLayer: View): (Float, Boolean) -> Unit {
        val blur = checkNotNull(blurUtils.get(instance))
        root.set(instance, rootView)
        background.set(instance, blurLayer)
        added.setBoolean(instance, true)
        applyContainerBlur.invoke(blur, 1f, rootView)
        return { fraction, entering ->
            setBlurRatio.invoke(instance, fraction, entering)
            if (fraction == 0f && !entering) applyContainerBlur.invoke(blur, 0f, rootView)
        }
    }

    fun animate(view: View, x: Float, y: Float, scale: Float, opacity: Float, entering: Boolean, menu: Boolean) {
        val settings = newConfig.newInstance()
        ease.invoke(settings, -2, floatArrayOf(if (entering) 0.75f else 0.9f, 0.3f))
        if (menu) {
            val opacitySettings = newSpecial.newInstance()
            ease.invoke(opacitySettings, -2, floatArrayOf(0.95f, 0.15f))
            setSpecial.invoke(settings, alpha, opacitySettings)
        }
        val target = state.invoke(useAt.invoke(null, arrayOf(view)))
        to.invoke(target, arrayOf(translationX, x, translationY, y, scaleX, scale, scaleY, scale, alpha, opacity, settings))
    }

    fun release(view: View) { clean.invoke(null, view) }
}

internal class HyperOsMediaModal(
    private val anchor: View,
    private val actions: View,
    private val effects: HyperOsMediaModalEffects,
    private val modalManager: Any,
    private val gap: Int,
    private val bottomMargin: Int,
    private val notificationCollapsedHeight: Int,
    private val releaseCard: (HyperOsMediaContract.Page) -> Unit,
    private val onDismiss: (HyperOsMediaModal) -> Unit,
) {
    private val context = anchor.context
    private val windows = effects.windowManager(modalManager)
    private val back = OnBackInvokedCallback { exit() }
    private var backDispatcher: OnBackInvokedDispatcher? = null
    private var windowAdded = false
    private val root = object : FrameLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) exit()
            return true
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            HookFailurePolicy.open(HYPER_OS_MEDIA_TAG, "register media modal Back", Unit) {
                backDispatcher = findOnBackInvokedDispatcher()
                backDispatcher?.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, back)
            }
        }

        override fun onDetachedFromWindow() {
            windowAdded = false
            HookFailurePolicy.open(HYPER_OS_MEDIA_TAG, "unregister media modal Back", Unit) {
                backDispatcher?.unregisterOnBackInvokedCallback(back)
            }
            backDispatcher = null
            super.onDetachedFromWindow()
            dismiss()
        }
    }.apply {
        clipChildren = false
        isFocusableInTouchMode = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setOnClickListener { exit() }
    }
    private val motion = FrameLayout(context).apply { clipChildren = false }
    private val blurLayer = View(context).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val card = object : FrameLayout(context) {
        override fun onInterceptTouchEvent(event: MotionEvent): Boolean = true
    }.apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        setOnClickListener { exit() }
    }
    private var focusPage: HyperOsMediaContract.Page? = null
    private var pressedBoundsOnScreen: RectF? = null
    private var preDraw: ViewTreeObserver.OnPreDrawListener? = null
    private var mask: ((Float, Boolean) -> Unit)? = null
    private var animator: ValueAnimator? = null
    private var blurAmount = 0f
    private var sourceVisibility: Int? = null
    private var exiting = false
    private var closed = false

    fun show(createCard: (ViewGroup) -> HyperOsMediaContract.Page): Boolean {
        if (!anchor.isAttachedToWindow || anchor.width <= 0 || anchor.height <= 0) return false
        try {
            pressedBoundsOnScreen = boundsOnScreen(anchor)
            val page = createCard(card)
            focusPage = page
            card.addView(page.view, FrameLayout.LayoutParams(-1, -1))

            root.addView(blurLayer, FrameLayout.LayoutParams(-1, -1))
            root.addView(motion, FrameLayout.LayoutParams(anchor.width, -2, Gravity.START or Gravity.TOP))
            motion.addView(card, FrameLayout.LayoutParams(-1, anchor.height))
            motion.addView(actions, FrameLayout.LayoutParams(-1, -2).apply { topMargin = anchor.height + gap })
            motion.visibility = View.INVISIBLE

            preDraw = ViewTreeObserver.OnPreDrawListener {
                removePreDraw()
                if (!HookFailurePolicy.open(HYPER_OS_MEDIA_TAG, "enter media focus", false) {
                    startEnter()
                    true
                }) root.post { dismiss() }
                true
            }.also(root.viewTreeObserver::addOnPreDrawListener)
            windows.addView(root, effects.layoutParams())
            windowAdded = true
            root.requestFocus()
            return true
        } catch (error: Throwable) {
            dismiss()
            throw error
        }
    }

    fun refresh(data: Any, bind: (HyperOsMediaContract.Page, Any) -> Unit) {
        focusPage?.let { bind(it, data) }
    }

    private fun startEnter() {
        check(anchor.isAttachedToWindow && card.width > 0 && card.height > 0)
        val pressed = inModal(checkNotNull(pressedBoundsOnScreen))
        val startX = pressed.centerX() - card.width / 2f
        val startY = pressed.centerY() - card.height / 2f
        val insets = root.rootWindowInsets
        val cutout = insets?.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout())
        val centeredX = ((root.width - motion.width) / 2f).coerceAtLeast(0f)
        val top = cutout?.top ?: 0
        val bottomInset = insets?.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())?.bottom ?: 0
        val maxY = (root.height - bottomInset - maxOf(card.height + bottomMargin, motion.height))
            .coerceAtLeast(top)
        motion.translationX = startX - motion.left
        motion.translationY = startY - motion.top
        card.scaleX = pressed.width() / card.width
        card.scaleY = pressed.height() / card.height
        actions.scaleX = 0.8f
        actions.scaleY = 0.8f
        actions.alpha = 0f

        mask = HookFailurePolicy.open(HYPER_OS_MEDIA_TAG, "create native notification backdrop", null) {
            effects.backdrop(modalManager, root, blurLayer)
        }
        sourceVisibility = anchor.visibility
        // Keep the page in the pager layout while the modal owns its rendering.
        anchor.visibility = View.INVISIBLE
        resetSourceScale()
        val source = inModal(boundsOnScreen(anchor))
        // Media is already expanded. Use the host's collapsed notification height to limit
        // the lift, and move further only if the menu would otherwise run off the screen.
        val targetY = (source.top - notificationCollapsedHeight / 2f).coerceIn(top.toFloat(), maxY.toFloat())
        motion.visibility = View.VISIBLE
        animateContent(motion, centeredX - motion.left, targetY - motion.top, 1f, 1f, true)
        animateContent(card, 0f, 0f, 1f, 1f, true)
        animateContent(actions, 0f, 0f, 1f, 1f, true)
        animateBlur(1f, true)
        DebugLog.i(HYPER_OS_MEDIA_TAG, "media focus entered source=$source target=($centeredX,$targetY) viewport=${root.width}x${root.height}")
    }

    fun exit(action: (() -> Unit)? = null) {
        if (closed || exiting) return
        if (sourceVisibility == null) {
            dismiss()
            action?.invoke()
            return
        }
        exiting = true
        val started = HookFailurePolicy.open(HYPER_OS_MEDIA_TAG, "exit media focus", false) {
            val source = inModal(boundsOnScreen(anchor))
            animateContent(motion, source.left - motion.left, source.top - motion.top, 1f, 1f, false)
            animateContent(actions, 0f, 0f, 0.8f, 0f, false)
            animateBlur(0f, false) {
                dismiss()
                action?.invoke()
            }
            true
        }
        if (!started) {
            dismiss()
            action?.invoke()
        }
    }

    private fun boundsOnScreen(view: View): RectF = RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()).also {
        val matrix = Matrix()
        view.transformMatrixToGlobal(matrix)
        matrix.mapRect(it)
    }

    private fun inModal(screenBounds: RectF): RectF = RectF(screenBounds).also {
        val matrix = Matrix()
        root.transformMatrixToLocal(matrix)
        matrix.mapRect(it)
    }

    private fun animateContent(view: View, x: Float, y: Float, scale: Float, alpha: Float, entering: Boolean) {
        val animated = HookFailurePolicy.open(HYPER_OS_MEDIA_TAG, "animate media focus", false) {
            effects.animate(view, x, y, scale, alpha, entering, view === actions)
            true
        }
        if (!animated) view.animate().translationX(x).translationY(y).scaleX(scale).scaleY(scale).alpha(alpha)
            .setDuration(300L).start()
    }

    private fun animateBlur(target: Float, entering: Boolean, end: (() -> Unit)? = null) {
        animator?.let { it.removeAllListeners(); it.cancel() }
        animator = ValueAnimator.ofFloat(blurAmount, target).apply {
            duration = 300L
            interpolator = effects.blurInterpolator
            addUpdateListener { updateBlur(it.animatedValue as Float, entering) }
            if (end != null) addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = end()
            })
            start()
        }
    }

    private fun updateBlur(value: Float, entering: Boolean) {
        blurAmount = value
        val draw = mask
        if (draw != null) {
            if (HookFailurePolicy.open(HYPER_OS_MEDIA_TAG, "update native notification backdrop", false) {
                draw(value, entering)
                true
            }) return
            mask = null
        }
        root.setBackgroundColor(Color.argb((value * 153).toInt(), 0, 0, 0))
    }

    fun dismiss() {
        if (closed) return
        closed = true
        resetSourceScale()
        sourceVisibility?.let { anchor.visibility = it }
        sourceVisibility = null
        removePreDraw()
        animator?.let { it.removeAllListeners(); it.cancel() }
        animator = null
        if (root.isAttachedToWindow) updateBlur(0f, false)
        mask = null
        listOf(motion, card, actions).forEach { view ->
            view.animate().cancel()
            HookFailurePolicy.open(HYPER_OS_MEDIA_TAG, "release media animation", Unit) { effects.release(view) }
        }
        try {
            focusPage?.let(releaseCard)
        } finally {
            focusPage = null
            try {
                if (windowAdded) {
                    windowAdded = false
                    windows.removeViewImmediate(root)
                }
            } finally {
                onDismiss(this)
            }
        }
    }

    private fun removePreDraw() {
        preDraw?.let { if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(it) }
        preDraw = null
    }

    private fun resetSourceScale() {
        anchor.scaleX = 1f
        anchor.scaleY = 1f
    }
}
