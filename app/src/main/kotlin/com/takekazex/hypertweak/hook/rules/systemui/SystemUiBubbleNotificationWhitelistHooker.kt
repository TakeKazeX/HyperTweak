package com.takekazex.hypertweak.hook.rules.systemui

import android.content.Context
import android.content.ContentResolver
import android.provider.Settings
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.CompatibleMethodResolver
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Removes the second, SystemUI-side app-map check for MIUI bubble notifications.
 *
 * `MiuiBubbleSettings.isAppEnableBubbleNotification()` checks both the app map and the in-memory
 * active-bubble set. The Security Center/ SecurityCore hooks expand the app-management list, but
 * this separate map is initialized by the WindowManager-Shell library inside SystemUI. When an
 * edge-pinned app is absent from the stock map, the method returns false before the notification
 * can reach that existing bubble. This hook lets an active edge bubble pass when its secure app
 * switch is enabled or absent, while retaining the active-bubble and per-app-off requirements.
 */
object SystemUiBubbleNotificationWhitelistHooker : StaticHooker() {
    private const val TAG = "SystemUiBubbleWhitelist"

    private const val BUBBLE_SETTINGS =
        "com.android.wm.shell.multitasking.miuifreeform.miuibubbles.settings.MiuiBubbleSettings"
    private const val BUBBLE =
        "com.android.wm.shell.multitasking.miuifreeform.miuibubbles.MiuiBubble"
    private const val BUBBLE_MANAGER =
        "com.android.systemui.statusbar.notification.collection.coordinator.MiuiBubbleManager"
    private const val VISUAL_INTERRUPTION_INJECTOR =
        "com.android.systemui.statusbar.notification.interruption.VisualInterruptionDecisionProviderImplInjector"

    private const val ACTIVE_BUBBLES_FIELD = "mActiveBubbles"
    private const val CONTEXT_FIELD = "mContext"
    private const val SECURE_BUBBLE_APP_SETTINGS = "miui_bubble_app_settings"

    private var currentApplicationMethod: Method? = null
    private var secureStringForUserMethod: Method? = null

    private enum class SecureAppState {
        ABSENT,
        ENABLED,
        DISABLED,
        UNAVAILABLE
    }

    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    override fun onHook() {
        if (!Preferences.getBoolean(
                Preferences.KEY_SECURITY_CENTER_BUBBLE_NOTIFICATION_UNLOCK,
                false
            )
        ) {
            DebugLog.hookSkipped(TAG, "SystemUI bubble notification whitelist", "disabled")
            return
        }

        val settings = BUBBLE_SETTINGS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, BUBBLE_SETTINGS, "class not found")
            return
        }
        val isAppEnabled = CompatibleMethodResolver.find(
            settings,
            "isAppEnableBubbleNotification",
            returnType = Boolean::class.javaPrimitiveType,
            parameterTypes = listOf(
                String::class.java,
                Int::class.javaPrimitiveType!!
            )
        ) ?: run {
            DebugLog.hookSkipped(
                TAG,
                "$BUBBLE_SETTINGS#isAppEnableBubbleNotification(String,int)",
                "method not found"
            )
            return
        }

        val activeBubblesField = settings.getDeclaredFieldOrNull(ACTIVE_BUBBLES_FIELD) ?: run {
            DebugLog.hookSkipped(TAG, "$BUBBLE_SETTINGS#$ACTIVE_BUBBLES_FIELD", "field not found")
            return
        }
        val bubbleClass = BUBBLE.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, BUBBLE, "class not found")
            return
        }
        val packageNameMethod = bubbleClass.getDeclaredMethodOrNull("getPackageName") ?: run {
            DebugLog.hookSkipped(TAG, "$BUBBLE#getPackageName", "method not found")
            return
        }
        val userIdMethod = bubbleClass.getDeclaredMethodOrNull("getUserId") ?: run {
            DebugLog.hookSkipped(TAG, "$BUBBLE#getUserId", "method not found")
            return
        }
        val contextField = settings.getDeclaredFieldOrNull(CONTEXT_FIELD)

        // The target is tiny and is called from both the bubble coordinator and the normal
        // heads-up decision path. Deoptimize the verified direct callers so ART cannot retain the
        // original false result through an inlined call site.
        deoptimize(isAppEnabled)
        deoptimizeBubbleCallers()

        runCatching {
            isAppEnabled.hook {
                after { param ->
                    HookFailurePolicy.open(TAG, "isAppEnableBubbleNotification", Unit) {
                        if (!Preferences.getBoolean(
                                Preferences.KEY_SECURITY_CENTER_BUBBLE_NOTIFICATION_UNLOCK,
                                false
                            )
                        ) {
                            return@open
                        }
                        val packageName = param.args.getOrNull(0) as? String ?: return@open
                        val userId = param.args.getOrNull(1) as? Int ?: return@open
                        val context = resolveContext(contextField) ?: return@open
                        when (readSecureAppState(context, packageName, userId)) {
                            SecureAppState.DISABLED -> param.result = false
                            SecureAppState.ABSENT,
                            SecureAppState.ENABLED -> {
                                // The original method already refreshed this set before the
                                // after-hook runs. Keep the real edge/inflated-bubble gate intact.
                                if (hasActiveBubble(
                                        activeBubblesField,
                                        packageNameMethod,
                                        userIdMethod,
                                        packageName,
                                        userId
                                    )
                                ) {
                                    param.result = true
                                }
                            }
                            SecureAppState.UNAVAILABLE -> Unit
                        }
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(
                TAG,
                "$BUBBLE_SETTINGS#isAppEnableBubbleNotification(String,int)",
                it
            )
            return
        }

        DebugLog.i(
            TAG,
            "SystemUI bubble app-map gate removed; active edge bubble and per-app-off gates kept"
        )
    }

    private fun deoptimizeBubbleCallers() {
        BUBBLE_MANAGER.toClassOrNull()?.let { manager ->
            manager.declaredMethods
                .filter { it.name.contains("shouldHeadUp") && it.returnType == Boolean::class.javaPrimitiveType }
                .forEach(::deoptimize)

            // The listener calls the synthetic shouldHeadUp helper. Deoptimize these callbacks as
            // well in case the helper was inlined before the module attached.
            manager.declaredClasses
                .filter { it.name.endsWith("\$4") }
                .flatMap { it.declaredMethods.asSequence() }
                .filter { it.name == "onEntryAdded" || it.name == "onEntryUpdated" }
                .forEach(::deoptimize)
        }
        VISUAL_INTERRUPTION_INJECTOR.toClassOrNull()?.declaredMethods
            ?.filter {
                it.name == "shouldPeek" &&
                    it.parameterTypes.size == 1 &&
                    it.returnType == Boolean::class.javaPrimitiveType
            }
            ?.forEach(::deoptimize)
    }

    private fun hasActiveBubble(
        activeBubblesField: Field,
        packageNameMethod: Method,
        userIdMethod: Method,
        packageName: String,
        userId: Int
    ): Boolean = HookFailurePolicy.open(TAG, "read active bubble set", false) {
        val activeBubbles = activeBubblesField.get(null) as? Iterable<*> ?: return@open false
        activeBubbles.any { bubble ->
            if (bubble == null) return@any false
            runCatching {
                packageNameMethod.invoke(bubble) == packageName &&
                    (userIdMethod.invoke(bubble) as? Int) == userId
            }.getOrDefault(false)
        }
    }

    private fun readSecureAppState(
        context: Context,
        packageName: String,
        userId: Int
    ): SecureAppState {
        val serialized: String? = runCatching {
            val method = secureStringForUserMethod ?: Settings.Secure::class.java
                .getDeclaredMethod(
                    "getStringForUser",
                    ContentResolver::class.java,
                    String::class.java,
                    Int::class.javaPrimitiveType
                )
                .apply {
                    isAccessible = true
                    secureStringForUserMethod = this
                }
            method.invoke(null, context.contentResolver, SECURE_BUBBLE_APP_SETTINGS, userId) as? String
        }.onFailure {
            DebugLog.w(TAG, "failed to read per-app bubble setting", it)
        }.getOrElse { return SecureAppState.UNAVAILABLE }

        if (serialized.isNullOrBlank()) return SecureAppState.ABSENT
        for (entry in serialized.split(',')) {
            val separator = entry.lastIndexOf(':')
            if (separator <= 0 || separator == entry.lastIndex) continue
            if (entry.substring(0, separator) != packageName) continue
            return when (entry.substring(separator + 1)) {
                "1" -> SecureAppState.ENABLED
                "0" -> SecureAppState.DISABLED
                else -> SecureAppState.UNAVAILABLE
            }
        }
        return SecureAppState.ABSENT
    }

    private fun resolveContext(contextField: Field?): Context? {
        val stored = runCatching {
            (contextField?.get(null) as? WeakReference<*>)?.get() as? Context
        }.getOrNull()
        if (stored != null) return stored

        return runCatching {
            val method = currentApplicationMethod ?: Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication")
                .apply {
                    isAccessible = true
                    currentApplicationMethod = this
                }
            method.invoke(null) as? Context
        }.getOrNull()
    }

    private fun Class<*>.getDeclaredFieldOrNull(name: String): Field? =
        runCatching { getDeclaredField(name).apply { isAccessible = true } }.getOrNull()

    private fun Class<*>.getDeclaredMethodOrNull(name: String): Method? =
        runCatching { getDeclaredMethod(name).apply { isAccessible = true } }.getOrNull()
}
