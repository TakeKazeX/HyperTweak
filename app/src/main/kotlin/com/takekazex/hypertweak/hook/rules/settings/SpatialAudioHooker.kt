package com.takekazex.hypertweak.hook.rules.settings

import android.util.Log
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.base.CompatibleMethodResolver
import com.takekazex.hypertweak.hook.base.HookFailurePolicy

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
        val preferenceClass = sequenceOf("androidx.preference.Preference", "android.preference.Preference")
            .mapNotNull { it.toClassOrNull() }.firstOrNull()
        val onPrefChange = preferenceClass?.let {
            CompatibleMethodResolver.find(
                clazz, "onPreferenceChange", Boolean::class.javaPrimitiveType,
                listOf(it, Any::class.java)
            )
        }
        if (onPrefChange != null) {
            Log.d("HyperTweak", "SpatialAudioHooker: Hooking onPreferenceChange")
            onPrefChange.hook {
                before { param ->
                    HookFailurePolicy.open("SpatialAudio", "onPreferenceChange", Unit) {
                        val disabled = Preferences.getBoolean(Preferences.KEY_DISABLE_SPATIAL_AUDIO, false)
                        if (disabled && param.args[1] == true) {
                            Log.d("HyperTweak", "SpatialAudioHooker: Blocking spatial audio enable")
                            param.result = false
                        }
                    }
                }
            }
            return
        }

        val compoundButton = "android.widget.CompoundButton".toClassOrNull()
        val onCheckedChanged = compoundButton?.let {
            CompatibleMethodResolver.find(
                clazz, "onCheckedChanged", Void.TYPE,
                listOf(it, Boolean::class.javaPrimitiveType!!)
            )
        }
        if (onCheckedChanged != null) {
            Log.d("HyperTweak", "SpatialAudioHooker: Hooking onCheckedChanged")
            onCheckedChanged.hook {
                before { param ->
                    HookFailurePolicy.open("SpatialAudio", "onCheckedChanged", Unit) {
                        val disabled = Preferences.getBoolean(Preferences.KEY_DISABLE_SPATIAL_AUDIO, false)
                        if (disabled && param.args[1] == true) {
                            Log.d("HyperTweak", "SpatialAudioHooker: Blocking spatial audio enable via onCheckedChanged")
                            param.result = null
                        }
                    }
                }
            }
            return
        }

        Log.e("HyperTweak", "SpatialAudioHooker: No suitable toggle method found in ${clazz.name}")
    }
}
