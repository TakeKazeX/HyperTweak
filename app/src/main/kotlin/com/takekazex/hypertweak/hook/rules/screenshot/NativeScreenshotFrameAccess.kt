package com.takekazex.hypertweak.hook.rules.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Path
import android.net.Uri
import java.io.File
import com.takekazex.hypertweak.hook.rules.camera.CameraDexIndex
import java.lang.reflect.Field
import java.lang.reflect.Method

/** Reflected access to a uniquely resolved host save/editor graph. */
internal class NativeScreenshotFrameAccess(private val loader: ClassLoader, private val contract: ScreenshotFrameResolver.Contract) {
    private fun resolvedMethod(role: String): Method {
        val ref = contract.methods.getValue(role)
        return CameraDexIndex.type(ref.definingClass, loader)
            .getDeclaredMethod(ref.name, *ref.parameterTypes.map {
                CameraDexIndex.type(it.toString(), loader)
            }.toTypedArray()).apply {
                check(returnType == CameraDexIndex.type(ref.returnType, loader))
                isAccessible = true
            }
    }
    private fun resolvedField(role: String): Field {
        val ref = contract.fields.getValue(role)
        return field(CameraDexIndex.type(ref.definingClass, loader), ref.name,
            CameraDexIndex.type(ref.type, loader))
    }
    private fun load(name: String) = loader.loadClass(name)
    private fun field(type: Class<*>, name: String, valueType: Class<*>): Field =
        type.getDeclaredField(name).apply {
            check(this.type == valueType) { "Unexpected ${type.name}.$name layout" }
            isAccessible = true
        }
    private fun method(type: Class<*>, name: String, vararg args: Class<*>): Method =
        type.getDeclaredMethod(name, *args).apply { isAccessible = true }

    private val entry = load("com.miui.gallery.editor.photo.screen.shell.ScreenShellEntry")
    private val shellInfo = load("com.miui.gallery.editor.photo.screen.shell.ShellInfoBean")
    private val fetcher = load("com.miui.gallery.editor.photo.screen.shell.res.ShellResourceFetcher")
    private val float = Float::class.javaPrimitiveType!!

    val request = resolvedMethod("request")
    val save = resolvedMethod("save")
    private val constructor = contract.methods.getValue("taskConstructor")
    val taskConstructor = CameraDexIndex.type(constructor.definingClass, loader)
        .getDeclaredConstructor(*constructor.parameterTypes.map {
            CameraDexIndex.type(it.toString(), loader)
        }.toTypedArray()).apply { isAccessible = true }
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

    /** Independent native entry per job, with no shared mutable Rect/Paint or network requests. */
    fun render(input: Bitmap): Bitmap? {
        if (available.invoke(null) != true) return null
        val initialPath = materialPath.invoke(null) as? String ?: return null
        val directory = File(initialPath)
        if (listOf("shell.json", "shell.png", "shell.svg").any { name ->
                val file = File(directory, name)
                !file.isFile || file.length() !in 1..16_777_216
            }) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(File(directory, "shell.png").path, options)
        if (!ScreenshotFramePolicy.safeOutput(options.outWidth.toFloat(), options.outHeight.toFloat())) return null
        val nativeEntry = entryConstructor.newInstance()
        var softwareCopy: Bitmap? = null
        var result: Bitmap? = null
        try {
            generate.invoke(nativeEntry)
            val material = entryInfo.get(nativeEntry) ?: return null
            if (shellBitmap.get(nativeEntry) !is Bitmap || shellPath.get(nativeEntry) !is Path) return null
            if (margins.any { it.getFloat(material).let { value -> !value.isFinite() || value < 0 || value > 4096 } }) return null
            if (!ScreenshotFramePolicy.matchesMaterial(input.width, input.height,
                    materialWidth.getFloat(material), materialHeight.getFloat(material))) return null
            val outWidth = (outputWidth.invoke(material) as Number).toFloat()
            val outHeight = (outputHeight.invoke(material) as Number).toFloat()
            if (!ScreenshotFramePolicy.safeOutput(outWidth, outHeight)) return null
            val source = if (input.config == Bitmap.Config.HARDWARE) {
                input.copy(Bitmap.Config.ARGB_8888, false)?.also { softwareCopy = it } ?: return null
            } else input
            result = apply.invoke(nativeEntry, source) as? Bitmap ?: return null
            if (result === input || result.width != outWidth.toInt() || result.height != outHeight.toInt() ||
                available.invoke(null) != true || materialPath.invoke(null) != initialPath) {
                if (result !== input) result.recycle()
                result = null
                return null
            }
            return result
        } catch (error: Throwable) {
            result?.takeIf { it !== input && !it.isRecycled }?.recycle()
            throw error
        } finally {
            softwareCopy?.recycle()
            (shellBitmap.get(nativeEntry) as? Bitmap)?.takeIf { !it.isRecycled }?.recycle()
        }
    }
}
