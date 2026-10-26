package com.takekazex.hypertweak.hook.rules.camera

import com.takekazex.hypertweak.hook.base.cachedPublicMethod
import com.takekazex.hypertweak.hook.base.HostAccess
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.SparseArray
import androidx.core.util.isNotEmpty
import com.takekazex.hypertweak.settings.contract.CameraLegendaryMomentMode
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.settings.contract.CameraSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.util.platform.StaticFieldWriter
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType

object CameraImpersonationHooker : StaticHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingCameraImpersonateThemeLcc by hookSetting(false) {
        Preferences.getBoolean(CameraSettingsKeys.KEY_CAMERA_IMPERSONATE_THEME_LCC, false)
    }
    private val settingCameraAllShutterSounds by hookSetting(false) {
        Preferences.getBoolean(CameraSettingsKeys.KEY_CAMERA_ALL_SHUTTER_SOUNDS, false)
    }
    private val settingCameraLegendaryMomentMode by hookSetting(CameraLegendaryMomentMode.DEFAULT) { Preferences.cameraLegendaryMomentMode() }
    private val settingCameraLegendaryExtraModes by hookSetting(false) {
        Preferences.getBoolean(CameraSettingsKeys.KEY_CAMERA_LEGENDARY_EXTRA_MODES, false,)
    }
    private val settingCameraLeicaStyle by hookSetting(false) { Preferences.getBoolean(CameraSettingsKeys.KEY_CAMERA_LEICA_STYLE, false) }
    private val settingCameraSmartComposition by hookSetting(false) {
        Preferences.getBoolean(CameraSettingsKeys.KEY_CAMERA_SMART_COMPOSITION, false)
    }
    private val settingCameraContentCredential by hookSetting(false) {
        Preferences.getBoolean(CameraSettingsKeys.KEY_CAMERA_CONTENT_CREDENTIAL, false)
    }
    private val settingCameraAdaptiveLens by hookSetting(false) { Preferences.getBoolean(CameraSettingsKeys.KEY_CAMERA_ADAPTIVE_LENS, false) }

    private const val TAG = "CamImpersonate"
    private const val PACKAGE = "com.android.camera"
    private const val TINT_COLOR_PREFERENCE_KEY = "pref_tint_color"
    private const val CUSTOMIZATION_CATEGORY_KEY = "category_customization"
    private const val MIUI_WIDGET_METADATA_KEY = "miuiWidget"
    private const val DEFAULT_WIDGET_LAYOUT_METADATA_KEY = "defaultLayoutInPA"
    private const val APP_WIDGET_PROVIDER_METADATA_KEY = "android.appwidget.provider"

    private val config = CameraConfigAccess(this)
    private val hostProfile get() = config.profile
    private val masterLive = CameraMasterLiveHooks(this, config, ::legendaryMomentUnlock)
    private val shutterSoundLoadScope = ThreadLocal<ArrayDeque<Boolean>>()

    private val legendaryEntryClass = AtomicReference<Class<*>?>(null)
    private val originalThirdSlot = AtomicReference<String?>(null)
    private val disabledWidgetReceiverNames = AtomicReference<Set<String>?>(null)
    private val disabledWidgetReceiverNamesLock = Any()

    override fun onHook() {
        com.takekazex.hypertweak.hook.rules.camera.prepareCameraWatermarkBrandSettings()
        com.takekazex.hypertweak.hook.rules.camera.prepareCameraMasterLiveHooksSettings()
        if (hookParam.packageName != PACKAGE) return
        // Config-dependent hooks read the active profile. Wait until its provider initializes it,
        // while still installing these gates before Application.onCreate builds camera modes.
        CameraApplicationInit.afterConfigProviderCreate(this) {
            installHooks()
        }
    }

    private fun installHooks() {
        val features: List<Pair<String, () -> Unit>> = listOf(
            "resolveMasterLiveType" to { masterLive.resolveMasterLiveType() },
            "hookWatermarkKeep" to { hookWatermarkKeep() },
            "hookWatermarkConfigCache" to { hookWatermarkConfigCache() },
            "hookWatermarkRender" to { hookWatermarkRender() },
            "hookWatermarkBrandText" to { hookWatermarkBrandText() },
            "hookLccTheme" to { hookLccTheme() },
            "hookLccCustomizationProvider" to { hookLccCustomizationProvider() },
            "hookDisabledWidgetReceiverMetadataLookup" to { hookDisabledWidgetReceiverMetadataLookup() },
            "hookLeicaStyle" to { hookLeicaStyle() },
            "hookShutterSoundPlaybackRoute" to { hookShutterSoundPlaybackRoute() },
            "hookLegendaryProfileGate" to { hookLegendaryProfileGate() },
            "hookLegendarySupport" to { hookLegendarySupport() },
            "hookLegendaryRegistry" to { hookLegendaryRegistry() },
            "hookSmartComposition" to { hookSmartComposition() },
            "hookSmartCompositionTopRow" to { hookSmartCompositionTopRow() },
            "hookSmartCompositionFeatureBar" to { hookSmartCompositionFeatureBar() },
            "hookContentCredential" to { hookContentCredential() },
            "hookAdaptiveLens" to { hookAdaptiveLens() },
            // Validate the donor before our focal hooks can alter either reflected table.
            "resolveMasterLiveEffects" to { if (masterLive.masterliveEnabled()) masterLive.borrowK100EffectTable() },
            "hookMasterLiveModePlacement" to { masterLive.hookMasterLiveModePlacement() },
            "hookMasterLiveSupportGate" to { masterLive.hookMasterLiveSupportGate() },
            "hookMasterLiveSlowMotionFallback" to { masterLive.hookMasterLiveSlowMotionFallback() },
            "hookMasterLiveRealEffectTable" to { masterLive.hookMasterLiveRealEffectTable() },
            "hookMasterLiveFullFocal" to { masterLive.hookMasterLiveFullFocal() },
            "hookMasterLiveOrderFunnel" to { masterLive.hookMasterLiveOrderFunnel() },
            "hookMasterLiveSupportEntry" to { masterLive.hookMasterLiveSupportEntry() },
            "hookMasterLiveTeleFallback" to { masterLive.hookMasterLiveTeleFallback() },
            "hookMasterLiveVideoSizeProbe" to { masterLive.hookMasterLiveVideoSizeProbe() },
            "hookMasterLiveVideoSurfaceSize" to { masterLive.hookMasterLiveVideoSurfaceSize() },
            "hookShutterSoundBoundary" to { hookShutterSoundBoundary() },
        )
        features.forEach { (name, install) ->
            runCatching(install).onFailure { DebugLog.w(TAG, "$name initialization failed; other features continue", it) }
        }
    }

    // Borrow only the effect table; validate its DDF and focal contract against the native config.

    // ─── 2. Keep this device's brand + model on the watermark (or a user custom one) ─

    private fun hookWatermarkKeep() {
        captureOriginalThirdSlot()
        val profile = hostProfile ?: return
        val clazz = profile.facade
        val brandMethod = profile.brandGetter?.let { name ->
            CameraResolver.resolveMethod(TAG, "watermark brand", clazz, name) {
                it.parameterCount == 0 && it.returnType == String::class.java
            }
        }
        if (brandMethod != null) {
            deoptimize(brandMethod)
            brandMethod.hook("cam_keep_model_logo") {
                after { param -> if (keepModel()) param.result = CameraWatermarkBrand.brand() }
            }
        }

        val vMethod = CameraResolver.resolveMethod(
            scope = TAG, key = "wm_keep_v", clazz = clazz,
            name = profile.modelArrayGetter ?: return,
            shape = { it.parameterTypes.isEmpty() && it.returnType == Array<String>::class.java },
        ) ?: run {
            DebugLog.w(TAG, "${clazz.name}#${profile.modelArrayGetter}() not found; watermark keep skipped")
            return
        }
        deoptimize(vMethod)
        vMethod.hook("cam_keep_model_brand") {
            after { param ->
                if (!keepModel()) return@after
                // Mirror the platform's 3-slot shape [brand, model, third] so `w()` (=v()[2])
                // keeps behaving and `y()` (=v()[1]) / `x()` (=v()[0]) read back our values.
                // WARNING: `v()` returns `String[]` — the array MUST materialize as a real
                // String[] (Kotlin Array<String?>). A bare `arrayOf(brand, model, Any?)` infers
                // Array<Any?> -> Object[] and the caller's `String[] v()` check-cast then throws
                // ClassCastException, which dead-locked the camera with keep-model on.
                param.result = arrayOf<String?>(
                    CameraWatermarkBrand.brand(),
                    CameraWatermarkBrand.model(),
                    originalThirdSlot.get()
                )
            }
        }
        DebugLog.d(TAG, "watermark identity hooks registered on ${clazz.name}")
    }

    private fun captureOriginalThirdSlot() {
        val profile = hostProfile ?: return
        CameraLegendaryProfileState.nativeWatermarkSnapshot()?.let {
            originalThirdSlot.set(it.getOrNull(2))
            return
        }
        val owner = profile.facadeInstance() ?: return
        val getter = profile.modelArrayGetter ?: return
        runCatching {
            val values = HostAccess.requirePublicMethod(profile.facade, getter, accessible = false).invoke(owner) as? Array<*>
            originalThirdSlot.set(values?.getOrNull(2) as? String)
        }.onFailure { DebugLog.w(TAG, "native watermark text could not be read", it) }
    }

    // ─── 3. Keep the watermark config cache (S8.d) fresh with this device's brand/model ─

    private fun hookWatermarkConfigCache() {
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
        val clazz = CameraResolver.resolveClass(
            scope = TAG, key = "wm_config_singleton", ctx = ctx,

            // `CloudWatermark` survives as a plaintext dex string (classes.dex).
            probe = { bridge ->
                bridge.findClass { matcher { usingStrings("CloudWatermark") } }
                    .singleOrNull { cd ->
                        ctx.loadOrNull(cd.name)?.declaredMethods?.any {
                            it.parameterCount == 0 &&
                                Modifier.isStatic(it.modifiers) && it.returnType == it.declaringClass
                        } == true
                    }?.name
            },
            validate = { c ->
                c.declaredMethods.any {
                    it.parameterTypes.isEmpty() &&
                        java.lang.reflect.Modifier.isStatic(it.modifiers) && it.returnType == c
                }
            },
        ) ?: run {
            DebugLog.w(TAG, "watermark cache manager not resolved; config cache refresh skipped")
            return
        }
        val aMethod = clazz.declaredMethods.singleOrNull {
            Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == clazz
        }?.apply { isAccessible = true } ?: return
        val paths = clazz.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.flatMap { outer ->
            outer.type.declaredFields.filter { inner ->
                !Modifier.isStatic(inner.modifiers) && inner.type.declaredConstructors.any {
                    it.parameterTypes.contentEquals(arrayOf(String::class.java, String::class.java))
                }
            }.map { outer to it }
        }
        val (outer, entry) = paths.singleOrNull() ?: run {
            DebugLog.w(TAG, "watermark brand/model cache graph is not unique"); return
        }
        outer.isAccessible = true
        entry.isAccessible = true
        val constructor = entry.type.getDeclaredConstructor(String::class.java, String::class.java).apply { isAccessible = true }
        val lock = Any()
        var lastEntry: Any? = null
        var lastLabels: Pair<String, String>? = null
        deoptimize(aMethod)
        aMethod.hook("cam_wm_config_refresh") {
            after { param ->
                if (!keepModel()) return@after
                runCatching {
                    val holder = outer.get(param.result ?: return@runCatching) ?: return@runCatching
                    val labels = CameraWatermarkBrand.brand() to CameraWatermarkBrand.model()
                    synchronized(lock) {
                        if (lastLabels != labels || entry.get(holder) !== lastEntry) {
                            val value = constructor.newInstance(labels.first, labels.second)
                            entry.set(holder, value)
                            lastEntry = value
                            lastLabels = labels
                        }
                    }
                }.onFailure { DebugLog.w(TAG, "watermark cache refresh failed", it) }
            }
        }
    }

    // ─── 4. Force this device's brand/model into every watermark render ───────────

    private fun hookWatermarkRender() {
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
        val semantics = CameraSemantics.create(ctx, TAG) ?: return
        val j0 = semantics.anchored("watermark renderer", "deviceLogo") {
            it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;", "Ljava/lang/String;", "Z") && it.returnType == "V"
        } ?: return
        val clazz = j0.declaringClass
        deoptimize(j0)
        j0.hook("cam_wm_render_keep") {
            before { param ->
                val incomingBrand = param.args[0] as? String
                val incomingModel = param.args[1] as? String
                if (incomingBrand.isNullOrEmpty() && incomingModel.isNullOrEmpty()) return@before
                param.args[0] = CameraWatermarkBrand.brand()
                param.args[1] = CameraWatermarkBrand.model()
            }
        }
        DebugLog.d(TAG, "watermark render keep hooked on ${clazz.name}#${j0.name}")
    }

    // ─── 5. Render the custom watermark brand through the stock logo slot ─────────

    private fun hookWatermarkBrandText() {
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
        val semantics = CameraSemantics.create(ctx, TAG) ?: return
        val oMethod = semantics.anchored("watermark model formatter", "modelFormat", "@{logo}") {
            it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;", "Ljava/lang/String;", "Z", "Z")
        } ?: return
        val clazz = oMethod.declaringClass
        val ref = semantics.reference(oMethod) ?: return
        val formatRef = semantics.dex.code(ref).reads.filter {
            it.definingClass == ref.definingClass && it.type == "Ljava/lang/String;"
        }.distinctBy { it.toString() }.singleOrNull() ?: return
        val formatField = semantics.dex.field(formatRef, classLoader) ?: return
        deoptimize(oMethod)
        // Saved format for the current invocation. before/after of one call always run on the
        // same thread; J0 updates views sequentially, so the pairs never interleave.
        val pendingFormat = ThreadLocal<String?>()
        oMethod.hook("cam_wm_brand_text") {
            before { param ->
                val customBrand = CameraWatermarkBrand.customBrand().takeIf { it.isNotEmpty() }
                    ?: return@before
                val receiver = param.thisObject
                runCatching {
                    val format = formatField.get(receiver) as? String
                    val injected = CameraWatermarkBrand.formatWithLogoLine(format, customBrand)
                        ?: return@runCatching
                    pendingFormat.set(format)
                    formatField.set(receiver, injected)
                }.onFailure { t ->
                    DebugLog.w(TAG, "brand logo-line injection failed (defensive)", t)
                }
            }
            after { param ->
                val saved = pendingFormat.get() ?: return@after
                pendingFormat.set(null)
                val receiver = param.thisObject
                runCatching { formatField.set(receiver, saved) }
            }
        }
        DebugLog.d(TAG, "brand logo-line hooked on ${clazz.name}#${oMethod.name}()")
    }

    // ─── 6. Fake the LCC theme so LCC-gated branches open (independent of any config swap) ──

    private fun hookLccTheme() {
        val profile = hostProfile ?: return
        val clazz = profile.facade
        val vMethod = CameraResolver.resolveMethod(
            scope = TAG, key = "lcc_theme_v", clazz = clazz,
            name = profile.lccGate ?: return,
            shape = {
                Modifier.isStatic(it.modifiers) && it.parameterTypes.isEmpty() &&
                    it.returnType == java.lang.Boolean.TYPE
            },
        ) ?: run {
            DebugLog.w(TAG, "${clazz.name}#${profile.lccGate}() LCC gate not found; theme gate skipped")
            return
        }
        deoptimize(vMethod)
        vMethod.hook("cam_impersonate_theme_lcc") {
            after { param ->
                if (settingCameraImpersonateThemeLcc) {
                    param.result = true
                }
            }
        }
        DebugLog.d(TAG, "LCC theme gate hooked on ${clazz.name}#${vMethod.name}()")
    }

    // ─── 7. Keep the 相机配色 (tint color) settings entry visible under the fake LCC theme ─

    private fun hookLccCustomizationProvider() {
        if (!settingCameraImpersonateThemeLcc) {
            DebugLog.d(TAG, "adaptive tint-color gate skipped; fake LCC theme is disabled")
            return
        }

        hookParam.appInfo?.sourceDir?.takeIf { it.isNotBlank() } ?: run {
            DebugLog.w(TAG, "camera APK path unavailable; adaptive tint-color gate skipped")
            return
        }
        val gateMethod = CameraResolver.resolveCachedMethod(TAG, "lcc_customization_provider",
            CameraResolver.Ctx(classLoader, hookParam.appInfo), shape = {
                it.parameterCount == 0 && it.returnType == java.lang.Boolean.TYPE &&
                    !Modifier.isStatic(it.modifiers)
            }) { bridge ->
            resolveTintColorProviderGate(bridge)
        } ?: run {
            DebugLog.w(TAG, "adaptive tint-color gate not resolved; tint-color restore skipped")
            return
        }
        if (gateMethod.parameterCount != 0 || gateMethod.returnType != java.lang.Boolean.TYPE ||
            Modifier.isStatic(gateMethod.modifiers)
        ) {
            DebugLog.w(TAG, "adaptive tint-color gate has an unexpected shape; restore skipped")
            return
        }
        deoptimize(gateMethod)
        gateMethod.hook("cam_restore_tint_color") {
            before { param ->
                if (settingCameraImpersonateThemeLcc) {
                    param.result = true
                }
            }
        }
        DebugLog.d(TAG, "adaptive tint-color gate hooked on ${gateMethod.declaringClass.name}#${gateMethod.name}()")
    }

    private fun resolveTintColorProviderGate(bridge: DexKitBridge): Method? {
        val entryCandidates = runCatching {
            bridge.findMethod { matcher { usingStrings(TINT_COLOR_PREFERENCE_KEY) } }
                .filter { method -> CUSTOMIZATION_CATEGORY_KEY in method.usingStrings }
        }.onFailure { t ->
            DebugLog.d(TAG, "tint-color preference call-site query failed: ${t.javaClass.simpleName}")
        }.getOrNull().orEmpty()

        val entry = entryCandidates.singleOrNull() ?: run {
            DebugLog.d(TAG, "tint-color preference call-site matches=${entryCandidates.size}")
            return null
        }
        val invokes = entry.invokes.filter { it.isMethod }
        val gateInvokes = invokes.filter { it.paramCount == 0 && it.returnTypeName == "boolean" }
        val holderFields = entry.usingFields.map { it.field }
            .filter { Modifier.isStatic(it.modifiers) }

        val resolved = LinkedHashMap<String, Method>()
        for (gateData in gateInvokes) {
            val gate = runCatching { gateData.getMethodInstance(classLoader) }.getOrNull() ?: continue
            if (gate.parameterCount != 0 || gate.returnType != java.lang.Boolean.TYPE ||
                Modifier.isStatic(gate.modifiers)
            ) {
                continue
            }
            runCatching { gate.isAccessible = true }

            for (accessorData in invokes) {
                if (accessorData.paramCount != 0) continue
                val accessor = runCatching { accessorData.getMethodInstance(classLoader) }.getOrNull()
                    ?: continue
                if (accessor.returnType != gate.declaringClass || Modifier.isStatic(accessor.modifiers)) {
                    continue
                }
                runCatching { accessor.isAccessible = true }

                for (fieldData in holderFields) {
                    val field = runCatching { fieldData.getFieldInstance(classLoader) }.getOrNull()
                        ?: continue
                    if (!Modifier.isStatic(field.modifiers)) continue
                    if (!accessor.declaringClass.isAssignableFrom(field.type) &&
                        !field.type.isAssignableFrom(accessor.declaringClass)
                    ) {
                        continue
                    }
                    runCatching { field.isAccessible = true }
                    val holder = runCatching { field.get(null) }.getOrNull() ?: continue
                    if (!accessor.declaringClass.isInstance(holder)) continue
                    val provider = runCatching { accessor.invoke(holder) }.getOrNull() ?: continue
                    val implementation = CameraResolver.findConcreteImplementation(provider, gate) ?: continue
                    resolved.putIfAbsent(implementation.toGenericString(), implementation)
                }
            }
        }

        if (resolved.size != 1) {
            DebugLog.d(TAG, "tint-color provider path matches=${resolved.size}; refusing ambiguous gate")
            return null
        }
        return resolved.values.single()
    }

    private fun hookDisabledWidgetReceiverMetadataLookup() {
        if (!isMainProcess) return
        val applicationPackageManager = runCatching {
            Class.forName("android.app.ApplicationPackageManager", false, classLoader)
        }.getOrNull() ?: return
        val getReceiverInfo = applicationPackageManager.declaredMethods.singleOrNull { method ->
            method.name == "getReceiverInfo" &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[0] == ComponentName::class.java &&
                method.parameterTypes[1] == Integer.TYPE &&
                method.returnType == ActivityInfo::class.java &&
                !Modifier.isAbstract(method.modifiers)
        } ?: run {
            DebugLog.d(TAG, "PackageManager receiver-info implementation unavailable")
            return
        }

        deoptimize(getReceiverInfo)
        getReceiverInfo.hook("cam_disabled_widget_metadata") {
            before { param ->
                runCatching {
                    val component = param.args.getOrNull(0) as? ComponentName ?: return@runCatching
                    if (component.packageName != PACKAGE) return@runCatching
                    val flags = param.args.getOrNull(1) as? Int ?: return@runCatching
                    if ((flags and PackageManager.GET_META_DATA) == 0 ||
                        (flags and PackageManager.MATCH_DISABLED_COMPONENTS) != 0
                    ) {
                        return@runCatching
                    }
                    val packageManager = param.thisObject as? PackageManager ?: return@runCatching
                    if (!isDisabledCameraWidgetReceiver(packageManager, component.className)) {
                        return@runCatching
                    }
                    param.args[1] = flags or PackageManager.MATCH_DISABLED_COMPONENTS
                    DebugLog.d(TAG, "including metadata for disabled camera widget ${component.className}")
                }.onFailure { t ->
                    DebugLog.d(TAG, "disabled widget metadata probe skipped: ${t.javaClass.simpleName}")
                }
            }
        }
        DebugLog.d(TAG, "disabled camera widget metadata lookup compatibility hook installed")
    }

    private fun isDisabledCameraWidgetReceiver(packageManager: PackageManager, className: String): Boolean {
        val cached = disabledWidgetReceiverNames.get()
        if (cached != null) return className in cached

        val names = synchronized(disabledWidgetReceiverNamesLock) {
            disabledWidgetReceiverNames.get() ?: runCatching {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(
                    PACKAGE,
                    PackageManager.GET_RECEIVERS or
                        PackageManager.GET_META_DATA or
                        PackageManager.MATCH_DISABLED_COMPONENTS,
                ).receivers.orEmpty()
                    .asSequence()
                    .filter { receiver ->
                        !receiver.enabled &&
                            receiver.metaData?.getBoolean(MIUI_WIDGET_METADATA_KEY, false) == true &&
                            receiver.metaData?.containsKey(DEFAULT_WIDGET_LAYOUT_METADATA_KEY) == true &&
                            receiver.metaData?.containsKey(APP_WIDGET_PROVIDER_METADATA_KEY) == true
                    }
                    .map { it.name }
                    .toSet()
            }.getOrDefault(emptySet()).also(disabledWidgetReceiverNames::set)
        }
        return className in names
    }

    private fun hookLeicaStyle() {
        val profile = hostProfile ?: return
        val semantics = CameraSemantics.create(CameraResolver.Ctx(classLoader, hookParam.appInfo), TAG) ?: return
        val gate = semantics.unique("native Leica sound/style capability",
            semantics.dex.strings("leica_default", "leica_mechanical").flatMap {
                semantics.dex.code(it).calls.map { call -> call.method }
            }.filter { DexOperandIndex.isInstanceGetter(it, DexOperandIndex.descriptor(profile.configType), "Z") }) ?: return
        configDispatchClasses().mapNotNull { profile.configMethod(it, gate.name, java.lang.Boolean.TYPE) }
            .distinct().forEach { method ->
                deoptimize(method)
                method.hook("cam_leica_style_${method.declaringClass.name}") {
                    after { param ->
                        if (leicaStyle() || settingCameraAllShutterSounds) {
                            param.result = true
                        }
                    }
                }
            }
    }

    private fun hookShutterSoundPlaybackRoute() {
        val profile = hostProfile ?: return
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
        val loadMethod = CameraResolver.resolveMethodByStrings(
            scope = TAG,
            key = "camera_shutter_sound_resource_loader",
            ctx = ctx,
            anchors = listOf("loadSound failed: audioData is null for sound"),
            shape = {
                !Modifier.isStatic(it.modifiers) && it.parameterTypes.contentEquals(arrayOf(Integer.TYPE)) &&
                    it.returnType == Integer.TYPE
            },
        ) ?: return

        val styleGate = runCatching {
            CameraResolver.resolveCachedMethod(TAG, "shutter_sound_style_gate", ctx, shape = {
                it.declaringClass == profile.facade && it.parameterCount == 0 &&
                    it.returnType == java.lang.Boolean.TYPE && !Modifier.isStatic(it.modifiers)
            }) { bridge ->
                bridge.findMethod {
                    matcher { usingStrings("loadSound failed: audioData is null for sound") }
                }.asSequence()
                    .filter { data ->
                        runCatching { data.getMethodInstance(ctx.classLoader).toGenericString() == loadMethod.toGenericString() }
                            .getOrDefault(false)
                    }
                    .flatMap { it.invokes.asSequence() }
                    .filter { call ->
                        call.isMethod && call.declaredClassName == profile.facade.name &&
                            call.paramCount == 0 && call.returnTypeName == java.lang.Boolean.TYPE.name
                    }
                    .mapNotNull { call ->
                        profile.facade.declaredMethods.singleOrNull {
                            it.name == call.methodName && it.parameterCount == 0 &&
                                it.returnType == java.lang.Boolean.TYPE && !Modifier.isStatic(it.modifiers)
                        }?.apply { isAccessible = true }
                    }
                    .distinctBy { it.toGenericString() }
                    .singleOrNull()
            }
        }.getOrNull() ?: run {
            DebugLog.w(TAG, "shutter-sound Leica resource branch was not uniquely resolved")
            return
        }

        deoptimize(loadMethod)
        loadMethod.hook("cam_shutter_sound_load_scope") {
            before {
                currentShutterSoundLoadScope().addLast(
                    settingCameraAllShutterSounds,
                )
            }
            after {
                val scopes = shutterSoundLoadScope.get() ?: return@after
                if (!scopes.isEmpty()) scopes.removeLast()
                if (scopes.isEmpty()) shutterSoundLoadScope.remove()
            }
        }

        deoptimize(styleGate)
        styleGate.hook("cam_shutter_sound_leica_resources") {
            after { param ->
                val scopes = shutterSoundLoadScope.get() ?: return@after
                if (scopes.peekLast() == true) param.result = true
                if (scopes.isEmpty()) shutterSoundLoadScope.remove()
            }
        }
        DebugLog.i(
            TAG,
            "Leica shutter previews use the packaged raw samples only inside ${loadMethod.declaringClass.name}#${loadMethod.name}()",
        )
    }

    private fun currentShutterSoundLoadScope(): ArrayDeque<Boolean> {
        return shutterSoundLoadScope.get() ?: ArrayDeque<Boolean>().also(shutterSoundLoadScope::set)
    }

    private fun hookLegendaryProfileGate() {
        val profile = hostProfile ?: return
        val method = CameraResolver.resolveMethod(
            scope = TAG,
            key = "legendary_profile_lcc_gate",
            clazz = profile.facade,
            name = profile.lccGate ?: return,
            shape = {
                Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                    it.returnType == java.lang.Boolean.TYPE
            },
        ) ?: return
        deoptimize(method)
        method.hook("cam_legendary_profile_gate") {
            after { param ->
                val mode = settingCameraLegendaryMomentMode
                if (!CameraLegendaryProfileState.isApplied(mode)) return@after
                when (mode) {
                    CameraLegendaryMomentMode.MODE_LEICA -> param.result = true
                    CameraLegendaryMomentMode.MODE_LEGENDARY -> param.result = false
                }
            }
        }
        DebugLog.d(TAG, "Legendary mode profile gate hooked on ${method.declaringClass.name}#${method.name}()")
    }

    private fun hookLegendarySupport() {
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
        val clazz = CameraResolver.resolveClass(
            scope = TAG, key = "legendary_enter", ctx = ctx,

            probe = { bridge ->
                bridge.findMethod {
                    matcher {
                        name("getModuleId", StringMatchType.Equals)
                        paramCount(0)
                        returnType(Integer.TYPE)
                    }
                }
                    .asSequence()
                    .filter {
                        it.name == "getModuleId" && it.paramCount == 0 &&
                            it.returnTypeName == Integer.TYPE.name
                    }
                    .mapNotNull { data ->
                        ctx.loadOrNull(data.className)?.takeIf(::isLegendaryEntryClass)?.name
                    }
                    .distinct()
                    .singleOrNull()
            },
            validate = ::isLegendaryEntryClass,
        ) ?: run {
            DebugLog.w(TAG, "LegendaryEnter not resolved; legendary guard skipped")
            return
        }
        legendaryEntryClass.set(clazz)
        val support = CameraResolver.resolveMethod(
            scope = TAG, key = "legendary_support", clazz = clazz,
            name = "support",
            shape = { it.parameterTypes.isEmpty() && it.returnType == java.lang.Boolean.TYPE },
        ) ?: run {
            DebugLog.w(TAG, "${clazz.name}#support() not found; legendary guard skipped")
            return
        }
        deoptimize(support)
        support.hook("cam_unlock_legendary") {
            after { param ->
                if (legendaryMomentUnlock()) param.result = true
            }
        }
        DebugLog.d(TAG, "legendary unlock hooked on ${clazz.name}#support()")
    }

    private fun isLegendaryEntryClass(type: Class<*>): Boolean {
        val methods = type.declaredMethods
        return type.simpleName.contains("Legendary", ignoreCase = true) &&
            methods.count {
                it.name == "getModuleId" && it.parameterCount == 0 && it.returnType == Integer.TYPE
            } == 1 &&
            methods.count {
                it.name == "support" && it.parameterCount == 0 &&
                    it.returnType == java.lang.Boolean.TYPE
            } == 1 &&
            methods.any { it.name == "getModeItem" && it.parameterCount == 0 && it.returnType != Void.TYPE }
    }

    private val registryContextGetter by lazy {
        runCatching { classLoader.loadClass("com.xiaomi.camera.basic.Global").cachedPublicMethod("getContext", accessible = false) }.getOrNull()
    }
    private val masterLiveRegistryEntry by lazy {
        runCatching { classLoader.loadClass("com.android.camera.features.mode.masterlive.MasterLiveModuleEntry") }.getOrNull()
    }

    private fun hookLegendaryRegistry() {
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
        val clazz = CameraResolver.resolveClass(
            scope = TAG,
            key = "feature_registry",
            ctx = ctx,

            probe = { bridge ->
                bridge.findClass {
                    matcher { usingStrings("FeatureLoader", "Build In Entries is NOT ready.") }
                }.asSequence()
                    .mapNotNull { descriptor ->
                        ctx.loadOrNull(descriptor.name)?.takeIf { candidate ->
                            candidate.declaredMethods.any {
                                Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                                    SparseArray::class.java.isAssignableFrom(it.returnType)
                            } && candidate.declaredFields.any {
                                Modifier.isStatic(it.modifiers) &&
                                    SparseArray::class.java.isAssignableFrom(it.type)
                            }
                        }
                    }
                    .distinctBy { it.name }
                    .singleOrNull()
                    ?.name
            },
            validate = { c ->
                c.declaredMethods.any {
                    java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                        it.parameterCount == 0 && SparseArray::class.java.isAssignableFrom(it.returnType)
                } && c.declaredFields.any {
                    java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                        SparseArray::class.java.isAssignableFrom(it.type)
                }
            },
        ) ?: run {
            DebugLog.w(TAG, "FeatureLoader registry not resolved; legendary registry repair skipped")
            return
        }

        val methods = clazz.declaredMethods.filter {
            Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && SparseArray::class.java.isAssignableFrom(it.returnType)
        }.onEach { it.isAccessible = true }
        if (methods.isEmpty()) {
            DebugLog.w(TAG, "${clazz.name} registry methods not found; legendary registry repair skipped")
            return
        }
        methods.forEach { method ->
            deoptimize(method)
            method.hook("cam_unlock_legendary_registry_${method.name}") {
                after { param ->
                    val unlockLegendary = legendaryMomentUnlock()
                    val unlockExtras = settingCameraLegendaryExtraModes
                    if (!unlockLegendary && !unlockExtras &&
                        !(masterLive.masterLiveGateReady && masterLive.masterliveEnabled() && masterLive.masterLiveHasEffectTable())) return@after
                    @Suppress("UNCHECKED_CAST")
                    val registry = param.result as? SparseArray<Any?> ?: return@after
                    ensureCameraModeRegistry(registry, unlockLegendary, unlockExtras)
                }
            }
        }
        DebugLog.d(TAG, "camera mode registry repair hooked on ${clazz.name}#${methods.joinToString { it.name }}()")
    }

    private fun ensureCameraModeRegistry(
        registry: SparseArray<Any?>,
        unlockLegendary: Boolean,
        unlockExtras: Boolean,
    ) {
        val context = runCatching {
            registryContextGetter?.invoke(null)
        }.getOrNull() ?: return
        if (masterLive.masterLiveGateReady && masterLive.masterliveEnabled() && masterLive.masterLiveHasEffectTable()) {
            ensureModeEntry(registry, masterLiveRegistryEntry, context, CameraIdentity.MASTER_LIVE_MODE_ID)
        }
        if (unlockLegendary) {
            ensureModeEntry(
                registry,
                legendaryEntryClass.get(),
                context,
                CameraIdentity.LEGENDARY_MOMENT_MODE_ID,
            )
        }
        if (unlockExtras) {
            ensureModeEntry(registry, CameraLegendaryProfileState.pixelEntry(), context)
            ensureModeEntry(registry, CameraLegendaryProfileState.cinematicEntry(), context)
        }
    }

    private fun ensureModeEntry(
        registry: SparseArray<Any?>,
        entryClass: Class<*>?,
        context: Any,
        expectedModuleId: Int? = null,
    ) {
        if (entryClass == null || (expectedModuleId != null && registry.indexOfKey(expectedModuleId) >= 0)) return
        val registeredEntry = runCatching {
            entryClass.declaredConstructors.asSequence()
                .filter { it.parameterCount == 1 && it.parameterTypes[0].isInstance(context) }
                .mapNotNull { ctor -> runCatching { ctor.apply { isAccessible = true }.newInstance(context) }.getOrNull() }
                .mapNotNull { candidate ->
                    val moduleId = runCatching {
                        HostAccess.requirePublicMethod(candidate.javaClass, "getModuleId", accessible = false).invoke(candidate) as? Int
                    }.getOrNull() ?: return@mapNotNull null
                    if (expectedModuleId != null && moduleId != expectedModuleId) return@mapNotNull null
                    moduleId to candidate
                }
                .firstOrNull()
        }.getOrNull() ?: return
        val (moduleId, entry) = registeredEntry
        if (registry.indexOfKey(moduleId) >= 0) return
        registry.put(moduleId, entry)
        DebugLog.i(TAG, "camera mode registry repaired: inserted module $moduleId (${entryClass.name})")
    }

    private fun hookSmartComposition() {
        val profile = hostProfile ?: return
        val semantics = CameraSemantics.create(CameraResolver.Ctx(classLoader, hookParam.appInfo), TAG) ?: return
        val gate = semantics.preferenceGate(SMART_COMPOSITION_PREF_KEY, profile.configType) ?: return
        configDispatchClasses().mapNotNull { profile.configMethod(it, gate.name, java.lang.Boolean.TYPE) }
            .distinct().forEach { method ->
                deoptimize(method)
                method.hook("cam_smart_composition_${method.declaringClass.name}") {
                    after { param -> if (smartCompositionUnlock()) param.result = true }
                }
            }
    }

    private var smartCompositionResourceIds: Pair<Int, Int>? by generationState { null }
    private const val SMART_COMPOSITION_PREF_KEY = "pref_camera_crop_preferred_key"
    private fun hookSmartCompositionTopRow() {
        val clazz = CameraFeatureResolver.captureSettings(
            CameraResolver.Ctx(classLoader, hookParam.appInfo), TAG,
        ) ?: run {
            DebugLog.w(TAG, "capture settings fragment not resolved; top-row smart composition skipped")
            return
        }
        val add = CameraResolver.resolveMethod(
            scope = TAG, key = "settings_add_photo_prefs", clazz = clazz,
            name = "addPhotoPreferences",
            shape = { it.parameterCount == 0 },
        ) ?: run {
            DebugLog.w(TAG, "${clazz.name}#addPhotoPreferences() not found; top-row smart composition skipped")
            return
        }
        val semantics = CameraSemantics.create(CameraResolver.Ctx(classLoader, hookParam.appInfo), TAG) ?: return
        val resourceIds = semantics.dex.preferenceResources(SMART_COMPOSITION_PREF_KEY)
        if (resourceIds.size != 2) { DebugLog.w(TAG, "smart-composition title/summary are not unique"); return }
        smartCompositionResourceIds = resourceIds[0] to resourceIds[1]
        deoptimize(add)
        add.hook("cam_smart_composition_top_row") {
            after { param ->
                if (!smartCompositionUnlock()) return@after
                injectSmartCompositionTopRow(param.thisObject)
            }
        }
        DebugLog.i(TAG, "smart-composition top-row hook installed on ${clazz.name}#addPhotoPreferences()")
    }

    private fun injectSmartCompositionTopRow(fragment: Any?) {
        if (fragment == null) return
        runCatching {
            val fragClass = fragment.javaClass
            // mPreferenceGroup is declared on the settings base classes; walk the hierarchy.
            var holder: Class<*>? = fragClass
            var groupField: Field? = null
            while (holder != null && groupField == null) {
                groupField = runCatching { HostAccess.requireDeclaredField(holder, "mPreferenceGroup", accessible = true) }.getOrNull()
                holder = holder.superclass
            }
            val groupField0 = groupField ?: run {
                DebugLog.d(TAG, "mPreferenceGroup not found on $fragClass; top row skipped")
                return
            }
            val screen = groupField0.get(fragment) ?: return
            // androidx.preference.PreferenceGroup is host-bundled and its findPreference was
            // R8-renamed to `k0` on this build — resolve by name candidates + single-arg shape.
            val groupClass = "androidx.preference.PreferenceGroup".toClassOrNull() ?: return
            val find = groupClass.methods.firstOrNull {
                it.parameterTypes.contentEquals(arrayOf(CharSequence::class.java)) &&
                    it.returnType.name == "androidx.preference.Preference"
            } ?: return
            val category = find.invoke(screen, "category_photo_setting") ?: return
            if (find.invoke(screen, SMART_COMPOSITION_PREF_KEY) != null) return
            val res = HostAccess.requirePublicMethod(fragClass, "getResources", accessible = false).invoke(fragment) as android.content.res.Resources
            val (titleRes, summaryRes) = smartCompositionResourceIds ?: return
            val helper = HostAccess.requirePublicMethod(fragClass,
                "addCheckBoxPreference", groupClass, String::class.java,
                java.lang.Boolean.TYPE, java.lang.Integer.TYPE, java.lang.Integer.TYPE, accessible = false,
            )
            helper.invoke(fragment, category, SMART_COMPOSITION_PREF_KEY, true, titleRes, summaryRes)
            DebugLog.i(TAG, "smart-composition top row injected into category_photo_setting")
        }.onFailure { t ->
            DebugLog.w(TAG, "smart-composition top-row injection failed", t)
        }
    }

    private fun hookContentCredential() {
        if (!contentCredentialUnlock()) return
        val semantics = CameraSemantics.create(CameraResolver.Ctx(classLoader, hookParam.appInfo), TAG) ?: return
        val fields = semantics.dex.preferenceFields("pref_cai_type_key")
        val reference = fields.singleOrNull() ?: run {
            DebugLog.w(TAG, "content-credential preference field is not unique (${fields.size})")
            return
        }
        val field = semantics.dex.field(reference, classLoader) ?: return
        if (!Modifier.isStatic(field.modifiers) || field.type != java.lang.Boolean.TYPE) return
        runCatching {
            field.getBoolean(null) // Initialize the host before applying the user override.
            StaticFieldWriter.setBoolean(field, true)
        }.onFailure { DebugLog.w(TAG, "content-credential flag write failed", it) }
    }

    private fun hookAdaptiveLens() {
        val semantics = CameraSemantics.create(CameraResolver.Ctx(classLoader, hookParam.appInfo), TAG) ?: return
        val references = semantics.dex.preferenceGates("pref_camera_auto_fallback").filter {
            it.returnType == "Z" && it.parameterTypes.size == 1 && it.parameterTypes.single().startsWith("L")
        }
        val methods = references.mapNotNull(semantics::reflect).distinct()
        if (methods.size != 2 || methods.map { it.parameterTypes.toList() }.distinct().size != 1 ||
            methods.any { !Modifier.isStatic(it.modifiers) }) {
            DebugLog.w(TAG, "adaptive-lens preference did not identify two compatible capability gates")
            return
        }
        methods.forEach { method ->
            deoptimize(method)
            method.hook("cam_adaptive_lens_${method.name}") {
                after { param -> if (adaptiveLensUnlock()) param.result = true }
            }
        }
        DebugLog.i(TAG, "adaptive-lens consumer gates: ${methods.joinToString { it.name }}")
    }

    private fun hookSmartCompositionFeatureBar() {
        val semantics = CameraSemantics.create(CameraResolver.Ctx(classLoader, hookParam.appInfo), TAG) ?: return
        val method = semantics.anchored("smart composition version", "SupportSmartCompositionVersion:") {
            it.returnType == "Z" && it.parameterTypes.size == 1
        } ?: return
        deoptimize(method)
        method.hook("cam_smart_composition_feature_bar") {
            after { param -> if (smartCompositionUnlock()) param.result = true }
        }
    }

    private fun hookShutterSoundBoundary() {
        val semantics = CameraSemantics.create(CameraResolver.Ctx(classLoader, hookParam.appInfo), TAG) ?: return
        val selected = semantics.anchored("shutter sound selection", "key_shutter_sound") {
            it.returnType == "I" && it.parameterTypes.isEmpty()
        } ?: return
        val list = semantics.methodByShape(selected.declaringClass, "shutter sound list") {
            Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && List::class.java.isAssignableFrom(it.returnType)
        } ?: return
        val default = semantics.unique("shutter sound default", semantics.calls(selected).filter {
            it.definingClass == DexOperandIndex.descriptor(selected.declaringClass) &&
                it.parameterTypes.isEmpty() && it.returnType == "I"
        }) ?: return
        deoptimize(selected)
        selected.hook("cam_shutter_sound_bounds") {
            after { param ->
                runCatching {
                    val index = param.result as? Int ?: return@runCatching
                    val size = (list.invoke(null) as? List<*>)?.size ?: return@runCatching
                    if (index !in 0 until size) {
                        val fallback = default.invoke(null) as? Int ?: 0
                        param.result = fallback.takeIf { it in 0 until size } ?: 0
                    }
                }.onFailure { DebugLog.w(TAG, "shutter sound bounds callback failed", it) }
            }
        }
    }

    private fun configDispatchClasses() = config.dispatchClasses()

    // ─── gate helpers ─────────────────────────────────────────────────────────────

    private fun keepModel(): Boolean = true

    private fun leicaStyle(): Boolean =
        settingCameraLeicaStyle

    private fun legendaryMomentUnlock(): Boolean {
        val mode = settingCameraLegendaryMomentMode
        return mode != CameraLegendaryMomentMode.MODE_OFF && CameraLegendaryProfileState.isApplied(mode)
    }

    private fun smartCompositionUnlock(): Boolean =
        settingCameraSmartComposition

    private fun contentCredentialUnlock(): Boolean =
        settingCameraContentCredential

    private fun adaptiveLensUnlock(): Boolean =
        settingCameraAdaptiveLens

}
