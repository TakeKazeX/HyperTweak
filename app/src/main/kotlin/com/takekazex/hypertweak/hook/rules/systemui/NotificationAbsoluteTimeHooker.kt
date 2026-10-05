package com.takekazex.hypertweak.hook.rules.systemui

import android.icu.text.RelativeDateTimeFormatter
import android.text.format.DateFormat
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.core.view.isGone
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.StatusIconHostAccess
import com.takekazex.hypertweak.util.DebugLog
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap

/** Only native notification headers enroll framework DateTimeViews; MIUI/custom bodies are untouched. */
object NotificationAbsoluteTimeHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RECREATE
    private const val TAG = "NotificationAbsoluteTime"
    private const val HEADER = "com.android.systemui.statusbar.notification.row.wrapper.NotificationHeaderViewWrapper"
    private const val WRAPPER = "com.android.systemui.statusbar.notification.row.wrapper.NotificationViewWrapper"
    private val views = WeakHashMap<TextView, Boolean>()
    private val callbacks = WeakHashMap<TextView, Runnable>()
    private var timeField: Field? = null
    private var relativeField: Field? = null
    private var nextUpdateField: Field? = null
    private var wrapperViewField: Field? = null
    private var updateMethod: Method? = null
    private var nowTextId = 0
    private var timeId = 0
    @Volatile private var retiring = false

    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = boundary("attach") {
            (view as? TextView)?.let { updateMethod?.invoke(it) }
        }
        override fun onViewDetachedFromWindow(view: View) = boundary("detach") {
            (view as? TextView)?.let(::cancel)
        }
    }

    override fun onHook() {
        retiring = false
        // All notification headers execute in the main SystemUI process; screenshot/tuner services do not.
        if (!isMainProcess) return
        val type = "android.widget.DateTimeView".toClassOrNull() ?: return
        val header = HEADER.toClassOrNull() ?: return
        val wrapper = WRAPPER.toClassOrNull() ?: return
        boundary("resolve") {
            timeField = type.getDeclaredField("mTimeMillis").apply { isAccessible = true }
            relativeField = type.getDeclaredField("mShowRelativeTime").apply { isAccessible = true }
            nextUpdateField = type.getDeclaredField("mUpdateTimeMillis").apply { isAccessible = true }
            wrapperViewField = wrapper.getDeclaredField("mView").apply { isAccessible = true }
            val update = type.getDeclaredMethod("update").apply { isAccessible = true }
            updateMethod = update
            val content = header.declaredMethods.single { method ->
                method.name == "onContentUpdated" && method.parameterTypes.map { it.name } ==
                    listOf("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow")
            }
            content.hook("notification_absolute_time_bind") {
                after { param -> boundary("bind") {
                    val root = wrapperViewField?.get(param.thisObject) as? View ?: return@boundary
                    if (timeId == 0) timeId = root.resources.getIdentifier("time", "id", "android")
                    val view = root.findViewById<View>(timeId) as? TextView ?: return@boundary
                    if (!type.isInstance(view)) return@boundary
                    enroll(view)
                    update.invoke(view)
                } }
            }
            update.hook("notification_absolute_time_update") {
                after { param -> boundary("update") {
                    val view = param.thisObject as? TextView ?: return@boundary
                    if (views.containsKey(view)) render(view)
                } }
            }
            type.getDeclaredMethod("onInitializeAccessibilityNodeInfoInternal", AccessibilityNodeInfo::class.java)
                .hook("notification_absolute_time_accessibility") {
                    after { param -> boundary("accessibility") {
                        val view = param.thisObject as? TextView ?: return@boundary
                        if (!retiring && views.containsKey(view) && relativeField?.getBoolean(view) == true &&
                            Preferences.getBoolean(Preferences.KEY_NOTIFICATION_ABSOLUTE_TIME, false)) {
                            (param.args.firstOrNull() as? AccessibilityNodeInfo)?.text = view.text
                        }
                    } }
                }
            DebugLog.d(TAG, "native header and DateTimeView update hooks installed")
        }
    }

    internal fun recoverExistingView(candidate: View) = boundary("existing header") {
        val view = candidate as? TextView ?: return@boundary
        if (retiring || timeField?.declaringClass?.isInstance(view) != true) return@boundary
        if (timeId == 0) timeId = view.resources.getIdentifier("time", "id", "android")
        if (timeId == 0 || view.id != timeId) return@boundary
        // The view inventory also contains custom bodies and promoted/AOD clocks. Only recover
        // the standard framework header's time, including headers of existing notification groups.
        val nativeHeader = generateSequence(view.parent) { it.parent }.any {
            it.javaClass.name == "android.view.NotificationHeaderView" ||
                it.javaClass.name == "android.widget.NotificationHeaderView"
        }
        if (!nativeHeader) return@boundary
        enroll(view)
        updateMethod?.invoke(view)
    }

    private fun enroll(view: TextView) {
        if (retiring || views.containsKey(view)) return
        views[view] = true
        view.addOnAttachStateChangeListener(attachListener)
    }

    private fun render(view: TextView) {
        cancel(view)
        if (retiring || !Preferences.getBoolean(Preferences.KEY_NOTIFICATION_ABSOLUTE_TIME, false) ||
            relativeField?.getBoolean(view) != true || view.isGone) return
        val time = timeField?.getLong(view) ?: return
        val now = System.currentTimeMillis()
        val display = NotificationTimePolicy.display(time, now, ZoneId.systemDefault())
        val locale = view.resources.configuration.locales[0] ?: Locale.getDefault()
        val clock = DateFormat.getTimeFormat(view.context).format(Date(time))
        val text = when (display.label) {
            NotificationTimePolicy.Label.NOW -> {
                if (nowTextId == 0) nowTextId = view.resources.getIdentifier("now_string_shortest", "string", "android")
                view.resources.getString(nowTextId)
            }
            NotificationTimePolicy.Label.TODAY -> clock
            NotificationTimePolicy.Label.YESTERDAY -> {
                val yesterday = RelativeDateTimeFormatter.getInstance(locale).format(
                    RelativeDateTimeFormatter.Direction.LAST, RelativeDateTimeFormatter.AbsoluteUnit.DAY
                )
                "$yesterday $clock"
            }
            NotificationTimePolicy.Label.DATE, NotificationTimePolicy.Label.YEAR_DATE -> {
                val skeleton = if (display.label == NotificationTimePolicy.Label.DATE) "MMMd" else "yMMMd"
                val date = DateFormat.format(DateFormat.getBestDateTimePattern(locale, skeleton), time)
                "$date $clock"
            }
        }
        if (view.text.toString() != text) view.text = text
        // Keep the framework minute receiver aware of the next date transition.
        nextUpdateField?.setLong(view, display.nextUpdateMillis)
        if (display.label == NotificationTimePolicy.Label.NOW && view.isAttachedToWindow) {
            val reference = WeakReference(view)
            val callback = Runnable { boundary("30 second boundary") {
                val target = reference.get() ?: return@boundary
                callbacks.remove(target)
                if (!retiring && target.isAttachedToWindow) updateMethod?.invoke(target)
            } }
            callbacks[view] = callback
            view.postDelayed(callback, (display.nextUpdateMillis - now).coerceAtLeast(1L))
        }
    }

    private fun cancel(view: TextView) {
        callbacks.remove(view)?.let { view.removeCallbacks(it) }
    }

    override fun saveHotReloadState(): Any = StatusIconHostAccess.onMain { views.keys.toList() }

    override fun restoreHotReloadState(state: Any?) {
        StatusIconHostAccess.onMain {
            (state as? List<*>)?.filterIsInstance<TextView>()?.forEach { view -> boundary("recover") {
                if (timeField?.declaringClass?.isInstance(view) != true) return@boundary
                enroll(view)
                updateMethod?.invoke(view)
            } }
        }
    }

    override fun onPrepareHotReload() {
        retiring = true
        StatusIconHostAccess.onMain {
            views.keys.toList().forEach { view -> boundary("restore") {
                cancel(view)
                view.removeOnAttachStateChangeListener(attachListener)
                updateMethod?.invoke(view)
            } }
            views.clear()
            callbacks.clear()
        }
    }

    private fun boundary(operation: String, action: () -> Unit) {
        HookFailurePolicy.open(TAG, operation, Unit, action)
    }
}
