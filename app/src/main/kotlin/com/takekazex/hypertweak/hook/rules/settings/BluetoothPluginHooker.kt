package com.takekazex.hypertweak.hook.rules.settings

import android.util.Log
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker

object BluetoothPluginHooker : StaticHooker() {
    private const val TAG = "HyperTweak"

    override fun onHook() {
        Log.d(TAG, "BluetoothPluginHooker: onHook()")

        val prefClass = "androidx.preference.Preference".toClassOrNull()
        if (prefClass != null) {
            hookPreferenceClass(prefClass)
        } else {
            val legacyPrefClass = "android.preference.Preference".toClassOrNull()
            if (legacyPrefClass != null) {
                hookPreferenceClass(legacyPrefClass)
            } else {
                Log.e(TAG, "BluetoothPluginHooker: Neither androidx nor legacy Preference class found")
            }
        }
    }

    private fun hookPreferenceClass(prefClass: Class<*>) {
        val callChangeListener = prefClass.declaredMethods.firstOrNull {
            it.name == "callChangeListener" && it.parameterTypes.size == 1
        }
        if (callChangeListener == null) {
            Log.e(TAG, "BluetoothPluginHooker: callChangeListener not found")
            return
        }

        Log.d(TAG, "BluetoothPluginHooker: Hooking ${prefClass.simpleName}.callChangeListener")
        callChangeListener.hook {
            before { param ->
                runCatching {
                    if (!Preferences.getBoolean(Preferences.KEY_DISABLE_SPATIAL_AUDIO, false)) return@before

                    val pref = param.thisObject
                    val key = runCatching {
                        pref.javaClass.getMethod("getKey").invoke(pref) as? String
                    }.getOrNull()

                    if (key == null) return@before
                    val isSpatial = key.contains("spatial", ignoreCase = true) ||
                        key.contains("空间") ||
                        key.contains("3d_audio", ignoreCase = true) ||
                        key.contains("head_tracking", ignoreCase = true)

                    if (!isSpatial) return@before

                    val newValue = param.args[0]
                    if (newValue == true) {
                        Log.d(TAG, "BluetoothPluginHooker: Blocking spatial audio enable (key=$key)")
                        param.result = false
                    }
                }.onFailure { t ->
                    Log.e(TAG, "BluetoothPluginHooker: Error in callChangeListener hook", t)
                }
            }
        }
    }
}
