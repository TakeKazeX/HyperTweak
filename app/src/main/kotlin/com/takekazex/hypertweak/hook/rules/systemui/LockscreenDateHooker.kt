package com.takekazex.hypertweak.hook.rules.systemui

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap

/** Hide dates in keyguard clocks throughout lockscreen/full-AOD transitions, excluding previews. */
object LockscreenDateHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED
    private val bindings = WeakHashMap<View, Binding>()
    @Volatile private var enabled = false

    override fun onPrepareHotReload() {
        enabled = false
        bindings.values.toList().forEach { binding ->
            binding.root.get()?.let { root -> root.post { binding.dispose() } }
        }
        bindings.clear()
    }

    override fun onHook() {
        if (!isMainProcess) return
        enabled = Preferences.getBoolean(Preferences.KEY_HIDE_LOCKSCREEN_DATE, false)
        if (!enabled) return
        runCatching {
            val container = classLoader.loadClass("com.android.keyguard.clock.KeyguardClockContainer")
            val controller = classLoader.loadClass("com.miui.clock.MiuiClockController")
            val clock = classLoader.loadClass("com.miui.clock.MiuiClockController\$IClockView")
            val role = classLoader.loadClass("com.miui.clock.module.ClockViewType")
            val controllers = listOf("mMiuiClockController", "mMiuiSecondaryClockController")
                .map { container.getDeclaredField(it).apply { isAccessible = true } }
            require(controllers.all { it.type == controller })
            val activeClock = controller.getDeclaredField("mClockView").apply { isAccessible = true }
            require(activeClock.type == clock)
            val getView = clock.getMethod("getIClockView", role)
            require(View::class.java.isAssignableFrom(getView.returnType))
            val dateRoles = listOf("DATE", "WEEK", "FULL_DATE", "FULL_WEEK", "FULL_DATE_WEEK", "NOTIFICATION_DATE")
                .mapNotNull { name -> runCatching { role.getField(name).get(null) }.getOrNull() }
            require(dateRoles.isNotEmpty())
            // New clock styles expose their configurable first line as TEXT_AREA, even when
            // that line renders a date. Its native content type distinguishes it from weather,
            // health, lunar-only, and user text without inspecting localized display strings.
            val contentType = runCatching {
                val textArea = classLoader.loadClass("com.miui.clock.classic.ClassicTextAreaView")
                require(TextView::class.java.isAssignableFrom(textArea))
                textArea.getDeclaredField("mCurrentLineType").apply {
                    require(type == Int::class.javaPrimitiveType)
                    isAccessible = true
                }
            }.getOrNull()
            val textAreaRole = runCatching { role.getField("TEXT_AREA").get(null) }.getOrNull()
            val attach = container.getDeclaredMethod("onAttachedToWindow")
            attach.hook {
                after { param ->
                    val root = param.thisObject as? ViewGroup ?: return@after
                    runCatching {
                        if (enabled && !bindings.containsKey(root)) {
                            val binding = Binding(
                                root, controllers, activeClock, getView, dateRoles,
                                contentType, textAreaRole
                            )
                            bindings[root] = binding
                            binding.attach()
                        }
                    }.onFailure { DebugLog.hookFailed("LockscreenDate", "bind active clock", it) }
                }
            }
            DebugLog.hookRegistered("LockscreenDate", "KeyguardClockContainer#onAttachedToWindow")
        }.onFailure { DebugLog.hookFailed("LockscreenDate", "resolve clock date contract", it) }
    }

    private class Binding(
        rootView: ViewGroup,
        private val controllers: List<Field>,
        private val activeClock: Field,
        private val getView: Method,
        private val dateRoles: List<Any>,
        private val contentType: Field?,
        private val textAreaRole: Any?
    ) : ViewTreeObserver.OnPreDrawListener, ViewTreeObserver.OnGlobalLayoutListener,
        View.OnAttachStateChangeListener {
        val root = WeakReference(rootView)
        private val originals = WeakHashMap<View, Int>()
        private var boundClocks = emptyList<WeakReference<ViewGroup>>()
        private var boundDates = emptyList<WeakReference<View>>()
        private var boundTextAreas = emptyList<WeakReference<TextView>>()
        private var dirty = true
        private var failed = false

        fun attach() {
            root.get()?.let {
                it.addOnAttachStateChangeListener(this)
                it.viewTreeObserver.addOnPreDrawListener(this)
                it.viewTreeObserver.addOnGlobalLayoutListener(this)
            }
        }

        override fun onPreDraw(): Boolean {
            val host = root.get() ?: return true
            if (failed || !enabled) return true
            runCatching {
                val clocks = controllers.mapNotNull { field ->
                    field.get(host)?.let { activeClock.get(it) as? ViewGroup }
                }
                if (dirty || clocks.size != boundClocks.size ||
                    clocks.indices.any { clocks[it] !== boundClocks[it].get() }
                ) {
                    boundDates = resolveDates(clocks).map { WeakReference(it) }
                    boundTextAreas = resolveTextAreas(clocks).map { WeakReference(it) }
                    if (clocks.indices.any { it >= boundClocks.size || clocks[it] !== boundClocks[it].get() }) {
                        DebugLog.i(
                            "LockscreenDate",
                            "clock binding: classes=${clocks.map { it.javaClass.name }} " +
                                "dateViews=${boundDates.size} typedTextAreas=${boundTextAreas.size}"
                        )
                    }
                    boundClocks = clocks.map { WeakReference(it) }
                    dirty = false
                }
                val dates = boundDates.mapNotNull { it.get() }.toMutableSet()
                // Check every draw: a retained TEXT_AREA can switch from date to weather
                // without rebuilding its clock. Restore it as soon as it changes meaning.
                boundTextAreas.mapNotNull { it.get() }.forEach { area ->
                    val type = contentType?.getInt(area)
                    if (type == 11 || type == 100 || type == 103) dates += area
                }
                originals.keys.toList().filter { it !in dates }.forEach { view ->
                    originals.remove(view)?.let { visibility ->
                        if (view.visibility == View.GONE) view.visibility = visibility
                    }
                }
                dates.forEach { date ->
                    if (date.visibility != View.GONE) {
                        originals[date] = date.visibility
                        date.visibility = View.GONE
                    }
                }
            }.onFailure {
                failed = true
                restore()
                DebugLog.hookFailed("LockscreenDate", "apply active clock dates", it)
            }
            return true
        }

        private fun resolveDates(clocks: List<ViewGroup>): Set<View> {
            val dates = linkedSetOf<View>()
            clocks.forEach { clock ->
                dateRoles.forEach { role ->
                    val date = getView.invoke(clock, role) as? View
                    // Legacy IClockView implementations return the whole clock for every role.
                    // Only accept a TextView inside this active clock, never a mixed subtree.
                    if (date is TextView && isInside(date, clock)) dates += date
                }
                // Legacy clocks do not expose semantic date roles. IDs are resolved in the
                // active clock's own resources, preserving the separate lunar/owner labels.
                listOf("current_date", "current_week", "current_week_rote", "local_date", "resident_date")
                    .forEach { name ->
                        val id = clock.resources.getIdentifier(name, "id", "com.android.systemui")
                        if (id != 0) (clock.findViewById<View>(id) as? TextView)?.let { dates += it }
                    }
            }
            return dates
        }

        override fun onGlobalLayout() { dirty = true }

        private fun resolveTextAreas(clocks: List<ViewGroup>): List<TextView> {
            val field = contentType ?: return emptyList()
            val role = textAreaRole ?: return emptyList()
            return clocks.mapNotNull { clock ->
                val area = getView.invoke(clock, role) as? TextView
                area?.takeIf { field.declaringClass.isInstance(it) && isInside(it, clock) }
            }
        }

        private fun isInside(view: View, clock: View): Boolean {
            var parent = view.parent
            while (parent is View) {
                if (parent === clock) return true
                parent = parent.parent
            }
            return false
        }

        private fun restore() {
            originals.entries.toList().forEach { (view, visibility) ->
                runCatching {
                    if (view.visibility == View.GONE) view.visibility = visibility
                }.onFailure { DebugLog.hookFailed("LockscreenDate", "restore date visibility", it) }
            }
            originals.clear()
        }

        fun dispose() {
            root.get()?.let {
                if (it.viewTreeObserver.isAlive) it.viewTreeObserver.removeOnPreDrawListener(this)
                if (it.viewTreeObserver.isAlive) it.viewTreeObserver.removeOnGlobalLayoutListener(this)
                it.removeOnAttachStateChangeListener(this)
            }
            restore()
        }

        override fun onViewAttachedToWindow(view: View) = Unit

        override fun onViewDetachedFromWindow(view: View) {
            dispose()
            bindings.remove(view)
        }
    }
}
