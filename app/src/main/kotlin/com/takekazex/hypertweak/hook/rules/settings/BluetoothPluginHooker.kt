package com.takekazex.hypertweak.hook.rules.settings

import android.util.Log
import android.widget.TextView
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
        hookAncText()
    }

    private fun hookAncText() {
        val textView = "android.widget.TextView".toClassOrNull() ?: return
        textView.declaredMethods.filter { it.name == "setText" && it.parameterTypes.size == 1 }
            .forEach { method -> method.hook {
                after { param -> runCatching {
                    val view = param.thisObject as? TextView ?: return@runCatching
                    if (view.text?.toString() != "关闭" || !isAncView(view)) return@runCatching
                    view.text = "自适应"
                } }
            } }
    }

    private fun isAncView(view: TextView): Boolean {
        val idName = runCatching {
            if (view.id == 0 || view.id == -1) "" else view.resources.getResourceEntryName(view.id)
        }.getOrDefault("")
        if (idName.contains("anc", true) || idName.contains("noise", true)) return true
        var parent = view.parent
        repeat(5) {
            val p = parent ?: return@repeat
            val name = p.javaClass.name
            if (name.contains("headset", true) || name.contains("anc", true) || name.contains("noise", true)) return true
            parent = p.parent
        }
        return false
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

        // Bluetooth settings may be supplied by a dynamically loaded plugin. Hook the
        // shared PreferenceGroup boundary so entries are removed after construction,
        // regardless of which fragment/plugin created them.
        val groupClass = prefClass.classLoader?.loadClass(
            if (prefClass.name.startsWith("androidx.")) "androidx.preference.PreferenceGroup"
            else "android.preference.PreferenceGroup"
        )
        groupClass?.declaredMethods?.filter {
            it.name == "addPreference" && it.parameterTypes.size == 1
        }?.forEach { add ->
            add.hook {
                after { param -> runCatching {
                    val child = param.args.firstOrNull() ?: return@runCatching
                    val key = invokeString(child, "getKey")
                    val title = invokeString(child, "getTitle")
                    val summary = invokeString(child, "getSummary")
                    val parentKey = invokeString(param.thisObject, "getKey")
                    if (isSpatial(key, title, summary)) {
                        invokeBoolean(param.thisObject, "removePreference", child)
                        Log.d(TAG, "BluetoothPluginHooker: removed spatial preference key=$key title=$title")
                    } else if (isAnc(key, parentKey, title, summary) &&
                        (title == "关闭" || title?.contains("关闭") == true)) {
                        invokeTitle(child, "自适应")
                    }
                }.onFailure { t -> Log.e(TAG, "PreferenceGroup hook failed", t) } }
            }
        }
    }

    private fun isSpatial(key: String?, title: String?, summary: String?): Boolean {
        val text = listOf(key, title, summary).filterNotNull().joinToString(" ")
        return text.contains("spatial", true) || text.contains("head_tracking", true) ||
            text.contains("head tracking", true) || text.contains("空间音频") ||
            text.contains("头部跟踪") || text.contains("3d_audio", true) ||
            text.contains("3D 沉浸感") || text.contains("空间感")
    }

    private fun isAnc(key: String?, parentKey: String?, title: String?, summary: String?): Boolean {
        val text = listOf(key, parentKey, title, summary).filterNotNull().joinToString(" ")
        return text.contains("anc", true) || text.contains("降噪") || text.contains("noise", true)
    }

    private fun invokeString(target: Any, method: String): String? = runCatching {
        target.javaClass.methods.firstOrNull { it.name == method && it.parameterTypes.isEmpty() }
            ?.invoke(target)?.toString()
    }.getOrNull()

    private fun invokeBoolean(target: Any, method: String, arg: Any) {
        target.javaClass.methods.firstOrNull { it.name == method && it.parameterTypes.size == 1 }
            ?.invoke(target, arg)
    }

    private fun invokeTitle(target: Any, title: String) {
        target.javaClass.methods.firstOrNull { it.name == "setTitle" && it.parameterTypes.size == 1 }
            ?.invoke(target, title)
    }
}
