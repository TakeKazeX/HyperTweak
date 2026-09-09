package com.takekazex.hypertweak.hook.rules.securitycenter

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.ArrayMap
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.CompatibleMethodResolver
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Expands Security Center's bubble-app management list to installed applications and removes its
 * built-in/cloud app allowlist from the custom MIUI bubble path.
 *
 * The management list is backed by `BubblesSettings.mBubbleAppMaps`, so adding the installed apps
 * to `getDefaultBubbles()` makes them visible and gives each one an independent switch. The
 * downstream `NotificationController.sbnToBubbleEntry()` lookup still requires a real Bubble
 * whose icon is at the screen edge, so this hook does not create standalone notification bubbles
 * or change the freeform lifecycle.
 */
object BubbleNotificationWhitelistHooker : StaticHooker() {
    private const val TAG = "BubbleNotificationWhitelist"

    private const val BUBBLES_SETTINGS = "com.miui.bubbles.settings.BubblesSettings"
    private const val BUBBLE_APP = "com.miui.bubbles.settings.BubbleApp"
    private const val BUBBLE_UP_MANAGER = "com.miui.bubbles.utils.BubbleUpManager"
    private const val NOTIFICATION_CONTROLLER =
        "com.miui.bubbles.controller.NotificationController"
    private const val SECURITY_CENTER_PACKAGE = "com.miui.securitycenter"

    private const val BUBBLES_SETTINGS_CONTEXT_FIELD = "mContext"
    private const val BUBBLES_SETTINGS_CURRENT_USER_FIELD = "mCurrentUserId"
    private const val BUBBLES_SETTINGS_APP_MAP_FIELD = "mBubbleAppMaps"
    private const val BUBBLE_UP_MANAGER_SETTINGS_FIELD = "mBubblesSettings"
    private const val SECURE_BUBBLE_APP_SETTINGS = "miui_bubble_app_settings"

    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    override fun onHook() {
        if (!Preferences.getBoolean(
                Preferences.KEY_SECURITY_CENTER_BUBBLE_NOTIFICATION_UNLOCK,
                false
            )
        ) {
            DebugLog.hookSkipped(TAG, "Security Center bubble notification app limit", "disabled")
            return
        }

        val bubblesSettings = BUBBLES_SETTINGS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, BUBBLES_SETTINGS, "class not found")
            return
        }
        hookBubbleAppManagementList(bubblesSettings)

        val manager = BUBBLE_UP_MANAGER.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, BUBBLE_UP_MANAGER, "class not found")
            return
        }
        val gate = CompatibleMethodResolver.find(
            manager,
            "isEnableBubbleNotification",
            returnType = Boolean::class.javaPrimitiveType,
            parameterTypes = listOf(StatusBarNotification::class.java)
        ) ?: run {
            DebugLog.hookSkipped(
                TAG,
                "$BUBBLE_UP_MANAGER#isEnableBubbleNotification(StatusBarNotification)",
                "method not found"
            )
            return
        }

        val managerSettingsField = manager.getDeclaredFieldOrNull(BUBBLE_UP_MANAGER_SETTINGS_FIELD)
        val appMapField = bubblesSettings.getDeclaredFieldOrNull(BUBBLES_SETTINGS_APP_MAP_FIELD)
        val settingsContextField =
            bubblesSettings.getDeclaredFieldOrNull(BUBBLES_SETTINGS_CONTEXT_FIELD)
        if (managerSettingsField == null || appMapField == null) {
            DebugLog.w(TAG, "bubble app map fields unavailable; runtime allowlist bypass skipped")
        }

        // The gate is a tiny private helper and can be inlined into both of these callers before
        // the hook is installed. Bring the verified call chain back through the hook boundary.
        deoptimize(gate)
        CompatibleMethodResolver.find(
            manager,
            "shouldHeadUp",
            returnType = Boolean::class.javaPrimitiveType,
            parameterTypes = listOf(
                StatusBarNotification::class.java,
                NotificationListenerService.RankingMap::class.java
            )
        )?.let(::deoptimize)
        deoptimizeNotificationCaller()

        runCatching {
            gate.hook {
                before { param ->
                    HookFailurePolicy.open(TAG, "isEnableBubbleNotification", Unit) {
                        if (Preferences.getBoolean(
                            Preferences.KEY_SECURITY_CENTER_BUBBLE_NOTIFICATION_UNLOCK,
                            false
                        )
                        ) {
                            val notification = param.args.getOrNull(0) as? StatusBarNotification
                                ?: return@open
                            val packageName = notification.packageName
                            val isMapped = if (managerSettingsField != null && appMapField != null) {
                                isBubbleAppMapped(
                                    manager = param.thisObject,
                                    managerSettingsField = managerSettingsField,
                                    appMapField = appMapField,
                                    packageName = packageName
                                )
                            } else {
                                null
                            }
                            when (
                                readSecureBubbleAppState(
                                    manager = param.thisObject,
                                    managerSettingsField = managerSettingsField,
                                    settingsContextField = settingsContextField,
                                    packageName = packageName
                                )
                            ) {
                                false -> param.result = false
                                true -> param.result = true
                                null -> if (isMapped == false) {
                                    // Only an app installed after this Security Center instance
                                    // was created is allowed through this pre-filter; the
                                    // NotificationController edge-state lookup still follows it.
                                    param.result = true
                                }
                            }
                        }
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(
                TAG,
                "$BUBBLE_UP_MANAGER#isEnableBubbleNotification(StatusBarNotification)",
                it
            )
            return
        }

        DebugLog.i(
            TAG,
            "Security Center bubble app list expanded; secure app states honored; edge gate kept"
        )
    }

    /**
     * Appends installed user applications to the map consumed by the Security Center management
     * activity. Existing cloud/default entries stay first so the stock order is retained.
     */
    private fun hookBubbleAppManagementList(bubblesSettings: Class<*>) {
        val getDefaultBubbles = CompatibleMethodResolver.find(
            bubblesSettings,
            "getDefaultBubbles",
            returnType = ArrayMap::class.java
        ) ?: run {
            DebugLog.hookSkipped(TAG, "$BUBBLES_SETTINGS#getDefaultBubbles()", "method not found")
            return
        }
        val contextField = bubblesSettings.getDeclaredFieldOrNull(BUBBLES_SETTINGS_CONTEXT_FIELD)
        val currentUserField =
            bubblesSettings.getDeclaredFieldOrNull(BUBBLES_SETTINGS_CURRENT_USER_FIELD)
        val bubbleAppClass = BUBBLE_APP.toClassOrNull()
        val bubbleAppConstructor = bubbleAppClass?.getDeclaredConstructorOrNull(
            String::class.java,
            Int::class.javaPrimitiveType!!
        )
        val setChecked = bubbleAppClass?.getDeclaredMethodOrNull(
            "setChecked",
            Boolean::class.javaPrimitiveType!!
        )
        val getPackageName = bubbleAppClass?.getDeclaredMethodOrNull("getPackageName")
        if (contextField == null || currentUserField == null || bubbleAppConstructor == null ||
            setChecked == null || getPackageName == null
        ) {
            DebugLog.w(TAG, "bubble app management reflection unavailable; list expansion skipped")
            return
        }
        val getInstalledBubbleApps = CompatibleMethodResolver.find(
            bubblesSettings,
            "getInstalledBubbleApps",
            returnType = List::class.java
        )

        // getDefaultBubbles() is called from initBubbleApps() during the BubblesSettings
        // constructor. Deoptimize the call chain because both helpers are small/private on this
        // baseline and can otherwise be inlined before the hook is installed.
        deoptimize(getDefaultBubbles)
        CompatibleMethodResolver.find(
            bubblesSettings,
            "initBubbleApps",
            returnType = Void.TYPE
        )?.let(::deoptimize)
        getInstalledBubbleApps?.let(::deoptimize)
        bubblesSettings.declaredConstructors.singleOrNull {
            it.parameterTypes.contentEquals(arrayOf(Context::class.java))
        }?.let(::deoptimize)

        runCatching {
            getDefaultBubbles.hook {
                after { param ->
                    HookFailurePolicy.open(TAG, "getDefaultBubbles", Unit) {
                        val result = param.result as? ArrayMap<Any?, Any?> ?: return@open
                        val context = contextField.get(param.thisObject) as? Context ?: return@open
                        val userId = currentUserField.get(param.thisObject) as? Int ?: return@open
                        appendInstalledBubbleApps(
                            result = result,
                            context = context,
                            userId = userId,
                            bubbleAppConstructor = bubbleAppConstructor,
                            setChecked = setChecked
                        )
                    }
                }
            }
            getInstalledBubbleApps?.hook {
                after { param ->
                    HookFailurePolicy.open(TAG, "filter system bubble apps", Unit) {
                        val context = contextField.get(param.thisObject) as? Context
                            ?: return@open
                        @Suppress("UNCHECKED_CAST")
                        val result = param.result as? MutableList<Any?> ?: return@open
                        filterSystemApplications(
                            result = result,
                            context = context,
                            getPackageName = getPackageName
                        )
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, "$BUBBLES_SETTINGS#getDefaultBubbles()", it)
        }
    }

    private fun appendInstalledBubbleApps(
        result: ArrayMap<Any?, Any?>,
        context: Context,
        userId: Int,
        bubbleAppConstructor: Constructor<*>,
        setChecked: Method
    ) {
        val existingPackages = result.keys.filterIsInstance<String>().toHashSet()
        val packages = context.packageManager.getInstalledApplications(0)
            .asSequence()
            .filter(::isEligibleApplication)
            .map { it.packageName }
            .filter { it != SECURITY_CENTER_PACKAGE }
            .filter(existingPackages::add)
            .sorted()
            .toList()

        packages.forEach { packageName ->
            val bubbleApp = bubbleAppConstructor.newInstance(packageName, userId)
            setChecked.invoke(bubbleApp, true)
            result[packageName] = bubbleApp
        }
        DebugLog.i(TAG, "added ${packages.size} installed apps to Security Center bubble list")
    }

    private fun isEligibleApplication(info: ApplicationInfo): Boolean =
        info.enabled &&
            (info.flags and ApplicationInfo.FLAG_INSTALLED) != 0 &&
            (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
            info.uid >= Process.FIRST_APPLICATION_UID

    private fun filterSystemApplications(
        result: MutableList<Any?>,
        context: Context,
        getPackageName: Method
    ) {
        val systemPackages = context.packageManager.getInstalledApplications(0)
            .asSequence()
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 }
            .map { it.packageName }
            .toHashSet()
        result.removeAll { item ->
            runCatching {
                (getPackageName.invoke(item) as? String)?.let(systemPackages::contains) == true
            }.getOrDefault(false)
        }
    }

    /** Returns null on reflection failure so the stock gate remains in force. */
    private fun isBubbleAppMapped(
        manager: Any?,
        managerSettingsField: Field,
        appMapField: Field,
        packageName: String
    ): Boolean? = HookFailurePolicy.open(TAG, "read bubble app map", null) {
        val bubblesSettings = managerSettingsField.get(manager) ?: return@open null
        val appMap = appMapField.get(bubblesSettings) as? Map<*, *> ?: return@open null
        appMap.containsKey(packageName)
    }

    /** Reads the app switch used by SecurityCoreAdd; absent entries remain on the stock map path. */
    private fun readSecureBubbleAppState(
        manager: Any?,
        managerSettingsField: Field?,
        settingsContextField: Field?,
        packageName: String
    ): Boolean? = HookFailurePolicy.open(TAG, "read secure bubble app state", null) {
        val settings = managerSettingsField?.get(manager) ?: return@open null
        val context = settingsContextField?.get(settings) as? Context ?: return@open null
        val serialized = Settings.Secure.getString(
            context.contentResolver,
            SECURE_BUBBLE_APP_SETTINGS
        ) ?: return@open null
        serialized.split(',').asSequence()
            .mapNotNull { entry ->
                val separator = entry.lastIndexOf(':')
                if (separator <= 0 || separator == entry.lastIndex) return@mapNotNull null
                if (entry.substring(0, separator) != packageName) return@mapNotNull null
                when (entry.substring(separator + 1)) {
                    "1" -> true
                    "0" -> false
                    else -> null
                }
            }
            .firstOrNull()
    }

    private fun Class<*>.getDeclaredFieldOrNull(name: String): Field? =
        runCatching { getDeclaredField(name).apply { isAccessible = true } }.getOrNull()

    private fun Class<*>.getDeclaredConstructorOrNull(vararg parameterTypes: Class<*>): Constructor<*>? =
        runCatching { getDeclaredConstructor(*parameterTypes).apply { isAccessible = true } }.getOrNull()

    private fun Class<*>.getDeclaredMethodOrNull(
        name: String,
        vararg parameterTypes: Class<*>
    ): Method? = runCatching { getDeclaredMethod(name, *parameterTypes).apply { isAccessible = true } }.getOrNull()

    /** Deoptimizes the notification callback that may inline shouldHeadUp and its private gate. */
    private fun deoptimizeNotificationCaller() {
        val controller = NOTIFICATION_CONTROLLER.toClassOrNull() ?: run {
            DebugLog.w(TAG, "$NOTIFICATION_CONTROLLER not found; caller deoptimization skipped")
            return
        }
        val posted = CompatibleMethodResolver.find(
            controller,
            "onNotificationPosted",
            returnType = Void.TYPE,
            parameterTypes = listOf(
                StatusBarNotification::class.java,
                NotificationListenerService.RankingMap::class.java
            )
        ) ?: run {
            DebugLog.w(TAG, "$NOTIFICATION_CONTROLLER#onNotificationPosted caller not found")
            return
        }
        deoptimize(posted)
    }
}
