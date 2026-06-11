package com.takekazex.hypertweak.hook.rules.system

import android.util.Log
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import org.luckypray.dexkit.query.enums.StringMatchType

object SpatialAudioBlockerHooker : StaticHooker() {
    private const val TAG = "HyperTweak"

    override fun onHook() {
        Log.d(TAG, "SpatialAudioBlockerHooker: onHook()")

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
                        matcher {
                            usingStrings("setSpatialAudioActive")
                        }
                    }.singleOrNull()?.name
                }
            )
        )

        if (clazz == null) {
            Log.e(TAG, "SpatialAudioBlockerHooker: QcomEffectPresenter not found")
            return
        }

        Log.d(TAG, "SpatialAudioBlockerHooker: Found class: ${clazz.name}")

        // Try AudioEffectCenter.setEffectActive(effect, active, from)
        val setEffectActive = clazz.declaredMethods.firstOrNull {
            it.name == "setEffectActive" && it.parameterTypes.size == 2
        }
        if (setEffectActive != null) {
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
                    }.onFailure { t ->
                        Log.e(TAG, "SpatialAudioBlockerHooker: Error in setEffectActive hook", t)
                    }
                }
            }
            return
        }

        Log.e(TAG, "SpatialAudioBlockerHooker: setEffectActive not found. Dumping methods:")
        clazz.declaredMethods.forEach { m ->
            Log.d(TAG, "  ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
        }

        // Fallback: setSpatialAudioActive(boolean)
        val setSpatial = clazz.declaredMethods.firstOrNull {
            it.name.contains("SpatialAudio", ignoreCase = true) &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Boolean::class.javaPrimitiveType
        }
        if (setSpatial != null) {
            Log.d(TAG, "SpatialAudioBlockerHooker: Hooking ${setSpatial.name}")
            setSpatial.hook {
                before { param ->
                    runCatching {
                        if (!Preferences.getBoolean(Preferences.KEY_DISABLE_SPATIAL_AUDIO, false)) return@before
                        val active = param.args[0] as? Boolean ?: return@before
                        if (active) {
                            Log.d(TAG, "SpatialAudioBlockerHooker: Blocking spatial audio activation")
                            param.args[0] = false
                        }
                    }
                }
            }
            return
        }
    }
}
