package com.takekazex.hypertweak.hook.rules.systemui.media

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.util.logging.DebugLog
import kotlin.math.abs
import kotlin.math.roundToInt

internal const val HYPER_OS_MEDIA_TAG = "HyperOsMediaCarousel"

internal fun shouldUseNativeMediaCard(apps: Collection<Pair<Int, String>?>, hasSuppressedSessions: Boolean): Boolean {
    if (hasSuppressedSessions || apps.isEmpty()) return false
    val firstApp = apps.first()
    return apps.size == 1 || (firstApp != null && apps.all { it == firstApp })
}

@SuppressLint("ViewConstructor")
internal class HyperOsMediaCarousel(
    player: View,
    owner: Any,
    factory: Any,
    contract: HyperOsMediaContract,
) : ShadeMediaCarouselLayer(player) {
    private val owner = owner
    private val factory = factory
    private val contract = contract
    private val pager = MediaCardPager(context)
    private val indicator = contract.chrome.createIndicator(context)
    private val hiddenApps = LinkedHashSet<Pair<Int, String>>()
    private val dismissedSessions = LinkedHashMap<Any, Boolean>()
    private var menu: HyperOsMediaModal? = null
    private var menuSession: Any? = null
    private var restoreAction: View? = null
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val labels by lazy { context.createPackageContext("com.takekazex.hypertweak", 0) }
    private var announcedPage = 0
    private var announcedCount = 0
    private val pages = LinkedHashMap<Any, HyperOsMediaContract.Page>()
    private var failed = false
    private var showingNativeSingle = false
    private var restoredSession: Any? = null

    private fun selectedSession(): Any? = pages.entries.firstOrNull { it.value.view === pager.selectedView }?.key

    fun saveState(): Array<Any?> = arrayOf(
        selectedSession() ?: restoredSession,
        hiddenApps.map { (user, app) -> arrayOf<Any>(user, app) },
        HashMap(dismissedSessions),
    )

    fun restoreState(state: Array<*>) {
        (state.getOrNull(1) as? List<*>)?.forEach { entry ->
            val app = entry as? Array<*> ?: return@forEach
            val user = app.getOrNull(0) as? Int ?: return@forEach
            val pkg = app.getOrNull(1) as? String ?: return@forEach
            hiddenApps.add(user to pkg)
        }
        (state.getOrNull(2) as? Map<*, *>)?.forEach { (id, paused) ->
            if (id != null && paused is Boolean) dismissedSessions[id] = paused
        }
        state.firstOrNull()?.let(::selectSession)
        refreshPages()
    }

    private fun selectSession(session: Any) {
        val page = pages[session]
        if (page == null) restoredSession = session else pager.select(page.view)
    }

    init {
        visibility = View.GONE
        column.addView(pager, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        column.addView(indicator, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        pager.onPagePosition = ::updateIndicator
        pager.onScroll = ::closeMenu
    }

    private fun updateIndicator(position: Int, fraction: Float, total: Int) {
        HookFailurePolicy.open(HYPER_OS_MEDIA_TAG, "update page indicator", Unit) {
            contract.chrome.setPosition(indicator, position, fraction)
            if (total < 2) return@open
            val physical = (position + fraction.roundToInt()).coerceIn(0, total - 1)
            val numbered = if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                total - physical
            } else {
                physical + 1
            }
            if (numbered != announcedPage || total != announcedCount) {
                indicator.contentDescription = labels.getString(R.string.settings_media_card_page_position, numbered, total)
                announcedPage = numbered
                announcedCount = total
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshPages()
    }

    override fun onDetachedFromWindow() {
        try {
            clearPages()
        } finally {
            super.onDetachedFromWindow()
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!isVisible) closeMenu()
    }

    fun refreshPages() {
        if (!isAttachedToWindow || failed) return
        try {
            val sessions = contract.sessions(owner)
            dismissedSessions.entries.removeAll { entry ->
                val media = sessions[entry.key] ?: return@removeAll true
                if (!contract.playing(media)) {
                    entry.setValue(true)
                    false
                } else {
                    entry.value
                }
            }
            if (sessions.isEmpty()) {
                showingNativeSingle = false
                clearPages()
                return
            }
            val candidates = sessions.filter { (id, data) ->
                id !in dismissedSessions && contract.app(data) !in hiddenApps
            }
            if (shouldUseNativeMediaCard(sessions.values.map(contract::app), candidates.size != sessions.size)) {
                if (!showingNativeSingle) {
                    clearPages()
                    showingNativeSingle = true
                    DebugLog.i(HYPER_OS_MEDIA_TAG, "Using native single media card")
                }
                return
            }
            showingNativeSingle = false
            val previousKeys = pages.keys.toSet()
            if (previousKeys != candidates.keys) closeMenu()
            val removedKeys = previousKeys.filterNot { it in candidates }
            val removed = removedKeys.mapNotNull(pages::remove)
            removed.forEach(contract::close)
            for ((id, media) in candidates) {
                val page = pages.getOrPut(id) {
                    contract.createPage(owner, factory, player, pager.row).also { created ->
                        created.player.setOnLongClickListener { showActions(id, created) }
                        created.player.setOnTouchListener { _, event ->
                            when (event.actionMasked) {
                                MotionEvent.ACTION_DOWN -> press(created, 0.9f, 140L)
                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                    if (menuSession != id) press(created, 1f, 180L)
                                }
                            }
                            false
                        }
                    }
                }
                contract.bind(page, media)
                if (menuSession == id) {
                    HookFailurePolicy.open(HYPER_OS_MEDIA_TAG, "bind focused media card", Unit) {
                        menu?.refresh(media, contract::bind)
                    }
                }
            }
            val pagesChanged = pages.keys != previousKeys
            if (pagesChanged) {
                contract.chrome.setPageCount(indicator, pages.size)
            }
            pager.setPages(pages.values.toList())
            if (pagesChanged) {
                restoredSession?.let { saved ->
                    pages[saved]?.let { pager.select(it.view) }
                    restoredSession = null
                }
                DebugLog.i(HYPER_OS_MEDIA_TAG, "HyperOS media pages=${pages.size}")
            }
            updateRestoreAction(pages.isEmpty() && sessions.values.any { contract.app(it) in hiddenApps })
            visibility = View.VISIBLE
            showCarousel()
        } catch (error: Throwable) {
            failed = true
            clearPages()
            DebugLog.w(HYPER_OS_MEDIA_TAG, "HyperOS pager fell back to the system card", error)
        }
    }

    private fun showActions(id: Any, page: HyperOsMediaContract.Page): Boolean =
        HookFailurePolicy.open(HYPER_OS_MEDIA_TAG, "show media card menu", false) {
            val media = page.data ?: return@open false
            if (pages[id] !== page) return@open false
            closeMenu()
            page.pressAnimator?.cancel()
            menu = contract.chrome.showMenu(
                page.view, contract.notificationMaterial(owner), contract.newModalManager(factory),
                createFocusCard = { parent ->
                    contract.createPage(owner, factory, player, parent).also { focus ->
                        try {
                            contract.bind(focus, media)
                        } catch (error: Throwable) {
                            contract.close(focus)
                            throw error
                        }
                    }
                },
                releaseFocusCard = contract::close,
                closePage = {
                    applyAction(id, page) { media ->
                        dismissedSessions[id] = !contract.playing(media)
                        HookFailurePolicy.open(HYPER_OS_MEDIA_TAG, "pause closed media session", Unit) {
                            contract.pause(page)
                        }
                    }
                },
                hideApp = if (contract.app(media) != null) {
                    { applyAction(id, page) { media -> contract.app(media)?.let(hiddenApps::add) } }
                } else null,
                restoreApps = if (hiddenApps.isNotEmpty()) ::restoreApps else null,
                onDismiss = { dismissed ->
                    if (menu === dismissed) {
                        menu = null
                        menuSession = null
                    }
                },
            )?.also { menuSession = id }
            menu != null
        }

    private fun press(page: HyperOsMediaContract.Page, scale: Float, duration: Long) {
        page.pressAnimator?.cancel()
        page.pressAnimator = ObjectAnimator.ofPropertyValuesHolder(
            page.view,
            PropertyValuesHolder.ofFloat(View.SCALE_X, scale),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, scale),
        ).apply {
            this.duration = duration
            start()
        }
    }

    private fun applyAction(id: Any, page: HyperOsMediaContract.Page, action: (Any) -> Unit) {
        HookFailurePolicy.open(HYPER_OS_MEDIA_TAG, "apply media card menu action", Unit) {
            val media = page.data ?: return@open
            if (pages[id] !== page) return@open
            closeMenu()
            action(media)
            refreshPages()
        }
    }

    private fun closeMenu() {
        val current = menu ?: return
        menu = null
        menuSession = null
        HookFailurePolicy.open(HYPER_OS_MEDIA_TAG, "close media card menu", Unit) { current.dismiss() }
    }

    private fun restoreApps() {
        closeMenu()
        hiddenApps.clear()
        refreshPages()
    }

    private fun updateRestoreAction(visible: Boolean) {
        if (!visible) {
            restoreAction?.let(column::removeView)
            restoreAction = null
        } else if (restoreAction == null) {
            restoreAction = contract.chrome.createRestoreAction(
                context, contract.notificationMaterial(owner), ::restoreApps,
            ).also {
                column.addView(it, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                })
            }
        }
    }

    private fun clearPages() {
        closeMenu()
        updateRestoreAction(false)
        visibility = View.GONE
        restorePlayer()
        pager.setPages(emptyList())
        pages.values.forEach(contract::close)
        pages.clear()
        HookFailurePolicy.open(HYPER_OS_MEDIA_TAG, "clear media page indicator", Unit) {
            contract.chrome.setPageCount(indicator, 0)
        }
    }
}

/** The platform scroller handles dragging; page snaps are confined to its horizontal axis. */
private class MediaCardPager(context: Context) : HorizontalScrollView(context) {
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
    }
    private val gap = (8 * resources.displayMetrics.density).roundToInt()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var pages = emptyList<HyperOsMediaContract.Page>()
    var selectedView: View? = null
        private set
    var onPagePosition: ((Int, Float, Int) -> Unit)? = null
    var onScroll: (() -> Unit)? = null
    private var snapAnimator: ValueAnimator? = null
    private var stride = 0
    private var alignOnLayout = false
    private var flingHandled = false
    private var skipIntercept = false
    private var downX = 0f
    private var downY = 0f

    init {
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        isHorizontalScrollBarEnabled = false
        isSmoothScrollingEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun setPages(value: List<HyperOsMediaContract.Page>) {
        val physicalOrder = if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            value.asReversed()
        } else {
            value
        }
        if (pages == physicalOrder) return
        cancelSnap()
        alignOnLayout = true
        pages.filterNot { it in physicalOrder }.forEach { row.removeView(it.view) }
        pages = physicalOrder
        if (pages.none { it.view === selectedView }) selectedView = value.firstOrNull()?.view
        // Retained pages keep their native attachment and material subscriptions.
        pages.forEachIndexed { index, page ->
            if (row.getChildAt(index) !== page.view) {
                if (page.view.parent === row) row.removeView(page.view)
                row.addView(page.view, index, LinearLayout.LayoutParams(1, LayoutParams.WRAP_CONTENT))
            }
        }
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cardWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        if (cardWidth > 0) {
            if (stride != cardWidth + gap) {
                cancelSnap()
                alignOnLayout = true
            }
            stride = cardWidth + gap
            pages.forEachIndexed { index, page ->
                val params = page.view.layoutParams as LinearLayout.LayoutParams
                params.width = cardWidth
                params.rightMargin = if (index == pages.lastIndex) 0 else gap
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (!alignOnLayout) return
        alignOnLayout = false
        val target = pages.indexOfFirst { it.view === selectedView }.coerceAtLeast(0)
        scrollTo(target * stride, 0)
        reportPosition()
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (l != oldl) onScroll?.invoke()
        if (!alignOnLayout) reportPosition()
    }

    private fun reportPosition() {
        if (pages.size < 2 || stride <= 0) return
        val dealt = (scrollX.toFloat() / stride).coerceIn(0f, pages.lastIndex.toFloat())
        val index = dealt.toInt()
        onPagePosition?.invoke(index, dealt - index, pages.size)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                skipIntercept = pages.any { it.seekBar?.let { seek -> hit(seek, event) } == true }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(event.x - downX)
                val dy = abs(event.y - downY)
                if (dy > slop && dy >= dx) skipIntercept = true
            }
        }
        return (!skipIntercept && super.onInterceptTouchEvent(event)).also { intercepted ->
            if (intercepted) cancelSnap()
        }
    }

    private fun hit(view: View, event: MotionEvent): Boolean {
        val bounds = android.graphics.Rect()
        if (!view.isShown || !view.getLocalVisibleRect(bounds)) return false
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return bounds.contains((event.rawX - location[0]).toInt(), (event.rawY - location[1]).toInt())
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) cancelSnap()
        flingHandled = false
        val handled = super.onTouchEvent(event)
        if ((event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) &&
            !flingHandled && stride > 0
        ) {
            snap((scrollX.toFloat() / stride).roundToInt())
        }
        return handled
    }

    override fun fling(velocityX: Int) {
        if (pages.isEmpty() || stride == 0) return
        flingHandled = true
        val progress = scrollX.toFloat() / stride
        val index = if (velocityX > 0) kotlin.math.floor(progress).toInt() + 1 else kotlin.math.ceil(progress).toInt() - 1
        snap(index)
    }

    fun select(view: View) {
        if (pages.none { it.view === view }) return
        cancelSnap()
        selectedView = view
        alignOnLayout = true
        requestLayout()
    }

    private fun snap(index: Int) {
        if (pages.isEmpty() || alignOnLayout) return
        cancelSnap()
        val target = index.coerceIn(pages.indices)
        selectedView = pages[target].view
        val destination = target * stride
        if (destination == scrollX) return
        snapAnimator = ValueAnimator.ofInt(scrollX, destination).apply {
            duration = 220L
            addUpdateListener { scrollTo(it.animatedValue as Int, 0) }
            start()
        }
    }

    private fun cancelSnap() {
        snapAnimator?.cancel()
        snapAnimator = null
    }
}
