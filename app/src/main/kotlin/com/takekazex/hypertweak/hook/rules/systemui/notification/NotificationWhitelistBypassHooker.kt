package com.takekazex.hypertweak.hook.rules.systemui.notification

import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys

import android.content.Context
import android.content.SharedPreferences
import android.service.notification.StatusBarNotification
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.logging.DebugLog

/** Bypass only the domestic whitelist fallback; explicit app/channel settings remain native. */
object NotificationWhitelistBypassHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RECREATE
    private const val TAG = "NotificationWhitelistBypass"
    @Volatile private var enabled = false
    @Volatile private var retired = false

    override fun onHook() {
        if (!isMainProcess) return
        retired = false
        boundary("resolve") {
            val settings = "com.miui.systemui.notification.NotificationSettingsManager".toClassOrNull()
                ?: error("Missing notification settings")
            val prefs = "com.miui.systemui.notification.NotificationSettingsManager\$Prefs".toClassOrNull()
                ?: error("Missing notification settings prefs")
            val canFloat = HostAccess.requireDeclaredMethod(settings, "canFloat", Context::class.java, String::class.java, String::class.java, accessible = false)
                .apply { check(returnType == Boolean::class.javaPrimitiveType) }
            val floatKey = HostAccess.requireDeclaredMethod(prefs, "getFloatKey", String::class.java, String::class.java, accessible = false)
            val getPrefs = HostAccess.requireDeclaredMethod(prefs, "getNotif", Context::class.java, accessible = false)
                .apply { check(SharedPreferences::class.java.isAssignableFrom(returnType)) }
            val domestic = HostAccess.requireDeclaredField(settings, "USE_WHITE_LISTS", accessible = false).getBoolean(null)
            val singleton = HostAccess.requireDeclaredField(settings, "sINSTANCE", accessible = false)
            val helper = "com.miui.systemui.notification.NotificationSettingsHelper".toClassOrNull()
                ?: error("Missing notification settings helper")
            val userContext = HostAccess.requireDeclaredMethod(helper, "getUserContext", accessible = false)
            val contextUserId = HostAccess.requireDeclaredMethod(Context::class.java, "getUserId", accessible = false)
            val expanded = "com.android.systemui.statusbar.notification.ExpandedNotification".toClassOrNull()
                ?: error("Missing expanded notification")
            val cachedFloat = HostAccess.requireDeclaredField(expanded, "mCanFloat", accessible = false)
                .apply { check(type == Boolean::class.javaPrimitiveType) }
            val effectivePackage = HostAccess.requireDeclaredField(expanded, "mPkgName", accessible = false)
                .apply { check(type == String::class.java) }
            val expandedCanFloat = HostAccess.requireDeclaredMethod(expanded, "canFloat", accessible = false)
            val producer = "com.miui.systemui.notification.MiuiBaseNotifUtil".toClassOrNull()
                ?: error("Missing notification settings producer")
            deoptimize(HostAccess.requireDeclaredMethod(producer, "generateInnerNotifBean", StatusBarNotification::class.java, accessible = false))
            val decision = "com.android.systemui.statusbar.notification.interruption.VisualInterruptionDecisionProviderImplInjector".toClassOrNull()
                ?: error("Missing native interruption decision")
            decision.declaredMethods.forEach { deoptimize(it) }
            enabled = Preferences.getBoolean(SystemUiSettingsKeys.KEY_NOTIFICATION_WHITELIST_BYPASS, false)
            canFloat.hook {
                after { param -> boundary("whitelist") {
                    if (!enabled || retired || !domestic || param.result != false) return@boundary
                    val context = param.args.getOrNull(0) as? Context ?: return@boundary
                    val pkg = param.args.getOrNull(1) as? String ?: return@boundary
                    val channel = param.args.getOrNull(2) as? String
                    if (pkg.isBlank() || channel.isNullOrEmpty()) return@boundary
                    val stored = getPrefs.invoke(null, context) as? SharedPreferences ?: return@boundary
                    val appKey = floatKey.invoke(null, pkg, null) as? String ?: return@boundary
                    val channelKey = floatKey.invoke(null, pkg, channel) as? String ?: return@boundary
                    val appAllowed = !stored.contains(appKey) || stored.getInt(appKey, 1) == 2
                    if (appAllowed && !stored.contains(channelKey)) param.result = true
                } }
            }
            // Existing notifications cache mCanFloat. Recompute just this native setting at the
            // next decision, then let the getter retain notification extras and focus semantics.
            // This also restores cached values after disable/reset without alerting old entries.
            expandedCanFloat.hook {
                before { param -> boundary("cached permission") {
                    if (retired) return@boundary
                    val sbn = param.thisObject as? StatusBarNotification ?: return@boundary
                    val context = userContext.invoke(null) as? Context ?: return@boundary
                    // Other users use their own provider in the native producer. Do not read the
                    // current user's preferences on behalf of a work-profile/secondary-user entry.
                    if (sbn.user != android.os.Process.myUserHandle() ||
                        contextUserId.invoke(context) != android.os.Process.myUid() / 100000) return@boundary
                    val pkg = effectivePackage.get(sbn) as? String ?: return@boundary
                    val manager = singleton.get(null) ?: return@boundary
                    val allowed = canFloat.invoke(manager, context, pkg, sbn.notification.channelId) as? Boolean ?: return@boundary
                    cachedFloat.setBoolean(sbn, allowed)
                } }
            }
            DebugLog.i(TAG, "HOOK_OK native float whitelist switch enabled=$enabled")
        }
    }

    fun refreshSettings() {
        Preferences.invalidateRuntimeReadCache()
        enabled = Preferences.getBoolean(SystemUiSettingsKeys.KEY_NOTIFICATION_WHITELIST_BYPASS, false)
    }

    override fun onPrepareHotReload() {
        retired = true
        enabled = false
    }

    private inline fun boundary(action: String, crossinline block: () -> Unit) =
        HookFailurePolicy.open(TAG, action, Unit) { block() }
}
