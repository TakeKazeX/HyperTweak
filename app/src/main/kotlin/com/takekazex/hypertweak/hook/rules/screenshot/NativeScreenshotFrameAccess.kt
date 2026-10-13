package com.takekazex.hypertweak.hook.rules.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Path
import android.net.Uri
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.security.MessageDigest

/** The obfuscated save layout is used only with the exact APK whose bytecode was audited. */
internal class NativeScreenshotFrameAccess(private val loader: ClassLoader) {
    companion object {
        const val APK_SHA256 = "59c9d09986c4107c84ad74281b3985fb969a488f2d4056208619b397683c6322"
        fun supports(apk: File): Boolean {
            val digest = MessageDigest.getInstance("SHA-256")
            apk.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) } == APK_SHA256
        }
    }

    private fun load(name: String) = loader.loadClass(name)
    private fun field(type: Class<*>, name: String, valueType: Class<*>): Field =
        type.getDeclaredField(name).apply {
            check(this.type == valueType) { "Unexpected ${type.name}.$name layout" }
            isAccessible = true
        }
    private fun method(type: Class<*>, name: String, vararg args: Class<*>): Method =
        type.getDeclaredMethod(name, *args).apply { isAccessible = true }

    private val data = load("com.miui.screenshot.X")
    private val task = load("com.miui.screenshot.a0")
    private val info = load("com.miui.screenshot.h0")
    private val controller = load("com.miui.screenshot.z")
    private val screenshot = load("com.miui.screenshot.core.domain.ScreenshotData")
    private val dataArray = java.lang.reflect.Array.newInstance(data, 0).javaClass
    private val entry = load("com.miui.gallery.editor.photo.screen.shell.ScreenShellEntry")
    private val shellInfo = load("com.miui.gallery.editor.photo.screen.shell.ShellInfoBean")
    private val fetcher = load("com.miui.gallery.editor.photo.screen.shell.res.ShellResourceFetcher")
    private val bool = Boolean::class.javaPrimitiveType!!
    private val int = Int::class.javaPrimitiveType!!
    private val float = Float::class.javaPrimitiveType!!

    val request = controller.declaredMethods.single {
        it.parameterTypes.size == 3 && it.parameterTypes[0] == screenshot &&
            it.parameterTypes[1] == java.util.function.Consumer::class.java && it.returnType == Void.TYPE
    }.apply { isAccessible = true }
    val save = method(controller, "X1", load("com.miui.screenshot.interfaces.ISaveCallback"),
        load("com.miui.screenshot.interfaces.ITotalFinishCallback"))
    val taskConstructor = task.getDeclaredConstructor(data, load("p1.a"), bool).apply { isAccessible = true }
    val worker = method(task, "d", dataArray).apply { check(returnType == data) }
    // Cold injection worked while the already-running process bypassed these small private calls.
    // Deoptimize the enclosing save and bridge bodies as well as the hooked members on hot reload.
    val saveCaller = method(controller, "k2", Runnable::class.java,
        load("com.miui.screenshot.interfaces.ISaveCallback"),
        load("com.miui.screenshot.interfaces.ITotalFinishCallback"), bool)
    val workerBridge = method(task, "doInBackground", Array<Any>::class.java)
    val saveCallChain get() = listOf(request, saveCaller, save, taskConstructor, worker, workerBridge, write)
    val thumbnailReady = method(controller, "g2", Runnable::class.java)
    val captureBitmap = field(controller, "g", Bitmap::class.java)
    val thumbnail = field(controller, "k", android.widget.ImageView::class.java)
    private val drawable = load("com.miui.gallery.editor.transition.RoundedCornerDrawable")
    private val thumbnailDrawable = field(controller, "n", drawable)
    private val setPreviewBitmap = method(drawable, "setBitmap", Bitmap::class.java)
    private val setPreviewRadius = method(drawable, "setCornerRadius", float)

    fun updatePreview(owner: Any, original: Bitmap, preview: Bitmap) {
        if (captureBitmap.get(owner) !== original) return
        val target = thumbnailDrawable.get(owner) ?: return
        setPreviewBitmap.invoke(target, preview)
        setPreviewRadius.invoke(target, 0f)
        (thumbnail.get(owner) as? android.widget.ImageView)?.invalidate()
    }
    val write = method(task, "e", Context::class.java, dataArray, Bitmap::class.java, java.io.OutputStream::class.java)
    val bitmap = field(data, "b", Bitmap::class.java)
    val context = field(data, "a", Context::class.java)
    val format = field(data, "e", Bitmap.CompressFormat::class.java)
    val notify = field(task, "a", info)
    val taskWidth = field(task, "d", int)
    val taskHeight = field(task, "e", int)
    val fileTemplate = field(task, "i", String::class.java)
    val width = field(info, "n", Int::class.javaObjectType)
    val height = field(info, "o", Int::class.javaObjectType)
    val uri = field(info, "g", Uri::class.java)
    val path = field(info, "k", String::class.java)
    val success = field(info, "h", Boolean::class.javaObjectType)
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
