package com.takekazex.hypertweak.hook.rules.settings

import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import android.content.Intent
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Field

/**
 * Reveals Settings' own Google-services home-page header on domestic builds.
 *
 * `MiuiSettings.updateHeaderList(List)` already has the complete native path:
 * `AddGoogleSettingsHeaders(List)` queries system activities with the
 * `com.android.settings.action.EXTRA_SETTINGS` action, groups their metadata,
 * creates the stock header/icon/title and lets `onHeaderClick` launch it. The
 * ROM calls that helper only when `Build.IS_GLOBAL_BUILD` is true, so CN builds
 * retain the entry implementation but never add it to the home list.
 *
 * This hook calls that existing private helper after the normal list update,
 * leaving the header construction and click routing in Settings untouched.
 */
object GoogleServicesSettingsHooker : StaticHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingShowGoogleServicesInSettings by hookSetting(false) {
        Preferences.getBoolean(PlatformSettingsKeys.KEY_SHOW_GOOGLE_SERVICES_IN_SETTINGS, false)
    }

    private const val TAG = "GoogleServicesSettings"
    private const val SETTINGS_CLASS = "com.android.settings.MiuiSettings"
    private const val UPDATE_HEADER_LIST = "updateHeaderList"
    private const val ADD_GOOGLE_HEADERS = "AddGoogleSettingsHeaders"
    private const val GOOGLE_SETTINGS_PACKAGE = "com.google.android.gms"

    override fun onHook() {
        if (!settingShowGoogleServicesInSettings) {
            DebugLog.hookSkippedDebug(TAG, "Settings home Google services entry", "disabled")
            return
        }

        val clazz = SETTINGS_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, SETTINGS_CLASS, "class not found")
            return
        }
        val updateHeaderList = clazz.declaredMethods.firstOrNull {
                it.name == UPDATE_HEADER_LIST &&
                it.parameterTypes.size == 1 &&
                List::class.java.isAssignableFrom(it.parameterTypes[0])
        } ?: run {
            DebugLog.hookSkipped(TAG, "$SETTINGS_CLASS#$UPDATE_HEADER_LIST(List)", "method not found")
            return
        }
        val addGoogleHeaders = clazz.declaredMethods.firstOrNull {
                it.name == ADD_GOOGLE_HEADERS &&
                it.parameterTypes.size == 1 &&
                List::class.java.isAssignableFrom(it.parameterTypes[0])
        }?.apply { isAccessible = true } ?: run {
            DebugLog.hookSkipped(TAG, "$SETTINGS_CLASS#$ADD_GOOGLE_HEADERS(List)", "method not found")
            return
        }

        deoptimize(updateHeaderList)
        updateHeaderList.hook("settings_google_services_home_reveal") {
            after { param ->
                HookFailurePolicy.open(TAG, "$UPDATE_HEADER_LIST.after", Unit) {
                    if (!settingShowGoogleServicesInSettings
                    ) {
                        return@open
                    }
                    @Suppress("UNCHECKED_CAST") // Host header list passed into the hooked method.
                    val headers = param.args.getOrNull(0) as? MutableList<Any?> ?: return@open
                    if (containsGoogleSettingsHeader(headers)) return@open
                    addGoogleHeaders.invoke(param.thisObject, headers)
                }
            }
        }
        DebugLog.i(TAG, "Settings home Google services header reveal armed")
    }

    private fun containsGoogleSettingsHeader(headers: List<Any?>): Boolean {
        return headers.any { header ->
            val intent = header?.let { field(it.javaClass, "intent")?.get(it) as? Intent }
            intent?.component?.packageName == GOOGLE_SETTINGS_PACKAGE
        }
    }

    private fun field(type: Class<*>, name: String): Field? = HostAccess.field(type, name)
}
