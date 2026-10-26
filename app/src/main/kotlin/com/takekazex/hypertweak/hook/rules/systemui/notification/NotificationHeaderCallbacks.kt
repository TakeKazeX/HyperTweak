package com.takekazex.hypertweak.hook.rules.systemui.notification

import android.content.pm.ApplicationInfo
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.util.logging.DebugLog
import java.io.File
import java.lang.reflect.Method
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType

/** Optional callback contracts; the hook owner alone installs and retires the resolved methods. */
internal object NotificationHeaderCallbacks {
    private const val TAG = "NotificationHeader"
    private const val CONFIG_CALLBACK_OWNER_MARKER =
        "com.android.systemui.controlcenter.shade.NotificationHeaderExpandController\$configurationControllerCallback"
    private const val COMBINED_HEADER_CONFIG_CALLBACK_OWNER_MARKER =
        "com.android.systemui.controlcenter.shade.CombinedHeaderController\$configurationListener"
    private const val NOTIFICATION_EXPANSION_CALLBACK_OWNER_MARKER =
        "com.android.systemui.controlcenter.shade.NotificationHeaderExpandController\$notificationCallback"
    private const val COMBINED_EXPANSION_CALLBACK_OWNER_MARKER =
        "com.android.systemui.controlcenter.shade.CombinedHeaderExpandController\$notificationCallback"
    data class GeneratedCallbacks(
        val notificationExpansion: Method?,
        val combinedExpansion: Method?,
        val combinedHeaderConfiguration: Method?,
        val expandConfiguration: Method?
    )

    fun resolve(apkPath: String, dexCacheDirectory: File?, classLoader: ClassLoader,
        appInfo: ApplicationInfo?): GeneratedCallbacks? {
        val methods = DexRuntime.resolveMethodMap(
            "systemui.notification_header_callbacks", apkPath, dexCacheDirectory, classLoader, appInfo,
            validate = { key, method ->
                val contract = when (key) {
                    "notification" -> NOTIFICATION_EXPANSION_CALLBACK_OWNER_MARKER to "onExpansionChanged"
                    "combined" -> COMBINED_EXPANSION_CALLBACK_OWNER_MARKER to "onStretchHeightChanged"
                    "header" -> COMBINED_HEADER_CONFIG_CALLBACK_OWNER_MARKER to "onConfigChanged"
                    "expand" -> CONFIG_CALLBACK_OWNER_MARKER to "onConfigChanged"
                    else -> "" to ""
                }
                contract.second.isNotEmpty() && method.declaringClass.name.contains(contract.first) &&
                    method.name == contract.second && method.parameterCount == 1 &&
                    method.returnType == Void.TYPE && !java.lang.reflect.Modifier.isStatic(method.modifiers)
            },
        ) { bridge ->
            val targets = GeneratedCallbacks(
                notificationExpansion = resolveGeneratedCallback(
                    bridge,
                    NOTIFICATION_EXPANSION_CALLBACK_OWNER_MARKER,
                    "onExpansionChanged", classLoader
                ),
                combinedExpansion = resolveGeneratedCallback(
                    bridge,
                    COMBINED_EXPANSION_CALLBACK_OWNER_MARKER,
                    "onStretchHeightChanged", classLoader
                ),
                combinedHeaderConfiguration = resolveGeneratedCallback(
                    bridge,
                    COMBINED_HEADER_CONFIG_CALLBACK_OWNER_MARKER,
                    "onConfigChanged", classLoader
                ),
                expandConfiguration = resolveGeneratedCallback(
                    bridge,
                    CONFIG_CALLBACK_OWNER_MARKER,
                    "onConfigChanged", classLoader
                )
            )
            mapOf("notification" to targets.notificationExpansion, "combined" to targets.combinedExpansion,
                "header" to targets.combinedHeaderConfiguration, "expand" to targets.expandConfiguration)
                .filterValues { it != null }.mapValues { requireNotNull(it.value) }
        } ?: return null
        return GeneratedCallbacks(methods["notification"], methods["combined"], methods["header"], methods["expand"])
    }

    private fun resolveGeneratedCallback(
        bridge: DexKitBridge,
        ownerMarker: String,
        methodName: String,
        classLoader: ClassLoader
    ): Method? {
        val candidates = bridge.findMethod {
            matcher {
                declaredClass {
                    className(ownerMarker, StringMatchType.Contains)
                }
                name(methodName)
                paramCount(1)
                returnType(Void.TYPE)
            }
        }.toList().filter { data ->
            data.className.contains(ownerMarker) && data.methodName == methodName &&
                data.paramCount == 1 && data.returnTypeName == Void.TYPE.name
        }.mapNotNull { data ->
            runCatching { data.getMethodInstance(classLoader) }
                .onFailure { DebugLog.w(TAG, "failed to inspect ${data.className}#${data.methodName}", it) }
                .getOrNull()
        }.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
        return candidates.singleOrNull()
    }

}
