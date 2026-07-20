package com.takekazex.hypertweak.hook.rules.system

import android.util.Log
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.CompatibleMethodResolver
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType

object SpatialAudioBlockerHooker : StaticHooker() {
    private const val TAG = "HyperTweak"

    override fun onHook() {
        when (hookParam.packageName) {
            "com.xiaomi.bluetooth" -> {
                hookAirCoreManager()
            }
            else -> {
                hookAudioEffectCenter()
            }
        }
    }

    private fun hookAirCoreManager() {
        val clazz = resolveFirstAppClass(
            mapOf(
                "AirCoreManager" to { bridge ->
                    bridge.findClass {
                        matcher { className("AirCoreManager", StringMatchType.EndsWith) }
                    }.singleOrNull()?.name
                },
                "AirCoreByString" to { bridge ->
                    bridge.findClass {
                        matcher { usingStrings("air_anc", "setCommand") }
                    }.singleOrNull()?.name
                }
            ),
            fallbackClassNames = listOf("AirCoreManager", "AirCoreByString")
        ) ?: return

        val setCommand = clazz.declaredMethods.filter {
            it.name == "setCommand" && it.parameterTypes.size in 2..3 &&
                it.parameterTypes.all { parameter -> parameter == String::class.java }
        }.singleOrNull()

        if (setCommand == null) {
            Log.e(TAG, "SpatialAudioBlockerHooker: No 2-param method found in ${clazz.name}")
            return
        }

        setCommand.hook {
            before { param ->
                runCatching {
                    val p0 = param.args[0]?.toString() ?: ""
                    val p1 = param.args[1]?.toString() ?: ""
                    if (p0.contains("air_anc") || p1.contains("air_anc")) {
                        if (p1 == "01" && Preferences.getBoolean(Preferences.KEY_FORCE_ADAPTIVE_ANC, false)) {
                            param.args[1] = "04"
                        }
                    }
                }.onFailure { t ->
                    Log.e(TAG, "SpatialAudioBlockerHooker: Error in hook", t)
                }
            }
        }
    }

    private fun hookAudioEffectCenter() {
        val clazz = resolveFirstAppClass(
            mapOf(
                "AudioEffectCenter" to { bridge ->
                    bridge.findClass {
                        matcher { className("AudioEffectCenter", StringMatchType.EndsWith) }
                    }.singleOrNull()?.name
                },
                "SpatialAudioPresenter" to { bridge ->
                    bridge.findClass {
                        matcher { usingStrings("setSpatialAudioActive") }
                    }.singleOrNull()?.name
                }
            ),
            fallbackClassNames = listOf("AudioEffectCenter", "SpatialAudioPresenter")
        ) ?: return

        val setEffectActive = CompatibleMethodResolver.find(
            clazz,
            "setEffectActive",
            parameterTypes = listOf(String::class.java, Boolean::class.javaPrimitiveType!!)
        ) ?: return
        setEffectActive.hook {
            before { param ->
                runCatching {
                    if (!Preferences.getBoolean(Preferences.KEY_DISABLE_SPATIAL_AUDIO, false)) return@before
                    val effect = param.args[0] as? String ?: return@before
                    val active = param.args[1] as? Boolean ?: return@before
                    if (effect.contains("spatial", ignoreCase = true) && active) {
                        param.args[1] = false
                    }
                }
            }
        }
    }

    private fun resolveFirstAppClass(
        queries: Map<String, (DexKitBridge) -> String?>,
        fallbackClassNames: List<String>
    ): Class<*>? {
        val appInfo = hookParam.appInfo
        val baseDir = appInfo?.deviceProtectedDataDir ?: appInfo?.dataDir
        val apkPath = appInfo?.sourceDir
        if (baseDir != null && apkPath != null) {
            val resolved = DexKitManager.resolveClasses(
                cacheDir = java.io.File(baseDir, "cache"),
                apkPath = apkPath,
                classLoader = classLoader,
                queries = queries,
                logMissingQueries = false
            )
            queries.keys.firstNotNullOfOrNull { resolved[it] }?.let { return it }
        }

        return fallbackClassNames.firstNotNullOfOrNull { it.toClassOrNull() }
    }
}
