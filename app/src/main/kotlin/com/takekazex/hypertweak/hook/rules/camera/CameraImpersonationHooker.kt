package com.takekazex.hypertweak.hook.rules.camera

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Size
import android.util.SparseArray
import androidx.core.util.isNotEmpty
import com.takekazex.hypertweak.hook.CameraLegendaryMomentMode
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import com.takekazex.hypertweak.util.StaticFieldWriter
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayDeque
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReference

object CameraImpersonationHooker : StaticHooker() {
    private const val TAG = "CamImpersonate"
    private const val PACKAGE = "com.android.camera"
    private const val TINT_COLOR_PREFERENCE_KEY = "pref_tint_color"
    private const val CUSTOMIZATION_CATEGORY_KEY = "category_customization"
    private const val MIUI_WIDGET_METADATA_KEY = "miuiWidget"
    private const val DEFAULT_WIDGET_LAYOUT_METADATA_KEY = "defaultLayoutInPA"
    private const val APP_WIDGET_PROVIDER_METADATA_KEY = "android.appwidget.provider"

    private val hostProfile by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        CameraHostProfile.resolve(CameraResolver.Ctx(classLoader, hookParam.appInfo), TAG)
    }
    private val shutterSoundLoadScope = ThreadLocal<ArrayDeque<Boolean>>()

    private val originalThirdSlot = AtomicReference<String?>(null)
    private val deviceIsNezhaCache = AtomicReference<Boolean?>()
    private val disabledWidgetReceiverNames = AtomicReference<Set<String>?>(null)
    private val disabledWidgetReceiverNamesLock = Any()

    override fun onHook() {
        if (hookParam.packageName != PACKAGE) return
        // Config-dependent hooks read the active profile. Wait until its provider initializes it,
        // while still installing these gates before Application.onCreate builds camera modes.
        CameraApplicationInit.afterConfigProviderCreate(this) {
            installHooks()
        }
    }

    private fun installHooks() {
        val features: List<Pair<String, () -> Unit>> = listOf(
            "resolveMasterLiveType" to { resolveMasterLiveType() },
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
            "resolveMasterLiveEffects" to { if (masterliveEnabled()) borrowK100EffectTable() },
            "hookMasterLiveModePlacement" to { hookMasterLiveModePlacement() },
            "hookMasterLiveSupportGate" to { hookMasterLiveSupportGate() },
            "hookMasterLiveSlowMotionFallback" to { hookMasterLiveSlowMotionFallback() },
            "hookMasterLiveRealEffectTable" to { hookMasterLiveRealEffectTable() },
            "hookMasterLiveFullFocal" to { hookMasterLiveFullFocal() },
            "hookMasterLiveOrderFunnel" to { hookMasterLiveOrderFunnel() },
            "hookMasterLiveSupportEntry" to { hookMasterLiveSupportEntry() },
            "hookMasterLiveTeleFallback" to { hookMasterLiveTeleFallback() },
            "hookMasterLiveVideoSizeProbe" to { hookMasterLiveVideoSizeProbe() },
            "hookMasterLiveVideoSurfaceSize" to { hookMasterLiveVideoSurfaceSize() },
            "hookShutterSoundBoundary" to { hookShutterSoundBoundary() },
        )
        features.forEach { (name, install) ->
            runCatching(install).onFailure { DebugLog.w(TAG, "$name initialization failed; other features continue", it) }
        }
    }

    // Borrow only the effect table; validate its DDF and focal contract against the native config.

    private fun resolveK100Config(loader: ClassLoader, resolver: Method): Any? {
        // Snapshot once so every source-profile candidate uses the same identity baseline.
        val original = activeConfigInstance()
        for (sourceName in K100_SOURCE_NAME_CANDIDATES) {
            val instance = buildFrom(loader, resolver, sourceName)
            if (instance == null) {
                DebugLog.w(TAG, "MasterLive source profile unavailable: $sourceName")
                continue
            }
            val shaped = isK100Shaped(instance)
            if (shaped && isK100Candidate(instance, original)) {
                DebugLog.i(TAG, "K100 config resolved via resolver source name $sourceName -> ${instance.javaClass.name}")
                return instance
            }
            DebugLog.d(TAG, "K100 source probe $sourceName -> ${instance.javaClass.name} rejected (shaped=$shaped)")
        }
        DebugLog.w(TAG, "K100 config not resolved by semantic source profile; effect table unavailable")
        return null
    }

    private val K100_SOURCE_NAME_CANDIDATES = listOf(
        "com.mi.device.Songyuan",
    )

    private val legendaryEntryClass = AtomicReference<Class<*>?>(null)

    private fun isK100Candidate(instance: Any, original: Any?): Boolean = runCatching {
        if (!isK100Shaped(instance) || original == null) return@runCatching false
        val profile = hostProfile ?: return@runCatching false
        val semantics = CameraSemantics.create(CameraResolver.Ctx(classLoader, hookParam.appInfo), TAG)
            ?: return@runCatching false
        val ddf = semantics.unique("dynamic DDF config", semantics.dex.strings("dynamic_ddfid: ")
            .flatMap { semantics.dex.code(it).calls.map { call -> call.method } }
            .filter { CameraDexIndex.isInstanceGetter(it, CameraDexIndex.descriptor(profile.configType), "I") })
            ?: return@runCatching false
        fun stops(receiver: Any, mode: Int): FloatArray? {
            val getter = profile.configMethod(receiver.javaClass, profile.focalStops, SparseArray::class.java)
                ?: return null
            val values = (getter.invoke(receiver) as? SparseArray<*>)?.get(mode)
            return when (values) {
                is FloatArray -> values.copyOf()
                is Array<*> -> values.map { it as? Float ?: return null }.toFloatArray()
                else -> null
            }
        }
        val compatible = CameraMasterLiveCompatibility.accepts(
            ddf.invoke(original) as? Int, ddf.invoke(instance) as? Int,
            stops(original, 163), stops(instance, 163), stops(instance, CameraIdentity.MASTER_LIVE_MODE_ID),
        )
        if (!compatible) DebugLog.w(TAG, "MasterLive donor rejected: DDF/photo/MasterLive focal contract differs")
        compatible
    }.onFailure { DebugLog.w(TAG, "MasterLive donor contract could not be read", it) }.getOrDefault(false)

    private fun isK100Shaped(instance: Any): Boolean = runCatching {
        val profile = hostProfile ?: return@runCatching false
        val clazz = instance.javaClass
        profile.configType.isAssignableFrom(clazz) &&
            profile.configMethod(clazz, profile.masterLiveGate, java.lang.Boolean.TYPE) != null &&
            profile.configMethod(clazz, profile.effectTable, Map::class.java) != null
    }.getOrDefault(false)

    private fun buildFrom(
        loader: ClassLoader,
        resolver: java.lang.reflect.Method,
        name: String
    ): Any? = runCatching {
        val cls = resolver.invoke(null, name) as? Class<*> ?: return@runCatching null
        cls.getDeclaredConstructor().newInstance()
    }.getOrNull()

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
            val values = profile.facade.getMethod(getter).invoke(owner) as? Array<*>
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
                if (Preferences.getBoolean(Preferences.KEY_CAMERA_IMPERSONATE_THEME_LCC, false)) {
                    param.result = true
                }
            }
        }
        DebugLog.d(TAG, "LCC theme gate hooked on ${clazz.name}#${vMethod.name}()")
    }

    // ─── 7. Keep the 相机配色 (tint color) settings entry visible under the fake LCC theme ─

    private fun hookLccCustomizationProvider() {
        if (!Preferences.getBoolean(Preferences.KEY_CAMERA_IMPERSONATE_THEME_LCC, false)) {
            DebugLog.d(TAG, "adaptive tint-color gate skipped; fake LCC theme is disabled")
            return
        }

        val apkPath = hookParam.appInfo?.sourceDir?.takeIf { it.isNotBlank() } ?: run {
            DebugLog.w(TAG, "camera APK path unavailable; adaptive tint-color gate skipped")
            return
        }
        val gateMethod = DexKitManager.withBridge(apkPath) { bridge ->
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
                if (Preferences.getBoolean(Preferences.KEY_CAMERA_IMPERSONATE_THEME_LCC, false)) {
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

    @Volatile private var masterLiveGateReady = false

    private fun hookLeicaStyle() {
        val profile = hostProfile ?: return
        val semantics = CameraSemantics.create(CameraResolver.Ctx(classLoader, hookParam.appInfo), TAG) ?: return
        val gate = semantics.unique("native Leica sound/style capability",
            semantics.dex.strings("leica_default", "leica_mechanical").flatMap {
                semantics.dex.code(it).calls.map { call -> call.method }
            }.filter { CameraDexIndex.isInstanceGetter(it, CameraDexIndex.descriptor(profile.configType), "Z") }) ?: return
        configDispatchClasses().mapNotNull { profile.configMethod(it, gate.name, java.lang.Boolean.TYPE) }
            .distinct().forEach { method ->
                deoptimize(method)
                method.hook("cam_leica_style_${method.declaringClass.name}") {
                    after { param ->
                        if (leicaStyle() || Preferences.getBoolean(Preferences.KEY_CAMERA_ALL_SHUTTER_SOUNDS, false)) {
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

        val apkPath = ctx.appInfo?.sourceDir ?: return
        val styleGate = runCatching {
            DexKitManager.withBridge(apkPath) { bridge ->
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
                    Preferences.getBoolean(Preferences.KEY_CAMERA_ALL_SHUTTER_SOUNDS, false),
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
                val mode = Preferences.cameraLegendaryMomentMode()
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
        runCatching { classLoader.loadClass("com.xiaomi.camera.basic.Global").getMethod("getContext") }.getOrNull()
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
                    val unlockExtras = Preferences.getBoolean(
                        Preferences.KEY_CAMERA_LEGENDARY_EXTRA_MODES,
                        false,
                    )
                    if (!unlockLegendary && !unlockExtras &&
                        !(masterLiveGateReady && masterliveEnabled() && masterLiveHasEffectTable())) return@after
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
        if (masterLiveGateReady && masterliveEnabled() && masterLiveHasEffectTable()) {
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
                        candidate.javaClass.getMethod("getModuleId").invoke(candidate) as? Int
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

    private var smartCompositionResourceIds: Pair<Int, Int>? = null
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
                groupField = runCatching { holder.getDeclaredField("mPreferenceGroup") }.getOrNull()
                holder = holder.superclass
            }
            val groupField0 = groupField ?: run {
                DebugLog.d(TAG, "mPreferenceGroup not found on $fragClass; top row skipped")
                return
            }
            groupField0.isAccessible = true
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
            val res = fragClass.getMethod("getResources").invoke(fragment) as android.content.res.Resources
            val (titleRes, summaryRes) = smartCompositionResourceIds ?: return
            val helper = fragClass.getMethod(
                "addCheckBoxPreference", groupClass, String::class.java,
                java.lang.Boolean.TYPE, java.lang.Integer.TYPE, java.lang.Integer.TYPE,
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

    private fun hookMasterLiveModePlacement() {
        val profile = hostProfile ?: return
        // CameraIdentity.frontMasterLiveMode is a no-op for arrays that already contain 231,
        // so this is inert on configs with native 231 — it only fronts the base C1143 `M()`
        // the active config inherits (no 231).
        val methods = LinkedHashSet<Method>()
        for (target in configDispatchClasses()) {
            profile.modeOrders.forEach { name ->
                profile.configMethod(target, name, IntArray::class.java)?.let(methods::add)
            }
        }
        if (methods.isEmpty()) {
            DebugLog.w(TAG, "config ${profile.modeOrders}()[I not found; masterlive placement skipped")
            return
        }
        for (method in methods) {
            deoptimize(method)
            method.hook("cam_masterlive_mode_front_${method.declaringClass.name}") {
                after { param ->
                    if (!masterliveEnabled() || !masterLiveHasEffectTable()) return@after
                    val order = param.result as? IntArray ?: return@after
                    if (!order.contains(CameraIdentity.MODE_LIST_MORE_MARKER)) return@after
                    val fronted = CameraIdentity.frontMasterLiveMode(order)
                    if (fronted != null) param.result = fronted
                }
            }
            DebugLog.d(TAG, "masterlive placement hooked on ${method.declaringClass.name}#${method.name}()")
        }
    }

    private fun hookMasterLiveSupportGate() {
        val profile = hostProfile ?: return
        val methods = LinkedHashSet<Method>()
        activeConfigInstance()?.javaClass?.let { target ->
            profile.configMethod(target, profile.masterLiveGate, java.lang.Boolean.TYPE)
                ?.let(methods::add)
        }
        if (methods.isEmpty()) {
            DebugLog.w(TAG, "masterlive registry getter ${profile.masterLiveGate}() not found; support gate skipped")
            return
        }
        for (method in methods) {
            deoptimize(method)
            method.hook("cam_masterlive_support_gate_${method.declaringClass.name}") {
                after { param ->
                    if (!masterliveEnabled() || !masterLiveHasEffectTable()) return@after
                    if ((param.result as? Boolean) == true) return@after
                    param.result = true
                    if (mlGateLogged.getAndSet(true) == false) {
                        DebugLog.i(TAG, "masterlive registry gate ${method.name}() forced true on ${method.declaringClass.name}")
                    }
                }
            }
            masterLiveGateReady = true
            DebugLog.i(TAG, "masterlive support gate hooked on ${method.declaringClass.name}#${method.name}()")
        }
    }

    private val mlGateLogged = AtomicReference(false)

    private fun hookMasterLiveRealEffectTable() {
        val profile = hostProfile ?: return
        if (configDispatchClasses().isEmpty()) {
            DebugLog.w(TAG, "no config dispatch classes; masterlive effect table hook skipped")
            return
        }
        val seen = HashSet<Method>()
        var hooked = 0
        for (clazz in configDispatchClasses()) {
            val method = profile.configMethod(clazz, profile.effectTable, Map::class.java) ?: continue
            if (!seen.add(method)) continue
            deoptimize(method)
            method.hook("cam_masterlive_effect_table_${method.declaringClass.name}") {
                after { param ->
                    if (!masterliveEnabled()) return@after
                    var table = param.result
                    if (table == null || (table as? Map<*, *>)?.isEmpty() == true) {
                        table = borrowK100EffectTable() ?: return@after
                        param.result = table
                        if (mlTableBorrowLogged.getAndSet(true) == false) {
                            DebugLog.i(TAG, "masterlive effect table borrowed from K100 config (active config ${method.name}() is null)")
                        }
                    }
                    if (!redCarpetEnabled()) return@after
                    val merged = injectRedCarpetEntry(table) ?: return@after
                    param.result = merged
                    if (mlRedCarpetLogged.getAndSet(true) == false) {
                        DebugLog.i(TAG, "masterlive effect table: injected 红毯运镜 (type 1) entry")
                    }
                }
            }
            hooked++
        }
        if (hooked == 0) {
            DebugLog.w(TAG, "no ${profile.effectTable}() Method resolved; masterlive effect table hook skipped")
        } else {
            DebugLog.i(TAG, "masterlive effect table hook installed on $hooked ${profile.effectTable}() dispatch method(s)")
        }
    }

    private fun hookMasterLiveSlowMotionFallback() {
        if (!CameraMasterLiveRedCarpet.needsSlowMotionFallback(Build.DEVICE, Build.HARDWARE)) {
            return
        }
        val semantics = CameraSemantics.create(CameraResolver.Ctx(classLoader, hookParam.appInfo), TAG) ?: return
        val reader = masterLiveTypeMethod.get()?.let(semantics::reference) ?: return
        val readers = setOf(CameraDexIndex.descriptor(reader))
        val method = semantics.unique("MasterLive slow-motion type", semantics.dex.strings("1").filter { ref ->
            Modifier.isStatic(ref.accessFlags) && ref.returnType == "Z" && ref.parameterTypes.map(CharSequence::toString) == listOf("I") &&
                semantics.dex.code(ref).calls.any { CameraDexIndex.descriptor(it.method) in readers }
        }) ?: return
        val clazz = method.declaringClass
        deoptimize(method)
        method.hook("cam_masterlive_red_carpet_safe_session") {
            after { param ->
                if (!masterliveEnabled() || !redCarpetEnabled()) return@after
                if ((param.args.firstOrNull() as? Int) != CameraIdentity.MASTER_LIVE_MODE_ID) return@after
                if (currentMasterLiveType() != CameraMasterLiveRedCarpet.RED_CARPET_TYPE) return@after
                if (param.result != true) return@after
                param.result = false
                if (mlRedCarpetFallbackLogged.getAndSet(true) == false) {
                    DebugLog.w(
                        TAG,
                        "red-carpet slow-motion fallback: ${Build.DEVICE}/${Build.HARDWARE} " +
                            "has no HSR path; using the safe normal movement session"
                    )
                }
            }
        }
        redCarpetCaptureReady.set(true)
        DebugLog.i(TAG, "red-carpet slow-motion fallback hooked on ${clazz.name}#${method.name}(I)")
    }

    private val mlRedCarpetFallbackLogged = AtomicReference(false)

    private val mlRedCarpetLogged = AtomicReference(false)

    private data class EffectEntryShape(val fields: List<Field>, val constructor: java.lang.reflect.Constructor<*>)
    private val effectEntryShapes = java.util.concurrent.ConcurrentHashMap<Class<*>, EffectEntryShape>()

    private fun injectRedCarpetEntry(table: Any): Any? {
        if (table !is Map<*, *> || table.containsKey(CameraMasterLiveRedCarpet.RED_CARPET_TYPE)) return null
        val sourceType = CameraMasterLiveRedCarpet.CLONE_SOURCE_TYPES.firstOrNull { table[it] != null } ?: return null
        val source = table[sourceType] ?: return null
        return runCatching {
            val shape = effectEntryShapes.getOrPut(source.javaClass) {
                EffectEntryShape(source.javaClass.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
                    .onEach { it.isAccessible = true },
                    source.javaClass.getDeclaredConstructor().apply { isAccessible = true })
            }
            val fields = shape.fields
            val type = fields.filter { it.type == String::class.java && it.get(source) == sourceType }.singleOrNull()
                ?: return@runCatching null
            val default = fields.singleOrNull { it.type == java.lang.Boolean.TYPE } ?: return@runCatching null
            val lists = fields.filter { List::class.java.isAssignableFrom(it.type) }
                .mapNotNull { it.get(source) as? List<*> }.filter { it.isNotEmpty() }
            val roles = lists.singleOrNull { values -> values.all { it is String && it.matches(Regex("[A-Za-z_]+")) } }
                ?: return@runCatching null
            val ranges = lists.singleOrNull { values -> values.all { it is String && it.contains(":") } }
                ?: return@runCatching null
            val zooms = lists.singleOrNull { values -> values.size == roles.size * 2 && values.all { it is Float } }
                ?: return@runCatching null
            if (!CameraMasterLiveRedCarpet.segmentsConsistent(roles.size, zooms.size, ranges.size)) return@runCatching null
            val copy = shape.constructor.newInstance()
            fields.forEach { field ->
                val value = field.get(source)
                field.set(copy, if (value is List<*>) ArrayList(value) else value)
            }
            type.set(copy, CameraMasterLiveRedCarpet.RED_CARPET_TYPE)
            default.setBoolean(copy, false)
            val order = CameraMasterLiveRedCarpet.orderedKeys(table.keys.filterIsInstance<String>()) ?: return@runCatching null
            LinkedHashMap<Any?, Any?>().apply { order.forEach { key ->
                put(key, if (key == CameraMasterLiveRedCarpet.RED_CARPET_TYPE) copy else table[key])
            } }
        }.onFailure { DebugLog.w(TAG, "red-carpet entry schema rejected", it) }.getOrNull()
    }

    private val k100EffectTableInstance = AtomicReference<Any?>(null)
    private val k100EffectTableMethod = AtomicReference<Method?>(null)
    private val k100EffectTableProbeFinished = AtomicReference(false)

    private val mlTableBorrowLogged = AtomicReference(false)
    private val mlTableUnavailableLogged = AtomicReference(false)

    private fun masterLiveHasEffectTable(): Boolean {
        val profile = hostProfile ?: return false
        val config = activeConfigInstance() ?: return false
        val method = profile.configMethod(config.javaClass, profile.effectTable, Map::class.java)
            ?: return false
        val native = runCatching { method.invoke(config) as? Map<*, *> }.getOrNull()
        return native?.isNotEmpty() == true ||
            (borrowK100EffectTable() as? Map<*, *>)?.isNotEmpty() == true
    }

    private fun borrowK100EffectTable(): Any? {
        k100EffectTableInstance.get()?.let { instance ->
            k100EffectTableMethod.get()?.let { method ->
                return runCatching { method.invoke(instance) }.getOrNull()
            }
        }
        if (k100EffectTableProbeFinished.get()) return null
        synchronized(k100EffectTableInstance) {
            k100EffectTableInstance.get()?.let { instance ->
                k100EffectTableMethod.get()?.let { method ->
                    return runCatching { method.invoke(instance) }.getOrNull()
                }
            }
            if (k100EffectTableProbeFinished.get()) return null
            k100EffectTableProbeFinished.set(true)
            val loader = classLoader
            val resolver = CameraResolver.resolveSourceNameResolver(
                CameraResolver.Ctx(loader, hookParam.appInfo), TAG,
            ) ?: run {
                logEffectTableUnavailableOnce("source-name resolver unavailable")
                return null
            }
            val k100 = resolveK100Config(loader, resolver) ?: run {
                logEffectTableUnavailableOnce("K100 config not resolved by semantic source profile")
                return null
            }
            val effectName = hostProfile?.effectTable ?: return null
            val q0Method = runCatching {
                k100.javaClass.getMethod(effectName).takeIf {
                    it.parameterTypes.isEmpty() && Map::class.java.isAssignableFrom(it.returnType)
                }
            }.getOrNull() ?: run {
                logEffectTableUnavailableOnce("${k100.javaClass.name}#$effectName() not found")
                return null
            }
            k100EffectTableInstance.set(k100)
            k100EffectTableMethod.set(q0Method)
            val table = runCatching { q0Method.invoke(k100) }.getOrNull()
            if (table == null) logEffectTableUnavailableOnce("${k100.javaClass.name}#$effectName() returned null")
            return table
        }
    }

    private fun logEffectTableUnavailableOnce(reason: String) {
        if (mlTableUnavailableLogged.getAndSet(true) == false) {
            DebugLog.w(TAG, "masterlive effect table borrow unavailable: $reason")
        }
    }

    private fun hookMasterLiveFullFocal() {
        val profile = hostProfile ?: return
        if (configDispatchClasses().isEmpty()) {
            DebugLog.w(TAG, "no config dispatch classes; masterlive full focal hook skipped")
            return
        }
        val seen = HashSet<Method>()
        var hooked = 0
        for (clazz in configDispatchClasses()) {
            val method = profile.configMethod(clazz, profile.focalStops, SparseArray::class.java) ?: continue
            if (!seen.add(method)) continue
            deoptimize(method)
            method.hook("cam_masterlive_full_focal_${method.declaringClass.name}") {
                after { param ->
                    if (!masterliveEnabled() || !fullFocalEnabled()) return@after
                    val array = param.result as? SparseArray<*> ?: return@after
                    if (array.indexOfKey(CameraIdentity.MASTER_LIVE_MODE_ID) >= 0) return@after
                    // Copy before mutating: v1() results can be shared instances, and the hook
                    // must never widen the native table's visible state.
                    val clone = runCatching { array.javaClass.getMethod("clone") }.getOrNull()
                        ?: return@after
                    @Suppress("UNCHECKED_CAST")
                    val copy = runCatching { clone.invoke(array) as SparseArray<Any> }
                        .getOrNull() ?: return@after
                    copy.put(
                        CameraIdentity.MASTER_LIVE_MODE_ID,
                        // Mirror the value type of the existing table (boxed on every verified
                        // build); an empty table gets the boxed default.
                        CameraIdentity.masterLiveFocalStops(
                            if (array.isNotEmpty()) array.valueAt(0) else null
                        )
                    )
                    param.result = copy
                    if (fullFocalLogged.getAndSet(true) == false) {
                        DebugLog.i(TAG, "masterlive full focal: ${method.name}()[231] = ${CameraIdentity.MASTER_LIVE_FOCAL_STOPS.contentToString()}")
                    }
                }
            }
            hooked++
        }
        if (hooked == 0) {
            DebugLog.w(TAG, "no ${profile.focalStops}() Method resolved; masterlive full focal hook skipped")
        } else {
            DebugLog.i(TAG, "masterlive full focal hook installed on $hooked ${profile.focalStops}() dispatch method(s)")
        }
    }

    private val fullFocalLogged = AtomicReference(false)

    private val componentModuleList = AtomicReference<Class<*>?>()

    private fun resolveComponentModuleList(): Class<*>? {
        componentModuleList.get()?.let { return it }
        synchronized(componentModuleList) {
            componentModuleList.get()?.let { return it }
            val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
            val resolved = CameraResolver.resolveClass(
                scope = TAG, key = "component_module_list", ctx = ctx,

                probe = { bridge ->
                    // Both strings are CONTAINS-matched; the pair pins exactly one class.
                    bridge.findClass {
                        matcher { usingStrings("ComponentModuleList", "setAllSupportModeList") }
                    }.singleOrNull()?.name
                },
                validate = { c ->
                    hasDefaultModeListField(c) &&
                        c.declaredMethods.any {
                            !it.isSynthetic && it.returnType == IntArray::class.java && it.parameterCount == 1
                        }
                },
            )
            componentModuleList.set(resolved)
            return resolved
        }
    }

    private fun hasDefaultModeListField(clazz: Class<*>): Boolean =
        clazz.declaredFields.any { field ->
            java.lang.reflect.Modifier.isStatic(field.modifiers) && field.type == IntArray::class.java &&
                runCatching {
                    field.isAccessible = true
                    CameraIdentity.defaultModeListShape(field.get(null) as? IntArray)
                }.getOrDefault(false)
        }

    private fun hookMasterLiveOrderFunnel() {
        val clazz = resolveComponentModuleList() ?: run {
            DebugLog.w(TAG, "ComponentModuleList not resolved; masterlive order funnel skipped")
            return
        }
        val semantics = CameraSemantics.create(CameraResolver.Ctx(classLoader, hookParam.appInfo), TAG) ?: return
        val yMethod = semantics.anchored("mode order funnel", "pref_camera_sort_modes_key") {
            it.definingClass == CameraDexIndex.descriptor(clazz) && it.returnType == "[I" && it.parameterTypes.size == 1
        } ?: return
        deoptimize(yMethod)
        yMethod.hook("cam_masterlive_order_funnel") {
            after { param ->
                if (!masterliveEnabled() || !masterLiveHasEffectTable()) return@after
                val placed = CameraIdentity.placeMasterLiveModeBeforeMarker(param.result as? IntArray)
                if (placed != null) param.result = placed
            }
        }
        DebugLog.d(TAG, "masterlive order funnel hooked on ${clazz.name}#${yMethod.name}")
    }

    private fun hookMasterLiveSupportEntry() {
        val clazz = resolveComponentModuleList() ?: run {
            DebugLog.w(TAG, "ComponentModuleList not resolved; masterlive support entry skipped")
            return
        }
        val semantics = CameraSemantics.create(CameraResolver.Ctx(classLoader, hookParam.appInfo), TAG) ?: return
        val eMethod = semantics.anchored("persisted mode support", "all_support_mode_list") {
            it.definingClass == CameraDexIndex.descriptor(clazz) && it.returnType == "Z" &&
                it.parameterTypes.map(CharSequence::toString) == listOf("I")
        } ?: return
        deoptimize(eMethod)
        eMethod.hook("cam_masterlive_support_entry") {
            after { param ->
                val modeId = param.args.getOrNull(0) as? Int ?: return@after
                // The persisted all_support_mode_list can predate the 256 entry. Keep the
                // newly injected registry entry usable even when that stale list says false.
                if (legendaryMomentUnlock() && modeId == CameraIdentity.LEGENDARY_MOMENT_MODE_ID) {
                    if ((param.result as? Boolean) == false) param.result = true
                    return@after
                }
                if (!masterliveEnabled() || !masterLiveHasEffectTable()) return@after
                if ((param.result as? Boolean) != false) return@after
                if (modeId != CameraIdentity.MASTER_LIVE_MODE_ID) return@after
                param.result = true
            }
        }
        DebugLog.d(TAG, "masterlive support entry hooked on ${clazz.name}#${eMethod.name}(I)")
    }

    private fun hookMasterLiveTeleFallback() {
        // This optional path needs two DEX scans. It is installed only when the switch was
        // already enabled at camera attach; after hooking, the callback still reads it live.
        if (!masterliveTeleFallback()) return
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
        val role23 = CameraResolver.resolveMethodByStrings(
            scope = TAG, key = "role_23_camera_id", ctx = ctx,
            anchors = listOf("roleId=23"),
            shape = {
                !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                    it.returnType == java.lang.Integer.TYPE
            },
        ) ?: run {
            DebugLog.w(TAG, "role-23 camera-id getter not uniquely resolved; tele fallback skipped")
            return
        }
        val role20 = CameraResolver.resolveMethodByStrings(
            scope = TAG, key = "role_20_camera_id", ctx = ctx,
            anchors = listOf("roleId=20"),
            shape = {
                !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                    it.returnType == java.lang.Integer.TYPE
            },
        ) ?: run {
            DebugLog.w(TAG, "role-20 camera-id getter not uniquely resolved; tele fallback skipped")
            return
        }
        deoptimize(role23)
        role23.hook("cam_masterlive_tele_fallback") {
            after { param ->
                if (!masterliveTeleFallback() || deviceIsNezha()) return@after
                if ((param.result as? Int) != -1) return@after
                val teleId = runCatching { role20.invoke(param.thisObject) as? Int }
                    .getOrNull()?.takeIf { it != -1 } ?: return@after
                param.result = teleId
            }
        }
        DebugLog.d(TAG, "masterlive tele fallback hooked on semantic role-23 getter ${role23.declaringClass.name}#${role23.name}()")
    }

    private fun hookMasterLiveVideoSizeProbe() {
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)

        // is backup only — "getLivePhotoVideoSize" is NOT plaintext in the dex (runtime
        // concatenation), so candidates are the primary path.
        val semantics = CameraSemantics.create(ctx, TAG) ?: return
        val method = semantics.anchored("live-photo video size", "getLivePhotoVideoSize: fail") {
            it.returnType == "Landroid/util/Size;" && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == "Landroid/util/Size;"
        } ?: return
        val ref = semantics.reference(method) ?: return
        val moduleField = semantics.dex.code(ref).reads.filter {
            it.definingClass == ref.parameterTypes[1].toString() && it.type == "I"
        }.distinctBy { it.toString() }.singleOrNull()?.let { semantics.dex.field(it, classLoader) } ?: return
        deoptimize(method)
        method.hook("cam_masterlive_video_size_probe") {
            after { param ->
                if (!videoSizeProbeEnabled()) return@after
                if ((param.result as? Size) == null) return@after
                val receiver = param.args.getOrNull(1) ?: return@after
                val mode = runCatching { moduleField.getInt(receiver) }.getOrNull() ?: return@after
                if (mode != CameraIdentity.MASTER_LIVE_MODE_ID) return@after
                val original = param.result as? Size ?: return@after
                val pinned = CameraMasterLiveSizeBinding.boundSize(
                    currentMasterLiveType(), original.width, original.height
                ) ?: return@after
                param.result = Size(pinned.first, pinned.second)
                if (videoSizeProbeLogged.getAndSet(true) == false) {
                    DebugLog.i(
                        TAG,
                        "masterlive video size probe: getLivePhotoVideoSize $original " +
                            "(type ${currentMasterLiveType() ?: "?"}) -> ${pinned.first}x${pinned.second}"
                    )
                }
            }
        }
        DebugLog.i(TAG, "masterlive video size probe hooked on ${method.declaringClass.name}#${method.name}(Size,..)")
    }

    private val masterLiveTypeMethod = AtomicReference<Method?>(null)
    private fun resolveMasterLiveType() {
        val semantics = CameraSemantics.create(CameraResolver.Ctx(classLoader, hookParam.appInfo), TAG) ?: return
        val owners = semantics.dex.ownerStrings("ComponentRunningMasterLive", "pref_master_live_key")
        val reader = semantics.unique("MasterLive selected effect", semantics.dex.methods.filter { ref ->
            Modifier.isStatic(ref.accessFlags) && ref.returnType == "Ljava/lang/String;" &&
                ref.parameterTypes.map(CharSequence::toString) == listOf("I") &&
                semantics.dex.code(ref).calls.any { it.method.definingClass in owners && it.method.name == "getComponentValue" }
        })
        masterLiveTypeMethod.set(reader)
    }

    private fun currentMasterLiveType(): String? = runCatching {
        masterLiveTypeMethod.get()?.invoke(null, CameraIdentity.MASTER_LIVE_MODE_ID) as? String
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private val videoSizeProbeLogged = AtomicReference(false)

    private fun hookMasterLiveVideoSurfaceSize() {
        if (!videoSizeProbeEnabled()) return
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
        val method = CameraResolver.resolveMethodByNumbers(
            scope = TAG,
            key = "masterlive_surface_size_fallback",
            ctx = ctx,
            numbers = listOf(2304, 1296),
            shape = {
                !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                    it.returnType == Size::class.java
            },
        )
            ?: run {
                DebugLog.w(TAG, "surface-size fallback was not uniquely resolved; size probe skipped")
                return
            }
        deoptimize(method)
        method.hook("cam_masterlive_video_surface_size") {
            after { param ->
                val original = param.result as? Size ?: return@after
                val receiver = param.thisObject
                // Mode gate: only rewrite inside MasterLive (the same consumer also serves the
                // normal live-photo modes' 4:3 geometry). Inspect the bounded object graph by
                // value so owner/field names can move between camera builds.
                if (!receiverContainsUniqueModeId(receiver, CameraIdentity.MASTER_LIVE_MODE_ID)) {
                    return@after
                }
                val pinned = CameraMasterLiveSizeBinding.boundSize(
                    currentMasterLiveType(), original.width, original.height
                ) ?: return@after
                param.result = Size(pinned.first, pinned.second)
                if (videoSurfaceProbeLogged.getAndSet(true) == false) {
                    DebugLog.i(
                        TAG,
                        "masterlive video surface size: ${method.declaringClass.name}#${method.name}() $original " +
                            "(type ${currentMasterLiveType() ?: "?"}) -> ${pinned.first}x${pinned.second}"
                    )
                }
            }
        }
        DebugLog.i(TAG, "masterlive video surface size hooked on ${method.declaringClass.name}#${method.name}()")
    }

    private val videoSurfaceProbeLogged = AtomicReference(false)

    private fun receiverContainsUniqueModeId(receiver: Any, expected: Int): Boolean {
        val pending = ArrayDeque<Pair<Any, Int>>()
        val visited = IdentityHashMap<Any, Boolean>()
        pending.add(receiver to 0)
        var matches = 0
        var visitedCount = 0
        while (pending.isNotEmpty() && visitedCount < 40) {
            val (owner, depth) = pending.removeFirst()
            if (visited.put(owner, true) != null) continue
            visitedCount++
            var type: Class<*>? = owner.javaClass
            while (type != null && type != Any::class.java) {
                for (field in type.declaredFields) {
                    if (Modifier.isStatic(field.modifiers)) continue
                    if (field.type == Integer.TYPE) {
                        val value = runCatching {
                            field.isAccessible = true
                            field.getInt(owner)
                        }.getOrNull()
                        if (value == expected && ++matches > 1) return false
                    } else if (depth < 4 && !field.type.isPrimitive && !field.type.isArray &&
                        !field.type.name.startsWith("java.") &&
                        !field.type.name.startsWith("android.") &&
                        !field.type.name.startsWith("kotlin.")
                    ) {
                        runCatching {
                            field.isAccessible = true
                            field.get(owner)
                        }.getOrNull()?.let { pending.add(it to depth + 1) }
                    }
                }
                type = type.superclass
            }
        }
        return matches == 1
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
            it.definingClass == CameraDexIndex.descriptor(selected.declaringClass) &&
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

    private fun deviceIsNezha(): Boolean {
        deviceIsNezhaCache.get()?.let { return it }
        synchronized(deviceIsNezhaCache) {
            deviceIsNezhaCache.get()?.let { return it }
            val isNezha = Build.DEVICE.equals("nezha", ignoreCase = true)
            deviceIsNezhaCache.set(isNezha)
            return isNezha
        }
    }

    private fun activeConfigInstance(): Any? =
        runCatching { hostProfile?.configInstance() }.getOrNull()

    private fun configDispatchClasses(): List<Class<*>> {
        val classes = LinkedHashSet<Class<*>>()
        activeConfigInstance()?.javaClass?.let { classes.add(it) }
        return classes.toList()
    }

    // ─── gate helpers ─────────────────────────────────────────────────────────────

    private fun keepModel(): Boolean = true

    private fun leicaStyle(): Boolean =
        Preferences.getBoolean(Preferences.KEY_CAMERA_LEICA_STYLE, false)

    private fun legendaryMomentUnlock(): Boolean {
        val mode = Preferences.cameraLegendaryMomentMode()
        return mode != CameraLegendaryMomentMode.MODE_OFF && CameraLegendaryProfileState.isApplied(mode)
    }

    private fun smartCompositionUnlock(): Boolean =
        Preferences.getBoolean(Preferences.KEY_CAMERA_SMART_COMPOSITION, false)

    private fun contentCredentialUnlock(): Boolean =
        Preferences.getBoolean(Preferences.KEY_CAMERA_CONTENT_CREDENTIAL, false)

    private fun adaptiveLensUnlock(): Boolean =
        Preferences.getBoolean(Preferences.KEY_CAMERA_ADAPTIVE_LENS, false)

    private fun masterliveEnabled(): Boolean =
        Preferences.getBoolean(Preferences.KEY_CAMERA_MASTERLIVE_ENABLE, false)

    private fun masterliveTeleFallback(): Boolean =
        Preferences.getBoolean(Preferences.KEY_CAMERA_MASTERLIVE_TELE_FALLBACK, false)

    private fun videoSizeProbeEnabled(): Boolean =
        Preferences.getBoolean(Preferences.KEY_CAMERA_MASTERLIVE_VIDEO_SIZE_PROBE, false)

    private val redCarpetCaptureReady = java.util.concurrent.atomic.AtomicBoolean(
        !CameraMasterLiveRedCarpet.needsSlowMotionFallback(Build.DEVICE, Build.HARDWARE),
    )

    private fun redCarpetEnabled(): Boolean = redCarpetCaptureReady.get() &&
        Preferences.getBoolean(Preferences.KEY_CAMERA_MASTERLIVE_RED_CARPET, false)

    private fun fullFocalEnabled(): Boolean =
        Preferences.getBoolean(Preferences.KEY_CAMERA_MASTERLIVE_FULL_FOCAL, false)
}
