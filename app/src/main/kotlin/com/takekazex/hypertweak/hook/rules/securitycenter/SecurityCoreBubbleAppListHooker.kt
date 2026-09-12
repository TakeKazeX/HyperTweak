@file:Suppress("QueryPermissionsNeeded")

package com.takekazex.hypertweak.hook.rules.securitycenter

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.CompatibleMethodResolver
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * Expands the bubble-app list used by the newer SecurityCoreAdd settings activity.
 *
 * SecurityCoreAdd stores its app switches in Settings.Secure/miui_bubble_app_settings and rebuilds
 * a process-local list in BubblesSettings.getSupportBubbleApps(Context). Appending to that same
 * list keeps the added rows visible to the management activity and makes its existing save path
 * persist their individual switch states.
 */
object SecurityCoreBubbleAppListHooker : StaticHooker() {
    private const val TAG = "SecurityCoreBubbleAppList"

    private const val BUBBLES_SETTINGS =
        "com.miui.freeform.settings.bubble.BubblesSettings"
    private const val BUBBLE_APP =
        "com.miui.freeform.settings.bubble.entity.MiuiBubbleApp"
    private const val BUBBLE_MANAGE_ACTIVITY =
        "com.miui.freeform.settings.bubble.BubbleAppManageActivity"

    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    override fun onHook() {
        if (!Preferences.getBoolean(
                Preferences.KEY_SECURITY_CENTER_BUBBLE_NOTIFICATION_UNLOCK,
                false
            )
        ) {
            DebugLog.hookSkipped(TAG, "SecurityCore bubble app list", "disabled")
            return
        }

        val settings = BUBBLES_SETTINGS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, BUBBLES_SETTINGS, "class not found")
            return
        }
        val getSupportBubbleApps = CompatibleMethodResolver.find(
            settings,
            "getSupportBubbleApps",
            returnType = List::class.java,
            parameterTypes = listOf(Context::class.java)
        ) ?: run {
            DebugLog.hookSkipped(
                TAG,
                "$BUBBLES_SETTINGS#getSupportBubbleApps(Context)",
                "method not found"
            )
            return
        }

        val bubbleAppClass = BUBBLE_APP.toClassOrNull()
        val constructor = bubbleAppClass?.getDeclaredConstructorOrNull(
            String::class.java,
            Int::class.javaPrimitiveType!!
        )
        val setChecked = bubbleAppClass?.getDeclaredMethodOrNull(
            "setChecked",
            Boolean::class.javaPrimitiveType!!
        )
        val getPackageName = bubbleAppClass?.getDeclaredMethodOrNull("getPackageName")
        if (constructor == null || setChecked == null || getPackageName == null) {
            DebugLog.w(TAG, "SecurityCore bubble app reflection unavailable; list expansion skipped")
            return
        }
        val getInstalledBubbleApps = CompatibleMethodResolver.find(
            settings,
            "getInstalledBubbleApps",
            returnType = List::class.java,
            parameterTypes = listOf(Context::class.java)
        )

        // The settings activity calls getInstalledBubbleApps(), which calls this method. Deoptimize
        // both methods and the one-line AsyncTask caller so ART cannot inline past the hook.
        deoptimize(getSupportBubbleApps)
        getInstalledBubbleApps?.let(::deoptimize)
        BUBBLE_MANAGE_ACTIVITY.toClassOrNull()
            ?.declaredClasses
            ?.filter { it.simpleName == "LoadBubbleAppTask" }
            ?.flatMap { inner ->
                inner.declaredMethods.filter { it.name == "doInBackground" }
            }
            ?.forEach(::deoptimize)

        runCatching {
            getSupportBubbleApps.hook {
                after { param ->
                    HookFailurePolicy.open(TAG, "getSupportBubbleApps", Unit) {
                        val context = param.args.getOrNull(0) as? Context ?: return@open
                        @Suppress("UNCHECKED_CAST")
                        val result = param.result as? MutableList<Any?> ?: return@open
                        appendInstalledApps(
                            result = result,
                            context = context,
                            constructor = constructor,
                            setChecked = setChecked,
                            getPackageName = getPackageName
                        )
                    }
                }
            }
            getInstalledBubbleApps?.hook {
                after { param ->
                    HookFailurePolicy.open(TAG, "filter system bubble apps", Unit) {
                        val context = param.args.getOrNull(0) as? Context ?: return@open
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
            DebugLog.hookFailed(
                TAG,
                "$BUBBLES_SETTINGS#getSupportBubbleApps(Context)",
                it
            )
        }
    }

    private fun appendInstalledApps(
        result: MutableList<Any?>,
        context: Context,
        constructor: Constructor<*>,
        setChecked: Method,
        getPackageName: Method
    ) {
        val existingPackages = result.mapNotNull { item ->
            runCatching { getPackageName.invoke(item) as? String }.getOrNull()
        }.toHashSet()
        val packages = context.packageManager.getInstalledApplications(0)
            .asSequence()
            .filter(::isEligibleApplication)
            .map { it.packageName }
            .filter { it != context.packageName }
            .filter(existingPackages::add)
            .sorted()
            .toList()

        val userId = runCatching {
            Class.forName("android.os.UserHandle")
                .getMethod("myUserId")
                .invoke(null) as? Int
        }.getOrNull() ?: 0
        packages.forEach { packageName ->
            val bubbleApp = constructor.newInstance(packageName, userId)
            setChecked.invoke(bubbleApp, true)
            result.add(bubbleApp)
        }
        DebugLog.i(TAG, "added ${packages.size} installed apps to SecurityCore bubble list")
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

    private fun Class<*>.getDeclaredConstructorOrNull(
        vararg parameterTypes: Class<*>
    ): Constructor<*>? = runCatching {
        getDeclaredConstructor(*parameterTypes).apply { isAccessible = true }
    }.getOrNull()

    private fun Class<*>.getDeclaredMethodOrNull(
        name: String,
        vararg parameterTypes: Class<*>
    ): Method? = runCatching {
        getDeclaredMethod(name, *parameterTypes).apply { isAccessible = true }
    }.getOrNull()
}
