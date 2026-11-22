package com.takekazex.hypertweak.hook.rules.screenrecorder

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.view.Display
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.HostActivityRefresh
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.dex.DexFeature
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.ScreenRecorderSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.util.platform.PlatformLevel
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.roundToInt

private enum class RecorderFeature(override val id: String) : DexFeature {
    DIRECTORY("screenrecorder.directory"),
    FRAMES("screenrecorder.frames");

    override val schema = 1
}

private class RecorderMembers(private val values: Map<String, String>, private val loader: ClassLoader) {
    fun method(key: String): Method = methodDescriptor(values.getValue(key))
    fun methods(key: String) = values.getValue(key).lineSequence().map(::methodDescriptor).toList()
    fun field(key: String): Field = requireNotNull(MethodDescriptorCodec.field(values.getValue(key), loader))
    fun type(key: String): Class<*> = requireNotNull(MethodDescriptorCodec.type(values.getValue(key), loader))
    private fun methodDescriptor(descriptor: String) = requireNotNull(MethodDescriptorCodec.method(descriptor, loader))
}

object ScreenRecorderHooker : StaticHooker() {
    private const val TAG = "ScreenRecorder"
    private val saveToMovies by hookSetting(false) {
        Preferences.getBoolean(ScreenRecorderSettingsKeys.KEY_SAVE_TO_MOVIES, false)
    }
    private val moreFrameRates by hookSetting(false) {
        Preferences.getBoolean(ScreenRecorderSettingsKeys.KEY_MORE_FRAME_RATES, false)
    }

    private var frameAccess by generationState<FrameAccess?> { null }
    private var menuExpanded by generationState { false }

    override fun onHook() {
        if (!PlatformLevel.isOs4) return
        val apk = hookParam.appInfo?.sourceDir ?: return
        prepare(apk, RecorderFeature.DIRECTORY, ScreenRecorderResolver::directory, ::installDirectory)
        prepare(apk, RecorderFeature.FRAMES, ScreenRecorderResolver::frames, ::installFrames)
    }

    private fun prepare(
        apk: String,
        feature: RecorderFeature,
        compute: (ScreenRecorderResolver) -> Map<String, String>,
        install: (RecorderMembers) -> Unit,
    ) {
        fun resolve(cacheOnly: Boolean) = DexRuntime.resolve(
            feature = feature,
            apkPath = apk,
            cacheDir = dexCacheDirectory,
            cacheOnly = cacheOnly,
            compute = { compute(ScreenRecorderResolver(DexRuntime.operands(apk))) },
            decode = { values, _ -> RecorderMembers(values, classLoader) },
        )
        val cached = resolve(cacheOnly = true)
        if (cached == null) {
            prepareDexTargets(feature.id, resolve = { resolve(cacheOnly = false) }, install = install)
        } else {
            boundary("install ${feature.id}", Unit) { installDexContract { install(cached) } }
        }
    }

    private class SaveDecision(val enabled: Boolean, var redirected: Boolean = false)

    private fun installDirectory(access: RecorderMembers) {
        val save = access.method("save")
        val directory = access.method("directory")
        val album = access.method("album")
        val exists = access.method("exists")
        val documentUri = access.method("documentUri")
        val privateStorage = access.field("privateStorage")
        fun publicRedirectEnabled() = saveToMovies && boundary("read recorder storage mode", false) {
            !privateStorage.getBoolean(null)
        }
        deoptimizeCallers(listOf(save, directory, album, exists, documentUri) + access.methods("callers"))

        val saveScope = ThreadLocal<SaveDecision>()
        val albumScope = ThreadLocal<Boolean>()
        val queryScope = ThreadLocal<Boolean>()
        save.hook("recording_destination_scope") {
            before { saveScope.set(SaveDecision(publicRedirectEnabled())) }
            after { saveScope.remove() }
        }
        directory.hook("recording_directory") {
            before { param ->
                val saveDecision = saveScope.get()
                if (!(saveDecision?.enabled ?: publicRedirectEnabled())) return@before
                boundary("redirect filesystem directory", Unit) {
                    val target = File(Environment.getExternalStorageDirectory(), MOVIE_RECORDINGS)
                    if (target.isDirectory || target.mkdirs() || target.isDirectory) {
                        saveDecision?.redirected = true
                        param.result = target
                    } else {
                        DebugLog.w(TAG, "Unable to create $MOVIE_RECORDINGS")
                    }
                }
            }
        }
        ContentResolver::class.java.getMethod("insert", Uri::class.java, ContentValues::class.java)
            .hook("recording_media_store_location") {
                before { param ->
                    if (saveScope.get()?.redirected != true || param.args[0] != MediaStore.Video.Media.EXTERNAL_CONTENT_URI) return@before
                    val values = param.args[1] as? ContentValues ?: return@before
                    if (isNativeDirectory(values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))) {
                        param.args[1] = ContentValues(values).apply {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, MOVIE_RECORDINGS)
                        }
                        DebugLog.i(TAG, "Recording destination selected: $MOVIE_RECORDINGS")
                    }
                }
            }
        exists.hook("recording_query_scope") {
            before { queryScope.set(publicRedirectEnabled()) }
            after { queryScope.remove() }
        }
        ContentResolver::class.java.getMethod(
            "query", Uri::class.java, Array<String>::class.java, String::class.java,
            Array<String>::class.java, String::class.java,
        ).hook("recording_exists_query") {
            before { param ->
                if (queryScope.get() != true || param.args[0] != MediaStore.Video.Media.EXTERNAL_CONTENT_URI) return@before
                when (param.args[2]) {
                    "is_pending=0 AND _data LIKE '%/$NATIVE_RECORDINGS/%' ",
                    "is_pending=0 AND _data LIKE '%/$NATIVE_SCREENSHOTS/%' " ->
                        param.args[2] = "is_pending=0 AND relative_path = '$MOVIE_RECORDINGS/'"
                }
            }
        }
        album.hook("recording_album_scope") {
            before { albumScope.set(publicRedirectEnabled()) }
            after { albumScope.remove() }
        }
        Uri.Builder::class.java.getMethod("appendQueryParameter", String::class.java, String::class.java)
            .hook("recording_album_location") {
                before { param ->
                    if (albumScope.get() == true && param.args[0] == GALLERY_PATH && isNativeDirectory(param.args[1] as? String)) {
                        param.args[1] = MOVIE_RECORDINGS
                    }
                }
            }
        documentUri.hook("recording_documents_location") {
            after { param ->
                if (!saveToMovies) return@after
                val original = (param.result as? Uri)?.toString() ?: return@after
                if (original.startsWith(DOCUMENTS_PREFIX) &&
                    isNativeDirectory(Uri.decode(original.removePrefix(DOCUMENTS_PREFIX)))) {
                    param.result = Uri.parse(DOCUMENTS_PREFIX + Uri.encode(MOVIE_RECORDINGS))
                }
            }
        }
        DebugLog.hookRegistered(TAG, "recording destination and folder entry points")
    }

    private class FrameAccess(access: RecorderMembers) {
        val readFrame = access.method("readFrame")
        val prepare = access.method("prepare")
        val resolution = access.method("resolution")
        val config = access.method("config")
        val dropdown = access.method("dropdown")
        val dropdownCaller = access.method("dropdownCaller")
        val labelsSetter = access.method("labelsSetter")
        val valuesSetter = access.method("valuesSetter")
        val values = access.field("values")
        val default = access.field("default")
        val preferenceChanged = access.method("preferenceChanged")
        val settingsActivity = access.type("activity")
        val fragmentManager = access.method("fragmentManager")
        val findFragment = access.method("findFragment")
        val application = HostAccess.requirePublicMethod(
            Class.forName("android.app.ActivityThread"), "currentApplication", accessible = false,
        )

        fun nativeRates() = (values.get(null) as? List<*>)?.mapNotNull {
            (it as? String)?.toIntOrNull()
        }.orEmpty()

        fun extraRates(configuration: Any, floor: Int): List<Int> {
            val dimensions = (resolution.invoke(configuration) as? String)?.split('*', limit = 2)
                ?: return emptyList()
            val width = dimensions.firstOrNull()?.toIntOrNull()?.takeIf { it > 0 } ?: return emptyList()
            val height = dimensions.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 } ?: return emptyList()
            val context = application.invoke(null) as? Context ?: return emptyList()
            val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
                ?: return emptyList()
            val candidates = display.supportedModes.asSequence().map { it.refreshRate.roundToInt() }
                .filter { it > floor }.distinct().sorted().toList()
            if (candidates.isEmpty()) return emptyList()

            // The two verified host routes call createEncoderByType with AVC or HEVC.
            // Check the first regular encoder that Android advertises for each applicable MIME.
            val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder }
            val avc = encoders.firstOrNull { MediaFormat.MIMETYPE_VIDEO_AVC in it.supportedTypes } ?: return emptyList()
            val hevc = encoders.firstOrNull { MediaFormat.MIMETYPE_VIDEO_HEVC in it.supportedTypes }
            val codecs = listOf(avc to MediaFormat.MIMETYPE_VIDEO_AVC) +
                listOfNotNull(hevc?.let { it to MediaFormat.MIMETYPE_VIDEO_HEVC })
            val capabilities = codecs.map { (codec, mime) ->
                val caps = codec.getCapabilitiesForType(mime)
                if (!codec.isHardwareAccelerated || MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface !in caps.colorFormats) {
                    return emptyList()
                }
                caps.videoCapabilities ?: return emptyList()
            }
            return candidates.filter { fps ->
                capabilities.all { caps ->
                    caps.areSizeAndRateSupported(width, height, fps.toDouble()) &&
                        caps.areSizeAndRateSupported(height, width, fps.toDouble())
                }
            }
        }
    }

    private fun installFrames(members: RecorderMembers) {
        val access = FrameAccess(members)
        deoptimizeCallers(listOf(access.prepare, access.dropdown, access.dropdownCaller, access.preferenceChanged))
        val extras = ThreadLocal<List<Int>>()
        access.dropdown.hook("recording_frame_menu_scope") {
            before {
                if (moreFrameRates) {
                    val added = boundary("calculate frame options", emptyList()) {
                        val configuration = access.config.invoke(null) ?: return@boundary emptyList()
                        val nativeMax = access.nativeRates().maxOrNull() ?: return@boundary emptyList()
                        access.extraRates(configuration, nativeMax)
                    }
                    extras.set(added)
                    if (added.isNotEmpty()) DebugLog.d(TAG, "Additional recording rates: $added")
                }
            }
            after { extras.remove() }
        }
        for ((setter, labels) in listOf(access.labelsSetter to true, access.valuesSetter to false)) {
            setter.hook("recording_frame_dropdown") {
                before { param ->
                    val added = extras.get()?.takeIf { it.isNotEmpty() } ?: return@before
                    val original = param.args[0] as? Array<*> ?: return@before
                    param.args[0] = Array<CharSequence>(original.size + added.size) { index ->
                        if (index < original.size) original[index] as? CharSequence ?: ""
                        else if (labels) "${added[index - original.size]}fps" else added[index - original.size].toString()
                    }
                }
            }
        }
        access.readFrame.hook("recording_frame_selection") {
            after { param ->
                val selected = param.result as? Int ?: return@after
                boundary("validate saved frame rate", Unit) {
                    val native = access.nativeRates()
                    val max = native.maxOrNull() ?: return@boundary
                    if (selected <= max) return@boundary
                    val fallback = (access.default.get(null) as? String)?.toIntOrNull()?.takeIf { it in native } ?: max
                    val configuration = param.thisObject
                    val allowed = moreFrameRates &&
                        selected in boundary("calculate recording rates", emptyList()) { access.extraRates(configuration, max) }
                    if (!allowed) param.result = fallback
                }
            }
        }
        access.preferenceChanged.hook("recording_resolution_changed") {
            after { param ->
                if (moreFrameRates && param.args[1] == RESOLUTION_PREFERENCE) {
                    boundary("refresh frame menu after resolution change", Unit) {
                        access.dropdown.invoke(param.thisObject)
                    }
                }
            }
        }
        frameAccess = access
        refreshSettings()
        DebugLog.hookRegistered(TAG, "adaptive recording frame options")
    }

    fun refreshSettings(): Boolean = boundary("refresh existing recorder settings", false) {
        HostAccess.onMain {
            if (menuExpanded == moreFrameRates) return@onMain
            val access = frameAccess ?: return@onMain
            refreshOpenMenus(access)
            menuExpanded = moreFrameRates
        }
        true
    }

    private fun refreshOpenMenus(access: FrameAccess) {
        HostActivityRefresh.activities().filter(access.settingsActivity::isInstance).forEach { activity ->
            val manager = access.fragmentManager.invoke(activity) ?: return@forEach
            val fragment = access.findFragment.invoke(manager, access.dropdown.declaringClass.simpleName)
                ?: return@forEach
            access.dropdown.invoke(fragment)
        }
    }

    private fun deoptimizeCallers(methods: List<Method>) {
        methods.distinct().forEach { check(deoptimize(it)) { "Cannot prepare screen recorder caller: $it" } }
    }

    private fun isNativeDirectory(path: String?) = path == NATIVE_RECORDINGS || path == NATIVE_SCREENSHOTS

    private fun <T> boundary(operation: String, fallback: T, block: () -> T) =
        HookFailurePolicy.open(TAG, operation, fallback, block)
}
