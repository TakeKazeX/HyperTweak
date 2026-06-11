package com.takekazex.hypertweak.hook.rules.system

import android.util.Log
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import org.luckypray.dexkit.query.enums.StringMatchType

object SpatialAudioBlockerHooker : StaticHooker() {
    private const val TAG = "HyperTweak"

    override fun onHook() {
        Log.d(TAG, "SpatialAudioBlockerHooker: onHook() pkg=${hookParam.packageName}")

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
        val clazz = resolveAppClass(
            "AirCoreManager",
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
            )
        ) ?: return

        Log.d(TAG, "SpatialAudioBlockerHooker: Found AirCoreManager: ${clazz.name}, methods:")
        clazz.declaredMethods.forEach { m ->
            Log.d(TAG, "  ${m.name}(${m.parameterTypes.joinToString { it.simpleName }}) ret=${m.returnType.simpleName}")
        }

        val setCommand = clazz.declaredMethods.firstOrNull {
            it.parameterTypes.size == 3
        } ?: clazz.declaredMethods.firstOrNull {
            it.parameterTypes.size == 2 &&
                it.parameterTypes.all { p -> p == String::class.java }
        }

        if (setCommand == null) {
            Log.e(TAG, "SpatialAudioBlockerHooker: No 2-param method found in ${clazz.name}")
            clazz.declaredMethods.forEach { m ->
                Log.d(TAG, "  ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
            }
            return
        }

        Log.d(TAG, "SpatialAudioBlockerHooker: Hooking ${setCommand.name}(${setCommand.parameterTypes.joinToString { it.simpleName }})")
        setCommand.hook {
            before { param ->
                runCatching {
                    val p0 = param.args[0]?.toString() ?: ""
                    val p1 = param.args[1]?.toString() ?: ""
                    Log.d(TAG, "SpatialAudioBlockerHooker: ${setCommand.name} called: p0=[$p0] p1=[$p1] p0.class=${param.args[0]?.javaClass?.simpleName} p1.class=${param.args[1]?.javaClass?.simpleName}")

                    if (p0.contains("air_anc") || p1.contains("air_anc")) {
                        if (p1 == "01" && Preferences.getBoolean(Preferences.KEY_FORCE_ADAPTIVE_ANC, false)) {
                            Log.d(TAG, "SpatialAudioBlockerHooker: Remapping OFF(01) → ADAPTIVE(04)")
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
        val clazz = resolveAppClass(
            "AudioEffectCenter",
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
            )
        ) ?: return

        Log.d(TAG, "SpatialAudioBlockerHooker: Found AudioEffectCenter: ${clazz.name}")

        val setEffectActive = clazz.declaredMethods.firstOrNull {
            it.name == "setEffectActive" && it.parameterTypes.size == 2
        } ?: return

        Log.d(TAG, "SpatialAudioBlockerHooker: Hooking setEffectActive(${setEffectActive.parameterTypes.joinToString { it.simpleName }})")
        setEffectActive.hook {
            before { param ->
                runCatching {
                    if (!Preferences.getBoolean(Preferences.KEY_DISABLE_SPATIAL_AUDIO, false)) return@before
                    val effect = param.args[0] as? String ?: return@before
                    val active = param.args[1] as? Boolean ?: return@before
                    if (effect.contains("spatial", ignoreCase = true) && active) {
                        Log.d(TAG, "SpatialAudioBlockerHooker: Blocking spatial audio (effect=$effect)")
                        param.args[1] = false
                    }
                }
            }
        }
    }
}
