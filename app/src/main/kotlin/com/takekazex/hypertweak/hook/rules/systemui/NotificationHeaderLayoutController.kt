package com.takekazex.hypertweak.hook.rules.systemui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.TextView
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.IdentityHashMap
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/** Owns the reversible, orientation-dependent layout state for each OS4 notification header. */
internal class NotificationHeaderLayoutController(
    private val hostUsesVerticalMode: (Context) -> Boolean,
    private val hostUsesLandscapeMode: (Context) -> Boolean,
    private val reportFailure: (String, Throwable) -> Unit
) {
    data class Options(
        val hideCarrier: Boolean,
        val hideTime: Boolean,
        val hideDate: Boolean,
        val dateAboveTime: Boolean,
        val dateAlignment: Int,
        val timeAlignment: Int,
        val timeScale: Float
    )

    data class Views(
        val time: View,
        val date: View,
        val carrier: View?,
        val horizontalTime: View?
    )

    private enum class Mode { UNINITIALIZED, PORTRAIT, LANDSCAPE, HOST }

    private data class LayoutSnapshot(
        val values: LinkedHashMap<String, Int> = LinkedHashMap(),
        var marginStart: Int? = null
    )

    private data class ClockTextSnapshot(
        val singleLine: Boolean,
        val maxLines: Int,
        val ellipsize: TextUtils.TruncateAt?,
        val horizontallyScrollable: Boolean,
        val textScaleX: Float
    )

    private class Session(
        val root: ViewGroup,
        val views: Views,
        var stockHeaderHeight: Int,
        var notificationReserveHeight: Int,
        val originalTranslationY: Float,
        val originalClockScaleX: Float,
        var mode: Mode = Mode.UNINITIALIZED,
        var notificationScrollY: Int = 0,
        var stretchTranslationY: Float = originalTranslationY,
        var dateTimeSeparationView: View? = null,
        var dateTimeSeparationOffsetY: Float = 0f,
        var dateTimeSeparationAppliedTranslationY: Float = 0f,
        var latestInsets: WindowInsets? = null,
        var horizontalClockMode: Int? = null,
        var clockTextSnapshot: ClockTextSnapshot? = null,
        var clockLayoutListener: View.OnLayoutChangeListener? = null
    ) {
        val layouts = WeakHashMap<View, LayoutSnapshot>()
        val fontPadding = WeakHashMap<TextView, Boolean>()
    }

    private var options = Options(
        hideCarrier = false,
        hideTime = false,
        hideDate = false,
        dateAboveTime = false,
        dateAlignment = NotificationHeaderModel.ALIGN_START,
        timeAlignment = NotificationHeaderModel.ALIGN_START,
        timeScale = NotificationHeaderModel.DEFAULT_TIME_SCALE
    )
    private var notificationReserveChanged: (ViewGroup) -> Unit = {}

    // The session owns its root and children, so weak keys would retain the same objects through
    // the map value. Detach/hot-reload explicitly releases these short-lived SystemUI views.
    private val sessions = IdentityHashMap<ViewGroup, Session>()
    private val rootsByView = IdentityHashMap<View, ViewGroup>()
    private val fields = HashMap<Pair<Class<*>, String>, Field?>()
    private val methods = HashMap<Triple<Class<*>, String, Int>, Method?>()

    fun configure(options: Options) {
        this.options = options
    }

    fun setNotificationReserveChangedListener(listener: (ViewGroup) -> Unit) {
        notificationReserveChanged = listener
    }

    fun contains(root: ViewGroup): Boolean = sessions.containsKey(root)

    fun views(root: ViewGroup): Views? = sessions[root]?.views

    fun roots(): List<ViewGroup> = sessions.keys.toList()

    fun rootFor(view: View): ViewGroup? = rootsByView[view]

    fun isCustomPortrait(root: ViewGroup): Boolean = sessions[root]?.mode == Mode.PORTRAIT

    fun notificationPaddingExtra(root: ViewGroup): Int = sessions[root]?.let {
        (it.notificationReserveHeight - it.stockHeaderHeight).coerceAtLeast(0)
    } ?: 0

    fun scrollY(root: ViewGroup): Int = sessions[root]?.notificationScrollY ?: 0

    fun updateScrollForAll(scrollY: Int) {
        sessions.values.toList().forEach { session ->
            session.notificationScrollY = scrollY.coerceAtLeast(0)
            applyRootMotion(session)
        }
    }

    fun updateStretchTranslation(root: ViewGroup, translationY: Float) {
        sessions[root]?.let { session ->
            session.stretchTranslationY = translationY
            applyRootMotion(session)
        }
    }

    fun onNativeExpansionChanged(): Boolean {
        var changed = false
        sessions.values.toList().forEach { session ->
            if (session.mode == Mode.PORTRAIT) {
                maintainDateTimeSeparation(session)
                changed = measureNotificationReserveHeight(session) || changed
            }
        }
        return changed
    }

    /** Reconciles host layout, module layout, and current insets in one idempotent pass. */
    fun apply(root: ViewGroup, insets: WindowInsets? = null) {
        val session = sessions[root] ?: createSession(root)?.also { sessions[root] = it } ?: return
        session.latestInsets = insets ?: root.rootWindowInsets

        val targetMode = resolveMode(root.context)
        if (session.mode != targetMode) {
            if (session.mode != Mode.UNINITIALIZED) restoreOwnedState(session)
            session.stockHeaderHeight = root.layoutParams?.height ?: session.stockHeaderHeight
            session.notificationReserveHeight = session.stockHeaderHeight
            session.notificationScrollY = 0
            session.stretchTranslationY = session.originalTranslationY
            root.translationY = session.originalTranslationY
            session.mode = targetMode
        }

        when (targetMode) {
            Mode.PORTRAIT -> applyPortrait(session)
            Mode.LANDSCAPE -> applyLandscape(session)
            Mode.HOST, Mode.UNINITIALIZED -> applyHostLayout(session)
        }
    }

    /** Called before host resource methods that rewrite margins/constraints; after-hook reapplies. */
    fun beforeHostLayoutReset(root: ViewGroup) {
        val session = sessions[root] ?: return
        if (session.mode != Mode.UNINITIALIZED) restoreOwnedState(session)
        session.notificationScrollY = 0
        session.stretchTranslationY = session.originalTranslationY
        root.translationY = session.originalTranslationY
        session.mode = Mode.UNINITIALIZED
    }

    fun release(root: ViewGroup) {
        val session = sessions.remove(root) ?: return
        removeClockLayoutListener(session)
        restoreOwnedState(session)
        root.translationY = session.originalTranslationY
        session.views.time.scaleX = session.originalClockScaleX
        listOfNotNull(session.views.time, session.views.date, session.views.carrier, session.views.horizontalTime)
            .forEach(rootsByView::remove)
    }

    fun enforceVisibility(view: View) {
        val root = rootsByView[view] ?: (view.parent as? ViewGroup) ?: return
        val session = sessions[root] ?: return
        when (session.mode) {
            Mode.LANDSCAPE -> {
                if (hostUsesVerticalMode(root.context)) {
                    if (view === session.views.time || view === session.views.date) view.visibility = View.GONE
                    if (view === session.views.horizontalTime) view.visibility = View.VISIBLE
                }
                if (view === session.views.horizontalTime) applyHorizontalVisibility(session)
                if (view === session.views.carrier && options.hideCarrier) view.visibility = View.GONE
            }
            Mode.PORTRAIT -> applyPortraitVisibility(session, view)
            Mode.HOST -> {
                if (view === session.views.time && hostUsesVerticalMode(root.context) && options.hideTime) {
                    view.visibility = View.GONE
                }
                if (view === session.views.date && hostUsesVerticalMode(root.context) && options.hideDate) {
                    view.visibility = View.GONE
                }
                if (view === session.views.horizontalTime && !hostUsesVerticalMode(root.context)) {
                    applyHorizontalVisibility(session)
                }
                if (view === session.views.carrier && options.hideCarrier) view.visibility = View.GONE
            }
            Mode.UNINITIALIZED -> Unit
        }
    }

    fun onClockTextChanged(view: TextView): Boolean {
        val root = rootsByView[view] ?: return false
        val session = sessions[root] ?: return false
        if (session.mode == Mode.PORTRAIT && session.views.time === view) {
            fitClockText(session, view)
            val adjusted = maintainDateTimeSeparation(session)
            return measureNotificationReserveHeight(session) || adjusted
        }
        return false
    }

    fun restoreAll() {
        sessions.values.toList().forEach { session ->
            removeClockLayoutListener(session)
            restoreOwnedState(session)
            session.root.translationY = session.originalTranslationY
            session.views.time.scaleX = session.originalClockScaleX
        }
        sessions.clear()
        rootsByView.clear()
        fields.clear()
        methods.clear()
    }

    private fun createSession(root: ViewGroup): Session? {
        val time = read(root, "mBigTime") as? View ?: root.findViewById(id(root, "big_time")) ?: return null
        val date = read(root, "mDateView") as? View ?: root.findViewById(id(root, "date_time")) ?: return null
        val carrier = read(root, "mCarrierContainer") as? View
            ?: root.findViewById(id(root, "carrier_container"))
        val horizontalTime = read(root, "mLandClock") as? View
            ?: root.findViewById(id(root, "horizontal_time"))
        val views = Views(time, date, carrier, horizontalTime)
        val height = root.layoutParams?.height ?: 0
        val session = Session(
            root = root,
            views = views,
            stockHeaderHeight = height,
            notificationReserveHeight = height,
            originalTranslationY = root.translationY,
            originalClockScaleX = time.scaleX,
            latestInsets = root.rootWindowInsets
        )
        (time as? TextView)?.let { clock ->
            val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                if (session.mode == Mode.PORTRAIT) {
                    fitClockText(session, clock)
                    maintainDateTimeSeparation(session)
                    if (measureNotificationReserveHeight(session)) notificationReserveChanged(root)
                }
            }
            session.clockLayoutListener = listener
            clock.addOnLayoutChangeListener(listener)
        }
        listOfNotNull(time, date, carrier, horizontalTime).forEach { rootsByView[it] = root }
        return session
    }

    private fun resolveMode(context: Context): Mode = when {
        hostUsesLandscapeMode(context) -> Mode.LANDSCAPE
        context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE -> Mode.LANDSCAPE
        context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
            hostUsesVerticalMode(context) -> Mode.PORTRAIT
        else -> Mode.HOST
    }

    private fun applyPortrait(session: Session) {
        restoreHorizontalClock(session)
        applyHostVisibility(session)
        val views = session.views
        if (options.hideTime) views.time.visibility = View.GONE
        if (options.hideDate) views.date.visibility = View.GONE
        if (options.hideCarrier) views.carrier?.visibility = View.GONE

        if (options.dateAboveTime) {
            change(
                session,
                views.date,
                mapOf(
                    "topToTop" to PARENT_ID,
                    "topToBottom" to UNSET,
                    "bottomToTop" to UNSET,
                    "bottomToBottom" to UNSET,
                    "topMargin" to safeTopMarginPx(session)
                )
            )
            val timeVerticalConstraints = if (!options.hideDate && views.date.id != View.NO_ID) {
                mapOf(
                    "topToTop" to UNSET,
                    "topToBottom" to views.date.id,
                    "bottomToTop" to UNSET,
                    "bottomToBottom" to UNSET,
                    "topMargin" to dp(session.root, HEADER_ROW_GAP_DP),
                    "bottomMargin" to 0
                )
            } else {
                mapOf(
                    "topToTop" to PARENT_ID,
                    "topToBottom" to UNSET,
                    "bottomToTop" to UNSET,
                    "bottomToBottom" to UNSET,
                    "topMargin" to safeTopMarginPx(session),
                    "bottomMargin" to 0
                )
            }
            change(session, views.time, timeVerticalConstraints)
        }

        val compactTextBounds = options.dateAboveTime || options.timeScale > 1f
        if (compactTextBounds) {
            removeFontPadding(session, views.date)
            removeFontPadding(session, views.time)
        } else {
            restoreFontPadding(session, views.date)
            restoreFontPadding(session, views.time)
        }
        val timeClock = views.time as? TextView
        if (timeClock != null && options.timeScale > 1f) configureSingleLineClock(session, timeClock)
        else timeClock?.let { restoreClockText(session, it) }
        applyAlignment(session, views.date, options.dateAlignment)
        applyAlignment(session, views.time, options.timeAlignment)
        measureNotificationReserveHeight(session)
        applyRootMotion(session)
    }

    private fun applyLandscape(session: Session) {
        val views = session.views
        // Prefer the host's horizontal layout. Only override it for large-screen builds whose
        // MiuiConfigs.isVerticalMode() remains true after the display rotates.
        if (hostUsesVerticalMode(session.root.context)) {
            views.time.visibility = View.GONE
            views.date.visibility = View.GONE
            views.horizontalTime?.visibility = View.VISIBLE
            alignCarrierWithHorizontalClock(session)
        } else {
            applyHostVisibility(session)
        }
        if (options.hideCarrier) views.carrier?.visibility = View.GONE
        val clock = views.horizontalTime ?: run {
            applyRootMotion(session)
            return
        }
        val defaultMode = session.horizontalClockMode
            ?: (read(clock, "mClockMode") as? Number)?.toInt()?.also { session.horizontalClockMode = it }
            ?: 2
        when {
            options.hideTime && options.hideDate -> clock.visibility = View.GONE
            options.hideTime || options.hideDate -> {
                val mode = when {
                    options.hideTime -> DATE_ONLY_CLOCK_MODE
                    options.hideDate -> TIME_ONLY_CLOCK_MODE
                    else -> defaultMode
                }
                setClockMode(clock, mode)
                clock.visibility = View.VISIBLE
            }
            else -> {
                setClockMode(clock, defaultMode)
                if (hostUsesVerticalMode(session.root.context)) clock.visibility = View.VISIBLE
            }
        }
        applyRootMotion(session)
    }

    private fun applyHostLayout(session: Session) {
        applyHostVisibility(session)
        if (hostUsesVerticalMode(session.root.context)) {
            if (options.hideTime) session.views.time.visibility = View.GONE
            if (options.hideDate) session.views.date.visibility = View.GONE
        } else {
            applyHorizontalVisibility(session)
        }
        if (options.hideCarrier) session.views.carrier?.visibility = View.GONE
        applyRootMotion(session)
    }

    private fun applyPortraitVisibility(session: Session, view: View) {
        if (view === session.views.time && options.hideTime) view.visibility = View.GONE
        if (view === session.views.date && options.hideDate) view.visibility = View.GONE
        if (view === session.views.carrier && options.hideCarrier) view.visibility = View.GONE
    }

    private fun applyHostVisibility(session: Session) {
        updateClockVisibility(session.views.time)
        updateClockVisibility(session.views.date)
        session.views.horizontalTime?.let(::updateClockVisibility)
    }

    private fun applyHorizontalVisibility(session: Session) {
        val clock = session.views.horizontalTime ?: return
        val defaultMode = session.horizontalClockMode
            ?: (read(clock, "mClockMode") as? Number)?.toInt()?.also { session.horizontalClockMode = it }
            ?: 2
        when {
            options.hideTime && options.hideDate -> clock.visibility = View.GONE
            options.hideTime || options.hideDate -> {
                setClockMode(clock, if (options.hideTime) DATE_ONLY_CLOCK_MODE else TIME_ONLY_CLOCK_MODE)
                clock.visibility = View.VISIBLE
            }
            else -> setClockMode(clock, defaultMode)
        }
    }

    private fun alignCarrierWithHorizontalClock(session: Session) {
        val carrier = session.views.carrier ?: return
        val clock = session.views.horizontalTime ?: return
        val clockId = clock.id.takeIf { it != View.NO_ID } ?: return
        change(
            session,
            carrier,
            mapOf(
                "topToTop" to clockId,
                "topToBottom" to UNSET,
                "bottomToTop" to UNSET,
                "bottomToBottom" to clockId,
                "startToStart" to UNSET,
                "startToEnd" to clockId,
                "endToStart" to UNSET,
                "endToEnd" to PARENT_ID
            )
        )
    }

    private fun restoreOwnedState(session: Session) {
        restorePortraitState(session)
        restoreHorizontalClock(session)
        applyHostVisibility(session)
    }

    private fun removeClockLayoutListener(session: Session) {
        val clock = session.views.time as? TextView ?: return
        session.clockLayoutListener?.let(clock::removeOnLayoutChangeListener)
        session.clockLayoutListener = null
    }

    private fun restorePortraitState(session: Session) {
        clearDateTimeSeparation(session)
        restoreLayouts(session)
        session.fontPadding.forEach { (clock, included) -> clock.includeFontPadding = included }
        session.fontPadding.clear()
        (session.views.time as? TextView)?.let { restoreClockText(session, it) }
        session.root.layoutParams?.let { params ->
            if (params.height != session.stockHeaderHeight) {
                params.height = session.stockHeaderHeight
                session.root.layoutParams = params
            }
        }
        session.notificationReserveHeight = session.stockHeaderHeight
    }

    private fun restoreHorizontalClock(session: Session) {
        val clock = session.views.horizontalTime ?: return
        session.horizontalClockMode?.let { setClockMode(clock, it) }
        updateClockVisibility(clock)
    }

    private fun measureNotificationReserveHeight(session: Session): Boolean {
        val root = session.root
        val previousHeight = session.notificationReserveHeight
        val baseTimeSize = dimension(root, "shade_header_notification_clock_text_size")
        val dateSize = dimension(root, "shade_header_notification_date_text_size")
        var estimatedHeight = session.stockHeaderHeight
        if (baseTimeSize > 0 && dateSize > 0) {
            val usesSafeTop = (options.dateAboveTime && !options.hideDate) ||
                (options.timeScale > 1f && !options.hideTime)
            val safeTop = if (usesSafeTop) safeTopMarginPx(session) else 0
            val dateLineHeight = (session.views.date as? TextView)?.let { date ->
                maxOf(date.measuredHeight, (dateSize * 1.15f).roundToInt())
            } ?: (dateSize * 1.15f).roundToInt()
            val timeLineHeight = if (options.hideTime) 0 else {
                (baseTimeSize * options.timeScale * 1.12f).roundToInt()
            }
            val dateHeight = if (options.hideDate) 0 else dateLineHeight
            val gap = if (dateHeight > 0 && timeLineHeight > 0) dp(root, HEADER_ROW_GAP_DP) else 0
            estimatedHeight = safeTop + root.paddingBottom + dateHeight + timeLineHeight + gap
        }

        // OS4 moves and scales the big clock independently from the header's layout bounds. The
        // scaled glyph can therefore extend below the stock header even when a text-size estimate
        // fits inside it. Keep only the transformed overflow beyond the stock root as a
        // notification-stack padding delta; never resize the shared Control Center header.
        val currentRootHeight = maxOf(root.height, session.stockHeaderHeight).coerceAtLeast(0)
        val transformedBottom = transformedContentBottom(session)
        val visualOverflow = NotificationHeaderGeometryModel.visualOverflow(
            currentRootHeight,
            transformedBottom
        )
        session.notificationReserveHeight = NotificationHeaderGeometryModel.requiredNotificationReserveHeight(
            session.stockHeaderHeight,
            estimatedHeight,
            visualOverflow
        )
        return previousHeight != session.notificationReserveHeight
    }

    /** Keeps the date and clock apart after SystemUI applies its animated scale and translation. */
    private fun maintainDateTimeSeparation(session: Session): Boolean {
        clearDateTimeSeparation(session)
        if (options.hideDate || options.hideTime) return false

        val time = session.views.time
        val date = session.views.date
        val timeBounds = transformedBounds(session, time) ?: return false
        val dateBounds = transformedBounds(session, date) ?: return false
        val (earlier, later, target) = if (options.dateAboveTime) {
            Triple(dateBounds, timeBounds, time)
        } else {
            Triple(timeBounds, dateBounds, date)
        }
        val requiredTop = earlier.bottom + dp(session.root, HEADER_ROW_GAP_DP)
        val offset = (requiredTop - later.top).coerceAtLeast(0)
        if (offset <= 0) return false

        target.translationY += offset.toFloat()
        session.dateTimeSeparationView = target
        session.dateTimeSeparationOffsetY = offset.toFloat()
        session.dateTimeSeparationAppliedTranslationY = target.translationY
        return true
    }

    private fun clearDateTimeSeparation(session: Session) {
        val view = session.dateTimeSeparationView
        if (view != null && session.dateTimeSeparationOffsetY != 0f &&
            abs(view.translationY - session.dateTimeSeparationAppliedTranslationY) < 0.5f
        ) {
            view.translationY -= session.dateTimeSeparationOffsetY
        }
        session.dateTimeSeparationView = null
        session.dateTimeSeparationOffsetY = 0f
        session.dateTimeSeparationAppliedTranslationY = 0f
    }

    private fun transformedBounds(session: Session, view: View): Rect? {
        if (view.visibility == View.GONE || view.width <= 0 || view.height <= 0) return null
        return runCatching {
            Rect(0, 0, view.width, view.height).also { bounds ->
                session.root.offsetDescendantRectToMyCoords(view, bounds)
            }
        }.getOrNull()
    }

    private fun transformedContentBottom(session: Session): Int {
        return listOfNotNull(session.views.date, session.views.time, session.views.carrier)
            .asSequence()
            .mapNotNull { view -> transformedBounds(session, view)?.bottom }
            .maxOrNull() ?: 0
    }

    private fun applyAlignment(session: Session, view: View, alignment: Int) {
        val values = when (NotificationHeaderModel.normalizeAlignment(alignment)) {
            NotificationHeaderModel.ALIGN_CENTER -> mapOf(
                "startToStart" to PARENT_ID,
                "startToEnd" to UNSET,
                "endToStart" to UNSET,
                "endToEnd" to PARENT_ID
            )
            NotificationHeaderModel.ALIGN_END -> mapOf(
                "startToStart" to UNSET,
                "startToEnd" to UNSET,
                "endToStart" to UNSET,
                "endToEnd" to PARENT_ID
            )
            else -> mapOf(
                "startToStart" to PARENT_ID,
                "startToEnd" to UNSET,
                "endToStart" to UNSET,
                "endToEnd" to UNSET
            )
        }
        change(session, view, values)
        clearStartMargin(session, view)
    }

    private fun change(session: Session, view: View, values: Map<String, Int>) {
        val params = view.layoutParams ?: return
        val resolved = values.keys.associateWith { field(params.javaClass, it) }
        if (resolved.values.any { it == null }) return
        val snapshot = session.layouts.getOrPut(view) { LayoutSnapshot() }
        var changed = false
        values.forEach { (name, value) ->
            val target = resolved[name] ?: return@forEach
            snapshot.values.putIfAbsent(name, target.getInt(params))
            if (target.getInt(params) != value) {
                target.setInt(params, value)
                changed = true
            }
        }
        if (changed) view.layoutParams = params
    }

    private fun clearStartMargin(session: Session, view: View) {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val snapshot = session.layouts.getOrPut(view) { LayoutSnapshot() }
        if (snapshot.marginStart == null) snapshot.marginStart = params.marginStart
        if (params.marginStart != 0) {
            params.marginStart = 0
            view.layoutParams = params
        }
    }

    private fun restoreLayouts(session: Session) {
        session.layouts.keys.toList().forEach { view ->
            runCatching {
                val snapshot = session.layouts.remove(view) ?: return@runCatching
                val params = view.layoutParams ?: return@runCatching
                snapshot.values.forEach { (name, value) -> field(params.javaClass, name)?.setInt(params, value) }
                (params as? ViewGroup.MarginLayoutParams)?.let { lp ->
                    snapshot.marginStart?.let(lp::setMarginStart)
                }
                view.layoutParams = params
            }.onFailure { reportFailure("constraint restore", it) }
        }
    }

    private fun removeFontPadding(session: Session, view: View) {
        val clock = view as? TextView ?: return
        session.fontPadding.putIfAbsent(clock, clock.includeFontPadding)
        clock.includeFontPadding = false
    }

    private fun restoreFontPadding(session: Session, view: View) {
        val clock = view as? TextView ?: return
        session.fontPadding.remove(clock)?.let { clock.includeFontPadding = it }
    }

    private fun configureSingleLineClock(session: Session, clock: TextView) {
        session.clockTextSnapshot = session.clockTextSnapshot ?: ClockTextSnapshot(
            clock.isSingleLine,
            clock.maxLines,
            clock.ellipsize,
            clock.isHorizontallyScrollable,
            clock.textScaleX
        )
        clock.setSingleLine(true)
        clock.maxLines = 1
        clock.ellipsize = null
        clock.setHorizontallyScrolling(true)
        fitClockText(session, clock)
    }

    private fun restoreClockText(session: Session, clock: TextView) {
        val state = session.clockTextSnapshot ?: return
        clock.setSingleLine(state.singleLine)
        clock.maxLines = state.maxLines
        clock.ellipsize = state.ellipsize
        clock.setHorizontallyScrolling(state.horizontallyScrollable)
        clock.textScaleX = state.textScaleX
        session.clockTextSnapshot = null
    }

    private fun fitClockText(session: Session, clock: TextView) {
        val state = session.clockTextSnapshot ?: return
        val rootWidth = session.root.width
        val available = (rootWidth - session.root.paddingStart - session.root.paddingEnd).toFloat() * 0.985f
        val currentScale = clock.textScaleX
        val text = clock.text?.toString().orEmpty()
        if (available <= 0f || currentScale <= 0f || text.isEmpty()) return
        val nativeWidth = clock.paint.measureText(text) * state.textScaleX / currentScale
        val renderedWidth = nativeWidth * abs(clock.scaleX)
        if (renderedWidth <= 0f) return
        val target = state.textScaleX * (available / renderedWidth).coerceAtMost(1f)
        if (clock.textScaleX != target) clock.textScaleX = target
    }

    private fun safeTopMarginPx(session: Session): Int {
        val root = session.root
        val insets = root.rootWindowInsets ?: session.latestInsets
        val location = IntArray(2)
        root.getLocationInWindow(location)
        val rootTopInWindow = location[1] - root.translationY.roundToInt()
        if (insets == null) {
            return NotificationHeaderGeometryModel.safeTopMargin(
                statusBarFallbackPx(root),
                rootTopInWindow
            )
        }
        val insetTypes = WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()
        val safeTop = runCatching {
            maxOf(
                insets.getInsets(insetTypes).top,
                insets.getInsetsIgnoringVisibility(insetTypes).top,
                insets.displayCutout?.safeInsetTop ?: 0
            )
        }.getOrDefault(statusBarFallbackPx(root))
        return NotificationHeaderGeometryModel.safeTopMargin(safeTop, rootTopInWindow)
    }

    private fun statusBarFallbackPx(root: View): Int {
        val id = root.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id == 0) 0 else root.resources.getDimensionPixelSize(id)
    }

    private fun applyRootMotion(session: Session) {
        val target = if (session.mode == Mode.PORTRAIT) {
            NotificationHeaderMotionModel.rootTranslationY(
                session.stretchTranslationY,
                session.notificationScrollY
            )
        } else {
            session.originalTranslationY
        }
        if (session.root.translationY != target) session.root.translationY = target
    }

    private fun dimension(view: View, name: String): Int {
        val id = view.resources.getIdentifier(name, "dimen", "com.android.systemui")
        return if (id == 0) 0 else view.resources.getDimensionPixelSize(id)
    }

    private fun dp(view: View, value: Float): Int =
        (value * view.resources.displayMetrics.density).roundToInt()

    private fun setClockMode(view: View, mode: Int) {
        val current = (read(view, "mClockMode") as? Number)?.toInt()
        if (current == mode) return
        runCatching {
            method(view.javaClass, "setClockMode", 1)?.invoke(view, mode)
        }
    }

    private fun updateClockVisibility(view: View) {
        runCatching {
            method(view.javaClass, "updateClockVisibility", 0)?.invoke(view)
        }
    }

    private fun method(type: Class<*>, name: String, parameterCount: Int): Method? =
        methods.getOrPut(Triple(type, name, parameterCount)) {
            type.methods.firstOrNull { it.name == name && it.parameterCount == parameterCount }
        }

    private fun field(type: Class<*>, name: String): Field? = fields.getOrPut(type to name) {
        var current: Class<*>? = type
        var result: Field? = null
        while (current != null && result == null) {
            result = runCatching { current.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
            current = current.superclass
        }
        result
    }

    private fun read(owner: Any, name: String): Any? = field(owner.javaClass, name)?.get(owner)

    private fun id(view: View, name: String): Int =
        view.resources.getIdentifier(name, "id", "com.android.systemui")

    companion object {
        private const val PARENT_ID = 0
        private const val UNSET = -1
        private const val HEADER_ROW_GAP_DP = 4f
        private const val TIME_ONLY_CLOCK_MODE = 0
        private const val DATE_ONLY_CLOCK_MODE = 1
    }
}
