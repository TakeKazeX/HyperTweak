package com.takekazex.hypertweak.hook.rules.googleapp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.UserHandle
import androidx.core.net.toUri
import com.takekazex.hypertweak.util.DebugLog

/**
 * Starts Google Lens (Google 智能镜头) for the long-press-power-button action.
 *
 * Lens is an *exported* entry point of the Google App, so this needs none of the
 * contextual-search machinery Circle to Search uses. It does need two Google-side gates opened,
 * which [GoogleAppLensEntryHooker] does from inside the Google App process; this class only
 * performs the start on the foreground user.
 *
 * - `google://lens` matches the `direct_intent_api` alias `com.google.android.apps.lens.MainActivity`
 *   → `com.google.android.apps.search.lens.LensExportedActivity`, the app's own Lens API and the
 *   route measured to open Lens. Tried first.
 * - `googleapp://lens` (`...lens.deeplink.LensDeeplink` → `GoogleAppGatewayActivity`) is kept as
 *   the declared fallback only: measured on the device it resolves, draws a transparent frame and
 *   returns RESULT_CANCELED without opening any Lens surface, so it is a dead end on 17.58.13.
 *
 * Both intents are pinned to the Google App with `setPackage`, so resolving them can neither raise
 * a chooser nor hand the launch to another app declaring the same scheme, and both are started
 * with `startActivityAsUser` for `ActivityManager.getCurrentUser()` (both hidden on the public
 * stub, hence reflective; user 0 is the last resort) so a secondary user's press does not start
 * Lens invisibly on user 0.
 *
 * The start deliberately comes from system_server rather than from an activity in the module app.
 * A module-side trampoline would satisfy Google's "must be started for result" check by itself, but
 * MIUI's 关联启动 guard (`com.miui.wakepath.ui.ConfirmStartActivity`) intercepts an ordinary app
 * starting the Google App and asks the user for confirmation; a system-uid start is allowed
 * silently. This is why the caller package is supplied inside the Google App instead — see
 * [GoogleAppLensEntryHooker].
 *
 * A route that does not resolve throws `ActivityNotFoundException`, and the ladder fails closed to
 * false: `PowerButtonCtsHooker.dispatchAction` then leaves `param.result` unset, so the platform's
 * own long-press handling stays in place instead of the press being swallowed.
 */
object GoogleLensLauncher {
    private const val SCOPE = "GoogleLens"

    /**
     * Marks the launch as this action's own.
     *
     * The Google App reports `Activity#getLaunchedFromUid()` as `-1` and the calling package as
     * null for any start without a source activity, so the caller identity of a system_server
     * launch cannot be recognised from the framework. [GoogleAppLensEntryHooker] uses this extra,
     * together with the power-button preference, to leave every other caller's Lens launch alone.
     */
    internal const val EXTRA_POWER_LAUNCH = "com.takekazex.hypertweak.extra.POWER_LENS_LAUNCH"

    /** The Google App; the only package these Lens entry points exist in. */
    private const val GOOGLE_APP_PACKAGE = GoogleAppRuntime.PACKAGE

    private const val URI_LENS = "google://lens"
    private const val URI_LENS_DEEPLINK = "googleapp://lens"

    /**
     * Starts Lens for the user the press happened for.
     *
     * @return true when an entry point accepted the intent. False means nothing was started, and
     *   the caller must leave the platform's own power action in place rather than swallow the
     *   press.
     */
    fun launch(context: Context): Boolean {
        for ((route, intent) in routeIntents()) {
            if (startActivity(context, intent)) {
                DebugLog.i(SCOPE, "opened Google Lens through $route")
                return true
            }
        }
        DebugLog.w(SCOPE, "no Google Lens entry point accepted the intent")
        return false
    }

    /** The routes in order of preference, each a complete intent. */
    private fun routeIntents(): List<Pair<String, Intent>> = listOf(
        "google://lens direct alias" to viewIntent(URI_LENS.toUri()),
        "googleapp://lens deeplink" to viewIntent(URI_LENS_DEEPLINK.toUri()),
        // Filter-independent last resort: an explicit start of the exported alias still works if a
        // build keeps the component but drops its intent filter.
        "LensExportedActivity component" to Intent(Intent.ACTION_VIEW)
            .setComponent(
                ComponentName(
                    GOOGLE_APP_PACKAGE,
                    "com.google.android.apps.search.lens.LensExportedActivity"
                )
            )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ).map { (route, intent) -> route to intent.putExtra(EXTRA_POWER_LAUNCH, true) }

    /** An `ACTION_VIEW` addressed to the Google App alone, as a fresh task. */
    private fun viewIntent(uri: Uri): Intent = Intent(Intent.ACTION_VIEW)
        .setPackage(GOOGLE_APP_PACKAGE)
        .setData(uri)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Starts [intent] as the foreground user.
     *
     * `startActivityAsUser` and `UserHandle.of` are `@hide` on the public stub, so they are
     * reflective here; a plain `Context#startActivity` is the fallback.
     */
    private fun startActivity(context: Context, intent: Intent): Boolean = runCatching {
        val userHandle = UserHandle::class.java
            .getMethod("of", Int::class.javaPrimitiveType)
            .invoke(null, currentUserId())
        context.javaClass
            .getMethod("startActivityAsUser", Intent::class.java, UserHandle::class.java)
            .invoke(context, intent, userHandle)
        true
    }.recoverCatching {
        context.startActivity(intent)
        true
    }.onFailure { failure ->
        DebugLog.w(SCOPE, "Lens route failed: ${intent.action} ${intent.data}", failure)
    }.getOrDefault(false)

    /**
     * The foreground user. system_server serves every user and its own process user is always
     * user 0, which would start Lens invisibly for anyone else; both `ActivityManager.getCurrentUser()`
     * and `UserHandle.myUserId()` are `@hide`, so both are reflective, with 0 as the last resort.
     */
    private fun currentUserId(): Int = runCatching {
        Class.forName("android.app.ActivityManager")
            .getMethod("getCurrentUser")
            .invoke(null) as? Int
    }.getOrNull() ?: runCatching {
        UserHandle::class.java.getMethod("myUserId").invoke(null) as? Int
    }.getOrNull() ?: 0
}
