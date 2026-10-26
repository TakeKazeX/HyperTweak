package com.takekazex.hypertweak.hook.rules.screenshot

import com.takekazex.hypertweak.hook.base.HostAccess
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Path
import android.net.Uri
import java.io.File
import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import java.lang.reflect.Field
import java.lang.reflect.Method

/** Reflected access to a uniquely resolved host save/editor graph. */
internal class NativeScreenshotFrameAccess(private val loader: ClassLoader, private val contract: ScreenshotFrameResolver.Contract) {
    private fun resolvedMethod(role: String): Method = requireNotNull(MethodDescriptorCodec.method(
        MethodDescriptorCodec.descriptor(contract.methods.getValue(role)), loader))
    private fun resolvedField(role: String): Field = requireNotNull(MethodDescriptorCodec.field(
        MethodDescriptorCodec.descriptor(contract.fields.getValue(role)), loader))
    private fun load(name: String) = loader.loadClass(name)
    private fun field(type: Class<*>, name: String, valueType: Class<*>): Field =
        HostAccess.requireDeclaredField(type, name, accessible = true).apply {
            check(this.type == valueType) { "Unexpected ${type.name}.$name layout" }
        }
    private fun method(type: Class<*>, name: String, vararg args: Class<*>): Method =
        HostAccess.requireDeclaredMethod(type, name, *args, accessible = true)

    private val entry = load("com.miui.gallery.editor.photo.screen.shell.ScreenShellEntry")
    private val shellInfo = load("com.miui.gallery.editor.photo.screen.shell.ShellInfoBean")
    private val fetcher = load("com.miui.gallery.editor.photo.screen.shell.res.ShellResourceFetcher")
    private val float = Float::class.javaPrimitiveType!!

    val request = resolvedMethod("request")
    val save = resolvedMethod("save")
    val taskConstructor = requireNotNull(MethodDescriptorCodec.constructor(
        MethodDescriptorCodec.descriptor(contract.methods.getValue("taskConstructor")), loader))
    val worker = resolvedMethod("worker")
    val saveCaller = resolvedMethod("saveCaller")
    val workerBridge = resolvedMethod("workerBridge")
    val saveCallChain get() = listOf(request, saveCaller, save, taskConstructor, worker, workerBridge, write)
    val thumbnailReady = resolvedMethod("thumbnailReady")
    val captureBitmap = resolvedField("captureBitmap")
    val thumbnail = resolvedField("thumbnail")
    private val drawable = load("com.miui.gallery.editor.transition.RoundedCornerDrawable")
    private val thumbnailDrawable = resolvedField("thumbnailDrawable")
    private val setPreviewBitmap = method(drawable, "setBitmap", Bitmap::class.java)
    private val setPreviewRadius = method(drawable, "setCornerRadius", float)

    fun updatePreview(owner: Any, original: Bitmap, preview: Bitmap) {
        if (captureBitmap.get(owner) !== original) return
        val target = thumbnailDrawable.get(owner) ?: return
        setPreviewBitmap.invoke(target, preview)
        setPreviewRadius.invoke(target, 0f)
        (thumbnail.get(owner) as? android.widget.ImageView)?.invalidate()
    }
    val write = resolvedMethod("write")
    val bitmap = resolvedField("bitmap")
    val context = resolvedField("context")
    val format = resolvedField("format")
    val notify = resolvedField("notify")
    val taskWidth = resolvedField("taskWidth")
    val taskHeight = resolvedField("taskHeight")
    val fileTemplate = resolvedField("fileTemplate")
    val width = resolvedField("width")
    val height = resolvedField("height")
    val uri = resolvedField("uri")
    val path = resolvedField("path")
    val success = resolvedField("success")
    private val screenshot = request.parameterTypes.first()
    val requestType = method(screenshot, "getType")
    val displayId = method(screenshot, "getDisplayId")

    private val entryConstructor = entry.getDeclaredConstructor().apply { isAccessible = true }
    private val generate = method(entry, "generateBitmap")
    private val apply = method(entry, "apply", Bitmap::class.java)
    private val materialPath = method(fetcher, "getMaterialPath")
    private val available = method(fetcher, "isLocalResAvailable")
    private val entryInfo = field(entry, "mShellInfo", shellInfo)
    private val shellBitmap = field(entry, "mShellBitmap", Bitmap::class.java)
    private val shellPath = field(entry, "mShellSvgPath", Path::class.java)
    private val materialWidth = field(shellInfo, "with", float)
    private val materialHeight = field(shellInfo, "height", float)
    private val margins = listOf("leftMargin", "topMargin", "rightMargin", "bottomMargin")
        .map { field(shellInfo, it, float) }
    private val outputWidth = method(shellInfo, "getShellWidth")
    private val outputHeight = method(shellInfo, "getShellHeight")

    /** Recognize native framed PNGs created before receipts worked, without decoding shell.png on UI. */
    fun matchesSavedFrame(width: Int, height: Int, mime: String): Boolean {
        if (mime != "image/png" || available.invoke(null) != true) return false
        val directory = materialPath.invoke(null) as? String ?: return false
        val json = File(directory, "shell.json")
        if (!json.isFile || json.length() !in 1..16384) return false
        val values = org.json.JSONObject(json.readText())
        val material = shellInfo.getDeclaredConstructor().newInstance()
        materialWidth.setFloat(material, values.getDouble("with").toFloat())
        materialHeight.setFloat(material, values.getDouble("height").toFloat())
        margins.forEach { it.setFloat(material, values.getDouble(it.name).toFloat()) }
        return (outputWidth.invoke(material) as Number).toInt() == width &&
            (outputHeight.invoke(material) as Number).toInt() == height
    }

    /** Legacy saved frames have no original snapshot; remove exactly the native material margins. */
    fun recoverOriginal(input: Bitmap): Bitmap {
        val directory = materialPath.invoke(null) as String
        val values = org.json.JSONObject(File(directory, "shell.json").readText())
        return Bitmap.createBitmap(input, values.getInt("leftMargin"), values.getInt("topMargin"),
            values.getInt("with"), values.getInt("height"))
    }

    private data class MaterialFile(val path: String, val length: Long, val modified: Long)
    private fun materialFiles(directory: String): List<MaterialFile>? {
        return listOf("shell.json", "shell.png", "shell.svg").map { name ->
            val file = File(directory, name)
            if (!file.isFile || file.length() !in 1..16_777_216) return null
            MaterialFile(file.absolutePath, file.length(), file.lastModified())
        }
    }
    private fun disposeEntry(nativeEntry: Any) {
        (shellBitmap.get(nativeEntry) as? Bitmap)?.takeIf { !it.isRecycled }?.recycle()
    }
    private val materials = ScreenshotFrameMaterialCache<Any>(::disposeEntry)

    fun close() = materials.close()

    /** Reuse native material decoding; the cache serializes the entry's mutable Rect/Paint. */
    fun render(input: Bitmap): Bitmap? {
        if (available.invoke(null) != true) return null
        val initialPath = materialPath.invoke(null) as? String ?: return null
        val files = materialFiles(initialPath) ?: return null
        return materials.use(files, load = {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(File(initialPath, "shell.png").path, options)
            if (!ScreenshotFramePolicy.safeOutput(options.outWidth.toFloat(), options.outHeight.toFloat())) return@use null
            val nativeEntry = entryConstructor.newInstance()
            try {
                generate.invoke(nativeEntry)
                check(entryInfo.get(nativeEntry) != null && shellBitmap.get(nativeEntry) is Bitmap && shellPath.get(nativeEntry) is Path) {
                    "Incomplete native frame material"
                }
                nativeEntry
            } catch (error: Throwable) {
                disposeEntry(nativeEntry)
                throw error
            }
        }) { nativeEntry ->
            var softwareCopy: Bitmap? = null
            var result: Bitmap? = null
            try {
                val material = entryInfo.get(nativeEntry) ?: return@use null
                if (margins.any { it.getFloat(material).let { value -> !value.isFinite() || value < 0 || value > 4096 } }) return@use null
                if (!ScreenshotFramePolicy.matchesMaterial(input.width, input.height,
                        materialWidth.getFloat(material), materialHeight.getFloat(material))) return@use null
                val outWidth = (outputWidth.invoke(material) as Number).toFloat()
                val outHeight = (outputHeight.invoke(material) as Number).toFloat()
                if (!ScreenshotFramePolicy.safeOutput(outWidth, outHeight)) return@use null
                val source = if (input.config == Bitmap.Config.HARDWARE) {
                    input.copy(Bitmap.Config.ARGB_8888, false)?.also { softwareCopy = it } ?: return@use null
                } else input
                result = apply.invoke(nativeEntry, source) as? Bitmap ?: return@use null
                if (result === input || result.width != outWidth.toInt() || result.height != outHeight.toInt() ||
                    available.invoke(null) != true || materialPath.invoke(null) != initialPath || materialFiles(initialPath) != files) {
                    if (result !== input) result.recycle()
                    result = null
                    return@use null
                }
                result
            } catch (error: Throwable) {
                result?.takeIf { it !== input && !it.isRecycled }?.recycle()
                throw error
            } finally {
                softwareCopy?.recycle()
            }
        }
    }
}
