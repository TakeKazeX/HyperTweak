package com.takekazex.hypertweak.hook.rules.systemui.notification

import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys

import android.app.PendingIntent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Field
import java.lang.reflect.Method

/** Unlock the native whitelist, retaining PendingIntent, window-state and keyguard gates. */
object NotificationMiniWindowHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RECREATE
    private const val TAG = "NotificationMiniWindow"
    private const val ROW = "com.android.systemui.statusbar.notification.row.ExpandableNotificationRowInjector"
    private const val MANAGER = "com.android.systemui.statusbar.notification.policy.AppMiniWindowManagerImpl"
    private val managers = instanceTracker()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var settingsField: Field? = null
    private var whitelistFields = emptyList<Field>()
    private var injectorField: Field? = null
    private var updateBar: Method? = null
    private var updateTip: Method? = null
    @Volatile private var enabled = false
    @Volatile private var retired = false

    override fun onHook() {
        if (!isMainProcess) return
        retired = false
        boundary("resolve") {
            val manager = MANAGER.toClassOrNull() ?: error("Missing mini-window manager")
            val row = ROW.toClassOrNull() ?: error("Missing notification row injector")
            val settings = "com.miui.systemui.notification.NotificationSettingsManager".toClassOrNull()
                ?: error("Missing notification settings")
            val gate = manager.getDeclaredMethod("canNotificationSlide", String::class.java, PendingIntent::class.java)
            check(gate.returnType == Boolean::class.javaPrimitiveType)
            settingsField = manager.getDeclaredField("notificationSettingsManager").apply {
                check(type == settings); isAccessible = true
            }
            whitelistFields = listOf("mAllowNotificationSlide", "mAllowNotificationSlideLocal").map { name ->
                settings.getDeclaredField(name).apply { check(type == List::class.java); isAccessible = true }
            }
            injectorField = "com.android.systemui.statusbar.notification.row.ExpandableView".toClassOrNull()
                ?.getDeclaredField("mInjector")?.apply { isAccessible = true }
                ?: error("Missing native row injector field")
            updateBar = row.getDeclaredMethod("updateMiniWindowBar").apply { isAccessible = true }
            updateTip = row.getDeclaredMethod("updateFreeformTipView").apply { isAccessible = true }
            enabled = Preferences.getBoolean(SystemUiSettingsKeys.KEY_NOTIFICATION_MINI_WINDOW, false)
            // Both verified callers write canSlide, then use native bar/touch handling. Avoid
            // already-inlined gate bodies after attaching to a living SystemUI process.
            deoptimize(updateBar!!)
            deoptimize(updateTip!!)
            gate.hook {
                before { param -> boundary("whitelist") {
                    if (!retired && enabled) {
                        val settingsInstance = settingsField?.get(param.thisObject) ?: return@boundary
                        managers.add(settingsInstance)
                        wrap(settingsInstance)
                    }
                } }
            }
            DebugLog.i(TAG, "HOOK_OK native notification slide whitelist; bar and gesture remain host-owned")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun wrap(settings: Any) {
        whitelistFields.forEach { field ->
            val source = field.get(settings) as? List<String> ?: return@forEach
            if (source !is NotificationSlideWhitelist) {
                field.set(settings, NotificationSlideWhitelist(source) { enabled && !retired })
            }
        }
    }

    fun refreshSettings() {
        Preferences.invalidateRuntimeReadCache()
        enabled = Preferences.getBoolean(SystemUiSettingsKeys.KEY_NOTIFICATION_MINI_WINDOW, false)
        main.post {
            if (!retired) boundary("refresh existing rows") {
                val views = ArrayList<View>()
                fun collect(view: View) {
                    views += view
                    if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
                }
                WindowInspector.getGlobalWindowViews().forEach(::collect)
                recoverExistingViews(views)
            }
        }
    }

    fun recoverExistingViews(views: List<View>) {
        val field = injectorField ?: return
        val bar = updateBar ?: return
        val tip = updateTip ?: return
        views.filter { field.declaringClass.isInstance(it) }.forEach { view ->
            boundary("repaint native bar") {
                val injector = field.get(view) ?: return@boundary
                if (bar.declaringClass.isInstance(injector)) {
                    bar.invoke(injector)
                    tip.invoke(injector)
                }
            }
        }
    }

    override fun onPrepareHotReload() {
        retired = true
        enabled = false
        main.removeCallbacksAndMessages(null)
        // Never leave a retired module's List/callback in the host, and never overwrite newer
        // cloud data that replaced our wrapper. Replacement recovery enrolls existing rows.
        managers.snapshot().forEach { settings -> boundary("restore whitelist") {
            whitelistFields.forEach { field ->
                val current = field.get(settings)
                if (current is NotificationSlideWhitelist) field.set(settings, current.source)
            }
        } }
    }

    private inline fun boundary(action: String, crossinline block: () -> Unit) =
        HookFailurePolicy.open(TAG, action, Unit) { block() }
}
