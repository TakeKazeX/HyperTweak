package com.takekazex.hypertweak.hook.rules.systemui.volume.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.settings.contract.AppVolumePercentColor
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import java.util.Locale

/**
 * App volume inside one hardware-key volume dialog (`VolumePanelViewController`).
 *
 * Two module views join the native collapsed dialog, both inside `MiuiVolumeDialogView`, whose
 * collapsed touchable region is its own bounds:
 * - an entry button, inflated from the host's `miui_ringer_mode_layout` and styled by the same
 *   helpers as the silent/DND buttons, placed under them and following their drag stretch;
 * - a strip of [AppVolumeColumn]s at the start of `volume_dialog_container`, to the left of the
 *   native media column, shown while the entry is on.
 *
 * Opening splits the app columns out from under the native media column; closing folds them
 * back. The entry follows the silent/DND show stagger one step later, and while the strip is up
 * [showXOffset] moves the host's show target left so the native column keeps its screen position.
 * Outside percentages are capsules in a non-touchable layer of the dialog window, placed above
 * each column on every frame.
 *
 * Both are absent while expanded, in the Control Center's embedded panel, while the native footer
 * is hidden (call streams), with Sound Assistant off, or with no adjustable media app. Native
 * columns, ringer buttons, timeouts and the system media stream stay host-owned.
 */
internal class AppVolumePanel(
    private val feature: AppVolumeHooker,
    val controller: Any,
    private val host: AppVolumeHost,
) : AppVolumeColumn.Callbacks {
    private var dialog: ViewGroup? = null
    private var container: ViewGroup? = null
    private var strip: LinearLayout? = null
    private var entry: EntryFrame? = null
    private var entryBlur: View? = null
    private var entryStandard: View? = null
    private var glyph: AppVolumeGlyph? = null
    private val columns = LinkedHashMap<String, AppVolumeColumn>()
    private var rows: List<AppVolumeCandidates.Row> = emptyList()
    private var open = false
    /** Auto-expand requested on show but the first playback snapshot had no row yet. */
    private var autoOpenPending = false
    private var soundAssist = false
    private var styledOn: Boolean? = null
    private var lastFooter: Boolean? = null
    /** Close animation running: the strip stays visible until the columns are folded back. */
    private var closing = false
    private var motion: Any? = null
    private var mainColumn: View? = null
    private var tempColumn: View? = null
    private var nativeBadge: View? = null
    /** A native show that included app columns needs the same badge anchor after they fold away. */
    private var alignBadgeForShow = false
    private val columnMatrix = Matrix()
    private val columnPoints = FloatArray(4)
    private var capsules: FrameLayout? = null
    private val capsuleViews = HashMap<String, Pair<FrameLayout, TextView>>()
    private val percentStyle = AppVolumePercentStyle(controller.javaClass.classLoader!!)
    /** Recent DND-button stagger offsets, replayed [ENTRY_LAG_NANOS] later on the entry. */
    private val staggerTimes = LongArray(STAGGER_SAMPLES)
    private val staggerValues = FloatArray(STAGGER_SAMPLES)
    private var staggerCount = 0
    private var staggerHead = 0

    /** Builds or re-anchors the module views; false when the host views are not inflated yet. */
    fun attach(): Boolean {
        val view = HostAccess.readDeclared(controller, "mVolumeView") as? ViewGroup ?: return false
        val target = HostAccess.readDeclared(controller, "mVolumeContainerView") as? ViewGroup ?: return false
        if (dialog === view && strip?.parent === target && entry?.parent === view) return true
        detach()
        removeStale(view)
        removeStale(target)
        return runCatching {
            val context = view.context
            val strip = LinearLayout(context).apply {
                tag = MARKER
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
                visibility = View.GONE
            }
            target.addView(strip, 0, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_VERTICAL })
            val entry = buildEntry(context)
            view.addView(entry, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.END })
            this.dialog = view
            this.container = target
            this.strip = strip
            this.entry = entry
            motion = HostAccess.readDeclared(view, "mMotion")
            mainColumn = HostAccess.readDeclared(controller, "mVolumeContentView") as? View
            tempColumn = HostAccess.readDeclared(controller, "mTempColumnContainer") as? View
            nativeBadge = HostAccess.readDeclared(controller, "mSuperVolumeBg") as? View
            (view.parent as? FrameLayout)?.let { root ->
                removeStale(root)
                capsules = FrameLayout(context).apply {
                    tag = MARKER
                    clipChildren = false
                    isClickable = false
                    isFocusable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    visibility = View.GONE
                }.also { root.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
            }
            applyHostStyle()
            DebugLog.d(TAG, "attached controller=${System.identityHashCode(controller)}")
            true
        }.onFailure {
            DebugLog.w(TAG, "Cannot attach app volume views", it)
            detach()
        }.getOrDefault(false)
    }

    fun detach() {
        alignBadgeForShow = false
        (strip?.parent as? ViewGroup)?.removeView(strip)
        (entry?.parent as? ViewGroup)?.removeView(entry)
        (capsules?.parent as? ViewGroup)?.removeView(capsules)
        columns.values.forEach { it.animate().cancel() }
        capsules = null
        capsuleViews.clear()
        motion = null
        mainColumn = null
        tempColumn = null
        nativeBadge = null
        closing = false
        strip = null
        entry = null
        entryBlur = null
        entryStandard = null
        glyph = null
        dialog = null
        container = null
        columns.clear()
        styledOn = null
    }

    /** Fresh show (the host's `mShowing` was false): reset the entry and take the current gains. */
    fun onShow() {
        if (!attach()) return
        soundAssist = feature.audio?.soundAssistEnabled() == true
        rows = select(retain = false)
        open = feature.autoExpand && rows.isNotEmpty()
        autoOpenPending = feature.autoExpand && rows.isEmpty()
        alignBadgeForShow = false
        closing = false
        // The columns ride in with the dialog; any split left over from the last show is reset.
        columns.values.forEach(::settle)
        render()
        feature.audio?.refreshGains(rows.map { it.key })
    }

    /** Players, app labels or gains changed. */
    fun onAudioChanged() {
        if (dialog == null) return
        val before = columns.keys.toSet()
        rows = select(retain = showing())
        val split = autoOpenPending && rows.isNotEmpty() && showing()
        if (split) {
            autoOpenPending = false
            open = true
        }
        render()
        if (split) splitOut()
        // A player that started while the dialog is up: show its real gain, not a default.
        rows.map { it.key }.filterNot(before::contains).takeIf { it.isNotEmpty() && showing() }
            ?.let { feature.audio?.refreshGains(it) }
    }

    /** Configuration, density or material mode changed: restyle in place. */
    fun applyHostStyle() {
        val entry = entry ?: return
        runCatching {
            val context = entry.context
            val width = host.buttonWidth(context)
            val height = host.buttonHeight(context)
            listOfNotNull(entryStandard, entryBlur).forEach { view ->
                view.layoutParams = view.layoutParams.apply { this.width = width; this.height = height }
            }
            (entry.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                params.topMargin = ringerGap()
                entry.layoutParams = params
            }
            styledOn = null
            styleEntry()
            columns.values.forEach(AppVolumeColumn::applyHostStyle)
            applyPercentStyle()
        }.onFailure { DebugLog.w(TAG, "Cannot restyle app volume views", it) }
        sync()
    }

    /** Visibility follows the host state; called from every host transition and before drawing. */
    fun sync() {
        val entry = entry ?: return
        val strip = strip ?: return
        val active = feature.enabled && soundAssist && rows.isNotEmpty() && !expanded() &&
            needShowDialog() && !inControlCenter() && footerVisible()
        if (!active) cancelSplit()
        setVisibility(entry, active)
        val stripVisible = active && (open || closing)
        if (stripVisible) {
            alignBadgeForShow = true
        } else if (strip.visibility == View.VISIBLE) {
            columns.values.forEach(AppVolumeColumn::resetStretch)
        }
        setVisibility(strip, stripVisible)
        capsules?.let { setVisibility(it, strip.visibility == View.VISIBLE && outerPercent()) }
        styleEntry()
    }

    /** Slider-percentage style or the app-volume percentage position changed. */
    fun onSettingsChanged() {
        runCatching { applyPercentStyle() }.onFailure { DebugLog.w(TAG, "Cannot style app volume percentages", it) }
        sync()
    }

    /**
     * Pixels to move the host's collapsed show target (`MiuiVolumeDialogMotion.getShowX`) left:
     * the target places the native column at the right edge, and the visible strip widens the
     * dialog by its own width. Left-anchored dialogs keep the native target.
     */
    fun showXOffset(motion: Any): Int {
        if (motion !== this.motion) return 0
        val strip = strip ?: return 0
        if (strip.visibility != View.VISIBLE || columns.isEmpty()) return 0
        val left = runCatching {
            HostAccess.declaredMethod(motion.javaClass, "volumeShowLeft", accessible = true)?.invoke(motion) as? Boolean
        }.getOrNull() ?: return 0
        if (left) return 0
        val context = strip.context
        return columns.size * (host.columnWidth(context) + columnGap(context))
    }

    /** Immediate hide ahead of a host transition; the next [sync] decides again. */
    fun hide() {
        alignBadgeForShow = false
        columns.values.forEach(AppVolumeColumn::resetStretch)
        cancelSplit()
        entry?.let { setVisibility(it, false) }
        strip?.let { setVisibility(it, false) }
        capsules?.let { setVisibility(it, false) }
    }

    // ─── Split animation ──────────────────────────────────────────────────────

    /** Distance from a column's slot to the native media column, which sits right of the strip. */
    private fun foldDistance(index: Int): Float {
        val context = strip?.context ?: return 0f
        return ((columns.size - index) * (host.columnWidth(context) + columnGap(context))).toFloat()
    }

    /** Columns start under the native column (the strip draws before it) and fan out leftward. */
    private fun splitOut() {
        val count = columns.size
        columns.values.forEachIndexed { index, column ->
            column.animate().cancel()
            column.translationX = foldDistance(index)
            column.alpha = 0f
            column.animate()
                .translationX(0f).alpha(1f)
                .setStartDelay((count - 1 - index) * SPLIT_STAGGER_MS)
                .setDuration(SPLIT_DURATION_MS)
                .setInterpolator(SPLIT_EASE)
                .start()
        }
    }

    /** Folds the columns back under the native column, then removes the strip. */
    private fun foldIn() {
        if (columns.isEmpty()) { closing = false; sync(); return }
        closing = true
        val count = columns.size
        var remaining = count
        columns.values.forEachIndexed { index, column ->
            column.animate().cancel()
            column.animate()
                .translationX(foldDistance(index)).alpha(0f)
                .setStartDelay(index * SPLIT_STAGGER_MS)
                .setDuration(FOLD_DURATION_MS)
                .setInterpolator(SPLIT_EASE)
                .withEndAction {
                    if (--remaining == 0 && closing) {
                        closing = false
                        columns.values.forEach(::settle)
                        sync()
                    }
                }
                .start()
        }
    }

    private fun cancelSplit() {
        closing = false
        columns.values.forEach(::settle)
    }

    private fun settle(column: View) {
        column.animate().cancel()
        column.translationX = 0f
        column.alpha = 1f
    }

    private fun render() {
        val strip = strip ?: return
        val audio = feature.audio ?: return
        val wanted = rows.map { it.key }.toSet()
        columns.keys.filterNot(wanted::contains).forEach { key ->
            columns.remove(key)?.let { it.animate().cancel(); strip.removeView(it) }
            capsuleViews.remove(key)?.let { capsules?.removeView(it.first) }
        }
        rows.forEachIndexed { index, row ->
            val existing = columns[row.key]
            val column = existing ?: run {
                AppVolumeColumn(strip.context, host, row.key, this).also { created ->
                    columns[row.key] = created
                    strip.addView(created, index, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ))
                }
            }
            if (strip.indexOfChild(column) != index) {
                strip.removeView(column)
                strip.addView(column, index)
            }
            val appChanged = column.bind(audio.apps[row.key], audio.gains[row.key])
            if (existing == null || appChanged) stylePercent(column)
        }
        updateColumnGaps()
        sync()
    }

    private fun select(retain: Boolean): List<AppVolumeCandidates.Row> {
        val players = feature.audio?.players.orEmpty()
        return AppVolumeCandidates.select(players, if (retain) columns.keys.toList() else emptyList())
    }

    // ─── Entry button ─────────────────────────────────────────────────────────

    private fun buildEntry(context: Context): EntryFrame {
        val frame = EntryFrame(context).apply {
            tag = MARKER
            clipChildren = false
            visibility = View.GONE
        }
        val button = LayoutInflater.from(context).inflate(host.ringerLayout, frame, false)
        val blur = requireNotNull(button.findViewById<View>(host.idBlur)) { "bg_blur" }
        val standard = requireNotNull(button.findViewById<View>(host.idStandard)) { "miui_standard_btn" }
        val icon = requireNotNull(standard.findViewById<ImageView>(host.idIcon)) { "icon" }
        val size = host.buttonIconSize(context)
        icon.layoutParams = icon.layoutParams.apply { width = size; height = size }
        val glyph = AppVolumeGlyph(INACTIVE_ICON)
        icon.setImageDrawable(glyph)
        blur.contentDescription = entryLabel(context)
        blur.setOnClickListener { view ->
            open = !open
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            if (open) {
                closing = false
                feature.audio?.refreshGains(rows.map { it.key })
                sync()
                splitOut()
            } else {
                styleEntry()
                foldIn()
            }
            rescheduleTimeout()
        }
        frame.addView(button, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))
        entryBlur = blur
        entryStandard = standard
        this.glyph = glyph
        return frame
    }

    private fun styleEntry() {
        val blur = entryBlur ?: return
        val standard = entryStandard ?: return
        if (styledOn == open) return
        runCatching {
            host.styleButton(blur, standard, open)
            glyph?.color = if (open) ACTIVE_ICON else INACTIVE_ICON
            blur.isSelected = open
            styledOn = open
        }.onFailure { DebugLog.w(TAG, "Cannot style app volume entry", it) }
    }

    /** The gap the host keeps between the silent and DND buttons. */
    private fun ringerGap(): Int {
        val ringer = ringerLayout() ?: return 0
        return ringer.findViewById<View>(host.idDivider)?.layoutParams?.height?.coerceAtLeast(0) ?: 0
    }

    /**
     * Centered under the ringer column, and following the DND button's drag stretch
     * (`SeekBarAnimListener.setDndY/setScale`), which the host applies to that button only.
     */
    private fun follow(frame: View) {
        val ringer = ringerLayout() ?: return
        val dndLayout = ringer.findViewById<View>(host.idDndLayout)
        // The show/hide animator moves each ringer button on its own delayed spring and rests it
        // at x = 0 (VolumeShowHideAnimator.setViewX); the entry replays the DND button's offset.
        val stagger = lagged(dndLayout?.x ?: 0f)
        if (ringer.parent === frame.parent && ringer.width > 0 && frame.width > 0) {
            frame.translationX = (ringer.left + ringer.width / 2f) - (frame.left + frame.width / 2f) + stagger.first
        }
        if (stagger.second) frame.postInvalidateOnAnimation()
        var y = 0f
        // Show scale: the animator pulls each button toward the container center in proportion.
        val dialog = dialog
        val container = container
        if (dndLayout != null && dialog != null && container != null && dndLayout.translationY != 0f) {
            val center = topIn(container, dialog) + container.height / 2f
            val dndDistance = topIn(dndLayout, dialog) + dndLayout.height / 2f - center
            val entryDistance = frame.top + frame.height / 2f - center
            if (dndDistance > 1f) y += dndLayout.translationY * entryDistance / dndDistance
        }
        dndLayout?.findViewById<View>(host.idBlur)?.let { dnd ->
            y += dnd.translationY
            frame.scaleX = dnd.scaleX
            frame.scaleY = dnd.scaleY
        }
        frame.translationY = y
        val stripVisible = strip?.visibility == View.VISIBLE
        if (alignBadgeForShow || stripVisible) {
            val temp = tempColumn?.takeIf { it.visibility == View.VISIBLE }
            if (alignBadgeForShow) {
                val aboveTemp = temp != null &&
                    (motion?.let { HostAccess.readDeclared(it, "mSuperVolumeShowAboveTemp") } as? Boolean == true)
                val anchor = if (aboveTemp) temp else mainColumn
                anchor?.let(::alignNativeBadge)
            }
            if (stripVisible) {
                // The active seekbar can stretch either native container while both are visible.
                val stretch = if (temp != null && (temp.scaleX != 1f || temp.translationY != 0f)) temp else mainColumn
                stretch?.let { source -> columns.values.forEach { it.followHostStretch(source) } }
                placeCapsules()
            }
        }
        // Footer visibility also changes on stream switches that no hooked method reports.
        val footer = ringer.visibility == View.VISIBLE
        if (footer != lastFooter) {
            lastFooter = footer
            sync()
        }
    }

    /** The offset the DND button had [ENTRY_LAG_NANOS] ago, and whether it is still moving. */
    private fun lagged(value: Float): Pair<Float, Boolean> {
        val now = System.nanoTime()
        staggerHead = (staggerHead + 1) % STAGGER_SAMPLES
        staggerTimes[staggerHead] = now
        staggerValues[staggerHead] = value
        if (staggerCount < STAGGER_SAMPLES) staggerCount++
        var result = staggerValues[(staggerHead - staggerCount + 1 + STAGGER_SAMPLES) % STAGGER_SAMPLES]
        for (step in 0 until staggerCount) {
            val index = (staggerHead - step + STAGGER_SAMPLES) % STAGGER_SAMPLES
            if (now - staggerTimes[index] >= ENTRY_LAG_NANOS) { result = staggerValues[index]; break }
        }
        return result to (result != value)
    }

    private fun topIn(view: View, ancestor: View): Float {
        var top = 0f
        var current: View? = view
        while (current != null && current !== ancestor) {
            top += current.top
            current = current.parent as? View
        }
        return top
    }

    // ─── Percentages ──────────────────────────────────────────────────────────

    private fun outerPercent() = feature.percentMode == SystemUiSettingsKeys.APP_VOLUME_PERCENT_OUTSIDE && capsules != null

    private fun applyPercentStyle() {
        percentStyle.policy = feature.percentPolicy
        percentStyle.invalidate()
        columns.values.forEach(::stylePercent)
        updateColumnGaps()
    }

    private fun columnGap(context: Context): Int {
        val nativeGap = host.columnGap(context)
        val capsuleWidth = capsuleViews.values.firstOrNull()?.first?.layoutParams?.width ?: return nativeGap
        val nativeWidth = nativeBadge?.layoutParams?.width ?: 0
        val width = maxOf(capsuleWidth, nativeWidth)
        val clearance = (CAPSULE_GAP_DP * context.resources.displayMetrics.density).toInt()
        return maxOf(nativeGap, width + clearance - host.columnWidth(context))
    }

    private fun updateColumnGaps() {
        val context = strip?.context ?: return
        val gap = columnGap(context)
        columns.values.forEach { column ->
            val params = column.layoutParams as? ViewGroup.MarginLayoutParams ?: return@forEach
            if (params.marginEnd != gap) {
                params.marginEnd = gap
                column.layoutParams = params
            }
        }
    }

    private fun stylePercent(column: AppVolumeColumn) {
        val outer = outerPercent()
        column.showInnerPercent = feature.percentMode == SystemUiSettingsKeys.APP_VOLUME_PERCENT_INSIDE ||
            (feature.percentMode == SystemUiSettingsKeys.APP_VOLUME_PERCENT_OUTSIDE && capsules == null)
        val colorMode = feature.percentColor
        val accent = if (colorMode == AppVolumePercentColor.APP_ICON) column.iconColor else colorMode.color
        val neutral = colorMode == AppVolumePercentColor.NEUTRAL
        if (column.showInnerPercent) percentStyle.inner(column, accent, neutral)
        val layer = capsules ?: return
        if (!outer) {
            capsuleViews.remove(column.key)?.let { layer.removeView(it.first) }
            return
        }
        val (background, label) = capsuleViews.getOrPut(column.key) {
            val label = TextView(layer.context).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                isSingleLine = true
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, host.percentTextSize(context))
            }
            val background = FrameLayout(layer.context).apply {
                addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            layer.addView(background, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START
            ))
            background to label
        }
        percentStyle.outer(background, label, accent, neutral)
        DebugLog.d(TAG) { "app capsule color mode=$colorMode accent=${accent?.toUInt()?.toString(16)} text=${label.currentTextColor.toUInt().toString(16)}" }
    }

    /** Show/hide positions the native badge from dialog X, assuming no app columns are present. */
    private fun alignNativeBadge(column: View) {
        val badge = nativeBadge ?: return
        val parent = badge.parent as? View ?: return
        if (badge.visibility != View.VISIBLE || badge.width == 0 || column.width == 0) return
        mapColumnTop(column, parent)
        val target = (columnPoints[0] + columnPoints[2]) / 2f - badge.width / 2f - badge.left
        if (kotlin.math.abs(target - badge.translationX) > 0.25f) badge.translationX = target
    }

    private fun mapColumnTop(column: View, destination: View) {
        columnPoints[0] = 0f
        columnPoints[1] = 0f
        columnPoints[2] = column.width.toFloat()
        columnPoints[3] = 0f
        columnMatrix.reset()
        column.transformMatrixToGlobal(columnMatrix)
        destination.transformMatrixToLocal(columnMatrix)
        columnMatrix.mapPoints(columnPoints)
    }

    /** Outside capsules follow layout, show/hide, split and stretch in the layer's coordinates. */
    private fun placeCapsules() {
        val layer = capsules ?: return
        if (layer.visibility != View.VISIBLE) return
        val spacing = CAPSULE_SPACING_DP * layer.resources.displayMetrics.density
        columns.forEach { (key, column) ->
            val (background, label) = capsuleViews[key] ?: return@forEach
            val text = column.percent.text
            if (label.text?.toString() != text?.toString()) label.text = text
            if (background.width == 0 || column.width == 0) return@forEach
            mapColumnTop(column, layer)
            val scale = (columnPoints[2] - columnPoints[0]) / column.width
            val centerX = (columnPoints[0] + columnPoints[2]) / 2f
            background.pivotX = background.width / 2f
            background.pivotY = background.height.toFloat()
            background.scaleX = scale
            background.scaleY = scale
            background.translationX = centerX - background.width / 2f - background.left
            background.translationY = columnPoints[1] - spacing * scale - background.height - background.top
            background.alpha = column.alpha
        }
    }

    private inner class EntryFrame(context: Context) : FrameLayout(context) {
        private val preDraw = ViewTreeObserver.OnPreDrawListener {
            HookFailurePolicy.open(TAG, "follow volume panel", Unit) { follow(this) }
            true
        }
        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            viewTreeObserver.addOnPreDrawListener(preDraw)
        }
        override fun onDetachedFromWindow() {
            viewTreeObserver.removeOnPreDrawListener(preDraw)
            super.onDetachedFromWindow()
        }
    }

    // ─── Column callbacks ─────────────────────────────────────────────────────

    override fun onGain(column: AppVolumeColumn, gain: Float, final: Boolean) {
        val row = rows.firstOrNull { it.key == column.key } ?: return
        feature.audio?.write(row, gain)
        rescheduleTimeout()
        if (final) DebugLog.d(TAG, "gain key=${row.key} uids=${row.uids} value=${AppVolumeCandidates.percent(gain)}%")
    }

    override fun onInteraction() = rescheduleTimeout()

    // ─── Host reads ───────────────────────────────────────────────────────────

    private fun rescheduleTimeout() {
        runCatching {
            HostAccess.declaredMethod(controller.javaClass, "rescheduleTimeoutH", accessible = true)?.invoke(controller)
        }.onFailure { DebugLog.w(TAG, "Cannot reschedule volume dialog timeout", it) }
    }

    private fun ringerLayout() = HostAccess.readDeclared(controller, "mRingerModeLayout") as? View
    private fun footerVisible() = ringerLayout()?.visibility == View.VISIBLE
    private fun expanded() = HostAccess.readDeclared(controller, "mExpanded") as? Boolean ?: true
    private fun showing() = HostAccess.readDeclared(controller, "mShowing") as? Boolean ?: false
    private fun needShowDialog() = HostAccess.readDeclared(controller, "mNeedShowDialog") as? Boolean ?: false
    private fun inControlCenter() = HostAccess.readDeclared(controller, "isControlCenterPanel") as? Boolean ?: true

    private fun setVisibility(view: View, visible: Boolean) {
        val next = if (visible) View.VISIBLE else View.GONE
        if (view.visibility != next) view.visibility = next
    }

    @SuppressLint("NewApi")
    private fun entryLabel(context: Context): String {
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        return if (locale.language == Locale.CHINESE.language) "应用音量" else "App volume"
    }

    companion object {
        private const val TAG = "AppVolume"
        /** Survives a module generation change, so a newer build can remove an older build's views. */
        const val MARKER = "hypertweak.appVolume"
        private const val INACTIVE_ICON = 0xFFFFFFFF.toInt()
        /**
         * HyperOS accent blue (the module's active percentage blue): distinct from the silent
         * button's red and the DND button's purple, and readable on the light "on" capsule.
         */
        private const val ACTIVE_ICON = 0xFF3482FF.toInt()
        /** Native stagger: volume 0 ms, silent 30 ms, DND 50 ms; the entry follows 20 ms later. */
        private const val ENTRY_LAG_NANOS = 20_000_000L
        private const val STAGGER_SAMPLES = 16
        private const val SPLIT_DURATION_MS = 420L
        private const val FOLD_DURATION_MS = 300L
        private const val SPLIT_STAGGER_MS = 35L
        private const val CAPSULE_SPACING_DP = 8f
        private const val CAPSULE_GAP_DP = 4f
        private val SPLIT_EASE = PathInterpolator(0.2f, 0f, 0f, 1f)

        fun removeStale(parent: ViewGroup) {
            (parent.childCount - 1 downTo 0).map(parent::getChildAt).filter { it.tag == MARKER }.forEach(parent::removeView)
        }
    }
}
