package com.takekazex.hypertweak.hook.rules.systemui

import android.app.Dialog
import android.app.NotificationManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import android.util.SparseArray
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt

/**
 * Adds the two small controls that are present in the requested AOSP-style volume panel:
 * the active stream percentage and a Do Not Disturb toggle.
 *
 * On OS4.0.0.21.XPMCNXM the AOSP panel is a regular XML shell around a Compose slider. The shell is
 * therefore the stable injection point: `VolumeDialogViewBinder#bind(CoroutineScope, Dialog,
 * boolean)` owns `volume_dialog_main_slider_container` and
 * `volume_dialog_bottom_section_container`, while `VolumeDialogControllerImpl$C#onStateChanged`
 * receives the same `VolumeDialogController.State` used by the Compose state flow. Keeping the
 * overlay in the shell avoids depending on generated Compose lambdas and keeps the state source at
 * the controller rather than querying AudioManager for a possibly different stream.
 */
object AospVolumeExtrasHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "AospVolumeExtras"
    private const val VOLUME_DIALOG_VIEW_BINDER =
        "com.android.systemui.volume.dialog.ui.binder.VolumeDialogViewBinder"
    private const val VOLUME_DIALOG_VIEW_MODEL =
        "com.android.systemui.volume.dialog.ui.viewmodel.VolumeDialogViewModel"
    private const val CONTROLLER_CALLBACKS = "com.android.systemui.volume.VolumeDialogControllerImpl\$C"
    private const val CONTROLLER = "com.android.systemui.volume.VolumeDialogControllerImpl"
    private const val STATE = "com.android.systemui.plugins.VolumeDialogController\$State"
    private const val SOUND_MODE = "android.provider.MiuiSettings\$SoundMode"
    private const val VIEW_TAG = "hypertweak_aosp_volume_extras"
    private const val PERCENT_TAG = "hypertweak_aosp_volume_percentage"
    private const val DND_TAG = "hypertweak_aosp_volume_dnd"
    private const val COMPOSE_SLIDER_ID = "volume_dialog_slider"
    private const val INVERT_THRESHOLD = 82
    private const val STREAM_MUSIC = 3

    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"

    @Volatile
    private var enabled = false

    private var controllerStateField: Field? = null
    private var viewModelField: Field? = null
    private var touchableBoundsViewsField: Field? = null
    private var stateStatesField: Field? = null
    private var stateActiveStreamField: Field? = null
    private var stateZenModeField: Field? = null
    private var streamLevelField: Field? = null
    private var streamLevelMinField: Field? = null
    private var streamLevelMaxField: Field? = null
    private var miuiSetZenModeOn: Method? = null
    private var notificationSetZenMode: Method? = null

    private val overlays = CopyOnWriteArrayList<WeakReference<VolumeExtras>>()

    // The controller can dispatch the first State before the dialog shell is bound, i.e. before
    // the overlay exists. Buffer the latest value and replay it when the overlay is created so
    // the percentage shows a number on the very first open instead of a second volume change.
    @Volatile
    private var pendingValue: Int? = null
    @Volatile
    private var pendingDnd: Boolean = false

    override fun onPrepareHotReload() {
        enabled = false
        overlays.clear()
        pendingValue = null
        pendingDnd = false
        controllerStateField = null
        viewModelField = null
        touchableBoundsViewsField = null
        stateStatesField = null
        stateActiveStreamField = null
        stateZenModeField = null
        streamLevelField = null
        streamLevelMinField = null
        streamLevelMaxField = null
        miuiSetZenModeOn = null
        notificationSetZenMode = null
    }

    override fun onHook() {
        if (!Preferences.getBoolean(Preferences.KEY_AOSP_VOLUME_PANEL, false)) {
            DebugLog.hookSkipped(TAG, "AOSP volume extras", "AOSP volume panel disabled")
            return
        }
        enabled = true
        resolveStateFields()
        hookVolumeDialogBind()
        hookStateChanged()
        hookVolumeChanged()
    }

    private fun hookVolumeDialogBind() {
        val binderClass = VOLUME_DIALOG_VIEW_BINDER.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, VOLUME_DIALOG_VIEW_BINDER, "class not found")
            return
        }
        val bind = binderClass.declaredMethods.firstOrNull { method ->
            method.name == "bind" && method.parameterTypes.size == 3 &&
                Dialog::class.java.isAssignableFrom(method.parameterTypes[1]) &&
                method.parameterTypes[2] == Boolean::class.javaPrimitiveType
        } ?: run {
            DebugLog.hookSkipped(TAG, "$VOLUME_DIALOG_VIEW_BINDER#bind(CoroutineScope,Dialog,Boolean)", "method not found")
            return
        }
        runCatching {
            deoptimize(bind)
            bind.hook {
                after { param ->
                    HookFailurePolicy.open(TAG, "VolumeDialogViewBinder.bind", Unit) {
                        if (!enabled) return@open
                        val dialog = param.args.getOrNull(1) as? Dialog ?: return@open
                        installExtras(param.thisObject, dialog)
                    }
                }
            }
            DebugLog.i(TAG, "HOOK_OK $VOLUME_DIALOG_VIEW_BINDER#bind(CoroutineScope,Dialog,Boolean)")
        }.onFailure { t ->
            DebugLog.hookFailed(TAG, "$VOLUME_DIALOG_VIEW_BINDER#bind", t)
        }
    }

    private fun hookStateChanged() {
        val callbacksClass = CONTROLLER_CALLBACKS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, CONTROLLER_CALLBACKS, "class not found")
            return
        }
        val onStateChanged = callbacksClass.declaredMethods.firstOrNull {
            it.name == "onStateChanged" && it.parameterTypes.size == 1
        } ?: run {
            DebugLog.hookSkipped(TAG, "$CONTROLLER_CALLBACKS#onStateChanged(State)", "method not found")
            return
        }
        runCatching {
            deoptimize(onStateChanged)
            onStateChanged.hook {
                before { param ->
                    HookFailurePolicy.open(TAG, "onStateChanged", Unit) {
                        if (!enabled) return@open
                        val state = param.args.getOrNull(0) ?: return@open
                        updateFromState(state)
                    }
                }
            }
            DebugLog.i(TAG, "HOOK_OK $CONTROLLER_CALLBACKS#onStateChanged(State)")
        }.onFailure { t ->
            DebugLog.hookFailed(TAG, "$CONTROLLER_CALLBACKS#onStateChanged", t)
        }
    }

    /** Covers the short interval where the controller changes a stream before dispatching State. */
    private fun hookVolumeChanged() {
        val controllerClass = CONTROLLER.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, CONTROLLER, "class not found")
            return
        }
        val onVolumeChanged = controllerClass.declaredMethods.firstOrNull {
            it.name == "onVolumeChangedW" && it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                it.parameterTypes[2] == Boolean::class.javaPrimitiveType
        } ?: run {
            DebugLog.hookSkipped(TAG, "$CONTROLLER#onVolumeChangedW(Int,Int,Boolean)", "method not found")
            return
        }
        runCatching {
            deoptimize(onVolumeChanged)
            onVolumeChanged.hook {
                after { param ->
                    HookFailurePolicy.open(TAG, "onVolumeChangedW", Unit) {
                        if (!enabled) return@open
                        val controller = param.thisObject
                        val state = controllerStateField?.get(controller) ?: return@open
                        val stream = param.args.getOrNull(0) as? Int
                        updateFromState(state, stream)
                    }
                }
            }
            DebugLog.i(TAG, "HOOK_OK $CONTROLLER#onVolumeChangedW(Int,Int,Boolean)")
        }.onFailure { t ->
            DebugLog.hookFailed(TAG, "$CONTROLLER#onVolumeChangedW", t)
        }
    }

    private fun resolveStateFields() {
        runCatching {
            val controllerClass = CONTROLLER.toClassOrNull()
            controllerStateField = controllerClass?.findField("mState")

            val viewModelClass = VOLUME_DIALOG_VIEW_MODEL.toClassOrNull()
            viewModelField = VOLUME_DIALOG_VIEW_BINDER.toClassOrNull()?.findField("viewModel")
            touchableBoundsViewsField = viewModelClass?.findField("touchableBoundsViews")

            val stateClass = STATE.toClassOrNull()
            stateStatesField = stateClass?.findField("states")
            stateActiveStreamField = stateClass?.findField("activeStream")
            stateZenModeField = stateClass?.findField("zenMode")

            val streamClass = "com.android.systemui.plugins.VolumeDialogController\$StreamState".toClassOrNull()
            streamLevelField = streamClass?.findField("level")
            streamLevelMinField = streamClass?.findField("levelMin")
            streamLevelMaxField = streamClass?.findField("levelMax")
        }.onFailure { t ->
            DebugLog.w(TAG, "state field resolution failed; percentage may remain unavailable", t)
        }
    }

    private fun installExtras(binder: Any?, dialog: Dialog) {
        val decor = dialog.window?.decorView ?: return
        val resources = decor.resources
        val rootId = resources.getIdentifier("volume_dialog", "id", SYSTEM_UI_PACKAGE)
        val root = if (rootId != 0) decor.findViewById<View>(rootId) else decor
        val mainId = resources.getIdentifier("volume_dialog_main_slider_container", "id", SYSTEM_UI_PACKAGE)
        val bottomId = resources.getIdentifier("volume_dialog_bottom_section_container", "id", SYSTEM_UI_PACKAGE)
        val sliderContainer = if (mainId != 0) root.findViewById<View>(mainId) as? ViewGroup else null
        val bottomContainer = if (bottomId != 0) root.findViewById<View>(bottomId) as? LinearLayout else null
        if (sliderContainer == null || bottomContainer == null) {
            DebugLog.w(TAG, "volume dialog containers not found")
            return
        }

        val existing = root.findViewWithTag<View>(VIEW_TAG)
        if (existing != null) return

        val context = root.context
        // The Compose slider content (the vertical track + note icon) lives inside this
        // real View. Measuring against it (not the FrameLayout) gives the percentage an
        // overlay that shares the track's coordinate space and follows the icon.
        val composeSliderId = resources.getIdentifier(COMPOSE_SLIDER_ID, "id", SYSTEM_UI_PACKAGE)
        val composeView = if (composeSliderId != 0) root.findViewById<View>(composeSliderId) else null
        val percentage = TextView(context).apply {
            tag = PERCENT_TAG
            text = ""
            textSize = 12f
            // The theme colour sampled by the note icon (materialColorPrimary), so the label
            // reads as part of the panel instead of a flat black/white value.
            setTextColor(resolveColor(context, "materialColorPrimary", android.R.color.white))
            visibility = View.GONE
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            // Keep the label above the Compose slider content so it is never occluded.
            elevation = 16f
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setTag(VIEW_TAG)
        }
        // The percentage is a sibling overlay in the same FrameLayout as the Compose slider.
        // Its Y offset is calculated from the AOSP stream-icon anchor below.
        sliderContainer.clipChildren = false
        sliderContainer.clipToPadding = false
        val percentageParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        )
        sliderContainer.addView(percentage, percentageParams)
        percentage.bringToFront()

        val dnd = ImageButton(context).apply {
            tag = DND_TAG
            setImageDrawable(DndDrawable(dp(context, 24)))
            val backgroundId = resources.getIdentifier("ripple_drawable_20dp", "drawable", SYSTEM_UI_PACKAGE)
            if (backgroundId != 0) setBackgroundResource(backgroundId)
            val color = resolveColor(context, "materialColorPrimary", android.R.color.white)
            (drawable as? DndDrawable)?.setTintColor(color)
            setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4))
            isSoundEffectsEnabled = false
            isClickable = true
            isEnabled = true
            contentDescription = "Do Not Disturb"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            isFocusable = true
            setTag(VIEW_TAG)
        }
        val sizeId = resources.getIdentifier("volume_dialog_button_size", "dimen", SYSTEM_UI_PACKAGE)
        val size = if (sizeId != 0) resources.getDimensionPixelSize(sizeId) else dp(context, 48)
        val dndParams = LinearLayout.LayoutParams(size, size).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val settingsIndex = bottomContainer.childCount.coerceAtLeast(0)
        bottomContainer.addView(dnd, settingsIndex, dndParams)

        val extras = VolumeExtras(root, sliderContainer, composeView, percentage, dnd, dp(context, 20), dp(context, 6))
        overlays += WeakReference(extras)
        registerTouchableBounds(binder, dnd)
        updateFromCurrentSettings(extras)
        // Replay the latest state so the percentage is populated the moment the panel opens,
        // without waiting for the next volume change (e.g. a second press).
        extras.update(pendingValue, pendingDnd)
        dnd.setOnClickListener {
            HookFailurePolicy.open(TAG, "DND button click", Unit) {
                val newValue = !dnd.isSelected
                if (setDnd(context, newValue)) {
                    extras.updateDnd(newValue)
                }
            }
        }
    }

    /** AOSP computes the dialog's internal touch region from this collection. */
    private fun registerTouchableBounds(binder: Any?, view: View) {
        runCatching {
            val model = viewModelField?.get(binder) ?: return@runCatching
            @Suppress("UNCHECKED_CAST")
            val views = touchableBoundsViewsField?.get(model) as? MutableCollection<View>
                ?: return@runCatching
            views.add(view)
            DebugLog.i(TAG, "DND registered in VolumeDialogViewModel.touchableBoundsViews")
        }.onFailure { t ->
            DebugLog.w(TAG, "could not register DND touch bounds", t)
        }
    }

    private fun updateFromState(state: Any, streamOverride: Int? = null) {
        val activeStream = streamOverride ?: readInt(stateActiveStreamField, state, -1)
        val zenMode = readInt(stateZenModeField, state, 0)
        val states = stateStatesField?.let { field -> runCatching { field.get(state) as? SparseArray<*> }.getOrNull() }
        // While the dialog first opens the controller may not have chosen an active stream yet
        // (NO_ACTIVE_STREAM = -1), which would show "--%". Fall back to the media stream so the
        // percentage has a real value immediately, then switch to the active stream once known.
        val resolvedStream = if (activeStream >= 0) activeStream
            else if (states != null && states.get(STREAM_MUSIC) != null) STREAM_MUSIC
            else if (states != null && states.size() > 0) states.keyAt(0)
            else -1
        val streamState = if (resolvedStream >= 0) states?.get(resolvedStream) else null
        val level = readInt(streamLevelField, streamState, -1)
        val min = readInt(streamLevelMinField, streamState, 0)
        val max = readInt(streamLevelMaxField, streamState, -1)
        val percentage = percentage(level, min, max)
        pendingValue = percentage
        pendingDnd = zenMode != 0
        postToOverlays(percentage, zenMode != 0)
    }

    private fun updateFromCurrentSettings(extras: VolumeExtras) {
        val zenMode = runCatching {
            Settings.Global.getInt(extras.context.contentResolver, "zen_mode", 0)
        }.getOrDefault(0)
        extras.updateDnd(zenMode != 0)
    }

    private fun postToOverlays(value: Int?, dndOn: Boolean) {
        val stale = ArrayList<WeakReference<VolumeExtras>>()
        overlays.forEach { reference ->
            val extras = reference.get()
            if (extras == null) {
                stale += reference
            } else {
                extras.root.post { extras.update(value, dndOn) }
            }
        }
        if (stale.isNotEmpty()) overlays.removeAll(stale.toSet())
    }

    private fun setDnd(context: Context, enabled: Boolean): Boolean {
        return runCatching {
            val method = miuiSetZenModeOn ?: SOUND_MODE.toClassOrNull()?.getDeclaredMethod(
                "setZenModeOn",
                Context::class.java,
                Boolean::class.javaPrimitiveType,
                String::class.java
            )?.apply {
                isAccessible = true
                miuiSetZenModeOn = this
            }
            if (method != null) {
                method.invoke(null, context, enabled, TAG)
                return true
            }
            val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return false
            val fallback = notificationSetZenMode ?: NotificationManager::class.java.getDeclaredMethod(
                "setZenMode",
                Int::class.javaPrimitiveType,
                Uri::class.java,
                String::class.java
            ).apply {
                isAccessible = true
                notificationSetZenMode = this
            }
            fallback.invoke(notificationManager, if (enabled) 1 else 0, null, TAG)
            true
        }.onFailure { t ->
            DebugLog.w(TAG, "set DND failed", t)
        }.getOrDefault(false)
    }

    private fun percentage(level: Int, min: Int, max: Int): Int? {
        if (level < 0 || max <= min) return null
        return (((level - min).toFloat() / (max - min).toFloat()) * 100f)
            .roundToInt()
            .coerceIn(0, 100)
    }

    private fun readInt(field: Field?, receiver: Any?, default: Int): Int {
        if (field == null || receiver == null) return default
        return runCatching { field.getInt(receiver) }.getOrDefault(default)
    }

    private fun Class<*>.findField(name: String): Field? =
        runCatching { getDeclaredField(name).apply { isAccessible = true } }.getOrNull()

    private fun resolveColor(context: Context, name: String, fallback: Int): Int {
        val id = context.resources.getIdentifier(name, "color", SYSTEM_UI_PACKAGE)
        return if (id != 0) runCatching { context.getColor(id) }.getOrDefault(context.getColor(fallback))
        else context.getColor(fallback)
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    private class VolumeExtras(
        root: View,
        private val sliderContainer: ViewGroup,
        private val composeView: View?,
        val percentage: TextView,
        val dnd: ImageButton,
        private val iconSizePx: Int,
        private val iconGapPx: Int
    ) {
        val root: View = root
        val context: Context get() = root.context
        private var currentPercentage: Int? = null
        private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updatePercentagePosition()
        }
        // The note icon inverts its colour once it flips to the active slot (high volume) so it
        // stays readable over the filled track. The percentage mirrors that invert: it uses the
        // theme colour (materialColorPrimary) on a low volume and its inverse on a high volume.
        private var normalColor: Int = android.graphics.Color.WHITE
        private var invertedColor: Int = android.graphics.Color.BLACK
        private var hasColors = false

        init {
            sliderContainer.addOnLayoutChangeListener(layoutListener)
            resolveColors()
        }

        fun update(value: Int?, dndOn: Boolean) {
            currentPercentage = value
            // Never leave a literal "--%" placeholder on screen: hide the label until a value is
            // actually known, so it only ever shows a real number.
            percentage.text = value?.let { "$it%" } ?: ""
            percentage.visibility = if (value == null) View.GONE else View.VISIBLE
            updatePercentagePosition()
            updatePercentageColor(value)
            updateDnd(dndOn)
        }

        fun updateDnd(dndOn: Boolean) {
            dnd.isSelected = dndOn
            (dnd.drawable as? DndDrawable)?.setActive(dndOn)
            dnd.contentDescription = if (dndOn) "Do Not Disturb on" else "Do Not Disturb off"
        }

        private fun resolveColors() {
            val base = resolveColor(context, "materialColorPrimary", android.R.color.white)
            normalColor = base
            invertedColor = android.graphics.Color.rgb(
                255 - android.graphics.Color.red(base),
                255 - android.graphics.Color.green(base),
                255 - android.graphics.Color.blue(base)
            )
            hasColors = true
        }

        private fun updatePercentageColor(value: Int?) {
            if (!hasColors) return
            percentage.setTextColor(if ((value ?: 0) >= INVERT_THRESHOLD) invertedColor else normalColor)
        }

        // AOSP's TrackMeasurePolicy keeps the stream note icon at the track start while the
        // value is below the mirror threshold, then slides it toward the mirrored fraction
        // (1 - volumeFraction) once it flips to the active slot. The note icon therefore hugs
        // the top on a low volume and only nudges down / inverts as the volume rises. Bind the
        // percentage to that same anchor so it follows the note icon, not the slider thumb.
        private fun noteIconTop(value: Int?, trackHeight: Int): Int {
            if (value == null) return 0
            return if (value < INVERT_THRESHOLD) 0
            else (trackHeight * (1f - value / 100f) + iconGapPx).roundToInt()
        }

        private fun updatePercentagePosition() {
            val trackHeight = trackHeight()
            if (trackHeight <= 0) {
                sliderContainer.post { updatePercentagePosition() }
                return
            }
            // composeView.top is the Compose slider's offset inside the shell (0 = it fills it),
            // so the label shares the note icon's vertical origin.
            val base = composeView?.top ?: 0
            val iconTop = noteIconTop(currentPercentage, trackHeight)
            val newY = base + iconTop + iconSizePx + dp(14f)
            percentage.translationY = newY.toFloat()
        }

        private fun trackHeight(): Int {
            composeView?.let { if (it.height > 0) return it.height }
            return sliderContainer.height
        }

        private fun dp(value: Float): Int =
            (value * root.resources.displayMetrics.density).roundToInt()
    }

    private class DndDrawable(size: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val size = size.toFloat()
        private var tint = 0xffffffff.toInt()
        private var active = false

        init {
            paint.strokeCap = Paint.Cap.ROUND
        }

        override fun draw(canvas: Canvas) {
            val bounds = bounds
            val cx = bounds.exactCenterX()
            val cy = bounds.exactCenterY()
            val radius = minOf(bounds.width(), bounds.height()) * 0.32f
            paint.color = tint
            paint.alpha = if (active) 255 else 150
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = minOf(bounds.width(), bounds.height()) * 0.10f
            canvas.drawCircle(cx, cy, radius, paint)
            paint.style = Paint.Style.FILL
            rect.set(cx - radius * 0.62f, cy - paint.strokeWidth * 0.55f, cx + radius * 0.62f, cy + paint.strokeWidth * 0.55f)
            canvas.drawRoundRect(rect, paint.strokeWidth, paint.strokeWidth, paint)
        }

        fun setActive(value: Boolean) {
            active = value
            invalidateSelf()
        }

        fun setTintColor(value: Int) {
            tint = value
            invalidateSelf()
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Drawable.getOpacity is deprecated on newer Android versions")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth(): Int = size.toInt()
        override fun getIntrinsicHeight(): Int = size.toInt()
    }
}
