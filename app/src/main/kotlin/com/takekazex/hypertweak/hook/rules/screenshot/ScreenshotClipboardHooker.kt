package com.takekazex.hypertweak.hook.rules.screenshot

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.dex.DexFeature
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.rules.camera.CameraDexIndex
import com.takekazex.hypertweak.util.DebugLog

private object ScreenshotClipboardFeature : DexFeature {
    override val id = "screenshot.clipboard"
    override val schema = 1
}

/** Keep the host's URI publication, permission grants and save failure handling intact. */
object ScreenshotClipboardHooker : StaticHooker() {
    @Volatile private var retired = false
    private val requests = ScreenshotClipboardRequests()

    override fun onHook() {
        retired = false
        if (hookParam.processName !in setOf("com.miui.screenshot", "com.miui.screenshot:photo_editor")) return
        val apk = hookParam.appInfo?.sourceDir ?: return
        // Cache replay is the only synchronous path. A miss never scans on a capture thread.
        val cached = resolve(apk, cacheOnly = true)
        if (cached != null) install(cached)
        else prepareDexTargets(name = ScreenshotClipboardFeature.id,
            resolve = { resolve(apk, cacheOnly = false) }, install = ::install)
    }

    private fun resolve(apk: String, cacheOnly: Boolean): NativeScreenshotClipboardAccess? = DexRuntime.resolve(
        feature = ScreenshotClipboardFeature, apkPath = apk, cacheDir = dexCacheDirectory, cacheOnly = cacheOnly,
        compute = { ScreenshotClipboardResolver(CameraDexIndex.open(apk)).resolve().encode() },
        decode = { values, _ -> NativeScreenshotClipboardAccess.decode(values, classLoader) },
    )

    private fun install(access: NativeScreenshotClipboardAccess) {
        check(deoptimize(access.gate)) { "Cannot prepare screenshot clipboard gate" }
        access.callers.forEach { check(deoptimize(it)) { "Cannot prepare screenshot clipboard caller: $it" } }
        access.copy.hook("screenshot_clipboard_scope") {
            before { requests.enter(!retired && Preferences.screenshotCopyToClipboard()) }
            after { requests.leave() }
        }
        access.gate.hook("screenshot_clipboard_gate") {
            before { param ->
                if (retired) return@before
                val enabled = requests.consume() ?: return@before
                param.result = !enabled
                DebugLog.d("ScreenshotClipboard", "native clipboard gate enabled=$enabled")
            }
        }
        DebugLog.hookRegistered("ScreenshotClipboard", "native gate and save chain ready")
    }

    override fun onPrepareHotReload() { retired = true }
}
