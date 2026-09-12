@file:Suppress("SdCardPath")

package com.takekazex.hypertweak.hook.rules.downloads

import android.content.Context
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Prevents the CN Download Manager from materialising its optional Xunlei log directory on shared
 * storage. The download engine itself is left intact: only log-path creation is intercepted.
 *
 * On the current OS4 provider, [XLConfig.initLog] eventually calls `XLConfig.setDebug(Context,
 * Boolean, String)`, which calls `FileUtil.createFile` for the sibling `dp_so.log` path even when
 * debug logging is disabled. Both boundaries are covered so a later debug-service call cannot
 * recreate `.xlDownload` either.
 */
object DownloadXlLogDirectoryHooker : StaticHooker() {
    private const val TAG = "DownloadXlLogDirectory"
    private const val XL_CONFIG = "com.android.providers.downloads.config.XLConfig"
    private const val FILE_UTIL = "com.android.providers.downloads.util.FileUtil"

    private val targetDirectories = setOf(
        "/storage/emulated/0/.xlDownload",
        "/sdcard/.xlDownload"
    )

    override fun onHook() {
        if (!Preferences.getBoolean(Preferences.KEY_BLOCK_DOWNLOAD_XL_LOG_DIR, false)) {
            return
        }

        var installed = 0
        runCatching {
            val configClass = XL_CONFIG.toClassOrNull()
            val setDebugMethods = configClass?.declaredMethods?.filter(::isSetDebug).orEmpty()
            setDebugMethods.forEach { method ->
                deoptimize(method)
                method.hook {
                    before { param ->
                        runCatching {
                            val path = param.args.getOrNull(2) as? String ?: return@runCatching
                            if (isXlDownloadPath(path)) {
                                param.result = null
                                logBlockedOnce("XLConfig.setDebug", path)
                            }
                        }.onFailure { DebugLog.w(TAG, "setDebug interception failed", it) }
                    }
                }
                installed++
            }
            if (setDebugMethods.isEmpty()) {
                DebugLog.hookSkipped(TAG, "$XL_CONFIG#setDebug", "expected signature not found")
            }
        }.onFailure { DebugLog.hookFailed(TAG, "$XL_CONFIG#setDebug", it) }

        runCatching {
            val fileUtilClass = FILE_UTIL.toClassOrNull()
            val createFileMethods = fileUtilClass?.declaredMethods?.filter(::isCreateFile).orEmpty()
            createFileMethods.forEach { method ->
                deoptimize(method)
                method.hook {
                    before { param ->
                        runCatching {
                            val path = param.args.getOrNull(0) as? String ?: return@runCatching
                            if (isXlDownloadPath(path)) {
                                // The caller already treats a null File as a failed optional-log
                                // setup and catches the resulting exception. No download state is
                                // touched by this path.
                                param.result = null
                                logBlockedOnce("FileUtil.createFile", path)
                            }
                        }.onFailure { DebugLog.w(TAG, "createFile interception failed", it) }
                    }
                }
                installed++
            }
            if (createFileMethods.isEmpty()) {
                DebugLog.hookSkipped(TAG, "$FILE_UTIL#createFile(String)", "expected signature not found")
            }
        }.onFailure { DebugLog.hookFailed(TAG, "$FILE_UTIL#createFile", it) }

        if (installed == 0) {
            DebugLog.w(TAG, "no log creation boundary was hooked")
        } else {
            DebugLog.i(TAG, "blocked Download Manager .xlDownload creation boundaries=$installed")
        }
    }

    private fun isSetDebug(method: Method): Boolean {
        val parameters = method.parameterTypes
        return Modifier.isStatic(method.modifiers) &&
            method.name == "setDebug" &&
            parameters.size == 3 &&
            Context::class.java.isAssignableFrom(parameters[0]) &&
            parameters[1] == Boolean::class.javaPrimitiveType &&
            parameters[2] == String::class.java
    }

    private fun isCreateFile(method: Method): Boolean {
        val parameters = method.parameterTypes
        return Modifier.isStatic(method.modifiers) &&
            method.name == "createFile" &&
            parameters.size == 1 &&
            parameters[0] == String::class.java
    }

    private fun isXlDownloadPath(path: String): Boolean {
        val normalized = path.trim().trimEnd('/')
        return targetDirectories.any { root ->
            normalized == root || normalized.startsWith("$root/")
        }
    }

    @Volatile
    private var logged = false

    private fun logBlockedOnce(boundary: String, path: String) {
        if (!logged) {
            synchronized(this) {
                if (!logged) {
                    logged = true
                    DebugLog.i(TAG, "blocked $boundary path=$path")
                }
            }
        }
    }
}
