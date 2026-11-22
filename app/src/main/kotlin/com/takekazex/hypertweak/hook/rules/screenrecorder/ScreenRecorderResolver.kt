package com.takekazex.hypertweak.hook.rules.screenrecorder

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex.Value
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference

internal const val NATIVE_RECORDINGS = "DCIM/ScreenRecorder"
internal const val NATIVE_SCREENSHOTS = "Pictures/Screenshots"
internal const val MOVIE_RECORDINGS = "Movies/ScreenRecorder"
internal const val FRAME_PREFERENCE = "miui.screenrecorder.frame"
internal const val RESOLUTION_PREFERENCE = "miui.screenrecorder.resolution"
internal const val GALLERY_PATH = "local_path"
internal const val DOCUMENTS_PREFIX = "content://com.android.externalstorage.documents/document/primary%3A"

internal class ScreenRecorderResolver(private val dex: DexOperandIndex) {
    private fun MethodReference.key() = DexOperandIndex.descriptor(this)
    private fun MethodReference.calls() = dex.code(this).calls
    private fun MethodReference.calls(owner: String, name: String) =
        calls().any { it.method.definingClass == owner && it.method.name == name }
    private fun MethodReference.calls(target: MethodReference) =
        calls().any { it.method.key() == target.key() }
    private fun MethodReference.shape(params: List<String>, result: String) =
        parameterTypes.map(CharSequence::toString) == params && returnType == result
    private fun MethodReference.callUsing(literal: String, owner: String, name: String, argument: Int) =
        calls().any { it.method.definingClass == owner && it.method.name == name &&
            it.arguments.getOrNull(argument) == Value.Text(literal) }

    fun directory(): Map<String, String> {
        val save = dex.strings("relative_path", "is_pending", NATIVE_RECORDINGS, NATIVE_SCREENSHOTS)
            .single { it.shape(emptyList(), "V") &&
                it.callUsing("relative_path", "Landroid/content/ContentValues;", "put", 1) &&
                it.calls("Landroid/content/ContentResolver;", "insert") }
        val file = dex.strings("ScreenRecorder", "Screenshots", "MediaFileUtils").single { method ->
            method.shape(listOf(CONTEXT), "Ljava/io/File;") &&
                method.calls("Landroid/os/Environment;", "getExternalStorageDirectory") &&
                method.calls("Ljava/io/File;", "mkdir") &&
                dex.code(method).reads.any { it.definingClass == "Landroid/os/Environment;" && it.name == "DIRECTORY_DCIM" }
        }
        val privateDirectoryCall = file.calls().single {
            it.method.definingClass == CONTEXT && it.method.name == "getExternalFilesDir"
        }
        val privateStorage = (dex.code(file).branches.single {
            it.skipsWhenFalse && privateDirectoryCall.address in (it.start + 1) until it.end
        }.value as? Value.Field)?.field
        check(privateStorage?.type == "Z" && dex.classDefinition(privateStorage.definingClass)
            ?.staticFields?.any { it == privateStorage } == true) { "Missing private recording directory gate" }
        val temp = dex.strings("/.Screenrecorder-", ".mp4").single {
            it.shape(listOf(CONTEXT), STRING) && it.calls(file) && save.calls(it)
        }
        val exists = dex.strings("screenRecorderFileExist", "is_pending=0 AND _data LIKE '%/$NATIVE_RECORDINGS/%' ")
            .single { it.shape(listOf(CONTEXT), "Z") && it.calls("Landroid/content/ContentResolver;", "query") }
        val album = dex.strings(GALLERY_PATH, NATIVE_RECORDINGS, NATIVE_SCREENSHOTS, "com.miui.gallery")
            .single {
                it.shape(listOf("Z"), "V") &&
                    it.callUsing(GALLERY_PATH, "Landroid/net/Uri\$Builder;", "appendQueryParameter", 1) &&
                    it.calls(CONTEXT, "startActivity")
            }
        val documentUri = dex.strings(DOCUMENTS_PREFIX + "DCIM%2FScreenRecorder", DOCUMENTS_PREFIX + "Pictures%2FScreenshots")
            .single { it.shape(emptyList(), "Landroid/net/Uri;") && it.calls("Landroid/net/Uri;", "parse") }
        val documentCallers = dex.strings("vnd.android.document/root").filter {
            it.calls(documentUri) && it.calls(file) && it.calls("Landroid/content/Intent;", "setDataAndType")
        }
        check(documentCallers.size == 2) { "Missing recorder document folder callers" }
        return mapOf(
            "save" to save.key(), "directory" to file.key(), "exists" to exists.key(),
            "album" to album.key(), "documentUri" to documentUri.key(),
            "privateStorage" to fieldKey(privateStorage),
            "callers" to (listOf(temp) + documentCallers + dex.declared(save.definingClass).filter { it.calls(save) } +
                dex.declared(album.definingClass).filter { it.calls(album) || it.calls(exists) })
                .distinctBy { it.key() }.joinToString("\n") { it.key() },
        )
    }

    fun frames(): Map<String, String> {
        val readFrame = dex.strings(FRAME_PREFERENCE).single {
            it.shape(emptyList(), "I") && it.callUsing(FRAME_PREFERENCE, PREFS, "getString", 1)
        }
        val resolution = dex.strings(RESOLUTION_PREFERENCE).single {
            it.definingClass == readFrame.definingClass && it.shape(emptyList(), STRING) &&
                it.callUsing(RESOLUTION_PREFERENCE, PREFS, "getString", 1)
        }
        val frames = dex.strings("Error when set frame value, maxValue = ", "fps").single {
            it.definingClass == readFrame.definingClass && it.shape(listOf("I", "I"), "V") &&
                it.calls("Ljava/util/ArrayList;", "add")
        }
        check(dex.declared(readFrame.definingClass).count {
            it.shape(listOf(CONTEXT), "V") && it.calls(frames) &&
                it.calls("Landroid/view/Display;", "getSupportedRefreshRates")
        } == 1) { "Missing native display capability initialization" }
        val prepare = dex.strings("frames", "relative_path", "resolution").single {
            it.shape(emptyList(), "V") && it.calls(readFrame) && it.calls(resolution) &&
                it.callUsing("frames", "Landroid/os/BaseBundle;", "putInt", 1)
        }
        val dropdown = dex.strings(FRAME_PREFERENCE).single {
            it.shape(emptyList(), "V") && it.callUsing(FRAME_PREFERENCE, PREFS, "getString", 1) &&
                dropdownSetters(it).size == 2
        }
        val setters = dropdownSetters(dropdown)
        val options = setters.map { setter ->
            val array = requireNotNull(setter.arguments.getOrNull(1) as? Value.Call)
            val list = array.arguments.firstOrNull() as? Value.Field
            requireNotNull(list?.field).also {
                check(array.method.name == "toArray" && it.definingClass == frames.definingClass &&
                    it.type == "Ljava/util/ArrayList;" && dex.code(frames).writes.any { (field, _) -> field == it })
            }
        }
        val values = options.single { field ->
            frames.calls().any { call ->
                (call.arguments.firstOrNull() as? Value.Field)?.field == field &&
                    (call.arguments.getOrNull(1) as? Value.Call)?.method?.let {
                        it.definingClass == STRING && it.name == "valueOf" && it.shape(listOf("I"), STRING)
                    } == true
            }
        }
        val defaultValue = requireNotNull(dropdown.calls().single {
            it.method.definingClass == PREFS && it.method.name == "getString"
        }.arguments.getOrNull(2) as? Value.Field).field
        check(dex.code(readFrame).reads.contains(defaultValue) && defaultValue.type == STRING)
        val labelsIndex = options.indexOf(options.single { it != values })
        val dropdownCaller = dex.declared(dropdown.definingClass).single { it.calls(dropdown) }
        val config = dex.declared(dropdown.definingClass).filter { it.calls(dropdownCaller) }.flatMap { it.calls() }
            .map { it.method }.distinctBy { it.key() }.single {
                it.definingClass == resolution.definingClass && it.shape(emptyList(), resolution.definingClass) &&
                    dex.definition(it)?.accessFlags?.and(8) == 8
            }
        val preferenceChanged = dex.strings(FRAME_PREFERENCE, RESOLUTION_PREFERENCE).single {
            it.definingClass == dropdown.definingClass && it.shape(listOf(PREFS, STRING), "V")
        }
        val activitySetup = dex.methods.asSequence().filter {
            it.definingClass.startsWith("Lcom/miui/screenrecorder/activity/") && it.returnType == "V"
        }.single { method ->
            method.calls(dropdown.definingClass, "<init>") &&
                method.calls().any { call ->
                    call.method.parameterTypes.map(CharSequence::toString) ==
                        listOf("I", "Landroidx/fragment/app/Fragment;", STRING) &&
                        call.method.returnType == call.method.definingClass
                }
        }
        val findFragment = activitySetup.calls().single {
            it.method.definingClass == "Landroidx/fragment/app/FragmentManager;" &&
                it.method.shape(listOf(STRING), "Landroidx/fragment/app/Fragment;")
        }
        val fragmentManager = requireNotNull(findFragment.arguments.firstOrNull() as? Value.Call).method
        check(fragmentManager.shape(emptyList(), findFragment.method.definingClass))
        val codecSetup = dex.strings("video/avc", "frame-rate").filter {
            it.calls("Landroid/media/MediaCodec;", "createEncoderByType") && it.calls("Landroid/media/MediaCodec;", "configure")
        }
        check(codecSetup.size == 2 && codecSetup.count { dex.code(it).hasString("video/hevc") } == 1)
        return mapOf(
            "readFrame" to readFrame.key(), "prepare" to prepare.key(), "resolution" to resolution.key(),
            "config" to config.key(), "dropdown" to dropdown.key(), "dropdownCaller" to dropdownCaller.key(),
            "labelsSetter" to setters[labelsIndex].method.key(), "valuesSetter" to setters[1 - labelsIndex].method.key(),
            "values" to fieldKey(values), "default" to fieldKey(defaultValue),
            "preferenceChanged" to preferenceChanged.key(), "activity" to activitySetup.definingClass,
            "fragmentManager" to fragmentManager.key(), "findFragment" to findFragment.method.key(),
        )
    }

    private fun dropdownSetters(method: MethodReference) = method.calls().filter {
        it.method.definingClass == "Lmiuix/preference/DropDownPreference;" &&
            it.method.shape(listOf("[Ljava/lang/CharSequence;"), "V")
    }

    private fun fieldKey(field: FieldReference) = MethodDescriptorCodec.descriptor(field)

    private companion object {
        const val CONTEXT = "Landroid/content/Context;"
        const val STRING = "Ljava/lang/String;"
        const val PREFS = "Landroid/content/SharedPreferences;"
    }
}
