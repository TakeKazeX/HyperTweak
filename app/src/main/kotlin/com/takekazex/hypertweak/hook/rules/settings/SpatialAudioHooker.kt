package com.takekazex.hypertweak.hook.rules.settings

import android.util.Log
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.hook.base.DynamicHooker

class SpatialAudioHooker(
    private val pluginContext: android.content.Context? = null,
    private val pluginApkPath: String = ""
) : DynamicHooker() {

    override fun onHook() {
        Log.d("HyperTweak", "SpatialAudioHooker: onHook() called, pluginApkPath=$pluginApkPath")

        val spatialAudioClass = resolveSpatialAudioClass()
        if (spatialAudioClass == null) {
            Log.e("HyperTweak", "SpatialAudioHooker: Failed to resolve spatial audio class")
            return
        }

        Log.d("HyperTweak", "SpatialAudioHooker: Found class: ${spatialAudioClass.name}")

        hookToggleMethod(spatialAudioClass)
    }

    private fun resolveSpatialAudioClass(): Class<*>? {
        if (pluginApkPath.isNotEmpty() && pluginContext != null) {
            val resolved = DexKitManager.resolveClasses(
                cacheDir = pluginContext.cacheDir,
                apkPath = pluginApkPath,
                classLoader = classLoader,
                queries = mapOf(
                    "SpatialAudioCN" to { bridge ->
                        bridge.findClass {
                            matcher { usingStrings("空间音频") }
                        }.singleOrNull()?.name
                    },
                    "SpatialAudioEN" to { bridge ->
                        bridge.findClass {
                            matcher { usingStrings("Spatial Audio", "spatial_audio") }
                        }.singleOrNull()?.name
                    }
                )
            )
            val clazz = resolved["SpatialAudioCN"] ?: resolved["SpatialAudioEN"]
            if (clazz != null) return clazz
        }

        return resolveViaDirectLoad()
    }

    private fun resolveViaDirectLoad(): Class<*>? {
        val candidates = listOf(
            "com.android.settings.bluetooth.SpatialAudioPreferenceController",
            "com.android.settings.bluetooth.SpatialAudioController",
            "com.android.settings.connecteddevice.SpatialAudioPreferenceController",
            "com.xiaomi.bluetooth.spatialaudio.SpatialAudioController"
        )
        for (name in candidates) {
            val clazz = name.toClassOrNull()
            if (clazz != null) {
                Log.d("HyperTweak", "SpatialAudioHooker: Found via direct load: $name")
                return clazz
            }
        }
        return null
    }

    private fun hookToggleMethod(clazz: Class<*>) {
        // Try onPreferenceChange first (standard Preference callback)
        val onPrefChange = clazz.declaredMethods.firstOrNull { method ->
            method.name == "onPreferenceChange" &&
                method.parameterTypes.size == 2
        }
        if (onPrefChange != null) {
            Log.d("HyperTweak", "SpatialAudioHooker: Hooking onPreferenceChange")
            onPrefChange.hook {
                before { param ->
                    runCatching {
                        if (!Preferences.getBoolean(Preferences.KEY_DISABLE_SPATIAL_AUDIO, false)) return@before
                        val newValue = param.args[1]
                        if (newValue == true) {
                            Log.d("HyperTweak", "SpatialAudioHooker: Blocking spatial audio enable")
                            param.result = null
                        }
                    }.onFailure { t ->
                        Log.e("HyperTweak", "SpatialAudioHooker: Error in onPreferenceChange hook", t)
                    }
                }
            }
            return
        }

        // Fallback: try onCheckedChanged (CompoundButton listener)
        val onCheckedChanged = clazz.declaredMethods.firstOrNull { method ->
            method.name == "onCheckedChanged" &&
                method.parameterTypes.size == 2
        }
        if (onCheckedChanged != null) {
            Log.d("HyperTweak", "SpatialAudioHooker: Hooking onCheckedChanged")
            onCheckedChanged.hook {
                before { param ->
                    runCatching {
                        if (!Preferences.getBoolean(Preferences.KEY_DISABLE_SPATIAL_AUDIO, false)) return@before
                        val isChecked = param.args[1] as? Boolean ?: return@before
                        if (isChecked) {
                            Log.d("HyperTweak", "SpatialAudioHooker: Blocking spatial audio enable via onCheckedChanged")
                            param.result = null
                        }
                    }.onFailure { t ->
                        Log.e("HyperTweak", "SpatialAudioHooker: Error in onCheckedChanged hook", t)
                    }
                }
            }
            return
        }

        // Fallback: find any method that takes a boolean and could be a setter
        val setterMethod = clazz.declaredMethods.firstOrNull { method ->
            method.parameterTypes.size == 1 &&
                method.parameterTypes[0] == Boolean::class.javaPrimitiveType &&
                (method.name.contains("set", ignoreCase = true) ||
                    method.name.contains("enable", ignoreCase = true) ||
                    method.name.contains("update", ignoreCase = true))
        }
        if (setterMethod != null) {
            Log.d("HyperTweak", "SpatialAudioHooker: Hooking boolean setter: ${setterMethod.name}")
            setterMethod.hook {
                before { param ->
                    runCatching {
                        if (!Preferences.getBoolean(Preferences.KEY_DISABLE_SPATIAL_AUDIO, false)) return@before
                        val value = param.args[0] as? Boolean ?: return@before
                        if (value) {
                            Log.d("HyperTweak", "SpatialAudioHooker: Blocking spatial audio enable via ${setterMethod.name}")
                            param.args[0] = false
                        }
                    }.onFailure { t ->
                        Log.e("HyperTweak", "SpatialAudioHooker: Error in setter hook", t)
                    }
                }
            }
            return
        }

        Log.e("HyperTweak", "SpatialAudioHooker: No suitable toggle method found in ${clazz.name}")
        Log.d("HyperTweak", "SpatialAudioHooker: Available methods:")
        clazz.declaredMethods.forEach { method ->
            Log.d("HyperTweak", "  ${method.name}(${method.parameterTypes.joinToString { it.simpleName }})")
        }
    }
}
