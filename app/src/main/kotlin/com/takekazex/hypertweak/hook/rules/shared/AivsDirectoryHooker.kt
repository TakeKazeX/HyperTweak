package com.takekazex.hypertweak.hook.rules.shared

import android.os.Environment
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.AivsPathPolicy
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Modifier

/** Keep the SDK's native mkdir/return contract and apply current policy at every path consumer. */
object AivsDirectoryHooker : StaticHooker() {
    private val enabled by hookSetting(false) {
        Preferences.getBoolean(PlatformSettingsKeys.KEY_AIVS_REDIRECT, false)
    }

    override fun onHook() {
        val pkg = hookParam.packageName
        if (pkg !in AivsPathPolicy.packages) return
        val files = "com.xiaomi.aivsbluetoothsdk.utils.FileUtil".toClassOrNull() ?: return
        val voice = "com.xiaomi.aivsbluetoothsdk.voice.VoiceManager".toClassOrNull() ?: return
        val strings = Array(4) { String::class.java }
        val splice = files.declaredMethods.singleOrNull {
            it.name == "splicingFilePath" && Modifier.isStatic(it.modifiers) &&
                it.returnType == String::class.java && it.parameterTypes.contentEquals(strings)
        }
        val getters = listOf("getOutEncodePath", "getOutFilePath").map { name ->
            voice.declaredMethods.singleOrNull {
                it.name == name && !Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == 0 && it.returnType == String::class.java
            }
        }
        val start = voice.declaredMethods.singleOrNull {
            it.name == "startFileThread" && !Modifier.isStatic(it.modifiers) &&
                it.returnType == Void.TYPE && it.parameterTypes.contentEquals(Array(2) { String::class.java })
        }
        if (splice == null || getters.any { it == null } || start == null) {
            DebugLog.hookSkipped("AivsDirectory", "SDK paths", "missing or ambiguous path contract")
            return
        }
        // The SDK's constructor and writer callers can have these small boundaries AOT-inlined.
        // Retain native behavior if their execution cannot be brought back through the hooks.
        val callers = voice.declaredConstructors.toList() + voice.declaredMethods.filter {
            it.name in setOf("startFileThread", "setOutFilePath", "onStartInput")
        }
        if (!(callers + listOf(splice) + getters.filterNotNull()).all { deoptimize(it) }) {
            DebugLog.hookSkipped("AivsDirectory", "SDK paths", "caller deoptimization failed")
            return
        }
        @Suppress("DEPRECATION")
        val storageRoot = Environment.getExternalStorageDirectory().path
        fun map(path: String?) = AivsPathPolicy.audioPath(path, storageRoot, pkg, enabled)
        splice.hook("aivs_directory") {
            before { param ->
                HookFailurePolicy.open("AivsDirectory", "root path", Unit) {
                    param.args[0] = AivsPathPolicy.rootArgument(param.args[0] as? String, pkg, enabled)
                }
            }
        }
        getters.filterNotNull().forEach { method ->
            method.hook("aivs_cached_path") {
                after { param ->
                    HookFailurePolicy.open("AivsDirectory", "cached path", Unit) {
                        param.result = map(param.result as? String)
                    }
                }
            }
        }
        start.hook("aivs_audio_writer") {
            before { param ->
                HookFailurePolicy.open("AivsDirectory", "writer paths", Unit) {
                    for (index in 0..1) {
                        val original = param.args[index] as? String
                        val path = map(original)
                        // Old instances can predate this module generation and its mkdir hook.
                        if (path != original && path != null) java.io.File(path).parentFile?.mkdirs()
                        param.args[index] = path
                    }
                }
            }
        }
        DebugLog.i("AivsDirectory", "SDK path boundaries installed package=$pkg process=${hookParam.processName}")
    }
}
