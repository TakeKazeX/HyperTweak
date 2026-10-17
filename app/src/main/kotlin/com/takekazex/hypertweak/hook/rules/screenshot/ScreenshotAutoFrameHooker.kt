package com.takekazex.hypertweak.hook.rules.screenshot

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.net.Uri
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.io.File
import java.lang.reflect.Field
import java.util.WeakHashMap

/** Native frame composition at the full-screen save boundary, leaving capture/scroll bitmaps alone. */
object ScreenshotAutoFrameHooker : StaticHooker() {
    private const val TAG = "ScreenshotAutoFrame"
    private const val RECEIPTS = "hypertweak_screenshot_frames"
    private data class Source(val type: Int, val displayId: Int, val owner: java.lang.ref.WeakReference<Any>? = null)
    private data class Transformation(val data: Any, val original: Bitmap, val framed: Bitmap, val source: File)
    private data class Preview(val original: Bitmap, val bitmap: Bitmap)
    private val previews = WeakHashMap<Any, Preview>()
    private val renderedSources = WeakHashMap<Bitmap, File>()
    private val sources = WeakHashMap<Any, Source>()
    private val enrolled = WeakHashMap<Any, Source>()
    private val transformations = WeakHashMap<Any, Transformation>()
    private val saveSources = ThreadLocal<ArrayDeque<Source>>()
    @Volatile private var retired = false
    private var frameAccess: NativeScreenshotFrameAccess? = null
    override fun saveHotReloadState(): Any? = Preferences.saveScreenshotSettings()
    override fun restoreHotReloadState(state: Any?) { Preferences.restoreScreenshotSettings(state) }

    override fun onHook() {
        retired = false
        if (hookParam.processName !in setOf("com.miui.screenshot", "com.miui.screenshot:photo_editor")) return
        boundary("resolve native screenshot contract") {
            val apk = hookParam.appInfo?.sourceDir?.let(::File) ?: return@boundary
            val contract = ScreenshotFrameResolver(apk.path).resolve()
            val access = NativeScreenshotFrameAccess(classLoader, contract)
            frameAccess = access
            access.saveCallChain.forEach { member ->
                check(deoptimize(member)) { "Cannot prepare native save call chain: $member" }
            }
            // Editing safeguards must be present before enrolling any automatic saves.
            installEditorHooks(access)
            if (isMainProcess) installSaveHooks(access)
            DebugLog.hookRegistered(TAG, "native frame/save/editor contract ${apk.name}")
        }
    }

    private fun installSaveHooks(access: NativeScreenshotFrameAccess) {
        deoptimize(access.thumbnailReady)
        access.thumbnailReady.hook("screenshot_frame_native_thumbnail") {
            after { param ->
                val preview = synchronized(previews) { previews[param.thisObject] } ?: return@after
                boundary("restore native framed thumbnail") { access.updatePreview(param.thisObject, preview.original, preview.bitmap) }
            }
        }
        access.request.hook("screenshot_frame_source") {
            before { param ->
                val owner = param.thisObject
                // Invalidate first so a failed new request cannot inherit a previous enrollment.
                synchronized(sources) { sources.remove(owner) }
                synchronized(previews) { previews.remove(owner) }
                if (retired) return@before
                boundary("read capture source") {
                    val request = param.args.firstOrNull() ?: return@boundary
                    val source = Source((access.requestType.invoke(request) as Number).toInt(),
                        (access.displayId.invoke(request) as Number).toInt(), java.lang.ref.WeakReference(owner))
                    synchronized(sources) { sources[owner] = source }
                    DebugLog.d(TAG, "capture source type=${source.type} display=${source.displayId}")
                }
            }
        }
        access.save.hook("screenshot_frame_full_save") {
            before { param ->
                val source = synchronized(sources) { sources[param.thisObject] } ?: Source(-1, -1)
                val stack = saveSources.get() ?: ArrayDeque<Source>().also(saveSources::set)
                stack.addLast(source)
            }
            after {
                saveSources.get()?.let { stack ->
                    if (stack.isNotEmpty()) stack.removeLast()
                    if (stack.isEmpty()) saveSources.remove()
                }
            }
        }
        access.taskConstructor.hook("screenshot_frame_save_task") {
            after { param ->
                val task = param.thisObject
                val source = saveSources.get()?.lastOrNull() ?: return@after
                if (!retired && source.type == 1 && source.displayId == 0) {
                    synchronized(enrolled) { enrolled[task] = source }
                    DebugLog.d(TAG, "full-screen save task enrolled")
                }
            }
        }
        access.write.hook("screenshot_frame_receipt") {
            after { param ->
                val task = param.thisObject
                if (synchronized(transformations) { !transformations.containsKey(task) }) return@after
                boundary("record saved frame") {
                    val notify = access.notify.get(task) ?: return@boundary
                    if (access.success.get(notify) != true) return@boundary
                    val data = (param.args.getOrNull(1) as? Array<*>)?.singleOrNull() ?: return@boundary
                    val context = access.context.get(data) as? Context ?: return@boundary
                    publish(context, access.uri.get(notify) as? Uri, synchronized(transformations) { transformations[task]?.source })
                    DebugLog.d(TAG, "native framed PNG saved")
                }
            }
        }
        access.worker.hook("screenshot_frame_worker") {
            before { param ->
                val task = param.thisObject
                val source = synchronized(enrolled) { enrolled.remove(task) } ?: return@before
                if (retired) return@before
                val enabled = Preferences.screenshotAutoFrameEnabled()
                DebugLog.d(TAG, "screenshot save settings enabled=$enabled")
                if (!enabled) return@before
                boundary("compose native screenshot frame") {
                    val data = (param.args.firstOrNull() as? Array<*>)?.singleOrNull() ?: return@boundary
                    val original = access.bitmap.get(data) as? Bitmap ?: return@boundary
                    val context = access.context.get(data) as? Context ?: return@boundary
                    if (original.isRecycled) return@boundary
                    val display = (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                        ?.getDisplay(0) ?: return@boundary
                    val size = Point()
                    @Suppress("DEPRECATION")
                    display.getRealSize(size)
                    if (!ScreenshotFramePolicy.eligible(source.type, source.displayId, original.width,
                            original.height, size.x, size.y)) return@boundary
                    val receiptDirectory = File(context.filesDir, RECEIPTS)
                    check(receiptDirectory.isDirectory || receiptDirectory.mkdirs()) {
                        "Cannot prepare editor frame receipts"
                    }
                    val composeStarted = System.nanoTime()
                    val framed = access.render(original) ?: run {
                        DebugLog.d(TAG, "native frame material unavailable or incompatible; saving original")
                        return@boundary
                    }
                    val composeMs = (System.nanoTime() - composeStarted) / 1_000_000
                    try {
                        boundary("prepare native framed thumbnail") { source.owner?.get()?.let { owner ->
                            val copy = Bitmap.createScaledBitmap(framed, 400, (framed.height * 400f / framed.width).toInt(), true)
                            synchronized(previews) { previews[owner] = Preview(original, copy) }
                            (access.thumbnail.get(owner) as? android.view.View)?.post {
                                boundary("update native screenshot preview") { access.updatePreview(owner, original, copy) }
                            }
                        } }
                        val preserveStarted = System.nanoTime()
                        val raw = sourceStore(context).capture { original.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        DebugLog.d(TAG, "frame prepare=${composeMs}ms original=${(System.nanoTime() - preserveStarted) / 1_000_000}ms")
                        synchronized(transformations) { transformations[task] = Transformation(data, original, framed, raw) }
                        mutateSave(task, data, framed, access)
                    } catch (error: Throwable) {
                        synchronized(transformations) { transformations.remove(task) }
                        boundary("restore preview after frame setup failure") {
                            source.owner?.get()?.let { owner ->
                                synchronized(previews) { previews.remove(owner) }
                                (access.thumbnail.get(owner) as? android.view.View)?.post {
                                    boundary("restore original screenshot preview") { access.updatePreview(owner, original, original) }
                                }
                            }
                        }
                        framed.recycle()
                        throw error
                    }
                }
            }
            after { param ->
                val task = param.thisObject
                val change = synchronized(transformations) { transformations.remove(task) } ?: return@after
                boundary("release frame save bitmap") {
                    // The host/controller still owns the original and may be starting a long capture.
                    if (access.bitmap.get(change.data) === change.framed) access.bitmap.set(change.data, change.original)
                    if (!change.framed.isRecycled) change.framed.recycle()
                }
            }
        }
    }

    /** Roll back all fields on setup failure, before the host creates any file or MediaStore row. */
    private fun mutateSave(task: Any, data: Any, framed: Bitmap, access: NativeScreenshotFrameAccess) {
        val notify = access.notify.get(task) ?: error("Missing native save metadata")
        val writes = listOf(
            Triple(access.bitmap, data, framed),
            Triple(access.format, data, Bitmap.CompressFormat.PNG),
            Triple(access.taskWidth, task, framed.width),
            Triple(access.taskHeight, task, framed.height),
            Triple(access.fileTemplate, task, "Screenshot_%s_%s.png"),
            Triple(access.width, notify, framed.width),
            Triple(access.height, notify, framed.height),
        )
        val originals = writes.map { (field, owner, _) -> field.get(owner) }
        try {
            writes.forEach { (field, owner, value) -> field.set(owner, value) }
        } catch (error: Throwable) {
            writes.forEachIndexed { index, (field, owner, _) ->
                runCatching { field.set(owner, originals[index]) }.onFailure(error::addSuppressed)
            }
            throw error
        }
    }

    private fun sourceStore(context: Context) = ScreenshotFrameSources(File(context.filesDir, "hypertweak_screenshot_sources"))

    private fun installEditorHooks(access: NativeScreenshotFrameAccess) {
        val manager = classLoader.loadClass("com.miui.gallery.editor.photo.scribbles.ScreenImageManager")
        val source = manager.getDeclaredField("mSource").accessible()
        val context = manager.getDeclaredField("mContext").accessible()
        val sessions = ScreenshotEditorSessions {
            Preferences.screenshotEditorMode().also { DebugLog.d(TAG, "screenshot editor session mode=$it") }
        }
        val saveAsPng = manager.getDeclaredMethod("setNeedSaveAsPng", Boolean::class.javaPrimitiveType)
        val rawFiles = WeakHashMap<Any, File>()
        val identities = WeakHashMap<Any, ScreenshotMediaIdentity>()
        val initialized = WeakHashMap<Any, Boolean>()
        val open = manager.getDeclaredMethod("openInputStream").apply { isAccessible = true }
        val initialize = manager.getDeclaredMethod("initializePreview")
        val release = manager.getDeclaredMethod("release")
        val activity = classLoader.loadClass("com.miui.gallery.editor.photo.screen.home.ScreenEditorActivity")
        val viewModel = activity.getDeclaredField("mViewModel").accessible()
        val model = classLoader.loadClass("com.miui.gallery.editor.photo.scribbles.ScreenEditorViewModel")
        val getShell = model.getDeclaredMethod("getShellManager")
        val shell = classLoader.loadClass("com.miui.gallery.editor.photo.scribbles.ScreenShellManager")
        val imageManagerField = activity.getDeclaredField("mScreenImageManager").accessible()
        val portrait = shell.getDeclaredField("mIsRightRatio").accessible()
        val checkEnabled = shell.getDeclaredMethod("checkShellFuncEnabled", Context::class.java)
        val operation = shell.getDeclaredField("mScreenShellOperation").accessible()
        val shellOperation = classLoader.loadClass("com.miui.gallery.editor.photo.screen.shell.IScreenShellOperation")
        val baseline = shellOperation.getDeclaredMethod("updateLastShellStatus")
        val doShell = shell.declaredMethods.single { it.name == "doShell" && it.parameterCount == 1 }
        val fragment = classLoader.loadClass("com.miui.gallery.editor.photo.scribbles.ImageEditorFragment")
        val ready = fragment.getDeclaredMethod("setPreviewBitmap", Bitmap::class.java)
        val getActivity = fragment.getMethod("getActivity")
        val export = classLoader.loadClass("com.miui.gallery.editor.photo.app.ExportTask")
        val exportContext = classLoader.loadClass("com.miui.gallery.editor.photo.app.ExportContext")
        val exportData = classLoader.loadClass("com.miui.gallery.editor.photo.app.ExportSaveData")
        val image = exportData.getDeclaredField("image").accessible()
        val notify = export.getDeclaredField("mNotifyMediaStoreData").accessible()
        val onExport = export.getDeclaredMethod("onExport", exportContext, exportData)
        val renderData = classLoader.loadClass("com.miui.gallery.editor.photo.screen.core.ScreenRenderData")
        val drawEntry = classLoader.loadClass("com.miui.gallery.editor.photo.screen.core.ScreenDrawEntry")
        val render = renderData.getDeclaredMethod("apply", Bitmap::class.java, Boolean::class.javaPrimitiveType)
        val draw = drawEntry.getDeclaredMethod("apply", Bitmap::class.java, Boolean::class.javaPrimitiveType)
        val shellEntry = renderData.getDeclaredField("mScreenShellEntry").accessible()
        val renderJob = ThreadLocal<Any>()
        val rawRender = WeakHashMap<Any, File>()
        listOf(initialize, open, ready, onExport, render, draw, checkEnabled).forEach { check(deoptimize(it)) }

        fun identify(owner: Any): ScreenshotMediaIdentity? {
            synchronized(identities) { identities[owner] }?.let { return it }
            val host = context.get(owner) as? Context ?: return null
            val uri = source.get(owner) as? Uri ?: return null
            val identity = ScreenshotMediaIdentity.read(host, uri) ?: return null
            val receipt = ScreenshotFrameReceipts(File(host.filesDir, RECEIPTS))
            if (!receipt.contains(identity.key, identity.stamp) &&
                !access.matchesSavedFrame(identity.width, identity.height, identity.mime)) return null
            synchronized(identities) { identities[owner] = identity }
            return identity
        }
        initialize.hook("screenshot_frame_editor_original") {
            before { param ->
                if (retired) return@before
                boundary("restore native editable original") {
                    val owner = param.thisObject
                    if (sessions.mode(owner) != 0) return@boundary
                    if (synchronized(rawFiles) { rawFiles.containsKey(owner) }) return@boundary
                    val identity = identify(owner) ?: return@boundary
                    val host = context.get(owner) as? Context ?: return@boundary
                    val store = sourceStore(host)
                    val cached = store.find(identity)
                    val raw = cached ?: run {
                        val uri = source.get(owner) as Uri
                        val framed = host.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
                            ?: return@boundary
                        try {
                            val restored = access.recoverOriginal(framed)
                            try { store.capture { restored.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                            finally { if (restored !== framed) restored.recycle() }
                        } finally { framed.recycle() }
                    }
                    synchronized(rawFiles) { rawFiles[owner] = raw }
                    DebugLog.d(TAG, "editable original ready cached=${cached != null}")
                }
            }
        }
        open.hook("screenshot_frame_editor_original_stream") {
            before { param ->
                val file = synchronized(rawFiles) { rawFiles[param.thisObject] } ?: return@before
                boundary("read editable screenshot") { param.result = file.inputStream() }
            }
        }
        ready.hook("screenshot_frame_editor_native_default") {
            after { param ->
                if (retired) return@after
                boundary("enable native removable frame") {
                    val host = getActivity.invoke(param.thisObject) as? Activity ?: return@boundary
                    if (!activity.isInstance(host)) return@boundary
                    val imageManager = imageManagerField.get(host) ?: return@boundary
                    if (synchronized(rawFiles) { !rawFiles.containsKey(imageManager) }) return@boundary
                    if (synchronized(initialized) { initialized.put(imageManager, true) == true }) return@boundary
                    val vm = viewModel.get(host) ?: return@boundary
                    val nativeShell = getShell.invoke(vm) ?: return@boundary
                    portrait.setBoolean(nativeShell, true)
                    checkEnabled.invoke(nativeShell, host)
                    doShell.invoke(nativeShell, host)
                    operation.get(nativeShell)?.let { baseline.invoke(it) }
                    DebugLog.d(TAG, "native editable frame enabled")
                }
            }
        }
        // Capture the annotated, unframed draw result, before the native shell applies once.
        render.hook("screenshot_frame_editor_render") {
            before { param -> if (shellEntry.get(param.thisObject) != null) renderJob.set(param.thisObject) }
            after { param ->
                val job = renderJob.get() ?: return@after
                renderJob.remove()
                val raw = synchronized(rawRender) { rawRender.remove(job) } ?: return@after
                (param.result as? Bitmap)?.let { synchronized(renderedSources) { renderedSources[it] = raw } }
            }
        }
        draw.hook("screenshot_frame_editor_draw_source") {
            after { param ->
                val job = renderJob.get() ?: return@after
                val bitmap = param.result as? Bitmap ?: return@after
                boundary("preserve edited unframed screenshot") {
                    val host = hookParam.appContext ?: classLoader.loadClass("com.miui.gallery.util.StaticContext")
                        .getDeclaredMethod("sGetAndroidContext").invoke(null) as? Context ?: return@boundary
                    val raw = sourceStore(host).capture { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    synchronized(rawRender) { rawRender[job] = raw }
                }
            }
        }
        onExport.hook("screenshot_frame_editor_export") {
            after { param ->
                if (param.result != true) return@after
                boundary("bind edited original") {
                    val data = param.args.getOrNull(1) ?: return@boundary
                    val bitmap = image.get(data) as? Bitmap ?: return@boundary
                    val raw = synchronized(renderedSources) { renderedSources.remove(bitmap) } ?: return@boundary
                    val owner = param.args.firstOrNull()?.takeIf(manager::isInstance) ?: return@boundary
                    val host = context.get(owner) as? Context ?: return@boundary
                    val info = notify.get(param.thisObject) ?: return@boundary
                    publish(host, access.uri.get(info) as? Uri, raw)
                }
            }
        }
        checkEnabled.hook("screenshot_frame_editor_flattened") {
            before { param ->
                if (retired) return@before
                boundary("keep framed screenshot directly editable") {
                    val host = param.args.firstOrNull()?.takeIf(activity::isInstance) ?: return@boundary
                    val imageManager = imageManagerField.get(host) ?: return@boundary
                    if (sessions.mode(imageManager) != 1) return@boundary
                    val editorContext = context.get(imageManager) as? Context ?: return@boundary
                    val uri = source.get(imageManager) as? Uri ?: return@boundary
                    val identity = ScreenshotMediaIdentity.read(editorContext, uri) ?: return@boundary
                    val receipts = ScreenshotFrameReceipts(File(editorContext.filesDir, RECEIPTS))
                    if (!receipts.contains(identity.key, identity.stamp) &&
                        !access.matchesSavedFrame(identity.width, identity.height, identity.mime)) return@boundary
                    // Keep the saved composite as the editor source. A separate native shell layer
                    // hides the crop handles and would add a second frame on export.
                    // Let the host publish its disabled state to the toolbar's LiveData observers.
                    saveAsPng.invoke(imageManager, true)
                    portrait.setBoolean(param.thisObject, false)
                    DebugLog.d(TAG, "framed screenshot opened for whole-image cropping")
                }
            }
        }
        release.hook("screenshot_frame_editor_release") {
            after { param ->
                sessions.release(param.thisObject)
                synchronized(rawFiles) { rawFiles.remove(param.thisObject) }
                synchronized(identities) { identities.remove(param.thisObject) }
                synchronized(initialized) { initialized.remove(param.thisObject) }
            }
        }
    }

    private fun publish(context: Context, uri: Uri?, original: File? = null) {
        if (uri == null) return
        val identity = ScreenshotMediaIdentity.read(context, uri) ?: error("Saved frame MediaStore identity unavailable")
        val receipts = ScreenshotFrameReceipts(File(context.filesDir, RECEIPTS))
        original?.let { sourceStore(context).bind(identity, it) }
        receipts.publish(identity.key, identity.stamp)
    }

    private fun Field.accessible() = apply { isAccessible = true }
    private inline fun boundary(action: String, block: () -> Unit) {
        runCatching(block).onFailure { DebugLog.w(TAG, action, it) }
    }

    @Synchronized override fun onPrepareHotReload() {
        retired = true
        frameAccess?.close()
        frameAccess = null
        synchronized(previews) { previews.clear() }
        synchronized(sources) { sources.clear() }
        synchronized(enrolled) { enrolled.clear() }
        // In-flight worker callbacks own their generated bitmap until native writing finishes.
    }
}
