package com.takekazex.hypertweak.hook.rules.system

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.database.ContentObserver
import android.provider.Settings
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog

/**
 * Makes the launcher's gesture-bar long press reach Circle to Search instead of XiaoAI.
 *
 * ## Why the native patch alone is not enough
 *
 * The native payload replaces the launcher's contextual-search *terminal*, so the gesture reaches
 * the Google app. But which action the gesture is bound to is a separate, user-facing setting that
 * the launcher reads on its own:
 *
 * ```
 * Settings.Secure.NavLongPress = VoiceAssistantVoiceInput      (CN default, and 小爱's setting)
 * ```
 *
 * The launcher resolves that value through an entity table in its own Dart snapshot, which maps a
 * package to the values that hand the gesture to it:
 *
 * ```dart
 * { "com.mi.android.globallauncher": "launch_google_search",
 *   "com.miui.home":                 "launch_google_search",
 *   "com.miui.voiceassist":          "VoiceAssistant|VoiceAssistantScreenRecognizer|VoiceAssistantIntentBall|VoiceAssistantVoiceInput",
 *   "com.miui.securitycenter":       "SecurityCenterGlobalDock" }
 * ```
 *
 * So a `VoiceAssistant*` value sends the long press to 小爱 through its own route
 * (`GestureLineSettingsUtils.launchXiaoAi` → `sendBroadcastToAppForHandleGesture`, a Rust handler in
 * `libapp_launcher.so`) — which is *not* the terminal the payload patches. Observed on
 * OS4.0.0.30: with `NavLongPress=VoiceAssistantVoiceInput` a single long press started Circle to
 * Search **and** XiaoAI, and clearing the value to `None` killed both, because the value is also
 * what admits the gesture to the contextual-search path
 * (`"launchCircleToSearch ignored: only NavLongPress supported, triggerType="`).
 *
 * `launch_google_search` is the value that keeps the gesture admitted *and* leaves XiaoAI out of
 * it: it is the launcher's own default for this key, it resolves to `com.miui.home` (the launcher
 * itself, not another app), and with it the launcher logs
 * `GestureLineSettingsUtils, launchCircleToSearch result=true` with no app-handle broadcast. This
 * hooker writes exactly that while the Circle to Search long-press switch is on.
 *
 * ## Notes
 *
 * - It runs in system_server because writing a secure setting needs `WRITE_SECURE_SETTINGS`, which
 *   the module app does not hold. The launcher has its own observer on this key, so the change
 *   applies live — no launcher restart.
 * - Only `NavLongPress` is touched. `NavDoubleClick` (also 小爱 by default) is a different gesture
 *   and is left alone.
 * - The previous value is saved and restored as soon as the switch is turned off, so this is not a
 *   one-way change; while the switch is on, this setting is module-owned, which is what the switch
 *   means. A `ContentObserver` re-applies it if something else moves it.
 */
object CircleToSearchGestureHooker : StaticHooker() {
    override val hookerName = "CircleToSearchGesture"
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val SCOPE = "CtsGesture"

    /** The MIUI gesture-line action that reaches Circle to Search without involving XiaoAI. */
    private const val SETTING_NAV_LONG_PRESS = "NavLongPress"
    private const val DESIRED_NAV_LONG_PRESS = "launch_google_search"

    // The recorded original lives in Settings.Secure, not Preferences: this hooker runs in
    // system_server, where Preferences writes do not reliably persist. See SecureSettingStore.
    private const val BACKUP_NAV_LONG_PRESS = SecureSettingStore.NAV_LONG_PRESS

    @Volatile
    private var observerInstalled = false

    override fun onHook() {
        applyAlignment()
        installSettingObserver()
    }

    override fun onPrepareHotReload() {
        observerInstalled = false
    }

    /** Aligns the gesture-line action with the switch: applied while on, restored while off. */
    fun applyAlignment() {
        val context = systemContext() ?: return
        if (!isCircleToSearchLongPressEnabled()) {
            restore(context)
            return
        }
        runCatching { align(context) }.onFailure {
            DebugLog.w(SCOPE, "could not align $SETTING_NAV_LONG_PRESS", it)
        }
    }

    private fun isCircleToSearchLongPressEnabled(): Boolean =
        Preferences.contextualSearchLongPress()

    private fun align(context: Context) {
        val current = readSetting(context)
        // Already the right value: change nothing and claim nothing, so switching the feature off
        // never "restores" a value this hooker did not set.
        if (current == DESIRED_NAV_LONG_PRESS) return
        rememberBackupIfNeeded(context, current)
        writeSetting(context, DESIRED_NAV_LONG_PRESS)
        DebugLog.i(
            SCOPE,
            "$SETTING_NAV_LONG_PRESS '$current' -> '$DESIRED_NAV_LONG_PRESS' " +
                "(gesture-bar long press now reaches Circle to Search without XiaoAI)"
        )
    }

    /**
     * Puts the gesture-line action back the way this hooker found it.
     *
     * Fail-safe: with no recorded original it changes nothing. It must never clear the setting as a
     * fallback — that is what killed the gesture entirely during development.
     */
    private fun restore(context: Context) {
        if (!SecureSettingStore.has(context, BACKUP_NAV_LONG_PRESS)) return
        val previous = SecureSettingStore.read(context, BACKUP_NAV_LONG_PRESS)
        if (SecureSettingStore.restore(context, BACKUP_NAV_LONG_PRESS, SETTING_NAV_LONG_PRESS)) {
            DebugLog.i(SCOPE, "$SETTING_NAV_LONG_PRESS restored to '$previous'")
        }
    }

    /**
     * Records the original once. Nothing is recorded when the setting already holds the desired
     * value: the hooker changed nothing, so switching the feature off must change nothing either.
     */
    private fun rememberBackupIfNeeded(context: Context, current: String?) {
        SecureSettingStore.recordIfAbsent(context, BACKUP_NAV_LONG_PRESS, current)
    }

    /**
     * Re-applies when something else moves the key, which the switch's owner is expected to undo.
     * Our own write settles immediately (the value already equals the target), so this cannot loop.
     */
    private fun installSettingObserver() {
        if (observerInstalled) return
        val context = systemContext() ?: return
        runCatching {
            context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(SETTING_NAV_LONG_PRESS),
                false,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        applyAlignment()
                    }
                }
            )
            observerInstalled = true
        }.onFailure {
            DebugLog.w(SCOPE, "could not observe $SETTING_NAV_LONG_PRESS", it)
        }
    }

    // ─── Platform access ──────────────────────────────────────────────────────

    private fun readSetting(context: Context): String? =
        runCatching { Settings.Secure.getString(context.contentResolver, SETTING_NAV_LONG_PRESS) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }

    private fun writeSetting(context: Context, value: String?) {
        Settings.Secure.putString(context.contentResolver, SETTING_NAV_LONG_PRESS, value)
    }

    /**
     * system_server's own context; it has no Application, so `getSystemContext()` is the usable
     * one (the hooker runs in system_server, not in the module app).
     */
    private fun systemContext(): Context? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getMethod("currentActivityThread").invoke(null) ?: return null
        activityThread.getMethod("getSystemContext").invoke(thread) as? Context
    }.onFailure {
        DebugLog.w(SCOPE, "could not resolve the system context", it)
    }.getOrNull()
}
