package com.takekazex.hypertweak.hook.rules.camera

import android.content.res.Resources
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/** Unlocks 超高图片质量 and selfie settings through feature keys, config ABI, and resource
 * content. Camera releases move these owners and generated resource IDs, so this hook resolves
 * the quality provider from its preference key, the selfie gates from the mirror preference,
 * and translated mirror labels from resource text. Each target must satisfy its full signature.
 */
object CameraUltraQualityHooker : StaticHooker() {
    private const val TAG = "CamUltraQuality"
    private const val PACKAGE = "com.android.camera"
    private val mirrorTextIds = ConcurrentHashMap<String, Int>()

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
        if (hostProfile?.family == CameraHostProfile.Family.CAMERA_66) {
            val gate = resolveSuperQualityGate()
            if (gate != null) {
                deoptimize(gate)
                gate.hook("cam_ultra_hd_quality") {
                    before { param -> param.result = cameraUltraHdQuality() }
                }
                DebugLog.d(TAG, "ultra image-quality gate hooked on ${gate.declaringClass.name}#${gate.name}()")
            } else {
                DebugLog.w(TAG, "legacy config l7 gate not resolved; ultra image-quality unlock skipped")
            }
        } else if (hostProfile?.family == CameraHostProfile.Family.CAMERA_68) {
            hookModernImageQuality()
        }
        // Selfie settings have independent host targets and must survive a quality-gate rename.
        hookSelfieCapabilityGates()
        hookSelfieMirrorPreference()
        hookCommonMirrorPreference()
    }

    /**
     * Camera 6.8 inlined the old `l7()` gate: its quality lists are fixed 3-entry resources
     * and `data.data.i#t()` always clamps SUPER back to HIGH. Extend the two specific host
     * arrays and the final clamp while the switch is on. The resource values and enum shape
     * are checked before each replacement, so an updated APK with reused IDs passes through.
     */
    private fun hookModernImageQuality() {
        val arrayIds = runCatching {
            val owner = classLoader.loadClass("com.android.camera.R\$array")
            owner.getField("di").getInt(null) to owner.getField("dj").getInt(null)
        }.getOrNull() ?: run {
            DebugLog.w(TAG, "6.8 image-quality resource IDs not resolved")
            return
        }
        val (labelsId, valuesId) = arrayIds
        val arrays = Resources::class.java.getMethod("getStringArray", Int::class.javaPrimitiveType)
        deoptimize(arrays)
        arrays.hook("cam_ultra_quality_lists") {
            after { param ->
                if (!cameraUltraHdQuality()) return@after
                val id = param.args.getOrNull(0) as? Int ?: return@after
                val values = param.result as? Array<*> ?: return@after
                if (values.any { it !is String }) return@after
                @Suppress("UNCHECKED_CAST")
                val strings = values as Array<String>
                when (id) {
                    valuesId -> if (strings.contentEquals(arrayOf("high", "normal", "low"))) {
                        param.result = arrayOf("super", *strings)
                    }
                    labelsId -> if (strings.size == 3) {
                        val resources = param.thisObject as? Resources ?: return@after
                        val language = resources.configuration.locales[0].language
                        param.result = arrayOf(if (language == "zh") "超高" else "Ultra", *strings)
                    }
                }
            }
        }

        val settings = CameraFeatureResolver.qualitySettings(
            CameraResolver.Ctx(classLoader, hookParam.appInfo), TAG,
        ) ?: return
        val selectedQuality = runCatching {
            settings.getDeclaredMethod("R", String::class.java, String::class.java).takeIf {
                Modifier.isStatic(it.modifiers) && it.returnType == String::class.java
            }
        }.getOrNull() ?: return
        val effectiveQuality = runCatching {
            settings.getDeclaredMethod("t").takeIf {
                Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType.isEnum
            }
        }.getOrNull() ?: return
        deoptimize(effectiveQuality)
        effectiveQuality.hook("cam_ultra_quality_clamp") {
            after { param ->
                if (!cameraUltraHdQuality()) return@after
                val stored = runCatching {
                    selectedQuality.invoke(null, "pref_camera_jpegquality_key", "high") as? String
                }.getOrNull() ?: return@after
                if (!stored.equals("super", ignoreCase = true)) return@after
                val superQuality = effectiveQuality.returnType.enumConstants?.firstOrNull {
                    (it as? Enum<*>)?.name == "SUPER"
                } ?: return@after
                param.result = superQuality
            }
        }
        DebugLog.i(TAG, "6.8 ultra image-quality lists and clamp hooked")
    }

    /** Resolve the legacy gate through the structurally selected device-config field type. */
    private fun resolveSuperQualityGate(): Method? {
        val profile = hostProfile ?: return null
        runCatching { profile.configType.getMethod("l7") }
            .getOrNull()?.takeIf(::isSuperQualityGate)?.let {
                DebugLog.d(TAG, "legacy config gate resolved via ${profile.configType.name}")
                return it
            }
        profile.configInstance()?.javaClass?.let { concrete ->
            runCatching { concrete.getMethod("l7") }
                .getOrNull()?.takeIf(::isSuperQualityGate)?.let {
                    DebugLog.d(TAG, "legacy config gate resolved via ${concrete.name}")
                    return it
                }
        }
        DebugLog.w(TAG, "no zero-arg boolean l7() found on the config hierarchy")
        return null
    }

    /** Zero-arg instance method returning a primitive boolean — the `l7()` shape. */
    private fun isSuperQualityGate(method: Method): Boolean =
        method.parameterTypes.isEmpty() &&
            method.returnType == java.lang.Boolean.TYPE &&
            !Modifier.isStatic(method.modifiers)

    /**
     * Live read of the 超高图片质量 pin switch (same accessor pattern as the impersonation
     * hookers' `streetEnable()` / `masterliveTeleFallback()`; served from the 100 ms
     * Preferences memo). Default OFF = 固定解锁.
     */
    private fun cameraUltraHdQuality(): Boolean =
        Preferences.getBoolean(Preferences.KEY_CAMERA_ULTRA_HD_QUALITY, false)

    private fun hookSelfieMirrorPreference() {
        val clazz = CameraFeatureResolver.selfieSettings(
            CameraResolver.Ctx(classLoader, hookParam.appInfo), TAG,
        ) ?: return
        val method = clazz.declaredMethods.firstOrNull {
            it.name == "addCurrentPreferences" && it.parameterCount == 0
        } ?: return
        deoptimize(method)
        method.hook("cam_selfie_mirror_preference") {
            before { param ->
                if (!Preferences.getBoolean(Preferences.KEY_CAMERA_SELFIE_SETTINGS, false)) return@before
                runCatching {
                    val resources = clazz.getMethod("getResources").invoke(param.thisObject) as Resources
                    val titleId = hostStringId(resources, title = true) ?: return@runCatching
                    val summaryId = hostStringId(resources, title = false) ?: return@runCatching
                    val base: Class<*> = clazz.superclass ?: return@runCatching
                    val groupField = generateSequence<Class<*>>(base) { it.superclass }
                        .flatMap { it.declaredFields.asSequence() }
                        .first { it.name == "mPreferenceGroup" }
                        .apply { isAccessible = true }
                    val group = groupField.get(param.thisObject) ?: return@runCatching
                    val add = generateSequence<Class<*>>(base) { it.superclass }
                        .flatMap { it.declaredMethods.asSequence() }
                        .first {
                            it.name == "addCheckBoxPreference" && it.parameterTypes.size == 5 &&
                                it.parameterTypes[0].isAssignableFrom(group.javaClass)
                        }
                        .apply { isAccessible = true }
                    add.invoke(param.thisObject, group, "pref_front_mirror_boolean_key", true, titleId, summaryId)
                }.onFailure {
                    DebugLog.w(TAG, "native selfie mirror preference creation skipped", it)
                }
            }
        }
    }

    /** Find host string resources by their localized content so resource IDs/names may move. */
    private fun hostStringId(resources: Resources, title: Boolean): Int? {
        val language = runCatching { resources.configuration.locales[0].language }.getOrDefault("en")
        val titleValue = if (language == "zh") "自拍镜像" else "Mirror front camera"
        val summaryValue = if (language == "zh") "自拍场景下，成片与预览画面完全一致" else "Selfies will match the preview"
        val value = if (title) titleValue else summaryValue
        return mirrorTextIds[value] ?: runCatching {
            val strings = classLoader.loadClass("com.android.camera.R\$string")
            val matchingIds = strings.declaredFields.asSequence()
                .filter { Modifier.isStatic(it.modifiers) && it.type == Integer.TYPE }
                .mapNotNull { field -> runCatching { field.getInt(null) }.getOrNull() }
                .distinct()
                .filter { id -> runCatching { resources.getString(id) == value }.getOrDefault(false) }
                .toList()
            matchingIds.singleOrNull()?.also { mirrorTextIds[value] = it }
        }.getOrNull()
    }

    private fun hookCommonMirrorPreference() {
        val clazz = CameraFeatureResolver.commonSettings(
            CameraResolver.Ctx(classLoader, hookParam.appInfo), TAG,
        ) ?: return
        val method = clazz.declaredMethods.firstOrNull {
            it.name == "addCommonPreferences1" && it.parameterCount == 0
        } ?: return
        deoptimize(method)
        method.hook("cam_remove_common_selfie_mirror") {
            after { param ->
                if (!Preferences.getBoolean(Preferences.KEY_CAMERA_SELFIE_SETTINGS, false)) return@after
                runCatching {
                    val field = generateSequence(clazz) { it.superclass }
                        .flatMap { it.declaredFields.asSequence() }
                        .first { it.name == "mPreferenceGroup" }
                        .apply { isAccessible = true }
                    val group = field.get(param.thisObject)
                    val remove = group.javaClass.methods.firstOrNull {
                        it.name == "n0" && it.parameterTypes.size == 1
                    } ?: return@runCatching
                    val pref = group.javaClass.methods.firstOrNull {
                        it.name == "k0" && it.parameterTypes.size == 1
                    }?.invoke(group, "pref_front_mirror_boolean_key")
                    if (pref != null) remove.invoke(group, pref)
                }.onFailure {
                    DebugLog.w(TAG, "common selfie mirror removal skipped", it)
                }
            }
        }
    }

    /**
     * Expose the complete stock selfie-settings page on devices that hide capability rows.
     *
     * RAISE-ONLY, and gated by [Preferences.KEY_CAMERA_SELFIE_SETTINGS] on every call: with the
     * switch on the gate is forced open, with it off the native result is left untouched (never
     * lowered — some devices already show the full page). An earlier build set `result = true`
     * unconditionally here, which unlocked the selfie capabilities even with the switch off.
     */
    private fun hookSelfieCapabilityGates() {
        val modern = hostProfile?.family == CameraHostProfile.Family.CAMERA_68
        val names = if (modern) listOf("E", "e0", "a0", "R0") else listOf("F", "c0", "Y", "O0")
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
        val clazz = CameraResolver.resolveClass(
            scope = TAG,
            key = "selfie_capability_owner",
            ctx = ctx,
            candidates = emptyList(),
            probe = { bridge ->
                bridge.findClass { matcher { usingStrings("pref_front_mirror_boolean_key") } }
                    .mapNotNull { runCatching { it.getInstance(classLoader) }.getOrNull() }
                    .filter { owner -> hasSelfieGateSurface(owner, names) }
                    .distinctBy { it.name }
                    .singleOrNull()?.name
            },
            validate = { hasSelfieGateSurface(it, names) },
        )
            ?: return
        val gates = names.map { name ->
            clazz.declaredMethods.filter {
                it.name == name && Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == 0 && it.returnType == java.lang.Boolean.TYPE && !it.isSynthetic
            }.singleOrNull()
        }
        if (gates.any { it == null }) {
            DebugLog.w(TAG, "selfie capability gates incomplete on ${clazz.name}; skipped")
            return
        }
        gates.filterNotNull().forEach { method ->
            val name = method.name
            deoptimize(method)
            method.hook("cam_selfie_unlock_$name") {
                before { param ->
                    if (!Preferences.getBoolean(Preferences.KEY_CAMERA_SELFIE_SETTINGS, false)) return@before
                    param.result = true
                }
            }
        }
        DebugLog.i(TAG, "selfie capability gates hooked=${gates.size} on ${clazz.name}")
    }

    private fun hasSelfieGateSurface(clazz: Class<*>, names: List<String>): Boolean =
        names.all { name ->
            clazz.declaredMethods.count {
                it.name == name && Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                    it.returnType == java.lang.Boolean.TYPE && !it.isSynthetic
            } == 1
        }
}
