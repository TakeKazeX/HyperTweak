package com.takekazex.hypertweak.hook.rules.systemui.island.flashlight

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import com.takekazex.hypertweak.util.logging.DebugLog
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One flashlight island: torch state, the native slider rules, and the expanded panel plus its
 * fake copy. All mutation happens on the main thread.
 */
internal class FlashlightIslandSession(
    context: Context,
    assets: FlashlightNativeAssets,
    private val torch: FlashlightTorch,
    private val haptics: FlashlightHaptics,
) {
    private val main = Handler(Looper.getMainLooper())
    /** Only the real panel runs the OpenGL renderer: `MglContext` is a per-process singleton. */
    val panel = FlashlightIslandPanel(context, this, assets.inflate(withGl = true), interactive = true)
    val fake = FlashlightIslandPanel(context, this, assets.inflate(withGl = false), interactive = false)

    /** The host's own expanded and fake views, restored on disable or hot reload. */
    @Volatile var nativeView: View? = null
    @Volatile var nativeFake: View? = null
    /** The host's own `miui.focus.isPromoted` before the panel lifted the height cap. */
    @Volatile var nativePromoted = false
    @Volatile var promoted = false

    var torchOn = false
        private set
    /** Slider progress 0..100, as the native page keeps it. */
    var progress = 0
        private set
    /** Beam layers' on/off fade, as the native static effect animates them. */
    var lights = 0f
        private set
    /** Displayed slider position; follows [progress], animated except while dragging. */
    var shown = 0f
        private set

    private var lightsAnimator: ValueAnimator? = null
    private var shownAnimator: ValueAnimator? = null
    private var dragStart = 0
    private var dragging = false
    private var lastSentAt = 0L
    private var callbackRegistered = false
    private var released = true
    private var disposed = false

    private val sendPending = Runnable { sendLevel() }
    private val releaseCheck = Runnable { onReleased() }

    /**
     * Until then, the camera's reports are echoes of this panel's own command. Switching on goes
     * through the default level before the saved one, and following that echo made the beam
     * jump up and back down.
     */
    private var ownCommandUntil = 0L

    private fun echoing() = SystemClock.uptimeMillis() < ownCommandUntil

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId != torch.cameraId || disposed) return
            // The page always lights at the saved logical level (`getLogicStrength`).
            if (enabled && !torchOn && !dragging && !echoing()) progress = toProgress(torch.storedLogic())
            setTorchState(enabled)
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            if (cameraId == torch.cameraId && !disposed) setTorchState(false)
        }

        override fun onTorchStrengthLevelChanged(cameraId: String, newStrengthLevel: Int) {
            if (cameraId != torch.cameraId || dragging || disposed || newStrengthLevel <= 0 || echoing()) return
            val next = toProgress(FlashlightLevels.logic(newStrengthLevel, torch.maxLevel))
            if (next == progress) return
            progress = next
            animateShown()
        }
    }

    private fun toProgress(logic: Float) = (logic * FlashlightSlider.MAX).roundToInt().coerceIn(1, FlashlightSlider.MAX)

    fun seed(on: Boolean) {
        torchOn = on
        progress = if (on) toProgress(torch.storedLogic()) else 0
        lights = if (on) 1f else 0f
        shown = progress.toFloat()
        render()
    }

    fun onPanelAttached() {
        if (callbackRegistered || disposed) return
        runCatching { torch.cameraManager.registerTorchCallback(torchCallback, main) }
            .onSuccess { callbackRegistered = true }
            .onFailure { DebugLog.w(TAG, "torch callback registration failed", it) }
    }

    fun onPanelDetached() {
        if (callbackRegistered) runCatching { torch.cameraManager.unregisterTorchCallback(torchCallback) }
        callbackRegistered = false
        onHeldChanged(false)
    }

    /** Held means the expanded panel itself is on screen; the host hides it on collapse. */
    fun onHeldChanged(held: Boolean) {
        if (disposed) return
        main.removeCallbacks(releaseCheck)
        if (held) {
            FlashlightIslandHold.held = true
            released = false
        } else if (!released) {
            // Expand and collapse animations briefly hide the real panel behind its fake copy.
            main.postDelayed(releaseCheck, RELEASE_DELAY_MS)
        }
    }

    private fun onReleased() {
        if (panel.isHeld()) return
        released = true
        FlashlightIslandHold.held = false
        closeIfSuppressed()
    }

    /** Replays a close the controller deferred while the panel was held. */
    fun closeIfSuppressed() {
        if (FlashlightIslandHold.takeSuppressedClose() && !torchOn) {
            DebugLog.i(TAG, "flashlight island released while off; closing")
            runCatching { torch.closeIsland() }.onFailure { DebugLog.e(TAG, "close flashlight island failed", it) }
        }
    }

    /** Tapping the body: the page's switch, which relights at the saved level. */
    fun toggle() {
        haptics.perform(panel)
        val next = !torchOn
        if (next) progress = toProgress(torch.storedLogic())
        setTorchState(next)
        main.removeCallbacks(sendPending)
        markOwnCommand()
        runCatching { torch.apply(next, torch.storedLogic()) }.onFailure { DebugLog.e(TAG, "toggle torch failed", it) }
    }

    fun beginDrag() {
        dragging = true
        shownAnimator?.cancel()
        dragStart = if (torchOn) progress else 0
    }

    /** `seekBarOnTouchListener` + `setFlashlightStatus`: 0 turns the torch off, above 0 lights it. */
    fun drag(travelUp: Float, length: Float) {
        val next = FlashlightSlider.drag(dragStart, travelUp, length)
        if (next == progress && (next == 0) != torchOn) return
        progress = next
        shown = next.toFloat()
        if (FlashlightSlider.isEdge(next)) haptics.perform(panel)
        if ((next > 0) != torchOn) setTorchState(next > 0)
        val wait = SEND_INTERVAL_MS - (SystemClock.uptimeMillis() - lastSentAt)
        main.removeCallbacks(sendPending)
        if (wait <= 0) sendLevel() else main.postDelayed(sendPending, wait)
        render()
    }

    fun endDrag() {
        if (!dragging) return
        dragging = false
        main.removeCallbacks(sendPending)
        sendLevel()
    }

    private fun markOwnCommand() {
        lastSentAt = SystemClock.uptimeMillis()
        ownCommandUntil = lastSentAt + ECHO_WINDOW_MS
    }

    private fun sendLevel() {
        markOwnCommand()
        val on = progress > 0
        // Like the page, switching off through the slider keeps the saved level for the next switch-on.
        val logic = if (on) progress / FlashlightSlider.MAX.toFloat() else torch.storedLogic()
        runCatching { torch.apply(on, logic) }.onFailure { DebugLog.e(TAG, "set torch strength failed", it) }
    }

    private fun setTorchState(on: Boolean) {
        if (on == torchOn) {
            render()
            return
        }
        torchOn = on
        lightsAnimator?.cancel()
        lightsAnimator = ValueAnimator.ofFloat(lights, if (on) 1f else 0f).apply {
            duration = if (on) 420L else 360L
            interpolator = SPRING_EASE
            addUpdateListener { lights = it.animatedValue as Float; render() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (lightsAnimator === animation) lightsAnimator = null
                }
            })
            start()
        }
        if (!dragging) animateShown()
    }

    private fun animateShown() {
        val target = if (torchOn) progress.toFloat() else 0f
        shownAnimator?.cancel()
        shownAnimator = ValueAnimator.ofFloat(shown, target).apply {
            duration = 300L
            interpolator = SPRING_EASE
            addUpdateListener { shown = it.animatedValue as Float; render() }
            start()
        }
    }

    private fun render() {
        panel.render()
        fake.render()
    }

    /** Keeps the frame on screen for the moments the live renderer cannot show it. */
    private var capturedAt = 0L

    fun captureFrame() {
        if (disposed) return
        // A hide and the re-add that follows it ask within the same transition; one read-back does.
        val now = SystemClock.uptimeMillis()
        if (now - capturedAt < CAPTURE_DEDUPE_MS) return
        val frame = panel.captureFrame() ?: return
        capturedAt = now
        panel.showFrame(frame)
        fake.showFrame(frame)
    }

    fun dispose() {
        if (disposed) return
        main.removeCallbacksAndMessages(null)
        lightsAnimator?.cancel()
        shownAnimator?.cancel()
        panel.release()
        if (callbackRegistered) runCatching { torch.cameraManager.unregisterTorchCallback(torchCallback) }
        callbackRegistered = false
        if (!released) FlashlightIslandHold.held = false
        disposed = true
    }

    private companion object {
        const val TAG = "IslandFlashlight"
        const val RELEASE_DELAY_MS = 450L
        const val SEND_INTERVAL_MS = 50L
        const val ECHO_WINDOW_MS = 800L
        const val CAPTURE_DEDUPE_MS = 150L
        /** Close to Folme's critically damped spring (`getStyle(-2, 1.0, ~0.33)`) the native page uses. */
        val SPRING_EASE = PathInterpolator(0.2f, 0f, 0f, 1f)
    }
}

/**
 * The expanded flashlight island: the plugin's own flashlight page, laid out at display size and
 * scaled uniformly to the card's width, with the slider's bottom above the strip the island keeps
 * for its pull-down handle and the beam filling the space above the head. The hooker sizes the
 * island from [desiredWidth] and [desiredHeight].
 */
@SuppressLint("ViewConstructor")
internal class FlashlightIslandPanel(
    context: Context,
    private val session: FlashlightIslandSession,
    private val page: FlashlightNativeViews,
    private val interactive: Boolean,
) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val windowLocation = IntArray(2)
    private val panelLocation = IntArray(2)
    private var aggregatedVisible = false
    private var retired = false
    private var pageScale = 1f
    private var pageWidth = 0
    private var pageHeight = 0
    /** The flashlight body in panel coordinates: the only area whose gestures the panel takes. */
    private val body = RectF()

    private var downY = 0f
    private var downX = 0f
    private var mode = MODE_IDLE

    init {
        tag = TAG_MARKER
        setBackgroundColor(Color.BLACK)
        contentDescription = "Flashlight"
        page.root.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(page.root)
    }

    fun isHeld() = interactive && isAttachedToWindow && aggregatedVisible

    fun desiredWidth() = (WIDTH_DP * density).toInt()
    fun desiredHeight() = (HEIGHT_DP * density).toInt()

    fun render() {
        page.gl?.setProgress(FlashlightSlider.renderProgress(session.torchOn, session.shown))
        // The static layers (fake copy before any frame exists, or renderer failure) follow the
        // renderer's own progress, so they are no brighter than it at low levels.
        val beam = session.lights * FlashlightSlider.renderProgress(session.torchOn, session.shown)
        page.longLight.alpha = beam
        page.composeLight.alpha = beam
        val progress = FlashlightSlider.shown(session.torchOn, session.shown.roundToInt())
        if (page.seekbar.progress != progress) page.seekbar.progress = progress
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // The host sizes the island from these; once it sets exact layout params those win.
        val width = sized(widthMeasureSpec, desiredWidth())
        val height = sized(heightMeasureSpec, desiredHeight())
        val metrics = resources.displayMetrics
        pageWidth = min(metrics.widthPixels, metrics.heightPixels)
        pageHeight = max(metrics.widthPixels, metrics.heightPixels)
        // Filling the width exactly: the shader's dark base is not pure black, so a narrower page
        // would leave visible strips of the panel's black at the sides.
        pageScale = (width.toFloat() / pageWidth).coerceIn(0.01f, 1f)
        page.sizeFor(pageWidth, pageHeight, pageScale)
        page.root.measure(MeasureSpec.makeMeasureSpec(pageWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(pageHeight, MeasureSpec.EXACTLY))
        setMeasuredDimension(width, height)
    }

    private fun sized(spec: Int, desired: Int) =
        if (MeasureSpec.getMode(spec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(spec) else desired

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val root = page.root
        root.layout(0, 0, pageWidth, pageHeight)
        root.pivotX = 0f
        root.pivotY = 0f
        root.scaleX = pageScale
        root.scaleY = pageScale
        root.translationX = (width - pageWidth * pageScale) / 2f
        val handleTop = height - HANDLE_ZONE_DP * density
        val sliderBottom = handleTop - SLIDER_GAP_DP * density
        root.translationY = sliderBottom - page.sliderContainer.bottom * pageScale
        // Both the shader and the static layers centre the head on the page.
        val headY = root.translationY + pageHeight / 2f * pageScale
        val halfWidth = pageWidth * BODY_HALF_WIDTH * pageScale + BODY_PAD_DP * density
        body.set(width / 2f - halfWidth, headY - BODY_PAD_DP * density, width / 2f + halfWidth, handleTop)
        render()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (interactive) session.onPanelAttached()
        updateRenderer()
    }

    override fun onDetachedFromWindow() {
        if (interactive) session.onPanelDetached()
        super.onDetachedFromWindow()
        updateRenderer()
    }

    private var frameBuffer: Bitmap? = null

    /** Only a revealed renderer has a drawn frame; a covered one would capture a blank surface. */
    fun captureFrame(): Bitmap? = page.gl?.takeIf { it.view.alpha == 1f }?.capture(frameBuffer)?.also { frameBuffer = it }

    fun showFrame(frame: Bitmap) = page.showFrame(frame)

    override fun onVisibilityAggregated(isVisible: Boolean) {
        // Hidden for an expand or collapse: the fake copy shows this frame meanwhile.
        if (!isVisible && aggregatedVisible && interactive) session.captureFrame()
        super.onVisibilityAggregated(isVisible)
        aggregatedVisible = isVisible
        if (interactive && isAttachedToWindow) session.onHeldChanged(isVisible)
        updateRenderer()
    }

    /** The shader animates every frame; it runs only while the panel is on screen. */
    private fun updateRenderer() {
        page.gl?.setRunning(!retired && isAttachedToWindow && aggregatedVisible)
    }

    fun release() {
        retired = true
        updateRenderer()
    }

    /**
     * Whether a gesture starting at [event] on the island window belongs to the panel. The window
     * feeds every event to its own press sink, drag-to-collapse and pull-down (mini window / full
     * screen) gestures from `dispatchTouchEvent`, before any child sees it, so the hooker routes
     * body gestures here directly. Everything else on the card, the beam above the head and the
     * handle strip at the bottom, stays with the island.
     */
    fun hitFromWindow(window: View, event: MotionEvent): Boolean {
        if (!isHeld() || width == 0 || height == 0) return false
        val (x, y) = toLocal(window, event)
        return body.contains(x, y)
    }

    fun dispatchFromWindow(window: View, event: MotionEvent) {
        val (x, y) = toLocal(window, event)
        val local = MotionEvent.obtain(event)
        local.setLocation(x, y)
        try {
            dispatchTouchEvent(local)
        } finally {
            local.recycle()
        }
    }

    private fun toLocal(window: View, event: MotionEvent): Pair<Float, Float> {
        window.getLocationOnScreen(windowLocation)
        getLocationOnScreen(panelLocation)
        return event.x + windowLocation[0] - panelLocation[0] to event.y + windowLocation[1] - panelLocation[1]
    }

    /** The page's own views never see touches; the panel plays the page's touch strip. */
    override fun onInterceptTouchEvent(ev: MotionEvent) = interactive

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                mode = MODE_UNDECIDED
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == MODE_UNDECIDED) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                        mode = MODE_IGNORED
                    } else if (abs(dy) > touchSlop) {
                        mode = MODE_DRAG
                        downY = event.y
                        session.beginDrag()
                    }
                }
                // `seekBarTouchLength`: the slider's unscaled height, so a finger moves the level
                // exactly as far as on the full-screen page however small the card shows it.
                if (mode == MODE_DRAG) session.drag(downY - event.y, page.sliderContainer.height.toFloat())
            }
            MotionEvent.ACTION_UP -> {
                if (mode == MODE_UNDECIDED) performClick()
                if (mode == MODE_DRAG) session.endDrag()
                mode = MODE_IDLE
            }
            MotionEvent.ACTION_CANCEL -> {
                if (mode == MODE_DRAG) session.endDrag()
                mode = MODE_IDLE
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (!interactive) return false
        session.toggle()
        return true
    }

    companion object {
        /** Survives R8 renames, so a later module generation still recognises an orphaned panel. */
        const val TAG_MARKER = "hypertweak:island-flashlight-panel"
        /** The card's width sets the flashlight's size: the page is scaled to fill it. */
        private const val WIDTH_DP = 200f
        private const val HEIGHT_DP = 320f
        /** Head spans x 104..288 of the 392-wide effect layers. */
        private const val BODY_HALF_WIDTH = 92f / 392f
        private const val BODY_PAD_DP = 12f
        /**
         * The island's own strip at the bottom: its `mini_window_bar` handle (3.6dp tall, 7.3dp
         * above the edge) plus room to grab it. Gestures starting there are never taken.
         */
        private const val HANDLE_ZONE_DP = 26f
        private const val SLIDER_GAP_DP = 6f
        private const val MODE_IDLE = 0
        private const val MODE_UNDECIDED = 1
        private const val MODE_DRAG = 2
        private const val MODE_IGNORED = 3
    }
}
