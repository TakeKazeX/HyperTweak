package com.takekazex.hypertweak.hook.rules.screenshot

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.dex.DexFeature
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Method
import java.lang.reflect.Modifier

private object ScreenshotDirectoryFeature : DexFeature {
    override val id = "screenshot.directory"
    override val schema = 1
}

private data class ScreenshotDirectoryAccess(val predicate: Method, val callers: List<Method>, val workers: List<Method>) {
    companion object {
        fun decode(values: Map<String, String>, loader: ClassLoader): ScreenshotDirectoryAccess? {
            val predicate = values["predicate"]?.let { MethodDescriptorCodec.method(it, loader) } ?: return null
            val callers = values["callers"]?.split('\n')?.takeIf { it.size in 2..32 }
                ?.map { MethodDescriptorCodec.method(it, loader) ?: return null } ?: return null
            val workers = values["workers"]?.split('\n')?.takeIf { it.size in 1..8 }
                ?.map { MethodDescriptorCodec.method(it, loader) ?: return null } ?: return null
            if (!Modifier.isStatic(predicate.modifiers) || predicate.parameterCount != 0 || predicate.returnType != Boolean::class.javaPrimitiveType ||
                workers.any { it.parameterCount != 1 || !it.parameterTypes[0].isArray ||
                    it.returnType != it.parameterTypes[0].componentType }) return null
            return ScreenshotDirectoryAccess(predicate, callers, workers)
        }
    }
}

/** Snapshot per save: toggling during encoding cannot split filesystem and MediaStore paths. */
internal class ScreenshotDirectoryDecisions {
    private val saves = ThreadLocal<ArrayDeque<Boolean>>()
    fun enter(enabled: Boolean) {
        (saves.get() ?: ArrayDeque<Boolean>().also(saves::set)).addLast(enabled)
    }
    fun current(fallback: () -> Boolean): Boolean = saves.get()?.lastOrNull() ?: fallback()
    fun leave() {
        saves.get()?.let {
            if (it.isNotEmpty()) it.removeLast()
            if (it.isEmpty()) saves.remove()
        }
    }
}

object ScreenshotSaveDirectoryHooker : StaticHooker() {
    @Volatile private var retired = false
    private val decisions = ScreenshotDirectoryDecisions()
    override fun onHook() {
        retired = false
        if (hookParam.processName !in setOf("com.miui.screenshot", "com.miui.screenshot:photo_editor")) return
        val apk = hookParam.appInfo?.sourceDir ?: return
        val cached = resolve(apk, cacheOnly = true)
        if (cached != null) install(cached)
        else prepareDexTargets(name = ScreenshotDirectoryFeature.id,
            resolve = { resolve(apk, cacheOnly = false) }, install = ::install)
    }
    private fun resolve(apk: String, cacheOnly: Boolean): ScreenshotDirectoryAccess? = DexRuntime.resolve(
        feature = ScreenshotDirectoryFeature, apkPath = apk, cacheDir = dexCacheDirectory, cacheOnly = cacheOnly,
        compute = { ScreenshotDirectoryResolver(DexRuntime.operands(apk)).resolve().encode() },
        decode = { values, _ -> ScreenshotDirectoryAccess.decode(values, classLoader) },
    )
    private fun install(access: ScreenshotDirectoryAccess) {
        (access.callers + access.workers + access.predicate).distinct().forEach {
            check(deoptimize(it)) { "Cannot prepare screenshot directory caller: $it" }
        }
        access.workers.forEach { worker ->
            worker.hook("screenshot_directory_save_scope") {
                before { decisions.enter(!retired && Preferences.screenshotPicturesDirectory()) }
                after { decisions.leave() }
            }
        }
        access.predicate.hook("screenshot_directory_predicate") {
            before { param ->
                if (!retired && decisions.current(Preferences::screenshotPicturesDirectory)) {
                    param.result = true
                    DebugLog.d("ScreenshotDirectory", "native Pictures/Screenshots directory selected")
                }
            }
        }
        DebugLog.hookRegistered("ScreenshotDirectory", "filesystem and MediaStore directory contract ready")
    }
    override fun onPrepareHotReload() { retired = true }
}
