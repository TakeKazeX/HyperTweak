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

/** A third native SmallIsland, lent back to the host queue during each scheduling transaction. */
class ThreeIslandHooker : DynamicHooker() {
    private class Session(val owner: Any) {
        val slot = ThirdIslandSlot<Any>()
        var extra: Any?
            get() = slot.task
            set(value) { slot.task = value }
        val seats = SmallIslandSeats()
        val gesture = IslandGestureScope()
        val swipe = SummaryIslandSwipe()
        var summaryLimit = 3
        var swipePreview: Any? = null
        var layoutIntent = false
        var layout: ThreeIslandLayout? = null
        var suspended = 0
        var failed = false
        val departingPositions = WeakHashMap<Any, Int>()
        var loggedKey: String? = null
    }
    private class Transaction(val session: Session, val oldExtra: Any?, val oldVisualState: Any?,
        val visible: List<Any>, val expandedKey: String?) {
        val oldLayout = session.layout
        var settled = false
    }
    private lateinit var host: NativeIslandAccess
    private val sessions = WeakHashMap<Any, Session>()
    private val transaction = ThreadLocal<Transaction?>()
    private val calculating = ThreadLocal<Any?>()
    private val main = Handler(Looper.getMainLooper())
    private var retiring = false

    override fun onHook() {
        host = NativeIslandAccess { name -> name.toClassOrNull() ?: error("Missing $name") }
        // Fully resolve first. A changed plugin must skip this feature before any mutation.
        val handle = host.method(host.coordinator, "handleEvent")
        check(handle.parameterTypes.toList() == listOf(host.event, host.content))
        check(host.method(host.handler, "stop").parameterCount == 0)
        check(host.method(host.touch, "onInterceptTouchEvent").parameterTypes.first() == MotionEvent::class.java)

        listOf(handle, host.method(host.handler, "stop"), host.method(host.handler, "addState"),
            host.method(host.touch, "onInterceptTouchEvent"), host.method(host.content, "calculateBigIslandWidth"),
            host.method(host.coords, "getSmallIslandX"), host.method(host.window, "dispatchTouchEvent"),
            host.method(host.content, "onIslandClick"), host.method(host.touch, "performClick"),
            host.method(host.touch, "onTouchEvent"), host.method(host.coordinator, "resetSwipe"),
            host.method(host.coordinator, "dispatchSwipe")).forEach { deoptimize(it) }
        registerRuntimeHook(handle, "three_islands_transaction", XposedInterface.Hooker { chain ->
            val owner = chain.thisObject ?: return@Hooker chain.proceed()
            if (Looper.myLooper() != Looper.getMainLooper() || transaction.get() != null) return@Hooker chain.proceed()
            val session = sessions.getOrPut(owner) { Session(owner) }
            if (session.extra == null && !enabled(session)) return@Hooker chain.proceed()
            val tx = Transaction(session, session.extra, session.extra?.let { host.call(it, "getState") },
                summaries(session), host.current(owner, "getExpandedStateHandler")?.let(::key))
            transaction.set(tx)
            try {
                extension(session, "snapshot small origins") {
                    listOfNotNull(session.extra, host.current(owner, "getSmallIslandStateHandler")).forEach { view ->
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
                if (param.args.firstOrNull() === tx.oldExtra && tx.oldVisualState != null) {
                    extension(tx.session, "preserve transition origin") {
                        host.call(tx.oldExtra!!, "setLastState", tx.oldVisualState)
                    }
                }
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
                    extension(session, "prepare configuration rebuild") { restore(session) }
                    chain.proceed()
                } finally { session.suspended-- }
            })
        // Host template measurement and clipping remain native; only the two-small geometry changes.
        registerRuntimeHook(host.method(host.content, "calculateBigIslandWidth"), "three_islands_measure",
            XposedInterface.Hooker { chain ->
                val previous = calculating.get()
                calculating.set(chain.thisObject)
                try { chain.proceed() } finally {
                    if (previous == null) calculating.remove() else calculating.set(previous)
                }
            })
        host.method(host.helper, "calculateBigIslandWidth").hook {
            after { param ->
                val view = calculating.get() ?: return@after
                val session = sessionFor(view) ?: return@after
                if (!session.layoutIntent || !enabled(session)) return@after
                extension(session, "measure three islands") {
                    val input = param.args[0] ?: return@extension
                    val result = param.result ?: return@extension
                    val big = host.current(session.owner, "getBigIslandStateHandler")
                    if (big === view) {
                        session.layout = ThreeIslandLayout.calculate(
                            host.number(input, "getScreenWidth"), host.number(input, "getScreenHeight"),
                            host.number(input, "getClockWidth"), host.number(input, "getBatteryWidth"),
                            minOf(host.number(result, "getBigIslandViewWidth"), host.number(input, "getMaxWidth")),
                            (host.minimumBig.invoke(param.thisObject) as Number).toInt(),
                            maxOf(host.number(view, "getSmallIslandViewWidth"),
                                host.number(session.extra ?: host.current(session.owner, "getSmallIslandStateHandler")!!, "getSmallIslandViewWidth")),
                            host.number(input, "getSpace")
                        )
                    }
                    val layout = session.layout ?: return@extension
                    val cutout = host.number(input, "getCutoutWidth")
                    val left = (layout.bigWidth - cutout) / 2
                    val right = layout.bigWidth - cutout - left
                    param.result = host.resultConstructor.newInstance(layout.bigWidth, left, right, layout.bigX,
                        host.number(result, "getBigIslandMarginWidth"), layout.bigWidth, left, right, layout.bigX)
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
                    val window = host.call(session.owner, "getWindowView") as? View ?: return@extension
                    // Native calculator returns a translation relative to the screen centre.
                    val screenWidth = window.resources.displayMetrics.widthPixels
                    param.result = x + host.number(view, "getSmallIslandViewWidth") / 2f - screenWidth / 2f
                }
            }
        }
        host.method(host.anim, "getSmallIslandX").hook {
            after { param ->
                val session = transaction.get()?.session ?: sessions.values.firstOrNull {
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
                    rect.offsetTo(x, rect.top)
                }
            }
        }
        listOf("getSmallBigIslandRegion", "computeIslandRegion").forEach { name ->
            host.method(host.coordinator, name).hook {
                after { param ->
                    val session = sessions[param.thisObject] ?: return@after
                    extension(session, "window region") {
                        val region = param.result as? Region ?: return@extension
                        if (name == "getSmallBigIslandRegion" && session.summaryLimit == 0 &&
                            session.swipe.hasHidden && summarySwipeEnabled(session)) {
                            // Keep a summary reveal target even while a separate card is expanded.
                            (host.call(session.owner, "getDefaultIslandTouchRegion") as? Region)?.let {
                                region.op(it, Region.Op.UNION)
                            }
                        }
                        listOfNotNull(host.current(session.owner, "getSmallIslandStateHandler"), session.extra).forEach { small ->
                            val x = smallX(session, small) ?: return@forEach
                            val top = host.number(small, "getIslandViewMarginTop")
                            region.op(Rect(x, top, x + host.number(small, "getSmallIslandViewWidth"),
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
                        val extra = session.extra ?: return@extension
                        if (name == "hasIsland") {
                            val data = host.call(extra, "getCurrentIslandData") ?: return@extension
                            val extras = host.call(data, "getExtras") as? Bundle ?: return@extension
                            if (extras.getString("miui.pkg.name") != param.args.firstOrNull()) return@extension
                            val list = param.result as? List<*> ?: return@extension
                            if (extra !in list) param.result = ArrayList(list).apply { add(extra) }
                        } else {
                            val list = param.result as? List<*> ?: return@extension
                            if (extra !in list) param.result = ArrayList(list).apply { add(extra) }
                        }
                    }
                }
            }
        }
        host.method(host.coordinator, "isBigOrSmallIsland").hook {
            after { param ->
                val session = sessions[param.thisObject] ?: return@after
                if (session.extra != null && param.args.firstOrNull() === session.extra) param.result = true
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
                    val smallHit = listOfNotNull(session.extra, host.current(owner, "getSmallIslandStateHandler"))
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
                    val target = listOfNotNull(host.current(owner, "getExpandedStateHandler"),
                        host.current(owner, "getBigIslandStateHandler"),
                        host.current(owner, "getSmallIslandStateHandler"), session.extra)
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
        DebugLog.hookRegistered(TAG, "native third task transaction, geometry and touch routing")
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
    private fun sessionForSwipe(coordinator: Any): Session? = try {
        host.call(coordinator, "getDynamicIslandEventCoordinator")?.let { sessions[it] }
    } catch (error: Throwable) {
        DebugLog.e(TAG, "resolve swipe owner", error)
        null
    }
    private fun enabled(session: Session): Boolean = !retiring && !session.failed && session.suspended == 0 &&
        Preferences.getBoolean(Preferences.KEY_THREE_ISLANDS, false)

    private fun smallX(session: Session, view: Any): Int? {
        val isExtra = session.extra === view
        if (!isExtra && host.current(session.owner, "getSmallIslandStateHandler") !== view) {
            return session.departingPositions[view]
        }
        val layout = session.layout ?: return null
        val left = session.seats.left(key(view)) ?: if (isExtra) session.seats.extraOnLeft else session.seats.primaryOnLeft
        return if (left) layout.leftX else layout.rightX
    }

    private fun key(view: Any): String? = host.call(view, "getIslandKey") as? String

    private fun summaries(s: Session): List<Any> = listOfNotNull(
        host.current(s.owner, "getBigIslandStateHandler"),
        host.current(s.owner, "getSmallIslandStateHandler"), s.extra).distinct()

    private fun summarySwipeEnabled(s: Session): Boolean {
        val window = host.call(s.owner, "getWindowView") as? View ?: return false
        return enabled(s) && window.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
            window.resources.configuration.smallestScreenWidthDp < 600 &&
            host.call(s.owner, "isTinyScreen") == false && host.call(s.owner, "getInConfigRebuild") == false &&
            host.current(s.owner, "getShowOnceIslandHandler") == null &&
            host.call(host.call(s.owner, "getBigIslandStateHandler")!!, "getCurrentTempShow") == null
    }

    private fun swipePlan(s: Session, visible: List<Any>, reveal: Boolean): SummaryIslandSwipe.Change? =
        s.swipe.plan(visible.mapNotNull(::key), host.queue(s.owner).filter {
            host.hidden.isInstance(host.call(it, "getState"))
        }.mapNotNull(::key), reveal, s.gesture.clickKey)

    private fun commitSwipe(s: Session, before: List<Any>, change: SummaryIslandSwipe.Change) {
        val queue = host.queue(s.owner)
        val pool = (before + queue).distinct()
        val after = change.visible.map { wanted -> pool.single { key(it) == wanted } }
        val target = pool.single { key(it) == change.task }
        s.swipe.commit(change)
        s.summaryLimit = after.size
        queue.removeAll(before.toSet() + after.toSet())
        val removed = before.filter { it !in after }
        removed.forEach { if (it !in queue) queue.add(it) }
        // The third slot is still leased through settle(), so preserve its exact candidate.
        after.getOrNull(2)?.let { queue.add(0, it) }
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

    /** Keep manually hidden tasks excluded until a swipe or new card vacancy admits them. */
    private fun enforceSwipeVisibility(s: Session) {
        if (!summarySwipeEnabled(s) || (s.summaryLimit == 3 && !s.swipe.hasHidden)) return
        val queue = host.queue(s.owner)
        val handler = host.call(s.owner, "getHiddenStateHandler")!!
        val bigHandler = host.call(s.owner, "getBigIslandStateHandler")!!
        val smallHandler = host.call(s.owner, "getSmallIslandStateHandler")!!
        val before = summaries(s)
        val queued = queue.filter { host.hidden.isInstance(host.call(it, "getState")) }
        val pool = (before + queued).distinct()
        val desired = s.swipe.reconcile(before.mapNotNull(::key), queued.mapNotNull(::key), minOf(2, s.summaryLimit))
            .map { wanted -> pool.single { key(it) == wanted } }
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
        s.swipe.retain((queue + summaries(s) + listOfNotNull(host.current(s.owner, "getExpandedStateHandler")))
            .mapNotNull(::key).toSet())
    }

    private fun claimSmall(session: Session, target: Any) {
        if (!enabled(session) || session.extra !== target) return
        val handler = host.call(session.owner, "getSmallIslandStateHandler")!!
        val previous = host.call(handler, "getCurrent") ?: return
        host.call(handler, "setCurrent", session.slot.exchange(previous))
        session.seats.assign(key(target), key(previous))
        host.call(session.owner, "updateTouchRegion")
    }

    private fun returnToQueue(session: Session) {
        val extra = session.extra ?: return
        val queue = host.queue(session.owner)
        smallX(session, extra)?.let { session.departingPositions[extra] = it }
        host.transition(extra, host.hidden)
        session.slot.lend(queue)
        host.call(host.call(session.owner, "getHiddenStateHandler")!!, "setCurrent", queue.firstOrNull())
        session.extra = null
    }

    private fun settle(tx: Transaction) {
        tx.settled = true
        val s = tx.session
        val owner = s.owner
        val queue = host.queue(owner)
        val expanded = host.current(owner, "getExpandedStateHandler")
        if (summarySwipeEnabled(s)) {
            // The host may already have promoted the queued task into a native summary
            // handler. Release its swipe exclusion before enforcing visibility, otherwise
            // that promotion is immediately hidden again and the third seat stays empty.
            s.swipe.fillExpandedVacancy(tx.expandedKey, expanded?.let(::key), tx.visible.mapNotNull(::key),
                (summaries(s) + queue.filter { host.hidden.isInstance(host.call(it, "getState")) })
                    .mapNotNull(::key))
        }
        enforceSwipeVisibility(s)
        val big = host.current(owner, "getBigIslandStateHandler")
        val right = host.current(owner, "getSmallIslandStateHandler")
        val window = host.call(owner, "getWindowView") as? View
        val canShow = enabled(s) && big != null && right != null && window != null &&
            window.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
            window.resources.configuration.smallestScreenWidthDp < 600 &&
            host.call(owner, "isTinyScreen") == false && host.call(owner, "getInConfigRebuild") == false &&
            host.current(owner, "getShowOnceIslandHandler") == null &&
            host.call(host.call(owner, "getBigIslandStateHandler")!!, "getCurrentTempShow") == null
        val candidate = if (canShow && s.summaryLimit >= 3) queue.firstOrNull {
            host.hidden.isInstance(host.call(it, "getState")) && !s.swipe.isHidden(key(it)) && host.number(it, "getSmallIslandViewWidth") > 0
        } else null
        s.extra = candidate
        s.layoutIntent = ThreeIslandAdmission.reserve(canShow, candidate != null, expanded != null, s.layout != null)
        s.seats.assign(right?.let(::key), candidate?.let(::key))
        s.layout = null
        if (big != null) host.call(big, "calculateBigIslandWidth")
        if (candidate != null && s.layout != null) {
            s.slot.take(queue, candidate)
            host.call(host.call(owner, "getHiddenStateHandler")!!, "setCurrent", queue.firstOrNull())
            host.call(host.call(owner, "getHiddenStateHandler")!!, "addState", candidate, host.nextState(candidate, host.small))
        } else s.extra = null
        val key = s.extra?.let { host.call(it, "getIslandKey") as? String }
        if (key != s.loggedKey) {
            DebugLog.i(TAG, "third native slot key=$key active=${s.extra != null}")
            s.loggedKey = key
        }
        // Add the selected task and the previous third task even when the native event did not
        // touch them, so hide/removal/refill transitions cannot leave an orphaned view.
        val oldSurvivor = tx.oldExtra?.takeIf { it in queue || it === big || it === right || it === s.extra }
        val geometryChanged = tx.oldLayout != s.layout
        val changed = (if (geometryChanged) listOfNotNull(big, right) else emptyList())
            .plus(listOfNotNull(s.extra, oldSurvivor)).distinct()
        val handler = host.call(owner, "getHiddenStateHandler")!!
        changed.forEach { view ->
            host.call(view, "calculateBigIslandWidth")
            host.call(handler, "addState", view, null)
            if (view === tx.oldExtra && tx.oldVisualState != null) host.call(view, "setLastState", tx.oldVisualState)
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
        val extra = session.extra
        val old = extra?.let { host.call(it, "getState") }
        returnToQueue(session)
        session.layout = null
        session.layoutIntent = false
        session.gesture.begin(null)
        session.swipe.clear()
        session.summaryLimit = 3
        session.swipePreview = null
        session.departingPositions.clear()
        val handler = host.call(session.owner, "getHiddenStateHandler")!!
        listOfNotNull(extra, host.current(session.owner, "getBigIslandStateHandler"),
            host.current(session.owner, "getSmallIslandStateHandler")).forEach {
            host.call(it, "calculateBigIslandWidth")
            host.call(handler, "addState", it, null)
        }
        if (extra != null) host.call(extra, "setLastState", old)
        host.call(handler, "stop")
        host.call(session.owner, "updateTouchRegion")
    }

    override fun onPrepareHotReload() {
        retiring = true
        if (!::host.isInitialized) return
        val owners = sessions.values.toList()
        val cleanup = Runnable {
            owners.forEach { session ->
                runCatching { restore(session) }.onFailure { DebugLog.e(TAG, "retire third slot", it) }
            }
            sessions.clear()
        }
        if (Looper.myLooper() == main.looper) cleanup.run() else main.post(cleanup)
    }

    private companion object { const val TAG = "ThreeIslands" }
}
