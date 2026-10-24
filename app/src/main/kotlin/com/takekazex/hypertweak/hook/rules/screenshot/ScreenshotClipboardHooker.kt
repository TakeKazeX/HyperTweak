package com.takekazex.hypertweak.hook.rules.screenshot

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import io.github.libxposed.api.XposedInterface
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.dex.DexFeature
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.util.DebugLog

private object ScreenshotClipboardFeature : DexFeature {
    override val id = "screenshot.clipboard"
    override val schema = 3
}

/** Keep the host's URI publication, permission grants and save failure handling intact. */
object ScreenshotClipboardHooker : StaticHooker() {
    @Volatile private var retired = false
    private val requests = ScreenshotClipboardRequests()
    private val publications = ScreenshotClipboardPublication {
        DebugLog.w("ScreenshotClipboard", "committed clipboard publication failed", it)
    }

    private val commits = ScreenshotClipboardCommits(failed = {
        DebugLog.w("ScreenshotClipboard", "MediaStore clipboard confirmation failed", it)
    })

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
        compute = { ScreenshotClipboardResolver(DexRuntime.operands(apk)).resolve().encode() },
        decode = { values, _ -> NativeScreenshotClipboardAccess.decode(values, classLoader) },
    )

    private fun install(access: NativeScreenshotClipboardAccess) {
        check(deoptimize(access.gate)) { "Cannot prepare screenshot clipboard gate" }
        access.callers.forEach { check(deoptimize(it)) { "Cannot prepare screenshot clipboard caller: $it" } }
        val originalCopy = module.getInvoker(access.copy).setType(XposedInterface.Invoker.Type.ORIGIN)
        access.saveWriter.hook("screenshot_clipboard_save") {
            intercept { chain ->
                publications.saving(null, {
                    runCatching { access.notify.get(chain.thisObject)?.let(access.success::get) == true }
                        .onFailure { DebugLog.w("ScreenshotClipboard", "save result unavailable", it) }.getOrDefault(false)
                }) { chain.proceed() }
            }
        }
        ContentResolver::class.java.declaredMethods.filter { method ->
            method.name == "update" && method.returnType == Int::class.javaPrimitiveType &&
                method.parameterTypes.take(2) == listOf(Uri::class.java, ContentValues::class.java)
        }.forEach { method ->
            method.hook("screenshot_clipboard_media_commit:${method.parameterTypes.joinToString { it.name }}") {
                after { param ->
                    if (retired || (param.result as? Int ?: 0) <= 0) return@after
                    val uri = param.args.getOrNull(0) as? Uri ?: return@after
                    val values = param.args.getOrNull(1) as? ContentValues ?: return@after
                    if (runCatching { values.getAsInteger(MediaStore.MediaColumns.IS_PENDING) }.getOrNull() == 0) commits.committed(uri)
                }
            }
        }
        access.commitWriter.hook("screenshot_clipboard_commit") {
            intercept { chain ->
                val uri = chain.args.getOrNull(2) as? Uri ?: return@intercept chain.proceed()
                publications.saving(uri, { it == true }) { chain.proceed() }
            }
        }
        access.copy.hook("screenshot_clipboard_scope") {
            intercept { chain ->
                val uri = chain.args.getOrNull(1) as? Uri
                val context = chain.args.getOrNull(0) as? Context
                if (!retired && uri != null && context != null) {
                    val receiver = chain.thisObject
                    val args = chain.args.toTypedArray()
                    if (publications.defer(uri) {
                        if (!retired && Preferences.screenshotCopyToClipboard()) commits.await(uri,
                            ready = { isPublished(context, uri) }, publish = {
                                if (!retired && Preferences.screenshotCopyToClipboard()) {
                                    requests.enter(true)
                                    try { originalCopy.invoke(receiver, *args) } finally { requests.leave() }
                                    DebugLog.d("ScreenshotClipboard", "native clipboard published after MediaStore commit")
                                }
                            })
                    }) return@intercept null
                }
                requests.enter(!retired && Preferences.screenshotCopyToClipboard())
                try { chain.proceed() } finally { requests.leave() }
            }
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

    private fun isPublished(context: Context, uri: Uri): Boolean {
        if (uri.authority?.substringAfter('@') != MediaStore.AUTHORITY) return true
        return context.contentResolver.query(uri,
            arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.IS_TRASHED), null, null, null)?.use {
            it.moveToFirst() && it.getInt(0) == 0 && it.getInt(1) == 0
        } == true
    }

    override fun onPrepareHotReload() { retired = true; commits.clear() }
}
