package com.takekazex.hypertweak.hook.rules.camera

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Modifier

object CameraUltraQualityHooker : StaticHooker() {
    private const val TAG = "CamUltraQuality"
    private const val PACKAGE = "com.android.camera"
    private val hostProfile by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        CameraHostProfile.resolve(CameraResolver.Ctx(classLoader, hookParam.appInfo), TAG)
    }

    override fun onHook() {
        if (hookParam.packageName != PACKAGE) return
        CameraApplicationInit.afterConfigProviderCreate(this) {
            installHooks()
        }
    }

    private fun installHooks() {
        val features: List<Pair<String, () -> Unit>> = listOf(
            "hookImageQuality" to { hookImageQuality() },
            "hookSelfieCapabilityGates" to { hookSelfieCapabilityGates() },
            "hookSelfieMirrorPreference" to { hookSelfieMirrorPreference() },
            "hookCommonMirrorPreference" to { hookCommonMirrorPreference() },
        )
        features.forEach { (name, install) ->
            runCatching(install).onFailure { DebugLog.w(TAG, "$name initialization failed; other features continue", it) }
        }
    }

    private fun hookImageQuality() {
        val profile = hostProfile ?: return
        val semantics = CameraSemantics.create(CameraResolver.Ctx(classLoader, hookParam.appInfo), TAG) ?: return
        val readers = semantics.dex.strings("pref_camera_jpegquality_key").filter { ref ->
            semantics.reflect(ref)?.returnType?.isEnum == true
        }
        val gate = semantics.unique("JPEG quality capability", readers.flatMap {
            semantics.dex.code(it).calls.map { call -> call.method }
        }.filter { CameraDexIndex.isInstanceGetter(it, CameraDexIndex.descriptor(profile.configType), "Z") }) ?: return
        val methods = listOfNotNull(gate, profile.configInstance()?.javaClass?.let {
            profile.configMethod(it, gate.name, java.lang.Boolean.TYPE)
        }).distinct()
        methods.forEach { method ->
            deoptimize(method)
            method.hook("cam_ultra_quality_${method.declaringClass.name}") {
                after { param -> if (cameraUltraHdQuality()) param.result = true }
            }
        }
        DebugLog.i(TAG, "native JPEG quality capability hooked through its preference consumer")
    }



    private fun cameraUltraHdQuality(): Boolean =
        Preferences.getBoolean(Preferences.KEY_CAMERA_ULTRA_HD_QUALITY, false)

    private fun hookSelfieMirrorPreference() {
        val clazz = CameraFeatureResolver.selfieSettings(
            CameraResolver.Ctx(classLoader, hookParam.appInfo), TAG,
        ) ?: return
        val method = clazz.declaredMethods.firstOrNull {
            it.name == "addCurrentPreferences" && it.parameterCount == 0
        } ?: return
        val semantics = CameraSemantics.create(CameraResolver.Ctx(classLoader, hookParam.appInfo), TAG) ?: return
        val ids = semantics.dex.preferenceResources("pref_front_mirror_boolean_key")
        if (ids.size != 2) { DebugLog.w(TAG, "mirror preference resources are not unique"); return }
        val groupField = generateSequence(clazz) { it.superclass }.flatMap { it.declaredFields.asSequence() }
            .firstOrNull { it.name == "mPreferenceGroup" }?.apply { isAccessible = true } ?: return
        val add = clazz.methods.singleOrNull {
            it.name == "addCheckBoxPreference" && it.parameterCount == 5 &&
                it.parameterTypes[0].name == "androidx.preference.PreferenceGroup"
        }?.apply { isAccessible = true } ?: return
        deoptimize(method)
        method.hook("cam_selfie_mirror_preference") {
            before { param ->
                if (!Preferences.getBoolean(Preferences.KEY_CAMERA_SELFIE_SETTINGS, false)) return@before
                runCatching {
                    val group = groupField.get(param.thisObject) ?: return@runCatching
                    add.invoke(param.thisObject, group, "pref_front_mirror_boolean_key", true, ids[0], ids[1])
                }.onFailure {
                    DebugLog.w(TAG, "native selfie mirror preference creation skipped", it)
                }
            }
        }
    }

    private fun hookCommonMirrorPreference() {
        val clazz = CameraFeatureResolver.commonSettings(
            CameraResolver.Ctx(classLoader, hookParam.appInfo), TAG,
        ) ?: return
        val method = clazz.declaredMethods.firstOrNull {
            it.name == "addCommonPreferences1" && it.parameterCount == 0
        } ?: return
        val semantics = CameraSemantics.create(CameraResolver.Ctx(classLoader, hookParam.appInfo), TAG) ?: return
        val groupClass = classLoader.loadClass("androidx.preference.PreferenceGroup")
        val groupField = generateSequence(clazz) { it.superclass }.flatMap { it.declaredFields.asSequence() }
            .firstOrNull { it.name == "mPreferenceGroup" }?.apply { isAccessible = true } ?: return
        val find = groupClass.methods.singleOrNull {
            it.parameterTypes.contentEquals(arrayOf(CharSequence::class.java)) &&
                it.returnType.name == "androidx.preference.Preference"
        } ?: return
        val remove = semantics.unique("preference removal", semantics.dex.declared(CameraDexIndex.descriptor(groupClass)).filter { ref ->
            ref.returnType == "Z" && ref.parameterTypes.map(CharSequence::toString) == listOf("Landroidx/preference/Preference;") &&
                semantics.dex.code(ref).calls.any { it.method.name == "remove" && it.method.definingClass == "Ljava/util/ArrayList;" }
        }) ?: return
        deoptimize(method)
        method.hook("cam_remove_common_selfie_mirror") {
            after { param ->
                if (!Preferences.getBoolean(Preferences.KEY_CAMERA_SELFIE_SETTINGS, false)) return@after
                runCatching {
                    val group = groupField.get(param.thisObject) ?: return@runCatching
                    val pref = find.invoke(group, "pref_front_mirror_boolean_key")
                    if (pref != null) remove.invoke(group, pref)
                }.onFailure {
                    DebugLog.w(TAG, "common selfie mirror removal skipped", it)
                }
            }
        }
    }

    private fun hookSelfieCapabilityGates() {
        val semantics = CameraSemantics.create(CameraResolver.Ctx(classLoader, hookParam.appInfo), TAG) ?: return
        val keys = listOf("pref_ai_aperture_key", "pref_beautify_nevus_wipe_switch",
            "pref_beautify_makeup_male_switch", "pref_photo_selfie_setting")
        keys.forEach { key ->
            val gate = semantics.preferenceGate(key) ?: return@forEach
            if (!Modifier.isStatic(gate.modifiers)) return@forEach
            deoptimize(gate)
            gate.hook("cam_selfie_$key") {
                after { param ->
                    if (Preferences.getBoolean(Preferences.KEY_CAMERA_SELFIE_SETTINGS, false)) param.result = true
                }
            }
        }
    }

}
