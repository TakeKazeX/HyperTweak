package com.takekazex.hypertweak.hook.rules.systemui.island

import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.Region
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.util.DebugLog
import io.github.libxposed.api.XposedInterface
import java.util.WeakHashMap
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Extra native summaries, lent back to the host queue during each scheduling transaction. */
class ThreeIslandHooker(private val pluginApkPath: String) : DynamicHooker() {
    private class Session(val owner: Any) {
        val slot = IslandSlotLeases<Any>()
        var extras: List<Any>
            get() = slot.tasks
            set(value) { slot.stage(value) }
        val extra: Any? get() = extras.firstOrNull()
        var relayout: IslandRelayoutDriver? = null
        val textWidths = IslandTextWidthLease()
        val templateWidths = IslandTemplateWidthLease()
        var displayOrder = emptyList<String>()
        var rowAvailable = 0
        var rowGap = 0
        var portraitContent: IslandContentSize? = null
        val seats = SmallIslandSeats()
        val gesture = IslandGestureScope()
        val scheduler = SummaryIslandScheduler()
        var swipePreview: Any? = null
        var layoutIntent = false
        var layout: ThreeIslandLayout? = null
        var suspended = 0
        var failed = false
        val contentSizes = WeakHashMap<Any, IslandContentSize>()
        val fullFrames = LinkedHashMap<Any, FullIslandFrame>()
        val fullPresentation = WeakHashMap<Any, Boolean>()
        val fullOrigins = WeakHashMap<Any, Int>()
        val departingPositions = WeakHashMap<Any, Int>()
        var loggedKey: String? = null
        var moreCapacity = false
        var admissionLog: String? = null
    }
    private class Transaction(val session: Session,
        val visible: List<Any>, val expandedKey: String?) {
        var oldStates = emptyMap<Any, Any?>()
        val oldLayout = session.layout
        val oldFullFrames = session.fullFrames.toMap()
        var settled = false
        var forcePublication = false
    }
    private lateinit var host: NativeIslandAccess
    private val sessions = Collections.synchronizedMap(WeakHashMap<Any, Session>())
    private val transaction = ThreadLocal<Transaction?>()
    private val calculating = ThreadLocal<Any?>()
    private val animating = ThreadLocal<Any?>()
    private val renderingState = ThreadLocal<Any?>()
    private val templatePublication = IslandTemplatePublication()
    private var geometryDiagnostics: IslandGeometryDiagnostics? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var retiring = false

    override fun onHook() {
        host = NativeIslandAccess(NativeIslandDiscovery.scan(pluginApkPath, classLoader))
        // Fully resolve first. A changed plugin must skip this feature before any mutation.
        val handle = host.method(host.coordinator, "handleEvent")
        check(handle.parameterTypes.toList() == listOf(host.event, host.content))
        check(host.method(host.handler, "stop").parameterCount == 0)
        installTemplatePublication()
        check(host.method(host.touch, "onInterceptTouchEvent").parameterTypes.first() == MotionEvent::class.java)

        listOf(handle, host.method(host.handler, "stop"), host.method(host.handler, "addState"),
            host.method(host.touch, "onInterceptTouchEvent"), host.method(host.content, "calculateBigIslandWidth"),
            host.method(host.coords, "getSmallIslandX"), host.method(host.window, "dispatchTouchEvent"),
            host.method(host.content, "onIslandClick"), host.method(host.touch, "performClick"),
            host.method(host.touch, "onTouchEvent"), host.method(host.coordinator, "resetSwipe"),
            host.method(host.coordinator, "dispatchSwipe"), host.method(host.anim, "onStateChange"),
            host.method(host.resolver, "resolve"), host.method(host.content, "getCurrentBigIslandX"),
            host.method(host.content, "getCurrentBigIslandWidth")).forEach { deoptimize(it) }
        registerRuntimeHook(handle, "three_islands_transaction", XposedInterface.Hooker { chain ->
            val owner = chain.thisObject ?: return@Hooker chain.proceed()
            if (Looper.myLooper() != Looper.getMainLooper() || transaction.get() != null) return@Hooker chain.proceed()
            val session = sessions.getOrPut(owner) { Session(owner) }
            if (session.extra == null && !enabled(session)) return@Hooker chain.proceed()
            val previousExpanded = host.current(owner, "getExpandedStateHandler")?.let(::key)
            val wasUserExpanded = host.call(owner, "getUserExpanded") == true
            val tx = Transaction(session,
                summaries(session), host.current(owner, "getExpandedStateHandler")?.let(::key)).also { tx ->
                    tx.oldStates = session.extras.associateWith { host.call(it, "getState") }
                }
            transaction.set(tx)
            try {
                extension(session, "snapshot small origins") {
                    (session.extras + listOfNotNull(host.current(owner, "getSmallIslandStateHandler"))).forEach { view ->
                        smallX(session, view)?.let { session.departingPositions[view] = it }
                    }
                }
                extension(session, "return third slot to host") { returnToQueue(session) }
                val result = chain.proceed() // Original failures propagate; never retry a host event.
                extension(session, "settle event") {
                    if (!tx.settled) {
                        settle(tx)
                        host.call(host.call(owner, "getHiddenStateHandler")!!, "stop")
                    }
                    val currentExpanded = host.current(owner, "getExpandedStateHandler")?.let(::key)
                    if (IslandExpansionOwnership.clearInheritedUserExpansion(wasUserExpanded, previousExpanded, currentExpanded)) {
                        host.call(owner, "setUserExpanded", false)
                        DebugLog.i(TAG, "automatic expansion replaced manual owner; native collapse timer released")
                    }
                    host.call(owner, "updateTouchRegion")
                }
                result
            } catch (failure: Throwable) {
                session.failed = true
                tx.settled = true
                runCatching { restore(session) }.onFailure { DebugLog.e(TAG, "restore after host failure", it) }
                throw failure
            } finally {
                transaction.remove()
            }
        })
        host.method(host.handler, "stop").hook {
            before {
                val tx = transaction.get() ?: return@before
                extension(tx.session, "select third slot") { if (!tx.settled) settle(tx) }
            }
        }
        host.method(host.handler, "addState").hook {
            after { param ->
                val tx = transaction.get() ?: return@after
                val view = param.args.firstOrNull() ?: return@after
                val old = tx.oldStates[view] ?: return@after
                extension(tx.session, "preserve transition origin") { host.call(view, "setLastState", old) }
            }
        }
        // A configuration rebuild snapshots only native slots/queue. Return our lease before
        // the window clears or reinflates any views, and suppress admission until it finishes.
        registerRuntimeHook(host.method(host.window, "onConfigChanged"), "three_islands_configuration",
            XposedInterface.Hooker { chain ->
                val owner = host.windowCoordinator.invoke(chain.thisObject)
                val session = owner?.let { sessions[it] } ?: return@Hooker chain.proceed()
                session.suspended++
                try {
                    extension(session, "prepare configuration rebuild") {
                        session.relayout?.request()
                        restore(session)
                    }
                    chain.proceed()
                } finally { session.suspended--; refreshSettings() }
            })
        // Config changes rebuild templates asynchronously after onConfigChanged has returned.
        listOf(host.method(host.content, "updateBigIslandView"), host.method(host.fake, "updateBigIslandView")).forEach(::deoptimize)
        host.method(host.content, "initBigIslandView").also(::deoptimize).hook {
            after { param -> templateBound(param.thisObject) }
        }
        host.method(host.fake, "setFakeSecondLayout").also(::deoptimize).hook {
            after { param ->
                runCatching { host.call(param.thisObject, "getRealView") }
                    .onSuccess { it?.let(::templateBound) }
                    .onFailure { DebugLog.e(TAG, "resolve rebound fake template", it) }
            }
        }
        // Keep native template measurement; choose compact three-island or occupied full-row geometry.
        registerRuntimeHook(host.method(host.content, "calculateBigIslandWidth"), "three_islands_measure",
            XposedInterface.Hooker { chain ->
                val previous = calculating.get()
                calculating.set(chain.thisObject)
                try {
                    chain.thisObject?.let { view -> sessionFor(view)?.let { s ->
                        extension(s, "prepare full native text measurement") {
                            releaseFullPosition(s, view)
                            val root = host.call(view, "getBigIslandView") as? View
                            if (root != null && (landscapeFullEnabled(s) || portraitCompactEnabled(s) ||
                                    (landscapeMoreEnabled(s) && view === host.current(s.owner, "getBigIslandStateHandler"))))
                                s.textWidths.acquire(root)
                        }
                    } }
                    chain.proceed()
                } finally {
                    chain.thisObject?.let { view -> sessionFor(view)?.let { s ->
                        extension(s, "release temporary text measurement") {
                            if (!fullTemplate(s, view)) (host.call(view, "getBigIslandView") as? View)?.let(s.textWidths::release)
                        }
                    } }
                    if (previous == null) calculating.remove() else calculating.set(previous)
                }
            })
        host.method(host.helper, "calculateBigIslandWidth").hook {
            after { param ->
                val view = calculating.get() ?: return@after
                val session = sessionFor(view) ?: return@after
                extension(session, "measure three islands") {
                    val input = param.args[0] ?: return@extension
                    val result = param.result ?: return@extension
                    session.contentSizes.remove(view)
                    fun natural(side: String): Int {
                        val measured = host.number(input, "getBigIslandArea${side}Width")
                        if (!landscapeFullEnabled(session) && !portraitCompactEnabled(session) && !(landscapeMoreEnabled(session) &&
                                view === host.current(session.owner, "getBigIslandStateHandler"))) return measured
                        val area = host.call(view, "getBigIslandArea$side") as? View ?: return measured
                        return maxOf(measured, session.textWidths.minimumWidth(area))
                    }
                    IslandContentSize.measured(natural("Left"), natural("Right"), host.number(view, "getIslandViewHeight"))
                        ?.let { session.contentSizes[view] = it }
                    if (!session.layoutIntent || !enabled(session)) return@extension
                    val big = host.current(session.owner, "getBigIslandStateHandler")
                    if (big === view) {
                        val window = host.call(session.owner, "getWindowView") as? View ?: return@extension
                        val insets = window.rootWindowInsets?.displayCutout
                        val screenWidth = host.number(input, "getScreenWidth")
                        val screenHeight = host.number(input, "getScreenHeight")
                        val clock = host.ceilDimension(input, "getClockWidth")
                        val battery = host.ceilDimension(input, "getBatteryWidth")
                        val gap = host.number(input, "getSpace")
                        val right = host.current(session.owner, "getSmallIslandStateHandler")
                        val native = listOfNotNull(big, right)
                        fun size(item: Any): IslandContentSize? = if (item === big || landscapeFullEnabled(session))
                            session.contentSizes[item] else host.number(item, "getSmallIslandViewWidth").takeIf { it > 0 }?.let { IslandContentSize(it, 0) }
                        val more = landscapeMoreEnabled(session)
                        val safeLeft = insets?.safeInsetLeft ?: 0
                        val safeRight = insets?.safeInsetRight ?: 0
                        session.rowAvailable = screenWidth - 2 * maxOf(clock, battery, safeLeft, safeRight, 0)
                        session.rowGap = gap
                        val selected = if (rowModeEnabled(session)) FullIslandRow.admit(native, session.extras) { items ->
                            val sizes = items.mapNotNull(::size)
                            sizes.size == items.size && FullIslandRow.calculate(screenWidth, screenHeight, clock, battery,
                                sizes, gap, safeLeft, safeRight) != null
                        } else native
                        if (more) session.extras = selected.filter { it !in native }
                        val all = (native + session.extras).distinct()
                        val initial = all.sortedBy { item -> if (item === big) 1 else if (session.seats.left(key(item)) == true) 0 else 2 }
                        val order = (session.displayOrder + initial.mapNotNull(::key)).distinct()
                        val ordered = all.sortedBy { order.indexOf(key(it)).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                        val contents = ordered.mapNotNull(::size)
                        val row = if (rowModeEnabled(session) && contents.size == ordered.size)
                            FullIslandRow.calculate(screenWidth, screenHeight, clock, battery, contents, gap, safeLeft, safeRight) else null
                        session.fullFrames.clear()
                        session.portraitContent = null
                        val portrait = if (portraitCompactEnabled(session)) session.contentSizes[big]?.let { content ->
                            PortraitIslandLayout.calculate(screenWidth, screenHeight, clock, battery, content,
                                host.number(input, "getCutoutWidth"),
                                (listOfNotNull(right) + session.extras).maxOfOrNull { host.number(it, "getSmallIslandViewWidth") } ?: 0,
                                gap, (listOfNotNull(right) + session.extras).any { session.seats.left(key(it)) == true },
                                (listOfNotNull(right) + session.extras).any { session.seats.left(key(it)) != true }, safeLeft, safeRight)?.also {
                                    session.portraitContent = content
                                }
                        } else null
                        if (portrait != null) {
                            session.layout = portrait
                        } else if (row != null) {
                            session.fullFrames.putAll(ordered.zip(row).map { (item, frame) ->
                                item to frame.copy(full = item === big || landscapeFullEnabled(session))
                            })
                            session.displayOrder = ordered.mapNotNull(::key)
                            val centre = session.fullFrames.getValue(big)
                            val sides = ordered.filter { it !== big }.map { session.fullFrames.getValue(it) }
                            session.layout = ThreeIslandLayout(centre.x, centre.width,
                                sides.firstOrNull { it.x < centre.x }?.x ?: centre.x,
                                sides.firstOrNull { it.x > centre.x }?.x ?: centre.x, true, screenWidth)
                        } else if (portraitCompactEnabled(session)) {
                            session.extras = emptyList()
                            session.layout = null
                        } else if (session.extra != null && right != null) {
                            session.extras = session.extras.take(1)
                            session.layout = ThreeIslandLayout.calculate(screenWidth, screenHeight, clock, battery,
                                minOf(host.number(result, "getBigIslandViewWidth"), host.number(input, "getMaxWidth")),
                                (host.minimumBig.invoke(param.thisObject) as Number).toInt(),
                                maxOf(host.number(right, "getSmallIslandViewWidth"), host.number(session.extra!!, "getSmallIslandViewWidth")),
                                gap, safeLeft = insets?.safeInsetLeft ?: 0, safeRight = insets?.safeInsetRight ?: 0)
                        } else session.layout = null
                    }
                    session.fullFrames.filterValues { it.full }.keys.forEach { acquireTemplateWidth(session, it, 0) }
                    if (session.portraitContent != null) acquireTemplateWidth(session, big!!, host.number(input, "getCutoutWidth"))
                    val layout = session.layout ?: return@extension
                    if (view !in summaries(session)) return@extension
                    val frame = session.fullFrames[view]
                    if (session.portraitContent != null && view !== host.current(session.owner, "getBigIslandStateHandler")) return@extension
                    if (frame != null && !frame.full) return@extension
                    val cutout = if (frame != null) 0 else host.number(input, "getCutoutWidth")
                    val width = frame?.width ?: layout.bigWidth
                    val x = frame?.x ?: layout.bigX
                    if (width < cutout) { session.layout = null; session.fullFrames.clear(); return@extension }
                    val portraitContent = session.portraitContent.takeIf { view === host.current(session.owner, "getBigIslandStateHandler") }
                    val left = frame?.content?.left ?: portraitContent?.left ?: ((width - cutout) / 2)
                    val right = frame?.content?.right ?: portraitContent?.right ?: (width - cutout - left)
                    param.result = host.resultConstructor.newInstance(width, left, right, x,
                        if (frame != null) 0 else host.number(result, "getBigIslandMarginWidth"), width, left, right, x)
                }
            }
        }
        host.method(host.coords, "getSmallIslandX").hook {
            after { param ->
                val view = host.coordsView.get(param.thisObject) ?: return@after
                if (!host.content.isInstance(view)) return@after
                val session = sessionFor(view) ?: return@after
                extension(session, "animation coordinates") {
                    val x = smallX(session, view) ?: return@extension
                    // Native calculator returns a translation relative to the screen centre.
                    val screenWidth = session.layout?.screenWidth ?: return@extension
                    param.result = x + host.number(view, "getSmallIslandViewWidth") / 2f - screenWidth / 2f
                }
            }
        }
        host.method(host.anim, "getSmallIslandX").hook {
            after { param ->
                val session = transaction.get()?.session ?: sessionSnapshot().firstOrNull {
                    host.call(it.owner, "getAnimationController") === param.thisObject
                } ?: return@after
                extension(session, "native small target") {
                    val small = host.current(session.owner, "getSmallIslandStateHandler") ?: return@extension
                    smallX(session, small)?.let { param.result = it }
                }
            }
        }
        host.method(host.content, "getSmallIslandRect").hook {
            after { param ->
                val view = param.thisObject
                val session = sessionFor(view) ?: return@after
                extension(session, "small hit region") {
                    val x = smallX(session, view) ?: return@extension
                    val rect = param.result as? Rect ?: return@extension
                    rect.set(x, rect.top, x + sideWidth(session, view), rect.bottom)
                }
            }
        }
        listOf("getSmallBigIslandRegion", "computeIslandRegion").forEach { name ->
            host.method(host.coordinator, name).hook {
                after { param ->
                    val session = sessions[param.thisObject] ?: return@after
                    extension(session, "window region") {
                        val region = param.result as? Region ?: return@extension
                        if (name == "getSmallBigIslandRegion" && host.current(session.owner, "getBigIslandStateHandler") == null &&
                            session.scheduler.hasHidden && summarySwipeEnabled(session)) {
                            // Keep a summary reveal target even while a separate card is expanded.
                            (host.call(session.owner, "getDefaultIslandTouchRegion") as? Region)?.let {
                                region.op(it, Region.Op.UNION)
                            }
                        }
                        (listOfNotNull(host.current(session.owner, "getSmallIslandStateHandler")) + session.extras).forEach { small ->
                            val x = smallX(session, small) ?: return@forEach
                            val top = host.number(small, "getIslandViewMarginTop")
                            region.op(Rect(x, top, x + sideWidth(session, small),
                                top + host.number(small, "getIslandViewHeight")), Region.Op.UNION)
                        }
                    }
                }
            }
        }
        // Content lookup/lifetime queries must still find the task detached from the hidden queue.
        listOf("hasIsland", "getBigIsLandAndSmallIsLandList").forEach { name ->
            host.method(host.coordinator, name).hook {
                after { param ->
                    val session = sessions[param.thisObject] ?: return@after
                    extension(session, "include third task") {
                        val matching = session.extras.filter { extra ->
                            if (name != "hasIsland") return@filter true
                            val data = host.call(extra, "getCurrentIslandData") ?: return@filter false
                            val extras = host.call(data, "getExtras") as? Bundle ?: return@filter false
                            extras.getString("miui.pkg.name") == param.args.firstOrNull()
                        }
                        val list = param.result as? List<*> ?: return@extension
                        if (matching.any { it !in list }) param.result = (list + matching).distinct()
                    }
                }
            }
        }
        host.method(host.coordinator, "isBigOrSmallIsland").hook {
            after { param ->
                val session = sessions[param.thisObject] ?: return@after
                if (param.args.firstOrNull() in session.extras) param.result = true
            }
        }
        host.method(host.window, "dispatchTouchEvent").hook {
            before { param ->
                val event = param.args.firstOrNull() as? MotionEvent ?: return@before
                val owner = host.windowCoordinator.invoke(param.thisObject) ?: return@before
                val session = sessions[owner] ?: return@before
                extension(session, "route third island before child dispatch") {
                    if (!enabled(session)) return@extension
                    if (event.actionMasked == MotionEvent.ACTION_CANCEL) session.gesture.cancel()
                    if (event.actionMasked != MotionEvent.ACTION_DOWN) return@extension
                    session.swipePreview = null
                    val expanded = host.current(owner, "getExpandedStateHandler")
                    val expandedRect = expanded?.let { host.call(it, "getExpandedIslandRect") as? Rect }
                    val inExpanded = expandedRect?.contains(event.x.toInt(), event.y.toInt()) == true
                    if (inExpanded) {
                        session.gesture.begin(expanded.let(::key), expanded = true)
                        return@extension
                    }
                    session.gesture.begin(null)
                    val smallHit = (session.extras + listOfNotNull(host.current(owner, "getSmallIslandStateHandler")))
                        .firstOrNull { small ->
                            val x = smallX(session, small) ?: return@firstOrNull false
                            val rect = host.call(small, "getSmallIslandRect", x) as? Rect
                            rect?.contains(event.x.toInt(), event.y.toInt()) == true
                        }
                    if (smallHit != null) {
                        session.gesture.begin(key(smallHit))
                        claimSmall(session, smallHit)
                    } else {
                        val big = host.current(owner, "getBigIslandStateHandler")
                        val rect = big?.let { host.call(it, "getBigIslandRect", null) as? Rect }
                        if (rect?.contains(event.x.toInt(), event.y.toInt()) == true) session.gesture.begin(big.let(::key))
                    }
                }
            }
        }
        // Accessibility and native media click callbacks can arrive without the window DOWN path.
        // Normalize ownership before onIslandClick checks state and dispatches its native event.
        host.method(host.content, "onIslandClick").hook {
            before { param ->
                val session = sessionFor(param.thisObject) ?: return@before
                extension(session, "claim native click target") { claimSmall(session, param.thisObject) }
            }
        }
        host.method(host.touch, "performClick").hook {
            before { param ->
                val window = host.touchView.get(param.thisObject) ?: return@before
                val owner = host.windowCoordinator.invoke(window) ?: return@before
                val session = sessions[owner] ?: return@before
                extension(session, "deliver click to captured task") {
                    val clickKey = session.gesture.clickKey ?: return@extension
                    if (!enabled(session) || session.layout == null) return@extension
                    val target = (listOfNotNull(host.current(owner, "getExpandedStateHandler"),
                        host.current(owner, "getBigIslandStateHandler"),
                        host.current(owner, "getSmallIslandStateHandler")) + session.extras)
                        .firstOrNull { key(it) == clickKey }
                    // If an update removed the task after DOWN, consume the stale click rather
                    // than opening the new occupant of its former native slot.
                    param.result = null
                    if (target != null) {
                        claimSmall(session, target)
                        host.call(target, "onIslandClick")
                    }
                }
            }
        }
        host.method(host.window, "collapse").hook {
            before { param ->
                val reason = param.args.firstOrNull() as? String ?: return@before
                if (!reason.startsWith("swipe")) return@before
                val owner = host.windowCoordinator.invoke(param.thisObject) ?: return@before
                val session = sessions[owner] ?: return@before
                extension(session, "validate expanded gesture release") {
                    if (!enabled(session) || session.layout == null) return@extension
                    val current = host.current(owner, "getExpandedStateHandler")?.let(::key)
                    if (!session.gesture.mayCollapse(current)) param.result = null
                }
            }
        }
        host.method(host.coordinator, "dispatchSwipe").hook {
            before { param ->
                val session = sessions[param.thisObject] ?: return@before
                if (enabled(session) && session.layout != null && session.gesture.expandedKey != null) param.args[2] = true
            }
        }
        // Keep the host event envelope (rebuild blocking, bookkeeping and publication), but
        // replace its two-slot carousel at the exact progress / commit / rollback boundaries.
        host.method(host.swipe, "handleAppEvent").also(::deoptimize).hook {
            before { param ->
                val tx = transaction.get() ?: return@before
                val event = param.args.firstOrNull()
                val reveal = when {
                    host.swipeRight.isInstance(event) -> true
                    host.swipeLeft.isInstance(event) -> false
                    else -> return@before
                }
                val s = tx.session
                extension(s, "commit one summary swipe") {
                    if (!summarySwipeEnabled(s)) return@extension
                    param.result = null
                    if (!s.gesture.cancelled && s.gesture.expandedKey == null) {
                        val plan = swipePlan(s, tx.visible, reveal)
                        if (plan != null) {
                            commitSwipe(s, tx.visible, plan)
                            s.swipePreview = null // State publication now owns the final animation.
                        }
                    }
                    s.swipePreview?.let { host.call(it, "resetSwipe", it) }
                    s.swipePreview = null
                    host.call(host.call(s.owner, "getHiddenStateHandler")!!, "stop")
                }
            }
        }
        host.method(host.swipe, "handleSwipe").also(::deoptimize).hook {
            before { param ->
                val s = sessionForSwipe(param.thisObject) ?: return@before
                val owner = s.owner
                val x = (param.args[0] as? Number)?.toFloat() ?: return@before
                val y = (param.args[1] as? Number)?.toFloat() ?: return@before
                if (kotlin.math.abs(x) <= kotlin.math.abs(y)) return@before
                extension(s, "track one summary swipe") {
                    if (!summarySwipeEnabled(s) || s.gesture.expandedKey != null) return@extension
                    param.result = null
                    val visible = summaries(s)
                    val window = host.call(owner, "getWindowView") ?: return@extension
                    val reveal = host.call(window, "isSwipeTowardsSmallIsland", x) == true
                    val plan = swipePlan(s, visible, reveal)
                    val target = plan?.let { change -> (visible + host.queue(owner)).firstOrNull { key(it) == change.task } }
                    if (s.swipePreview !== target) s.swipePreview?.let { host.call(it, "resetSwipe", it) }
                    s.swipePreview = target
                    if (target != null) {
                        // The native animator has Hidden->Big progress, but no Hidden->Small
                        // progress. Small admissions use the native final transition on release.
                        if (!plan.reveal || visible.isEmpty()) {
                            host.call(target, "onSwipe", x, y, target, host.call(target, "getState"),
                                host.nextState(target, if (plan.reveal) host.big else host.hidden))
                        }
                    }
                }
            }
        }
        host.method(host.swipe, "resetSwipe").also(::deoptimize).hook {
            before { param ->
                val s = sessionForSwipe(param.thisObject) ?: return@before
                extension(s, "rollback one summary swipe") {
                    if (!summarySwipeEnabled(s) || s.gesture.expandedKey != null) return@extension
                    param.result = null
                    s.swipePreview?.let { host.call(it, "resetSwipe", it) }
                    s.swipePreview = null
                }
            }
        }
        // Native resetSwipe resets every summary island, even after a card-only gesture.
        // Scope both progress and cancellation/reset to the card captured at DOWN.
        listOf("onSwipe", "resetSwipe").forEach { name ->
            val target = host.method(host.content, name)
            deoptimize(target)
            target.hook {
                before { param ->
                    val session = sessionFor(param.thisObject) ?: return@before
                    extension(session, "scope expanded $name") {
                        if (enabled(session) && session.layout != null && !session.gesture.allows(key(param.thisObject))) {
                            param.result = null // Native resetSwipe also returns null when its animator is absent.
                        }
                    }
                }
            }
        }
        installFullPresentation()
        DebugLog.hookRegistered(TAG, "DexKit native third task, adaptive full templates and touch routing")
    }

    private fun sessionFor(view: Any): Session? {
        if (!host.content.isInstance(view)) return null // Base methods also execute on native fake views.
        return try {
            val owner = host.call(view, "getDynamicIslandEventCoordinator") ?: return null
            sessions[owner]
        } catch (error: Throwable) {
            DebugLog.e(TAG, "resolve content owner", error)
            null
        }
    }

    private fun installTemplatePublication() {
        registerRuntimeHook(host.widthTween.also(::deoptimize), "three_islands_native_width_set_to",
            XposedInterface.Hooker { chain ->
                if (!templatePublication.active) chain.proceed()
                else host.widthSetTo.invoke(chain.thisObject, *chain.args.toTypedArray())
            })
    }

    /** Change only the animation resolver's state arguments, never the scheduler's real state. */
    private fun installFullPresentation() {
        // The fake template is positioned by this native width-animation path, not the real
        // IslandPropertyUpdater. Apply the same centre offset to both native template roots.
        deoptimize(host.method(host.content, "updateBigIslandLayoutWithAnim"))
        registerRuntimeHook(host.method(host.content, "doBigIslandAnim").also(::deoptimize),
            "three_islands_template_publication", XposedInterface.Hooker { chain ->
                val view = chain.args[8] ?: return@Hooker chain.proceed()
                val s = sessionFor(view) ?: return@Hooker chain.proceed()
                if (!alignedTemplate(s, view)) return@Hooker chain.proceed()
                val layout = s.layout ?: return@Hooker chain.proceed()
                val frame = s.fullFrames[view]
                val x = frame?.x ?: layout.bigX
                val width = frame?.width ?: layout.bigWidth
                // This native method owns both real/fake template widths, factory callbacks,
                // and root translation. Its targets must agree before clipping is published.
                val args = chain.args.toTypedArray()
                args[3] = IslandPresentation.contentTranslation(layout.screenWidth, x.toFloat(),
                    x.toFloat() + width, (chain.args[3] as Number).toFloat(), 1f)
                s.fullOrigins[view] = layout.screenWidth
                templatePublication.run { chain.proceed(args) }
            })
        host.method(host.content, "getFixedCutoutWidth").also(::deoptimize).hook {
            after { param ->
                val s = sessionFor(param.thisObject) ?: return@after
                extension(s, "remove landscape camera placeholder") {
                    if (fullTemplate(s, param.thisObject)) param.result = 0
                }
            }
        }
        // Root translation belongs to doBigIslandAnim. IslandPropertyUpdater continues to
        // own clipping, alpha, scale and area transitions; never add a second translation writer.
        listOf("onPressSmallIsland", "resetPressSmallIsland").forEach { name ->
            host.method(host.gestureAnimator, name).also(::deoptimize).hook {
                before { param ->
                    val view = runCatching { host.gestureView.get(param.thisObject) }
                        .onFailure { DebugLog.e(TAG, "resolve gesture animator view", it) }.getOrNull() ?: return@before
                    val s = sessionFor(view) ?: return@before
                    extension(s, "press full native template") {
                        if (!fullSide(s, view)) return@extension
                        val target = if (name == "onPressSmallIsland") "onPressBigIsland" else "resetPressBigIsland"
                        host.method(host.gestureAnimator, target).invoke(param.thisObject, view)
                        param.result = null
                    }
                }
            }
        }
        host.method(host.content, "onSwipe").hook {
            before { param ->
                val s = sessionFor(param.thisObject) ?: return@before
                extension(s, "swipe full native template") {
                    if (!fullSide(s, param.thisObject)) return@extension
                    for (index in 3..4) if (host.small.isInstance(param.args[index]))
                        param.args[index] = host.bigConstructor.newInstance()
                }
            }
        }
        registerRuntimeHook(host.method(host.content, "resetSwipe"), "three_islands_full_swipe_reset", XposedInterface.Hooker { chain ->
            val previous = renderingState.get()
            renderingState.set(chain.thisObject)
            try { chain.proceed() } finally {
                if (previous == null) renderingState.remove() else renderingState.set(previous)
            }
        })
        host.method(host.content, "getState").also(::deoptimize).hook {
            after { param ->
                if (renderingState.get() !== param.thisObject) return@after
                val s = sessionFor(param.thisObject) ?: return@after
                extension(s, "reset full native template") {
                    if (fullSide(s, param.thisObject) && host.small.isInstance(param.result))
                        param.result = host.bigConstructor.newInstance()
                }
            }
        }
        registerRuntimeHook(host.method(host.anim, "onStateChange"), "three_islands_render_context", XposedInterface.Hooker { chain ->
            val view = chain.args.firstOrNull() ?: return@Hooker chain.proceed()
            val previous = animating.get()
            animating.set(view)
            try {
                sessionFor(view)?.let { s -> extension(s, "return expanded template to native coordinates") {
                    releaseFullPosition(s, view)
                } }
                val result = chain.proceed()
                sessionFor(view)?.let { s -> extension(s, "remember native presentation") {
                    s.fullPresentation[view] = fullSide(s, view)
                } }
                result
            } finally {
                if (previous == null) animating.remove() else animating.set(previous)
            }
        })
        host.method(host.resolver, "resolve").hook {
            before { param ->
                val view = animating.get() ?: return@before
                val s = sessionFor(view) ?: return@before
                extension(s, "resolve full native template") {
                    val previousFull = s.fullPresentation[view] == true
                    val nextFull = fullSide(s, view)
                    param.args[0] = IslandPresentation.project(param.args[0], previousFull, host.small::isInstance) { host.bigConstructor.newInstance() }
                    param.args[1] = IslandPresentation.project(param.args[1], nextFull, host.small::isInstance) { host.bigConstructor.newInstance() }
                }
            }
        }
        listOf("getCurrentBigIslandX", "getCurrentBigIslandWidth").forEach { name ->
            host.method(host.content, name).hook {
                after { param ->
                    val s = sessionFor(param.thisObject) ?: return@after
                    extension(s, "full template geometry") {
                        if (!fullTemplate(s, param.thisObject)) return@extension
                        val frame = s.fullFrames.getValue(param.thisObject)
                        param.result = if (name == "getCurrentBigIslandX") frame.x else frame.width
                    }
                }
            }
        }
    }

    private fun fullTemplate(s: Session, view: Any): Boolean = rowModeEnabled(s) && s.fullFrames[view]?.full == true &&
        IslandPresentation.isSummary(view, s.fullFrames.keys, summaries(s), host.current(s.owner, "getExpandedStateHandler"))

    private fun portraitMain(s: Session, view: Any): Boolean = portraitCompactEnabled(s) && s.portraitContent != null &&
        view === host.current(s.owner, "getBigIslandStateHandler") && view !== host.current(s.owner, "getExpandedStateHandler")

    private fun alignedTemplate(s: Session, view: Any) = fullTemplate(s, view) || portraitMain(s, view)

    private inline fun templateAreas(view: Any, action: (View, View) -> Unit) {
        val left = host.call(view, "getBigIslandAreaLeft") as? View
        val right = host.call(view, "getBigIslandAreaRight") as? View
        if (left != null && right != null) action(left, right)
        host.call(view, "getFakeView")?.let { fake ->
            val fakeLeft = host.call(fake, "getFakeFirstLayout") as? View
            val fakeRight = host.call(fake, "getFakeSecondLayout") as? View
            if (fakeLeft != null && fakeRight != null) action(fakeLeft, fakeRight)
        }
    }

    private fun acquireTemplateWidth(s: Session, view: Any, cutout: Int) = templateAreas(view) { left, right ->
        s.templateWidths.acquire(view, left, right, cutout)
    }

    private fun templateBound(view: Any) {
        if (retiring) return
        if (Looper.myLooper() != main.looper) { main.post { templateBound(view) }; return }
        val s = sessionFor(view) ?: return
        extension(s, "publish rebound template") {
            s.fullFrames.remove(view)
            s.portraitContent = null
            s.contentSizes.remove(view)
            releaseFullPosition(s, view)
            DebugLog.d(TAG, "native template bound; frame generation invalidated")
            requestRelayout(s)
        }
    }

    private fun releaseFullPosition(s: Session, view: Any) {
        if (!alignedTemplate(s, view)) {
            val nativeCutout = host.number(view, "getFixedCutoutWidth")
            s.templateWidths.release(view, nativeCutout)
            templateAreas(view) { left, right -> s.templateWidths.restoreCutout(left, right, nativeCutout) }
            (host.call(view, "getBigIslandView") as? View)?.let { root ->
                if (s.fullOrigins.remove(view) != null) root.translationX = 0f
                s.textWidths.release(root)
            }
        }
    }

    private fun fullSide(s: Session, view: Any): Boolean = fullTemplate(s, view) &&
        (view in s.extras || host.current(s.owner, "getSmallIslandStateHandler") === view)

    private fun sideWidth(s: Session, view: Any): Int =
        if (fullTemplate(s, view)) s.fullFrames.getValue(view).width else host.number(view, "getSmallIslandViewWidth")
    private fun sessionForSwipe(coordinator: Any): Session? = try {
        host.call(coordinator, "getDynamicIslandEventCoordinator")?.let { sessions[it] }
    } catch (error: Throwable) {
        DebugLog.e(TAG, "resolve swipe owner", error)
        null
    }
    private fun enabled(session: Session): Boolean = !retiring && !session.failed && session.suspended == 0 &&
        (Preferences.getBoolean(Preferences.KEY_THREE_ISLANDS, false) ||
            Preferences.getBoolean(Preferences.KEY_LANDSCAPE_FULL_ISLANDS, false) ||
            Preferences.getBoolean(Preferences.KEY_LANDSCAPE_MORE_ISLANDS, false))

    private fun landscapeFullEnabled(s: Session): Boolean = phoneLayoutEligible(s) &&
        Preferences.getBoolean(Preferences.KEY_LANDSCAPE_FULL_ISLANDS, false) &&
        (host.call(s.owner, "getWindowView") as? View)?.resources?.configuration?.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun landscapeMoreEnabled(s: Session): Boolean = phoneLayoutEligible(s) &&
        Preferences.getBoolean(Preferences.KEY_LANDSCAPE_MORE_ISLANDS, false) &&
        (host.call(s.owner, "getWindowView") as? View)?.resources?.configuration?.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun portraitCompactEnabled(s: Session): Boolean = phoneLayoutEligible(s) &&
        Preferences.getBoolean(Preferences.KEY_THREE_ISLANDS, false) &&
        (host.call(s.owner, "getWindowView") as? View)?.resources?.configuration?.orientation == Configuration.ORIENTATION_PORTRAIT

    private fun rowModeEnabled(s: Session): Boolean = landscapeFullEnabled(s) || landscapeMoreEnabled(s)

    private fun smallX(session: Session, view: Any): Int? {
        val isExtra = view in session.extras
        if (!isExtra && host.current(session.owner, "getSmallIslandStateHandler") !== view) {
            return session.departingPositions[view]
        }
        val layout = session.layout ?: return null
        session.fullFrames[view]?.let { return it.x }
        val left = session.seats.left(key(view)) ?: if (isExtra) session.seats.extraOnLeft else session.seats.primaryOnLeft
        return if (left) layout.leftX else layout.rightX
    }

    private fun key(view: Any): String? = host.call(view, "getIslandKey") as? String

    private fun summaries(s: Session): List<Any> = listOfNotNull(
        host.current(s.owner, "getBigIslandStateHandler"),
        host.current(s.owner, "getSmallIslandStateHandler")).plus(s.extras).distinct()

    private fun summarySwipeEnabled(s: Session): Boolean =
        (Preferences.getBoolean(Preferences.KEY_THREE_ISLANDS, false) || landscapeMoreEnabled(s)) && phoneLayoutEligible(s)

    private fun phoneLayoutEligible(s: Session): Boolean {
        val window = host.call(s.owner, "getWindowView") as? View ?: return false
        return enabled(s) && window.resources.configuration.orientation != Configuration.ORIENTATION_UNDEFINED &&
            window.resources.configuration.smallestScreenWidthDp < 600 &&
            host.call(s.owner, "isTinyScreen") == false && host.call(s.owner, "getInConfigRebuild") == false &&
            host.current(s.owner, "getShowOnceIslandHandler") == null &&
            host.call(host.call(s.owner, "getBigIslandStateHandler")!!, "getCurrentTempShow") == null
    }

    private fun swipePlan(s: Session, visible: List<Any>, reveal: Boolean): SummaryIslandScheduler.Change? =
        s.scheduler.plan(visible.mapNotNull(::key), queuedSummaries(s).mapNotNull(::key), reveal, s.gesture.clickKey, swipeCapacity(s, visible))

    private fun swipeCapacity(s: Session, visible: List<Any>): Int {
        if (!landscapeMoreEnabled(s) || s.fullFrames.isEmpty()) return 3
        val next = queuedSummaries(s).firstOrNull { it !in visible } ?: return visible.size.coerceAtLeast(2)
        val width = if (landscapeFullEnabled(s)) s.contentSizes[next]?.width
            else host.number(next, "getSmallIslandViewWidth")
        val used = s.fullFrames.values.sumOf { it.width.toLong() } + s.rowGap.toLong() * s.fullFrames.size
        return if (width != null && width > 0 && used + width <= s.rowAvailable) visible.size + 1 else visible.size.coerceAtLeast(2)
    }

    fun snapshotOwners(): List<Any> = synchronized(sessions) { sessions.keys.toList() }
    private fun sessionSnapshot(): List<Session> = synchronized(sessions) { sessions.values.toList() }

    fun restoreOwners(owners: List<Any>) {
        if (!::host.isInitialized || retiring) return
        owners.filter(host.coordinator::isInstance).forEach { sessions.getOrPut(it) { Session(it) } }
        refreshSettings()
    }

    private fun prepareRelayout(s: Session) {
        val tx = Transaction(s, summaries(s), host.current(s.owner, "getExpandedStateHandler")?.let(::key))
        tx.oldStates = s.extras.associateWith { host.call(it, "getState") }
        tx.forcePublication = true
        transaction.set(tx)
        try {
            s.gesture.begin(null)
            returnToQueue(s)
            settle(tx)
            host.call(host.call(s.owner, "getHiddenStateHandler")!!, "stop")
        } finally { transaction.remove() }
    }

    /** Publish only after the new view layout, using the host's configuration/no-animation path. */
    private fun publishRelayout(s: Session) {
        val visible = summaries(s)
        val windowState = visible.firstOrNull()?.let { host.call(it, "getWindowState") }
        if (windowState == null) {
            host.call(s.owner, "updateTouchRegion")
            return
        }
        val previous = host.call(windowState, "getConfigChange")
        try {
            host.call(windowState, "setConfigChange", true)
            visible.forEach { view ->
                host.call(view, "setLastState", host.call(view, "getState"))
                host.call(view, "getViewModel")?.let { host.call(it, "updateState", view) }
            }
        } finally { host.call(windowState, "setConfigChange", previous) }
        host.call(s.owner, "updateTouchRegion")
        DebugLog.i(TAG, "frame relayout published summaries=${visible.size}")
    }

    private fun requestRelayout(s: Session) {
        if (retiring) return
        val window = host.call(s.owner, "getWindowView") as? View ?: return
        if (geometryDiagnostics == null) geometryDiagnostics = IslandGeometryDiagnostics(window.context, main) {
            sessionSnapshot().forEach { current ->
                DebugLog.i("IslandGeometry", "layout=${current.layout} portrait=${current.portraitContent} frames=${current.fullFrames.values} failed=${current.failed} intent=${current.layoutIntent} eligible=${phoneLayoutEligible(current)} orientation=${window.resources.configuration.orientation}")
                summaries(current).forEachIndexed { index, view ->
                    DebugLog.i("IslandGeometry", "summary=$index state=${host.call(view, "getState")} size=${current.contentSizes[view]}")
                    IslandGeometryDiagnostics.tree("summary$index", view as? View)
                    IslandGeometryDiagnostics.tree("left$index", host.call(view, "getBigIslandAreaLeft") as? View)
                    IslandGeometryDiagnostics.tree("right$index", host.call(view, "getBigIslandAreaRight") as? View)
                    IslandGeometryDiagnostics.tree("fake$index", host.call(view, "getFakeView") as? View)
                }
            }
        }
        if (s.relayout?.window !== window) {
            s.relayout?.retire()
            s.relayout = IslandRelayoutDriver(window,
                ready = { !retiring && s.suspended == 0 && transaction.get() == null &&
                    host.call(s.owner, "getInConfigRebuild") == false },
                prepare = { prepareRelayout(s) }, publish = { publishRelayout(s) },
                failure = { error ->
                    s.failed = true
                    DebugLog.e(TAG, "frame relayout failed; restore native geometry", error)
                    runCatching { restore(s) }.onFailure { DebugLog.e(TAG, "frame restoration failed", it) }
                })
        }
        s.relayout?.request()
    }

    private val settingsRefresh = Runnable {
        if (retiring || !::host.isInitialized) return@Runnable
        sessionSnapshot().forEach { s -> extension(s, "request frame relayout") { requestRelayout(s) } }
    }

    fun refreshSettings() {
        if (retiring) return
        main.removeCallbacks(settingsRefresh)
        main.post(settingsRefresh)
    }

    private fun commitSwipe(s: Session, before: List<Any>, change: SummaryIslandScheduler.Change) {
        val queue = host.queue(s.owner)
        val pool = (before + queue).distinct()
        val after = change.visible.map { wanted -> pool.single { key(it) == wanted } }
        val target = pool.single { key(it) == change.task }
        s.scheduler.commit(change)
        queue.removeAll(before.toSet() + after.toSet())
        val removed = before.filter { it !in after }
        removed.forEach { if (it !in queue) queue.add(it) }
        // The third slot is still leased through settle(), so preserve its exact candidate.
        after.drop(2).asReversed().forEach { queue.add(0, it) }
        host.call(host.call(s.owner, "getBigIslandStateHandler")!!, "setCurrent", after.getOrNull(0))
        host.call(host.call(s.owner, "getSmallIslandStateHandler")!!, "setCurrent", after.getOrNull(1))
        val handler = host.call(s.owner, "getHiddenStateHandler")!!
        (before + after + target).distinct().forEach { view ->
            val state = when (after.indexOf(view)) { 0 -> host.big; 1 -> host.small; else -> host.hidden }
            host.call(handler, "addState", view, host.nextState(view, state))
        }
        removed.forEach { host.method(host.hiddenHandler, "enqueueHiddenStateCallback").invoke(handler, it, true) }
        host.call(handler, "setCurrent", queue.firstOrNull())
    }

    private fun queuedSummaries(s: Session): List<Any> = host.queue(s.owner).filter {
        host.hidden.isInstance(host.call(it, "getState"))
    }

    /** Apply one policy decision to both native handlers and the leased third slot. */
    private fun selectSummaries(s: Session): List<Any> {
        val queue = host.queue(s.owner)
        val handler = host.call(s.owner, "getHiddenStateHandler")!!
        val bigHandler = host.call(s.owner, "getBigIslandStateHandler")!!
        val smallHandler = host.call(s.owner, "getSmallIslandStateHandler")!!
        val before = summaries(s)
        val queued = queuedSummaries(s)
        val pool = (before + queued).distinct()
        val byKey = pool.mapNotNull { view -> key(view)?.let { it to view } }.toMap()
        val smallEligible = pool.filter { host.number(it, "getSmallIslandViewWidth") > 0 }.mapNotNull(::key).toSet()
        val selected = s.scheduler.select(before.mapNotNull(::key), queued.mapNotNull(::key), smallEligible, if (landscapeMoreEnabled(s)) Int.MAX_VALUE else 3)
        val desired = selected.native.map(byKey::getValue)
        queue.removeAll(desired.toSet())
        host.call(bigHandler, "setCurrent", desired.getOrNull(0))
        host.call(smallHandler, "setCurrent", desired.getOrNull(1))
        (before + desired).distinct().forEach { view ->
            val target = when (desired.indexOf(view)) { 0 -> host.big; 1 -> host.small; else -> host.hidden }
            if (target == host.hidden && view !in queue) queue.add(view)
            if (!target.isInstance(host.call(view, "getState"))) {
                host.call(handler, "addState", view, host.nextState(view, target))
            }
        }
        host.call(handler, "setCurrent", queue.firstOrNull())
        s.scheduler.retain((queue + desired + listOfNotNull(host.current(s.owner, "getExpandedStateHandler")))
            .mapNotNull(::key).toSet())
        return selected.extras.map(byKey::getValue)
    }

    private fun claimSmall(session: Session, target: Any) {
        if (!enabled(session) || target !in session.extras) return
        val handler = host.call(session.owner, "getSmallIslandStateHandler")!!
        val previous = host.call(handler, "getCurrent") ?: return
        host.call(handler, "setCurrent", session.slot.exchange(target, previous))
        session.seats.assign(key(target), key(previous))
        host.call(session.owner, "updateTouchRegion")
    }

    private fun returnToQueue(session: Session) {
        val extras = session.extras
        if (extras.isEmpty()) return
        extras.forEach { extra ->
            smallX(session, extra)?.let { session.departingPositions[extra] = it }
            host.lendToHidden(extra)
        }
        val queue = host.queue(session.owner)
        session.slot.lend(queue)
        host.call(host.call(session.owner, "getHiddenStateHandler")!!, "setCurrent", queue.firstOrNull())
    }

    private fun settle(tx: Transaction) {
        tx.settled = true
        val s = tx.session
        val owner = s.owner
        val queue = host.queue(owner)
        val expanded = host.current(owner, "getExpandedStateHandler")
        val eligible = summarySwipeEnabled(s)
        val more = landscapeMoreEnabled(s)
        s.scheduler.capacityChanged(s.moreCapacity, more)
        s.moreCapacity = more
        if (eligible) {
            // The host may already have promoted the queued task into a native summary
            // handler. Release its swipe exclusion before enforcing visibility, otherwise
            // that promotion is immediately hidden again and the third seat stays empty.
            s.scheduler.fillExpandedVacancy(tx.expandedKey, expanded?.let(::key), tx.visible.mapNotNull(::key),
                (summaries(s) + queuedSummaries(s)).mapNotNull(::key))
        }
        val candidates = if (eligible) selectSummaries(s) else emptyList()
        val big = host.current(owner, "getBigIslandStateHandler")
        val right = host.current(owner, "getSmallIslandStateHandler")
        s.extras = candidates
        val reserve = big != null && ((eligible && right != null && candidates.isNotEmpty()) || rowModeEnabled(s) || portraitCompactEnabled(s))
        s.seats.assign(right?.let(::key), candidates.firstOrNull()?.let(::key))
        s.layoutIntent = false
        s.layout = null
        s.fullFrames.clear()
        s.portraitContent = null
        // Re-measure real contents, then pack only the currently occupied summary seats.
        if (reserve) (listOfNotNull(right) + candidates).forEach { host.call(it, "calculateBigIslandWidth") }
        s.layoutIntent = reserve
        if (big != null) host.call(big, "calculateBigIslandWidth")
        val admitted = s.extras
        if (admitted.isNotEmpty() && s.layout != null) {
            s.slot.take(queue, admitted)
            host.call(host.call(owner, "getHiddenStateHandler")!!, "setCurrent", queue.firstOrNull())
            admitted.forEach { view -> host.call(host.call(owner, "getHiddenStateHandler")!!, "addState", view, host.nextState(view, host.small)) }
        } else s.extras = emptyList()
        val admission = "more=$more visible=${summaries(s).size} candidates=${candidates.size} leased=${s.extras.size} " +
            "queued=${queuedSummaries(s).size} excluded=${queuedSummaries(s).count { s.scheduler.isHidden(key(it)) }} " +
            "available=${s.rowAvailable} used=${s.fullFrames.values.sumOf { it.width }}"
        if (s.admissionLog != admission) {
            DebugLog.i(TAG, "admission $admission")
            s.admissionLog = admission
        }
        val key = s.extra?.let { host.call(it, "getIslandKey") as? String }
        if (key != s.loggedKey) {
            DebugLog.i(TAG, "third native slot key=$key active=${s.extra != null}")
            s.loggedKey = key
        }
        // Add the selected task and the previous third task even when the native event did not
        // touch them, so hide/removal/refill transitions cannot leave an orphaned view.
        val oldSurvivors = tx.oldStates.keys.filter { it in queue || it === big || it === right || it in s.extras }
        val geometryChanged = tx.oldLayout != s.layout || tx.oldFullFrames != s.fullFrames
        val changed = (if (geometryChanged || s.layout?.full == true || tx.forcePublication) listOfNotNull(big, right) else emptyList())
            .plus(s.extras + oldSurvivors + listOfNotNull(expanded?.takeIf { it in tx.visible })).distinct()
        val handler = host.call(owner, "getHiddenStateHandler")!!
        changed.forEach { view ->
            host.call(view, "calculateBigIslandWidth")
            host.call(handler, "addState", view, null)
            tx.oldStates[view]?.let { host.call(view, "setLastState", it) }
        }
    }

    private inline fun extension(session: Session, operation: String, block: () -> Unit) {
        try { block() } catch (error: Throwable) {
            session.failed = true
            transaction.get()?.settled = true
            DebugLog.e(TAG, "$operation failed; restoring native scheduling", error)
            runCatching { restore(session) }.onFailure { DebugLog.e(TAG, "native restoration failed", it) }
        }
    }

    private fun restore(session: Session) {
        val extras = session.extras
        val old = extras.associateWith { host.call(it, "getState") }
        returnToQueue(session)
        session.layout = null
        session.layoutIntent = false
        session.fullFrames.clear()
        session.portraitContent = null
        session.gesture.begin(null)
        session.scheduler.clear()
        session.swipePreview = null
        session.departingPositions.clear()
        val handler = host.call(session.owner, "getHiddenStateHandler")!!
        (extras + listOfNotNull(host.current(session.owner, "getBigIslandStateHandler"),
            host.current(session.owner, "getSmallIslandStateHandler"))).forEach {
            host.call(it, "calculateBigIslandWidth")
            host.call(handler, "addState", it, null)
        }
        old.forEach { (view, state) -> host.call(view, "setLastState", state) }
        session.textWidths.releaseAll()
        session.templateWidths.releaseAll { host.number(it, "getFixedCutoutWidth") }
        host.call(handler, "stop")
        host.call(session.owner, "updateTouchRegion")
    }

    override fun onPrepareHotReload() {
        retiring = true
        geometryDiagnostics?.retire()
        geometryDiagnostics = null
        main.removeCallbacks(settingsRefresh)
        if (!::host.isInitialized) return
        val owners = sessionSnapshot()
        val cleanup = Runnable {
            owners.forEach { session ->
                session.relayout?.retire()
                session.relayout = null
                runCatching { restore(session) }.onFailure { DebugLog.e(TAG, "retire third slot", it) }
                session.fullOrigins.keys.toList().forEach { view ->
                    runCatching { (host.call(view, "getBigIslandView") as? View)?.translationX = 0f }
                        .onFailure { DebugLog.e(TAG, "retire full template position", it) }
                }
            }
            sessions.clear()
        }
        if (Looper.myLooper() == main.looper) cleanup.run() else {
            val done = CountDownLatch(1)
            main.post { try { cleanup.run() } finally { done.countDown() } }
            check(done.await(5, TimeUnit.SECONDS)) { "island cleanup timed out" }
        }
    }

    private companion object { const val TAG = "ThreeIslands" }
}
