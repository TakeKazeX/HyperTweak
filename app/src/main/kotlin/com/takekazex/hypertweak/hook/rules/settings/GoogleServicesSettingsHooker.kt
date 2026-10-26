package com.takekazex.hypertweak.hook.rules.settings

import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import android.content.Intent
import android.app.Activity
import java.lang.reflect.Method
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

    private var rebuild: Method? by generationState { null }
    private var hostClass: Class<*>? by generationState { null }
    private var retired = false
    init { generationCleanup { retired = true } }

    override fun onHook() {
        val clazz = SETTINGS_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, SETTINGS_CLASS, "class not found")
            return
        }
        retired = false
        hostClass = clazz
        rebuild = HostAccess.requireDeclaredMethod(clazz, "updateHeader", accessible = true)
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
                    if (retired || !settingShowGoogleServicesInSettings) {
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

    fun refreshSettings(): Boolean = HostAccess.onMain {
        val owner = hostClass ?: return@onMain false
        val method = rebuild ?: return@onMain false
        HookFailurePolicy.open(TAG, "rebuild living Settings headers", false) {
            val threadClass = Class.forName("android.app.ActivityThread")
            val thread = HostAccess.requirePublicMethod(threadClass, "currentActivityThread", accessible = false).invoke(null)
            val records = thread?.let { HostAccess.read(it, "mActivities") } as? Map<*, *>
            records?.values?.filterNotNull()?.forEach { record ->
                val activity = HostAccess.read(record, "activity") as? Activity ?: return@forEach
                if (owner.isInstance(activity) && !activity.isDestroyed) method.invoke(activity)
            }
            true
        }
    }

    override fun recoverHotReloadHosts() { refreshSettings() }

    override fun onPrepareHotReload() {
        retired = true
        refreshSettings()
    }

    private fun containsGoogleSettingsHeader(headers: List<Any?>): Boolean {
        return headers.any { header ->
            val intent = header?.let { field(it.javaClass, "intent")?.get(it) as? Intent }
            intent?.component?.packageName == GOOGLE_SETTINGS_PACKAGE
        }
    }

    private fun field(type: Class<*>, name: String): Field? = HostAccess.field(type, name)
}
