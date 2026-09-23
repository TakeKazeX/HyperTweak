package com.takekazex.hypertweak.hook.rules.camera

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Size
import android.util.SparseArray
import androidx.core.util.isNotEmpty
import com.takekazex.hypertweak.hook.CameraLegendaryMomentMode
import com.takekazex.hypertweak.hook.CameraStreetMode
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

/** Camera feature hooks resolved from semantic DEX anchors and runtime ABI contracts.
 *
 * Device-config class, config field, and singleton are discovered as one validated object
 * graph by [CameraHostProfile]. Other obfuscated owners are resolved by stable feature strings,
 * call-site signatures, or value/return contracts. Ambiguous targets skip that sub-feature.
 * This keeps a factory/class rename from disabling unrelated camera hooks.
 */
object CameraImpersonationHooker : StaticHooker() {
    private const val TAG = "CamImpersonate"
    private const val PACKAGE = "com.android.camera"
    private const val TINT_COLOR_PREFERENCE_KEY = "pref_tint_color"
    private const val CUSTOMIZATION_CATEGORY_KEY = "category_customization"
    private const val MIUI_WIDGET_METADATA_KEY = "miuiWidget"
    private const val DEFAULT_WIDGET_LAYOUT_METADATA_KEY = "defaultLayoutInPA"
    private const val APP_WIDGET_PROVIDER_METADATA_KEY = "android.appwidget.provider"

    /** Reflection names below are feature contracts only; target owner names come from DexKit. */

    private val hostProfile by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        CameraHostProfile.resolve(CameraResolver.Ctx(classLoader, hookParam.appInfo), TAG)
    }
    private val shutterSoundLoadScope = ThreadLocal<ArrayDeque<Boolean>>()

    /**
     * Watermark entry holder class used by `S8.d`'s brand/model cache (the value of `zi.b`'s
     * field `a`; fields a/b + (String,String) ctor):
     *  - 6.6.000510.0: `Ft.a` (verified in smali: `zi/b.smali` field `->a:LFt/a;`, ctor
     *    `(String,String)`; jadx misrendered the type as `a`/`zi.a`)
     *  - 6.6.000460.0: `i5.d` (jadx `p288i5.d`, imported type in `zi/b.java`)
     */
    /**
     * Method/field names in the camera dex are NOT obfuscated per build — the class names are
     * (jadx renders those as `p####` packages plus `f#####` field aliases when they collide
     * with root-package names; the on-device names are the short letters, e.g. `u6.e`,
     * `fs.m`, field `a`). The migration on 6.6.000510.0 (OS4.0.0.19) confirmed class names are
     * ALSO not stable and can even be REUSED for unrelated classes, so every resolution goes
     * through [CameraResolver] with candidate lists + semantic validation instead of bare
     * `toClassOrNull()`.
     */

    /** Resolve a class by its real dex name, falling back to the jadx alias for older ROMs. */
    private fun resolveClass(vararg names: String): Class<Any>? {
        for (name in names) name.toClassOrNull()?.let { return it }
        return null
    }

    /** Resolve a field by its real dex name, falling back to the jadx alias (`getDeclaredField`). */
    private fun resolveField(clazz: Class<*>, vararg names: String): Field? {
        for (name in names) {
            runCatching { clazz.getDeclaredField(name) }.getOrNull()?.let { return it }
        }
        return null
    }

    /** Resolve a public field (including inherited ones) by real dex name, falling back to the alias. */
    private fun resolvePublicField(clazz: Class<*>, vararg names: String): Field? {
        for (name in names) {
            runCatching { clazz.getField(name) }.getOrNull()?.let { return it }
        }
        return null
    }

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
        hookWatermarkKeep()
        hookWatermarkConfigCache()
        hookWatermarkRender()
        hookWatermarkBrandText()
        hookLccTheme()
        hookLccCustomizationProvider()
        hookDisabledWidgetReceiverMetadataLookup()
        // 兼容模式街拍 must install unconditionally: it opens the entry through the module's
        // own `StreetModuleEntry.support()`, independent of every other hook.
        hookCompatStreetSupport()
        // 新街拍 forces the street-support gate `a3()` on the active config's base Method —
        // master-independent by design, so it installs directly (no mode-guard wrapper).
        hookStreetEnable()
        // 快捷抢拍走街拍 (lock-screen fast-camera quick-capture → street) — independent of
        // `a3()`, complements both street modes.
        hookStreetQuickLaunch()
        hookLeicaStyle()
        hookShutterSoundPlaybackRoute()
        hookLegendaryProfileGate()
        hookLegendarySupport()
        hookLegendaryRegistry()
        hookSmartComposition()
        hookSmartCompositionTopRow()
        hookSmartCompositionFeatureBar()
        hookContentCredential()
        hookAdaptiveLens()
        hookMasterLiveModePlacement()
        hookMasterLiveSupportGate()
        hookMasterLiveRealEffectTable()
        hookMasterLiveSlowMotionFallback()
        hookMasterLiveFullFocal()
        hookMasterLiveOrderFunnel()
        hookMasterLiveSupportEntry()
        hookMasterLiveTeleFallback()
        hookMasterLiveVideoSizeProbe()
        hookMasterLiveVideoSurfaceSize()
        hookShutterSoundBoundary()
    }

    // ─── Legacy K100 effect table, validated against the real device's imaging identity ───

    /** Resolve the legacy K100 logical profile through the host source resolver and identity gate.
     * Owner class names never participate in selection. Modern config ABIs are rejected before
     * this path because their reused boolean getters are not the validated legacy identity.
     */
    private fun resolveK100Config(loader: ClassLoader, resolver: Method): Any? {
        // Snapshot once so every source-profile candidate uses the same identity baseline.
        val original = activeConfigInstance()
        for (sourceName in K100_SOURCE_NAME_CANDIDATES) {
            val instance = buildFrom(loader, resolver, sourceName) ?: continue
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

    /**
     * The source-family identifier is semantic (device profile), while the class and factory
     * names are resolved from the DEX call graph. A mapped source must still pass
     * [isK100Candidate].
     */
    private val K100_SOURCE_NAME_CANDIDATES = listOf(
        "com.mi.device.Songyuan",
    )

    private val legendaryEntryClass = AtomicReference<Class<*>?>(null)

    /** Shape + identity validation for a K100-role config candidate. */
    private fun isK100Candidate(instance: Any, original: Any?): Boolean {
        if (!isK100Shaped(instance)) return false
        if (original == null) return true // no original -> shape only
        val clazz = instance.javaClass
        if (clazz == original.javaClass || clazz.name == original.javaClass.name) return false
        return CameraIdentity.sharesImagingIdentity(instance, original)
    }

    /** Require the currently selected config ABI, including the effect-table return shape. */
    private fun isK100Shaped(instance: Any): Boolean = runCatching {
        val profile = hostProfile ?: return@runCatching false
        val clazz = instance.javaClass
        profile.configType.isAssignableFrom(clazz) &&
            profile.configMethod(clazz, profile.streetGate, java.lang.Boolean.TYPE) != null &&
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
        val xMethod = CameraResolver.resolveMethod(
            scope = TAG, key = "wm_keep_x", clazz = clazz,
            names = listOf(profile.brandGetter),
            shape = { it.parameterTypes.isEmpty() && it.returnType == String::class.java },
        ) ?: run {
            DebugLog.w(TAG, "${clazz.name}#x() not found; watermark keep skipped")
            return
        }
        deoptimize(xMethod)
        xMethod.hook("cam_keep_model_logo") {
            after { param ->
                if (!keepModel()) return@after
                param.result = CameraWatermarkBrand.brand()
            }
        }

        val vMethod = CameraResolver.resolveMethod(
            scope = TAG, key = "wm_keep_v", clazz = clazz,
            names = listOf(profile.modelArrayGetter),
            shape = { it.parameterTypes.isEmpty() && it.returnType == Array<String>::class.java },
        ) ?: run {
            DebugLog.w(TAG, "${clazz.name}#v() not found; watermark keep skipped")
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
        DebugLog.d(TAG, "watermark keep hooked on ${clazz.name}#x()/#v()")
    }

    /**
     * Best-effort capture of the ORIGINAL real config's watermark third text slot so the
     * keep-model `v()` after-hook reproduces the platform's 3-slot shape. The config classes
     * (C1143 family) do NOT expose a `v()` method — that lives on the `Je.c` facade — so this
     * normally resolves nothing and leaves the third slot null, which is safe (`Je.c.w()` guards
     * on `length>2`) and matches a stock non-flagship's empty third element.
     */
    private fun captureOriginalThirdSlot() {
        val original = CameraLegendaryProfileState.nativeConfigSnapshot()
            ?: activeConfigInstance()
            ?: return
        runCatching {
            val v = original.javaClass.getMethod("v")
            val arr = v.invoke(original) as? Array<*> ?: return@runCatching
            if (arr.size > 2) originalThirdSlot.set(arr[2] as? String)
        }.onFailure { t ->
            // NoSuchMethodException on the config class is expected (v() is a facade method);
            // only surface unexpected failures.
            if (t !is java.lang.reflect.InvocationTargetException &&
                t.message?.contains("NoSuchMethod", ignoreCase = true) != true
            ) {
                DebugLog.w(TAG, "original v()[2] capture failed (defensive)", t)
            }
        }
    }

    // ─── 3. Keep the watermark config cache (S8.d) fresh with this device's brand/model ─

    /**
     * The camera caches the classic/Leica-watermark brand + model once in the `S8.d` singleton
     * (`S8.d.a.a`, an `i5.d` built from `Je/c#x()/#y()` at first construction; jadx shows the
     * names as `f15058a`/`f68841a`/`p288i5.d`, `S8/d.java:36`). Because that cache is
     * process-lifetime, a custom-watermark change made later never reaches the cached config.
     * Hooking the singleton accessor `S8.d.a()` re-asserts the cached entry with the current
     * brand()/model() on every access, so the watermark fires this device's values (or the
     * custom override) at every render.
     */
    private fun hookWatermarkConfigCache() {
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
        val clazz = CameraResolver.resolveClass(
            scope = TAG, key = "wm_config_singleton", ctx = ctx,
            candidates = emptyList(),
            // `CloudWatermark` survives as a plaintext dex string (classes.dex).
            probe = { bridge ->
                bridge.findClass { matcher { usingStrings("CloudWatermark") } }
                    .firstOrNull { cd ->
                        ctx.loadOrNull(cd.name)?.declaredMethods?.any {
                            it.name == "a" && it.parameterCount == 0 &&
                                Modifier.isStatic(it.modifiers) && it.returnType == it.declaringClass
                        } == true
                    }?.name
            },
            validate = { c ->
                c.declaredMethods.any {
                    it.name == "a" && it.parameterTypes.isEmpty() &&
                        java.lang.reflect.Modifier.isStatic(it.modifiers) && it.returnType == c
                }
            },
        ) ?: run {
            DebugLog.w(TAG, "S8.d watermark manager not resolved; config cache refresh skipped")
            return
        }
        val aMethod = CameraResolver.resolveMethod(
            scope = TAG, key = "wm_config_singleton_a", clazz = clazz,
            names = listOf("a"),
            shape = { it.parameterTypes.isEmpty() && java.lang.reflect.Modifier.isStatic(it.modifiers) },
        ) ?: run {
            DebugLog.w(TAG, "${clazz.name}#a() not found; config cache refresh skipped")
            return
        }
        deoptimize(aMethod)
        aMethod.hook("cam_wm_config_refresh") {
            after { param ->
                refreshWatermarkConfigCache(param.result ?: return@after)
            }
        }
        DebugLog.d(TAG, "watermark config cache refresh hooked on ${clazz.name}#a()")
    }

    /**
     * Re-assert the brand+model watermark entry into the `S8.d` singleton unless it already
     * matches. The cache chain moved between builds: the entry holder was `i5.d` on
     * 6.6.000460.0 (fields a/b, (String,String) ctor) and became `Ft.a` on 6.6.000510.0
     * (verified in smali: `zi.b`'s field `a` is typed `LFt/a;`, class exposes the same
     * a/b fields + (String,String) ctor). The `S8.d` singleton field (`a`), the `zi.b` field
     * name (`a`) and the guarded field names (`a`/`b`) are stable across both builds, so only
     * the entry CLASS needs the candidate list; a failure here is defensive/logged and never
     * disturbs rendering (the J0 render-keep hook still forces brand/model per render).
     */
    private fun refreshWatermarkConfigCache(singleton: Any) {
        runCatching {
            val brand = CameraWatermarkBrand.brand()
            val model = CameraWatermarkBrand.model()
            // Real dex fields: S8.d.a (jadx `f15058a`/`f15059a`), zi.b.a (jadx `f68841a`/`f68816a`),
            // entry a/b (jadx renamed the root-package-colliding ones to `f#####`; the on-device
            // names are the short letters).
            val holderField = resolveField(singleton.javaClass, "a", "f15058a", "f15059a")
                ?.apply { isAccessible = true } ?: return
            val holder = holderField.get(singleton) ?: return
            val cacheField = resolveField(holder.javaClass, "a", "f68841a", "f68816a")
                ?.apply { isAccessible = true } ?: return
            val current = cacheField.get(holder)
            // Best-effort equality guard; if the current value can't be read, just rebuild.
            val same = runCatching {
                val curBrand = current?.javaClass
                    ?.let { resolveField(it, "a", "f43446a") }
                    ?.apply { isAccessible = true }?.get(current)
                val curModel = current?.javaClass
                    ?.let { resolveField(it, "b") }
                    ?.apply { isAccessible = true }?.get(current)
                curBrand == brand && curModel == model
            }.getOrDefault(false)
            if (same) return
            // The cache field declares the exact entry type on this APK (`zi.c` on 6.8,
            // `Ft.a` on 6.6). Deriving it avoids a second independently obfuscated name.
            val entryClass = cacheField.type
            // Use the (String,String) ctor by ITS OWN parameter types: looking it up as
            // `(Object,Object)` exact-match reflection would silently no-op the refresh.
            val ctor = entryClass.declaredConstructors.firstOrNull {
                it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == String::class.java
            } ?: return
            ctor.isAccessible = true
            cacheField.set(holder, ctor.newInstance(brand, model))
        }.onFailure { t ->
            DebugLog.w(TAG, "watermark config cache refresh failed (defensive)", t)
        }
    }

    // ─── 4. Force this device's brand/model into every watermark render ───────────

    /**
     * `com.xiaomi.cam.watermark.a#J0(String deviceLogo, String model, boolean)` is the final
     * funnel every classic/cloud watermark render passes through (called by `zi/b.d()`, jadx
     * `p890zi/b.d()`, with the `S8.d` cached brand+model). Two reasons to force it:
     * (a) the watermark model view `fs/m.o()` (jadx `p203fs/m.o()`) treats a model of
     *     "17 ultra by leica" / "leitzphone powered by xiaomi" as an lcc_gl device and renders
     *     the 17-Ultra-style watermark — that is the "17U watermark right after capture" leak,
     *     because some capture-time reads still see the impersonated strings. Forcing the J0
     *     args to `CameraWatermarkBrand` values makes the lcc_gl branch unreachable (the model
     *     is never a 17U string) for EVERY render, including the immediate capture one;
     * (b) the custom brand/model reach the renderer regardless of which intermediate
     *     cache/path fed J0.
     * Only a non-blank incoming render is overridden; an explicitly blank (watermark-off) call
     * is left untouched.
     */
    private fun hookWatermarkRender() {
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
        val clazz = CameraResolver.resolveClass(
            scope = TAG, key = "wm_renderer", ctx = ctx,
            candidates = emptyList(),
            probe = { bridge ->
                bridge.findMethod { matcher { usingStrings("deviceLogo") } }
                    .mapNotNull { data -> runCatching { data.getMethodInstance(classLoader) }.getOrNull() }
                    .filter { method ->
                        method.parameterTypes.contentEquals(
                            arrayOf(String::class.java, String::class.java, java.lang.Boolean.TYPE)
                        ) && method.returnType == Void.TYPE && !Modifier.isStatic(method.modifiers)
                    }
                    .map { it.declaringClass }
                    .distinctBy { it.name }
                    .singleOrNull()?.name
            },
            validate = { c ->
                c.declaredMethods.any {
                    it.name == "J0" && it.parameterTypes.size == 3 &&
                        it.parameterTypes[0] == String::class.java &&
                        it.parameterTypes[1] == String::class.java &&
                        it.parameterTypes[2] == java.lang.Boolean.TYPE
                }
            },
        ) ?: run {
            DebugLog.w(TAG, "watermark renderer not resolved; render keep skipped")
            return
        }
        val j0 = clazz.declaredMethods.singleOrNull { m ->
            !m.isSynthetic && !Modifier.isStatic(m.modifiers) &&
                m.parameterTypes.size == 3 &&
                    m.parameterTypes[0] == String::class.java &&
                    m.parameterTypes[1] == String::class.java &&
                    m.parameterTypes[2] == java.lang.Boolean.TYPE
        }?.apply { isAccessible = true } ?: run {
            DebugLog.w(TAG, "unique watermark render method not found; render keep skipped")
            return
        }
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
        DebugLog.d(TAG, "watermark render keep hooked on ${clazz.name}#J0")
    }

    // ─── 5. Render the custom watermark brand through the stock logo slot ─────────

    /**
     * Single-render design for the custom 厂商 (brand).
     *
     * Render chain (verified in `cache/camera-8f41d7b82453cdeb`): `S8.d` caches the brand+model
     * entry (`S8/d.java:36`), `zi/b.d()` feeds it to the one and only `J0` funnel
     * (`p890zi/b.java:110-119`), and `com.xiaomi.cam.watermark.a#J0`
     * (`com/xiaomi/cam/watermark/a.java:902-931`) (a) stores the brand/model on the watermark
     * config — from where the logo IMAGE view loads `<brand>_<color>.webp`
     * (`com/xiaomi/cam/watermark/b.smali` `loadAndScaleImage`, pathType=fill) — and (b) calls
     * `fs.m#o` on every WmModelView, whose format substitution replaces `@{logo}` with the brand
     * STRING (`p203fs/m.java:74`). A brand therefore becomes visible exactly once, through the
     * stock renderer: as the logo image when the asset exists (XIAOMI/REDMI/POCO), or as the
     * `@{logo}` text line when it does not. A missing logo asset renders NOTHING (the view is
     * skipped with a "bitmap is null" log), so there is no stock fallback that could duplicate
     * the brand.
     *
     * REGRESSION HISTORY (do not reintroduce): an earlier implementation after-hooked `fs.m#o`
     * and PREPENDED the brand onto the rendered text field, guarded by a `contains()` check on
     * the output. That composed onto values another path had already filled (a template whose
     * format carries `@{logo}` natively, the parse-time `m.c()` call that seeds the field with
     * the market-name values before `J0` runs, or layouts with more than one model view), so
     * the brand appeared in several places at once — stacked as two lines. The fix removes ALL
     * output composition: the hook now injects a leading `@{logo}` line into the view's FORMAT
     * (`fs.m` field `B`, jadx `f40617B`) before the original `o()` runs and restores the format
     * afterwards, so the stock substitution renders the brand line itself — exactly once per
     * view, by construction, whatever the template looks like.
     */
    private fun hookWatermarkBrandText() {
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
        val clazz = CameraResolver.resolveClass(
            scope = TAG, key = "wm_model_view", ctx = ctx,
            candidates = emptyList(),
            // `WmModelView` survives as a plaintext dex string (classes10.dex).
            probe = { bridge ->
                bridge.findClass { matcher { usingStrings("WmModelView") } }
                    .firstOrNull()?.name
            },
            validate = { c ->
                c.declaredMethods.any {
                    it.name in listOf("p", "o") && it.parameterTypes.size == 4 &&
                        it.parameterTypes[0] == String::class.java &&
                        it.parameterTypes[1] == String::class.java &&
                        it.parameterTypes[2] == java.lang.Boolean.TYPE &&
                        it.parameterTypes[3] == java.lang.Boolean.TYPE
                } &&
                    // The model format field (real dex name `B`, jadx alias `f40617B`) must
                    // exist — it is what the injection rewrites. Without it the hook cannot
                    // work and must not install.
                    (resolvePublicField(c, "B", "f40617B") != null)
            },
        ) ?: run {
            DebugLog.w(TAG, "WmModelView (fs.m) not resolved; brand logo-line skipped")
            return
        }
        val formatField = resolvePublicField(clazz, "B", "f67437B", "f40617B")
            ?.apply { isAccessible = true }
            ?: run {
                DebugLog.w(TAG, "${clazz.name} model format field not found; brand logo-line skipped")
                return
            }
        val oMethod = clazz.declaredMethods.singleOrNull { m ->
            !m.isSynthetic && !Modifier.isStatic(m.modifiers) &&
                m.parameterTypes.size == 4 &&
                    m.parameterTypes[0] == String::class.java &&
                    m.parameterTypes[1] == String::class.java &&
                    m.parameterTypes[2] == java.lang.Boolean.TYPE &&
                    m.parameterTypes[3] == java.lang.Boolean.TYPE
        }?.apply { isAccessible = true } ?: run {
            DebugLog.w(TAG, "unique model-view method not found; brand logo-line skipped")
            return
        }
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
        DebugLog.d(TAG, "brand logo-line hooked on ${clazz.name}#o()")
    }

    // ─── 6. Fake the LCC theme so LCC-gated branches open (independent of any config swap) ──

    private fun hookLccTheme() {
        val profile = hostProfile ?: return
        val clazz = profile.facade
        val vMethod = CameraResolver.resolveMethod(
            scope = TAG, key = "lcc_theme_v", clazz = clazz,
            names = listOf(profile.lccGate),
            shape = {
                Modifier.isStatic(it.modifiers) && it.parameterTypes.isEmpty() &&
                    it.returnType == java.lang.Boolean.TYPE
            },
        ) ?: run {
            DebugLog.w(TAG, "${clazz.name}#V() LCC gate not found; theme gate skipped")
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

    /**
     * Resolve the 相机配色 gate from the preference code's call graph. Both preference keys are
     * stable host data, while the provider holder, accessor, interface, implementation class,
     * and method names are all allowed to change between camera APKs. The resolver follows the
     * zero-arg boolean call through the zero-arg provider accessor and the static holder field,
     * then hooks the concrete method on the currently selected provider object.
     */
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

    /**
     * Camera's own updater reads metadata from disabled widget receivers with GET_META_DATA only.
     * Discover such receivers from the installed package metadata and add MATCH_DISABLED_COMPONENTS
     * only to those metadata lookups. This follows manifest role/metadata instead of receiver names.
     */
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

    /**
     * 新街拍 (street unlock mode `"new"`, [Preferences.KEY_CAMERA_STREET_MODE]): force the
     * street-support gate `a3()` true on the active device config's Method. No REDMI
     * config ships `a3=true` (510: `a3()` = `instanceof C1172`, declared ONCE on the base
     * C1143), so street is invisible until this hook turns it on. The mode then opens the REAL
     * HAL role-0 main camera (myron role 0 = camera 2): `StreetModule` has no camera-id
     * override, so the module framework's role-0 path opens the real main camera; `b6()` is
     * natively true on myron and must NOT be clamped.
     *
     * Because `a3()` is the single switch behind BOTH street visibility and the quick-launch
     * re-classification (`p700u2.S` IntentParser: STILL_IMAGE + launch source
     * `launch_camera_and_take_photo` → candidate mode 225 when `a3() && J.f()`), forcing it
     * keeps every consumer consistent with a WORKING street mode — that consistency is exactly
     * what distinguishes this mode from `"compat"`.
     *
     * RAISE-ONLY (the "修复没打开伪装旗舰相机配置时街拍用不了" fix): the callback does NOT read any
     * impersonation gate — it can never fight another hook over `a3()`. The entry lands in the
     * 更多 overflow (no config `M()` carries 225) and opens through the module framework's HAL
     * role-0 path = the REAL main camera; a camera restart is needed for visibility because the
     * entry registry caches per process. When the mode is off or compat the native value is
     * left untouched — it never LOWERS a native `a3()` (a genuinely street-capable device keeps
     * its own entry).
     */
    private fun hookStreetEnable() {
        val profile = hostProfile ?: return
        val methods = LinkedHashSet<Method>()
        for (clazz in configDispatchClasses()) {
            val method = profile.configMethod(clazz, profile.streetGate, java.lang.Boolean.TYPE) ?: run {
                DebugLog.d(TAG, "street-enable getter ${clazz.name}#${profile.streetGate}() not found; skipped")
                continue
            }
            methods.add(method)
        }
        if (methods.isEmpty()) {
            DebugLog.w(TAG, "street-enable getter a3() not resolved on any dispatch class; new-mode street skipped")
            return
        }
        var hooked = 0
        for (method in methods) {
            deoptimize(method)
            method.hook("cam_street_enable_${method.declaringClass.name}") {
                before { param ->
                    // RAISE-ONLY and MASTER-INDEPENDENT: only ever turn the gate ON. Skipping
                    // (instead of forcing false) when the mode is off or compat leaves native
                    // configs untouched — an earlier build always set the result, which hid
                    // NATIVE street on genuinely capable devices whenever the master switch
                    // was off.
                    if (streetMode() != CameraStreetMode.MODE_NEW) {
                        return@before
                    }
                    param.result = true
                    logStreetApplyOnce("new(${profile.streetGate})")
                }
            }
            hooked++
        }
        DebugLog.i(
            TAG,
            "street-enable hooked on $hooked ${profile.streetGate}() dispatch method(s): " +
                methods.joinToString { "${it.declaringClass.name}#${it.name}" } + " (mode=new)"
        )
    }

    /**
     * 兼容模式街拍 (street unlock mode `"compat"`): force
     * `com.android.camera.features.mode.street.StreetModuleEntry.support()` true — the REAL
     * registry gate (`p666t3.a.d()` instantiates all module entries and keeps only those whose
     * `support()` is true) — directly, without touching any capability-config gate.
     *
     * Street still registers (it lands in the 更多 overflow grid, like on natively
     * street-capable devices — no verified config `M()[I` carries 225), and `StreetModule` has
     * no camera-id override, so it opens through the module framework's HAL role-0 path = the
     * REAL main camera. Nothing else changes: `a3()` stays native (quick-launch keeps its stock
     * CAPTURE classification), 装备街拍 stays closed, colour pipelines stay on the device's own
     * calibration.
     *
     * The class name is plaintext in every verified dex (the entry registry references it
     * literally), but resolution still goes through [CameraResolver] with a shape validation
     * (zero-arg boolean `support()` + zero-arg int `getModuleId()`) plus a DexKit probe keyed
     * on `getEntryName()`'s constant, so a repurposed name can never win. The after-hook only
     * ever RAISES the result (never lowers a native true), and the applied state is logged
     * once per flip so an on-device failure is one-logcat-line diagnosable.
     */
    private fun hookCompatStreetSupport() {
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
        val clazz = CameraResolver.resolveClass(
            scope = TAG, key = "street_module_entry", ctx = ctx,
            candidates = emptyList(),
            // `getEntryName()` returns the class name constant — plaintext in classes.dex on
            // both verified builds; filter to the shape below so a mere REFERENCE to the name
            // (the registry class) can never match.
            probe = { bridge ->
                bridge.findClass {
                    matcher { usingStrings(STREET_ENTRY_CLASS) }
                }.firstOrNull { cd ->
                    cd.methods.any { m ->
                        m.name == "support" && m.returnTypeName == "boolean" &&
                            m.paramTypes.isEmpty()
                    }
                }?.name
            },
            validate = { c ->
                c.declaredMethods.any {
                    it.name == "support" && it.parameterCount == 0 &&
                        it.returnType == java.lang.Boolean.TYPE && !it.isSynthetic
                } && c.declaredMethods.any {
                    it.name == "getModuleId" && it.parameterCount == 0 &&
                        it.returnType == java.lang.Integer.TYPE
                }
            },
        ) ?: run {
            DebugLog.w(TAG, "StreetModuleEntry not resolved; compat-mode street skipped")
            return
        }
        val support = CameraResolver.resolveMethod(
            scope = TAG, key = "street_module_entry_support", clazz = clazz,
            names = listOf("support"),
            shape = { it.parameterTypes.isEmpty() && it.returnType == java.lang.Boolean.TYPE },
        ) ?: run {
            DebugLog.w(TAG, "${clazz.name}#support() not found; compat-mode street skipped")
            return
        }
        deoptimize(support)
        support.hook("cam_street_compat_support") {
            after { param ->
                if (streetMode() != CameraStreetMode.MODE_COMPAT) return@after
                param.result = true
                logStreetApplyOnce("compat(support)")
            }
        }
        DebugLog.i(TAG, "street compat support hooked on ${clazz.name}#support() (mode=compat)")
    }

    /** Plaintext dex name of the street module entry (stable across 460/510). */
    private const val STREET_ENTRY_CLASS =
        "com.android.camera.features.mode.street.StreetModuleEntry"

    /** Last logged street-apply state, so each hook logs ONE line per flip, not per call. */
    private val streetApplyLogged = AtomicReference<String?>(null)

    private fun logStreetApplyOnce(hook: String) {
        if (streetApplyLogged.getAndUpdate { hook } != hook) {
            DebugLog.i(TAG, "street unlock applied via $hook (mode=${streetMode()})")
        }
    }

    /** Plaintext launch-source extra (stable across verified builds). */
    private const val CAMERA_LAUNCH_SOURCE_EXTRA = "com.android.systemui.camera_launch_source"

    /** The 设置→锁屏→其他→急速相机「打开相机并拍照」 launch source value. */
    private const val CAMERA_LAUNCH_SOURCE_TAKE_PHOTO = "launch_camera_and_take_photo"

    /**
     * 快捷抢拍走街拍 (`Preferences.KEY_CAMERA_STREET_QUICK_LAUNCH`, default OFF): makes the
     * lock-screen fast-camera quick-capture route classify as 街拍 even when the street-support
     * gate `a3()` is native-false. The route: 设置→锁屏→其他→急速相机「打开相机并拍照」
     * (`Settings.System.volumekey_launch_camera` = 2) → system_server double-tap volume-down →
     * `STILL_IMAGE_CAMERA` intent with `camera_launch_source=launch_camera_and_take_photo`. The
     * camera's intent classifier (`CameraIntentManager.e()`; real dex `vr.l`/`vr.m`, jadx
     * `p757vr.C4755l`/`C4751m`) returns
     * `(a3() && launchSource == launch_camera_and_take_photo) ? "STREET" : "CAPTURE"`, which is
     * exactly the "quick-launch photo route re-classifies" switch behind `W/S.d()`'s module
     * mapping (STREET → 225) — so 新街拍 (a3 forced true) already classifies street there, while
     * 兼容模式街拍 (a3 stays native) keeps CAPTURE. This hook closes that gap independent of
     * `a3()`: an after-hook on `e()` forces "STREET" for the take-photo launch source. It only
     * RAISES (CAPTURE → STREET, never lowers a native STREET) and only fires while the switch is
     * on AND a street mode is active (the street module must be registered for 225 to open —
     * with street OFF nothing is forced, so no broken route is requested). Requires a camera app
     * restart for the hook to install; afterwards toggling is live.
     */
    private fun hookStreetQuickLaunch() {
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
        // Independent half: the guide gate must install even if the classifier resolution below
        // fails — a partial completion is still useful (module opens with take-photo semantics).
        hookStreetQuickLaunchGuideGate(ctx)
        val clazz = CameraResolver.resolveClass(
            scope = TAG, key = "camera_intent_manager", ctx = ctx,
            // Real dex names, newest build first; jadx renames these to p757vr.C4751m/C4755l
            // only for display.
            candidates = emptyList(),
            // The launch-source extra constant is plaintext in the dex; combined with the
            // shape check this pins vr.m/vr.l version-generically.
            probe = { bridge ->
                bridge.findClass { matcher { usingStrings(CAMERA_LAUNCH_SOURCE_EXTRA) } }
                    .firstOrNull { cd ->
                        ctx.loadOrNull(cd.name)?.let(::isCameraIntentManager) == true
                    }?.name
            },
            validate = ::isCameraIntentManager,
        ) ?: run {
            DebugLog.w(TAG, "CameraIntentManager not resolved; street quick-launch skipped")
            return
        }
        val classify = CameraResolver.resolveMethod(
            scope = TAG, key = "camera_intent_manager_e", clazz = clazz,
            names = listOf("e"),
            shape = { it.parameterTypes.isEmpty() && it.returnType == String::class.java },
        ) ?: run {
            DebugLog.w(TAG, "${clazz.name}#e() not found; street quick-launch skipped")
            return
        }
        deoptimize(classify)
        classify.hook("cam_street_quick_launch_e") {
            after { param ->
                if (!streetQuickLaunchActive()) return@after
                if (param.result != "CAPTURE") return@after
                val intent = runCatching {
                    readCameraIntentField(param.thisObject)
                }.getOrNull() ?: return@after
                if (intent.getStringExtra(CAMERA_LAUNCH_SOURCE_EXTRA) != CAMERA_LAUNCH_SOURCE_TAKE_PHOTO) {
                    return@after
                }
                param.result = "STREET"
                logStreetApplyOnce("quicklaunch(e)")
            }
        }
        DebugLog.i(TAG, "street quick-launch classification hooked on ${clazz.name}#e()")
    }

    /**
     * The guide-state half of the quick-launch street gate. `StreetModule.setParameter` only
     * CONSUMES the launch source when `Q5.J#f()` is true (`mLunchSource = J.f() ? f62426w :
     * null`), and the `W.g()` inline module decision plus the launch-source clearing
     * (`W.g():1871`, `v() && !z37` → drop the take-photo source) gate on `z37 = a3() && J.f()`.
     * On myron `J.f()` = (`pref_camera_global_guide_shown_key` == 2), i.e. false until the
     * camera's global guide is fully seen — so even 新街拍 (a3 forced) would open the module
     * WITHOUT the take-photo semantics. Raising `J.f()` — only while the quick-launch switch is
     * on AND a street mode is active — restores consumption. RAISE-ONLY and live-read; the
     * side effect (camera treats the global guide as shown) applies only while the switch is on.
     */
    private fun hookStreetQuickLaunchGuideGate(ctx: CameraResolver.Ctx) {
        val clazz = CameraResolver.resolveClass(
            scope = TAG, key = "camera_guide_manager", ctx = ctx,
            candidates = emptyList(),
            probe = { bridge ->
                bridge.findClass {
                    matcher { usingStrings("pref_camera_global_guide_shown_key") }
                }.firstOrNull { descriptor ->
                    descriptor.methods.any {
                        it.name == "f" && it.paramCount == 0 && it.returnTypeName == "boolean" &&
                            java.lang.reflect.Modifier.isStatic(it.modifiers)
                    }
                }?.name
            },
            validate = { c ->
                c.declaredMethods.any {
                    it.name == "f" && java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                        it.parameterCount == 0 && it.returnType == java.lang.Boolean.TYPE && !it.isSynthetic
                }
            },
        ) ?: run {
            DebugLog.w(TAG, "Q5.J not resolved; street quick-launch guide gate skipped")
            return
        }
        val guideShown = CameraResolver.resolveMethod(
            scope = TAG, key = "camera_guide_manager_f", clazz = clazz,
            names = listOf("f"),
            shape = { it.parameterTypes.isEmpty() && it.returnType == java.lang.Boolean.TYPE },
        ) ?: run {
            DebugLog.w(TAG, "Q5.J#f() not found; street quick-launch guide gate skipped")
            return
        }
        deoptimize(guideShown)
        guideShown.hook("cam_street_quick_launch_guide") {
            after { param ->
                if (!streetQuickLaunchActive()) return@after
                param.result = true
                logStreetApplyOnce("quicklaunch(J.f)")
            }
        }
        DebugLog.i(TAG, "street quick-launch guide gate hooked on ${clazz.name}#f()")
    }

    /** Shape contract of the CameraIntentManager (`vr.l`/`vr.m`): the classifier trio. */
    private fun isCameraIntentManager(clazz: Class<*>): Boolean =
        clazz.declaredMethods.any {
            it.name == "e" && it.parameterCount == 0 && it.returnType == String::class.java && !it.isSynthetic
        } && clazz.declaredMethods.any {
            it.name == "v" && java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                it.parameterCount == 1 && it.parameterTypes[0] == android.content.Intent::class.java &&
                it.returnType == java.lang.Boolean.TYPE && !it.isSynthetic
        } && clazz.declaredMethods.any {
            it.name == "f" && java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                it.parameterCount == 1 && it.parameterTypes[0] == android.content.Intent::class.java &&
                it.returnType == String::class.java && !it.isSynthetic
        } && clazz.declaredFields.any { it.type == android.content.Intent::class.java }

    /** The manager instance's held intent (real dex field `a`, jadx f64809a/f64792a). */
    private fun readCameraIntentField(receiver: Any?): android.content.Intent? {
        if (receiver == null) return null
        val field = resolveField(receiver.javaClass, "a", "f64809a", "f64792a")
            ?.takeIf { it.type == android.content.Intent::class.java }
            ?.apply { isAccessible = true }
            ?: return null
        return runCatching { field.get(receiver) as? android.content.Intent }.getOrNull()
    }

    /** True while the quick-launch street completion is live (switch on + street mode active). */
    private fun streetQuickLaunchActive(): Boolean =
        Preferences.cameraStreetQuickLaunch() && streetMode() != CameraStreetMode.MODE_OFF

    /**
     * Restore the Leica photography style (摄影风格 cv_type 徕卡经典 ↔ 徕卡生动) switcher. The 摄影风格
     * component (`C4164m` F3-gate at :148) and every top-bar / mode style entry gate on the
     * config's `F3()` (`X2()` additionally for the specific-capture path `capture/h0`) — `true`
     * on the CommonFlagship (Nezha) branch, `false` on the REDMI C1199 branch that myron's own
     * C1209 inherits (agent + jadx verified: `C1136#F3()/X2()=true`,
     * `C1199#F3()=instanceof C1156=false`, `C1143#X2()=false`), so the stock REDMI config
     * drops the switcher.
     *
     * `F3()` can also be opened independently by `KEY_CAMERA_ALL_SHUTTER_SOUNDS`; `X2()` stays
     * gated by `KEY_CAMERA_LEICA_STYLE`. The hooks install on the active
     * config class ([configDispatchClasses]) — myron C1209 inherits the same
     * C1199#F3 / C1143#X2 getters, so forcing them `true` brings the switcher back. The
     * callback is RAISE-ONLY — it never lowers a native value: with the user switch ON it
     * forces `true`; with the switch OFF it returns without touching the result, so a
     * natively-true gate stays native (the CommonFlagship/nezha branch declares its own true
     * `F3()/X2()` overrides and never reaches this hook's Methods). Leica Moment opens the
     * existing mode entry on the native config; Legendary Moment selects Madrid only when that
     * profile exists in the camera package. The 231 LCC-RAW stream is not touched,
     * so no purple/RAW regression. Side effect:
     * `f2.c.b()` adds the four Leica shutter sounds when `F3()` is true — benign (the 8-entry
     * list also removes the IOOBE that the legacy `key_shutter_sound=4` used to hit) and
     * bounded by the resident [hookShutterSoundBoundary] clamp.
     */
    private fun hookLeicaStyle() {
        val profile = hostProfile ?: return
        profile.leicaStyleGate?.let { gateName ->
            val method = CameraResolver.resolveMethod(
                scope = TAG, key = "leica_style_facade", clazz = profile.facade,
                names = listOf(gateName),
                shape = {
                    !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                        it.returnType == java.lang.Boolean.TYPE
                },
            ) ?: return
            deoptimize(method)
            method.hook("cam_leica_style_facade") {
                after { param ->
                    if (leicaStyle()) param.result = true
                }
            }
            DebugLog.i(TAG, "leica-style gate hooked on ${method.declaringClass.name}#${method.name}()")
            return
        }
        val dispatchClasses = configDispatchClasses()
        // Resolve F3/X2 from the active dispatch classes, deduplicated
        // by Method identity: `getMethod` on base-derived classes can return the same Method
        // (e.g. C1199#F3 inherited unchanged), which must be hooked exactly once.
        val seen = IdentityHashMap<Method, Boolean>()
        val resolved = ArrayList<Pair<Method, String>>()
        for (clazz in dispatchClasses) {
            for (name in arrayOf("F3", "X2")) {
                val method = runCatching {
                    clazz.getMethod(name).takeIf {
                        it.parameterCount == 0 && it.returnType == java.lang.Boolean.TYPE
                    }
                }.getOrNull() ?: run {
                    DebugLog.d(TAG, "leica-style getter ${clazz.name}#$name() not found; skipped")
                    continue
                }
                if (seen.put(method, true) != null) continue
                resolved += method to name
            }
        }
        var hooked = 0
        for ((method, name) in resolved) {
            deoptimize(method)
            method.hook("cam_leica_style_$name") {
                before { param ->
                    // The sound-list gate is independent; the capture-style gate remains tied
                    // to the Leica style switch.
                    val enabled = if (name == "F3") {
                        leicaStyle() || Preferences.getBoolean(
                            Preferences.KEY_CAMERA_ALL_SHUTTER_SOUNDS,
                            false,
                        )
                    } else {
                        leicaStyle()
                    }
                    // RAISE-ONLY: with the relevant user switch on force the gate open; with it off
                    // leave the native result untouched (never lower a native true). The
                    // `if (!enabled) return@before; param.result = true` shape always
                    // either returns or sets the result, so `proceed` is never re-entered
                    // (SOE-safe, same pattern as hookStreetEnable).
                    if (!enabled) return@before
                    param.result = true
                }
            }
            hooked++
        }
        DebugLog.d(
            TAG,
            "leica-style flags hooked on ${dispatchClasses.joinToString { it.name }}: " +
                "$hooked/${resolved.size} getters"
        )
    }

    /**
     * Leica sound labels are backed by packaged raw resources, while ordinary sounds load from
     * `assets/sounds/<name>/...`. The no-Madrid device config takes the asset branch, but this
     * camera APK has no `leica_*` asset folders, so selecting one is silent. Make only the
     * sound loader's style query use its existing raw-resource branch; keep the shared Leica
     * theme gate untouched for watermarks, tint colors, and other camera UI.
     */
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

    /**
     * The selector opens the shared mode entry on Leica's theme gate or uses the Madrid profile
     * for Legendary Moment. Leica selection itself keeps the active device config unchanged.
     */
    private fun hookLegendaryProfileGate() {
        val profile = hostProfile ?: return
        val method = CameraResolver.resolveMethod(
            scope = TAG,
            key = "legendary_profile_lcc_gate",
            clazz = profile.facade,
            names = listOf(profile.lccGate),
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

    /**
     * Leica Moment and Legendary Moment share the camera's one LegendaryEnter entry (mode id
     * 256). Leica only raises its LCC entry gate; Legendary raises it only when the Madrid
     * profile was actually installed. Otherwise keep native behavior.
     * The entry registry (`p666t3.a.d()`, support-filtered and cached per process) then registers
     * mode 256 into the 更多 overflow grid. Needs a camera restart after changing the profile.
     */
    private fun hookLegendarySupport() {
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
        val clazz = CameraResolver.resolveClass(
            scope = TAG, key = "legendary_enter", ctx = ctx,
            candidates = emptyList(),
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
            names = listOf("support"),
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

    /**
     * FeatureLoader registry defense for Leica/Legendary Moment, Pixel, and Cinematic entries.
     * The camera caches a support-filtered SparseArray and may build it before these support hooks
     * run. Resolve its current class by semantic strings and the stable SparseArray method shape,
     * then repair both cold-build and cached-return paths. Isolate reflection so a miss cannot
     * break camera startup.
     */
    private fun hookLegendaryRegistry() {
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
        val clazz = CameraResolver.resolveClass(
            scope = TAG,
            key = "feature_registry",
            ctx = ctx,
            // JADX prefixes collision packages with `p###`; the dex names are `t3.a` on
            // current builds, while the prefixed aliases are retained for older loaders.
            candidates = emptyList(),
            probe = { bridge ->
                bridge.findClass {
                    matcher { usingStrings("FeatureLoader", "Build In Entries is NOT ready.") }
                }.asSequence()
                    .mapNotNull { descriptor ->
                        ctx.loadOrNull(descriptor.name)?.takeIf { candidate ->
                            candidate.declaredMethods.any {
                                Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                                    SparseArray::class.java.isAssignableFrom(it.returnType) &&
                                    (it.name == "b" || it.name == "d")
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
                        it.parameterCount == 0 && SparseArray::class.java.isAssignableFrom(it.returnType) &&
                        (it.name == "b" || it.name == "d")
                } && c.declaredFields.any {
                    java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                        SparseArray::class.java.isAssignableFrom(it.type)
                }
            },
        ) ?: run {
            DebugLog.w(TAG, "FeatureLoader registry not resolved; legendary registry repair skipped")
            return
        }

        val methods = listOf("d", "b").mapNotNull { name ->
            CameraResolver.resolveMethod(
                scope = TAG,
                key = "feature_registry_$name",
                clazz = clazz,
                names = listOf(name),
                shape = {
                    java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                        it.parameterCount == 0 && SparseArray::class.java.isAssignableFrom(it.returnType)
                },
            )
        }.distinct()
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
                    if (!unlockLegendary && !unlockExtras) return@after
                    @Suppress("UNCHECKED_CAST")
                    val registry = param.result as? SparseArray<Any?> ?: return@after
                    ensureCameraModeRegistry(registry, unlockLegendary, unlockExtras)
                }
            }
        }
        DebugLog.d(TAG, "camera mode registry repair hooked on ${clazz.name}#${methods.joinToString { it.name }}()")
    }

    /** Restore optional entries if the camera cached its support-filtered registry too early. */
    private fun ensureCameraModeRegistry(
        registry: SparseArray<Any?>,
        unlockLegendary: Boolean,
        unlockExtras: Boolean,
    ) {
        val context = runCatching {
            classLoader.loadClass("com.xiaomi.camera.basic.Global").getMethod("getContext").invoke(null)
        }.getOrNull() ?: return
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
        if (entryClass == null) return
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

    /**
     * 智能构图 setting unlock ([Preferences.KEY_CAMERA_SMART_COMPOSITION], default OFF).
     * The 设置→拍照 entry `pref_camera_crop_preferred_key` is added only while the device
     * config's `D3()` reports true — declared once on the config base as
     * `return this instanceof <REDMI-flagship marker>` and overridden per branch, so devices
     * outside that branch (this one: `com.mi.device.Myron`) ship it false natively. The hook
     * raises `D3()` on the UNION of dispatch classes ([configDispatchClasses] plus the config
     * BASE class taken from the factory's static cache field type), deduplicated by Method
     * identity — the same pattern as [hookLeicaStyle]. RAISE-ONLY and live-read: with the
     * switch off the native value is untouched; turning it on shows the entry (reopen the
     * settings page) and consistently enables the capture-time consumers of the same gate.
     */
    private fun hookSmartComposition() {
        if (hostProfile?.family != CameraHostProfile.Family.CAMERA_66) return
        val classes = LinkedHashSet<Class<*>>()
        classes.addAll(configDispatchClasses())
        // Always include the config BASE class: the factory's static cache field is typed to
        // it (the same invariant the config factory's structural fallback relies on), and
        // `getMethod` from it resolves the base declaration every non-overriding subclass
        // dispatches to — even when neither instance could be built.
        hostProfile?.configType?.let(classes::add)
        if (classes.isEmpty()) {
            DebugLog.w(TAG, "no dispatch class resolved; smart-composition unlock skipped")
            return
        }
        val seen = IdentityHashMap<Method, Boolean>()
        var hooked = 0
        for (clazz in classes) {
            val method = runCatching {
                clazz.getMethod("D3").takeIf {
                    it.parameterCount == 0 && it.returnType == java.lang.Boolean.TYPE
                }
            }.getOrNull() ?: run {
                DebugLog.d(TAG, "smart-composition getter ${clazz.name}#D3() not found; skipped")
                continue
            }
            if (seen.put(method, true) != null) continue
            deoptimize(method)
            method.hook("cam_smart_composition_d3") {
                before { param ->
                    if (!smartCompositionUnlock()) return@before
                    param.result = true
                }
            }
            hooked++
        }
        DebugLog.i(TAG, "smart-composition hooked on $hooked D3() dispatch method(s): " +
            seen.keys.joinToString { "${it.declaringClass.name}#${it.name}" })
    }

    /** 智能构图 settings-preference key and its string resource names (obfuscated-but-stable). */
    private const val SMART_COMPOSITION_PREF_KEY = "pref_camera_crop_preferred_key"
    private val smartCompositionResources: Pair<String, String>
        get() = if (hostProfile?.family == CameraHostProfile.Family.CAMERA_68) {
            "h_i" to "hut"
        } else {
            "h24" to "h23"
        }

    /**
     * 智能构图 top-level row injection: the camera's own photo-settings page folds the whole
     * recommendation-toggle list (`p148e5.a.a()`, which the D3 hook feeds) into the
     * 「AI智能推荐」sub-page whenever its size is > 1, which is ALWAYS on this device (扫码
     * unconditional + 横竖屏引导 natively true). The other three unlocks (超高画质/内容凭证/
     * 自适应镜头) render as top-level rows, so 智能构图 was easy to miss. This hook makes the
     * checkbox appear directly in 拍照设置 too: an after-hook on
     * `addPhotoPreferences()` reuses the fragment's OWN `addCheckBoxPreference` helper
     * (title/summary from the host resources), so the row is byte-for-byte the same kind of
     * `AccessibleCheckBoxPreference` the sub-page builds — persistence flows through the
     * generic registerListener wiring (`Preference` -> fragment `onPreferenceChange` ->
     * `updateSharePreference`) exactly like every native checkbox, and reopening the page
     * re-syncs the checked state. Live-read: with the switch off the injection stops on the
     * next page build; the row and the sub-page row share the one pref key.
     */
    private fun hookSmartCompositionTopRow() {
        val clazz = CameraFeatureResolver.captureSettings(
            CameraResolver.Ctx(classLoader, hookParam.appInfo), TAG,
        ) ?: run {
            DebugLog.w(TAG, "capture settings fragment not resolved; top-row smart composition skipped")
            return
        }
        val add = CameraResolver.resolveMethod(
            scope = TAG, key = "settings_add_photo_prefs", clazz = clazz,
            names = listOf("addPhotoPreferences"),
            shape = { it.parameterCount == 0 },
        ) ?: run {
            DebugLog.w(TAG, "${clazz.name}#addPhotoPreferences() not found; top-row smart composition skipped")
            return
        }
        deoptimize(add)
        add.hook("cam_smart_composition_top_row") {
            after { param ->
                if (!smartCompositionUnlock()) return@after
                injectSmartCompositionTopRow(param.thisObject)
            }
        }
        DebugLog.i(TAG, "smart-composition top-row hook installed on ${clazz.name}#addPhotoPreferences()")
    }

    /** Add the 智能构图 checkbox into 拍照设置's `category_photo_setting` group (idempotent). */
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
                (it.name == "findPreference" || it.name == "k0") && it.parameterCount == 1
            } ?: return
            val category = find.invoke(screen, "category_photo_setting") ?: return
            if (find.invoke(screen, SMART_COMPOSITION_PREF_KEY) != null) return
            val res = fragClass.getMethod("getResources").invoke(fragment) as android.content.res.Resources
            val (titleName, summaryName) = smartCompositionResources
            val titleRes = res.getIdentifier(titleName, "string", PACKAGE)
            val summaryRes = res.getIdentifier(summaryName, "string", PACKAGE)
            if (titleRes == 0 || summaryRes == 0) {
                DebugLog.w(TAG, "smart-composition string resources unresolved " +
                    "($titleName=$titleRes, $summaryName=$summaryRes); top row skipped")
                return
            }
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

    /** Plaintext system property feeding the camera's 内容凭证 (C2PA / CAI) master flag. */
    private const val CAI_SUPPORT_PROPERTY = "ro.product.odm.support_cai"

    /**
     * Field-name candidates of the static boolean flag inside the property-holder class:
     * In 6.6.000570.5 the property-backed field is `x` (JADX alias `f11708x`); older 510
     * builds used `u` (`f13393u`). Do not include nearby boolean fields such as `s`: they
     * belong to unrelated camera/debug properties and can make the log claim a false unlock.
     */
    private val CAI_FLAG_FIELD_CANDIDATES = listOf("x", "f11708x", "u", "f13393u")

    private fun caiFlagField(clazz: Class<*>): Field? =
        CAI_FLAG_FIELD_CANDIDATES.firstNotNullOfOrNull { name ->
            runCatching { clazz.getDeclaredField(name) }.getOrNull()?.takeIf {
                java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    it.type == java.lang.Boolean.TYPE
            }
        }

    /**
     * 内容凭证 (Content Credentials, C2PA) setting unlock
     * ([Preferences.KEY_CAMERA_CONTENT_CREDENTIAL], default OFF). The 设置→水印 entry
     * `pref_cai_type_key` (→ `CaiSettingFragment`) is gated on a `static final boolean` in
     * the camera's debug-flag holder, initialised once in `<clinit>` from the system
     * property `ro.product.odm.support_cai`. The holder resolves through the plaintext
     * property constant (unique to this class across verified builds), the field through
     * [CAI_FLAG_FIELD_CANDIDATES]; the write goes through `StaticFieldWriter`
     * (reflective write first, Unsafe fallback — same path as `Je.e.b`). Because the value
     * is baked at class-init, enabling/disabling needs a camera restart; when the switch is
     * off at attach NOTHING is written, so the stock process stays untouched.
     */
    private fun hookContentCredential() {
        if (!contentCredentialUnlock()) return
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
        val clazz = CameraResolver.resolveClass(
            scope = TAG, key = "cai_flag_holder", ctx = ctx,
            candidates = emptyList(),
            validate = { caiFlagField(it) != null },
            probe = { bridge ->
                bridge.findClass { matcher { usingStrings(CAI_SUPPORT_PROPERTY) } }
                    .firstOrNull { cd ->
                        ctx.loadOrNull(cd.name)?.let(::caiFlagField) != null
                    }?.name
            },
        ) ?: run {
            DebugLog.w(TAG, "CAI flag holder not resolved; content-credential unlock skipped")
            return
        }
        val field = caiFlagField(clazz) ?: return
        runCatching {
            // Reading first forces <clinit> so the property-derived value exists, then the
            // write replaces it for the process lifetime.
            val original = field.getBoolean(null)
            StaticFieldWriter.setBoolean(field, true)
            DebugLog.i(TAG, "content-credential flag ${clazz.name}#${field.name}: $original -> true")
        }.onFailure { t ->
            DebugLog.w(TAG, "content-credential flag write failed on ${clazz.name}", t)
        }
    }

    /**
     * Log string unique to the camera's capabilities-util helper class on verified builds
     * (jadx C3545f): the anchor lives inside one of its ~200 capability getters, so a DexKit
     * class probe keyed on it pins the obfuscated class name version-generically.
     */
    private const val CAPABILITIES_UTIL_ANCHOR =
        "getSupportedHfrSettings: CameraCapabilities is null!!!"

    /**
     * Resolve ONE static boolean single-arg method named [name] on [clazz]. The class carries
     * hundreds of same-shape capability getters, so the exact name must match EXACTLY ONE
     * declaration — multiple matches mean the name was repurposed on this build and the
     * caller must skip instead of guessing.
     */
    private fun uniqueCapabilityMethod(clazz: Class<*>, name: String): Method? {
        val matches = clazz.declaredMethods.filter {
            it.name == name && java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                it.parameterCount == 1 && it.returnType == java.lang.Boolean.TYPE &&
                !it.isSynthetic
        }
        return matches.singleOrNull()
    }

    /** Newer camera builds renamed the adaptive-lens pair from g5/i5 to h5/j5. */
    private val ADAPTIVE_LENS_GATE_PAIRS = listOf(
        // 6.8.001960.0's capture settings call these near-range and tele support gates.
        "p5" to "r5",
        "o5" to "q5",
        "h5" to "j5",
        "g5" to "i5",
    )

    /** Shape contract of the adaptive-lens gate pair; newest names are preferred. */
    private fun adaptiveLensGatePair(clazz: Class<*>): Pair<Method, Method>? =
        CameraResolver.findUniqueStaticBooleanPair(clazz, ADAPTIVE_LENS_GATE_PAIRS)

    private fun isAdaptiveLensUtil(clazz: Class<*>): Boolean = adaptiveLensGatePair(clazz) != null

    /**
     * Resolve the camera's capabilities-util helper class (jadx C3545f, real dex name e.g.
     * `j9.f`) through the DexKit anchor string unique to it. [key] distinguishes the
     * DexKit cache entries (same class, different validation per feature); [validate] is
     * applied both inside the probe and again by [CameraResolver] so a wrong anchor hit is
     * rejected at every layer.
     */
    private fun resolveCapabilitiesUtil(
        ctx: CameraResolver.Ctx,
        key: String,
        validate: (Class<*>) -> Boolean,
    ): Class<*>? = CameraResolver.resolveClass(
        scope = TAG, key = key, ctx = ctx,
        candidates = emptyList(),
        validate = validate,
        probe = { bridge ->
            bridge.findClass { matcher { usingStrings(CAPABILITIES_UTIL_ANCHOR) } }
                .firstOrNull { cd -> ctx.loadOrNull(cd.name)?.let(validate) == true }
                ?.name
        },
    )

    /**
     * 自适应镜头 (adaptive lens / auto fallback) setting unlock
     * ([Preferences.KEY_CAMERA_ADAPTIVE_LENS], default OFF, experimental). The 设置→拍照
     * entry `pref_camera_auto_fallback` shows only while BOTH capabilities-util gates report
     * true: the near-range smooth-transition gate (HAL characteristics
     * `xiaomi.smoothTransition.nearRangeMode` plus the `disablefallback`/`fallbackRole` keys
     * available) and the tele-fallback gate (`com.xiaomi.teleFallback.isSupported`). The
     * sub-page (`AutoFallbackFragment`) and the module-level consumers read the same two
     * static getters, so forcing them keeps everything consistent. RAISE-ONLY and live-read;
     * reopen the settings page to refresh. Both methods must resolve uniquely or the whole
     * feature skips (fail-safe rather than half-open).
     */
    private fun hookAdaptiveLens() {
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
        val clazz = resolveCapabilitiesUtil(ctx, "capabilities_util", ::isAdaptiveLensUtil) ?: run {
            DebugLog.w(TAG, "capabilities util not resolved; adaptive-lens unlock skipped")
            return
        }
        val pair = adaptiveLensGatePair(clazz) ?: run {
            DebugLog.w(TAG, "adaptive-lens gate pair not resolvable on ${clazz.name}; skipped")
            return
        }
        var hooked = 0
        for (method in listOf(pair.first, pair.second)) {
            val name = method.name
            deoptimize(method)
            method.hook("cam_adaptive_lens_$name") {
                after { param ->
                    runCatching {
                        if (adaptiveLensUnlock()) param.result = true
                    }.onFailure { t ->
                        DebugLog.w(TAG, "adaptive-lens callback failed on $name", t)
                    }
                }
            }
            hooked++
        }
        DebugLog.i(
            TAG,
            "adaptive-lens hooked on $hooked gate(s) of ${clazz.name}: " +
                "${pair.first.name}/${pair.second.name}",
        )
    }

    /**
     * 智能构图 viewfinder feature-bar entry (功能条 id 2853) force-open
     * ([Preferences.KEY_CAMERA_SMART_COMPOSITION]). The entry is built only while
     * `C3545f.M3()` = HAL characteristics `com.xiaomi.camera.autoCrop.autoCropVersion == 2`
     * (jadx C3545f.java:1178), which this device's HAL does not publish — the icon is
     * otherwise skipped at list construction (`C2418c`). RAISE-ONLY and live-read.
     *
     * IMPORTANT on-device reality (verified 2026-08-29, myron OS4.0.0.19): the whole
     * autoCrop implementation lives in the camera HAL/ISP and the v2 app side is only a
     * renderer of `autoCropData` (float[6]). myron's /odm HAL binaries contain NO autoCrop
     * strings — not even the characteristics key — so forcing M3 produces an EMPTY SWITCH:
     * the feature-bar icon appears and is clickable, the click shows the camera's "not
     * supported" hint (`X#I6` Q0(autoCropEnable) check), and capture safely skips the
     * request/result wiring. No composition guidance can ever appear; the setting row
     * (D3) stays useful for the mode-175 capture metadata path.
     */
    private fun hookSmartCompositionFeatureBar() {
        if (hostProfile?.family != CameraHostProfile.Family.CAMERA_66) return
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
        val clazz = resolveCapabilitiesUtil(ctx, "capabilities_util_m3") {
            uniqueCapabilityMethod(it, "M3") != null
        } ?: run {
            DebugLog.w(TAG, "capabilities util M3 not resolved; smart-composition feature-bar skipped")
            return
        }
        val m3 = uniqueCapabilityMethod(clazz, "M3") ?: return
        deoptimize(m3)
        m3.hook("cam_smart_composition_m3") {
            before { param ->
                if (!smartCompositionUnlock()) return@before
                param.result = true
            }
        }
        DebugLog.i(TAG, "smart-composition feature-bar gate M3 hooked on ${clazz.name}#M3()")
    }

    /**
     * Front-load 实况运镜 (MasterLive, mode id 231) into the active config's mode
     * ordering array `M()[I` under the k100promax target.
     *
     * REGRESSION (2026-08-21, "没有实况运镜了"): the per-device config's `M()` array is the
     * sole ordering input of `u2.P` (ComponentModuleList), which splits the mode strip at the
     * 254 (更多) marker — modes absent from the array land in the overflow, not the carousel.
     * The Nezha config fronts `{231,…}` (carousel first); the K100 Pro Max config omits 231,
     * so switching targets moved the mode into the overflow even though `y4()` (the registry
     * gate, `MasterLiveModuleEntry.support()`) stays true on both.
     *
     * SECOND REGRESSION (2026-08-24, "实况运镜依旧失效"): the original hook ran in the
     * `before` stage and read `param.result` to decide whether fronting was needed. In
     * ezhooktool a `before` callback runs BEFORE the original method (`BeforeChainStage.
     * intercept` invokes the callback first and only calls `proceed()` when the result was
     * not replaced), so `param.result` was always null there,
     * `CameraIdentity.frontMasterLiveMode(null)` always returned null, and the hook NEVER
     * fronted anything. It is now an `after` hook reading the real return value.
     *
     * Even fixed, this hook alone cannot restore visibility: `M()` is read only by
     * `u2.P.t(Q)`, which loses to the in-memory sort cache `f62389h` (rewritten by `K()`
     * after every render) and the persisted `pref_camera_sort_modes_key` order — see
     * [hookMasterLiveOrderFunnel], which corrects whichever source wins. This hook stays as
     * defense-in-depth so the config itself reports a Nezha-shaped array (also keeps the
     * `t(Q)`/`o()` empty-`M()` fallback lists out of play). Gated on
     * `KEY_CAMERA_MASTERLIVE_ENABLE` only. Inert on a native nezha device and when the array
     * already contains 231.
     *
     * In the native myron profile (when no Legendary profile is selected), config C1209
     * inherits the base C1143#M() ({167,175,232,233,234,254} — no 231), so the running camera
     * dispatches to that base Method and it must be fronted. The hook is installed on
     * [configDispatchClasses] (the active config's `M()`, deduplicated by Method identity so the
     * same Method is never hooked twice). Base-class hooks are safe: only classes that do NOT
     * override `M()` dispatch to the base Method, so a target profile's own override is untouched.
     */
    private fun hookMasterLiveModePlacement() {
        val profile = hostProfile ?: return
        // CameraIdentity.frontMasterLiveMode is a no-op for arrays that already contain 231,
        // so this is inert on configs with native 231 — it only fronts the base C1143 `M()`
        // the active config inherits (no 231).
        val methods = LinkedHashSet<Method>()
        for (target in configDispatchClasses()) {
            profile.configMethod(target, profile.modeOrder, IntArray::class.java)
                ?.let(methods::add)
        }
        if (methods.isEmpty()) {
            DebugLog.w(TAG, "config ${profile.modeOrder}()[I not found; masterlive placement skipped")
            return
        }
        for (method in methods) {
            deoptimize(method)
            method.hook("cam_masterlive_mode_front_${method.declaringClass.name}") {
                after { param ->
                    if (!masterliveEnabled() || !masterLiveHasEffectTable()) return@after
                    val fronted = CameraIdentity.frontMasterLiveMode(param.result as? IntArray)
                    if (fronted != null) param.result = fronted
                }
            }
            DebugLog.d(TAG, "masterlive placement hooked on ${method.declaringClass.name}#${method.name}()")
        }
    }

    /**
     * MasterLive (实况运镜) REGISTRY gate: force the config's `y4()` true while
     * [Preferences.KEY_CAMERA_MASTERLIVE_ENABLE] is on.
     *
     * WHY THIS EXISTS (2026-08-22, "没开 k100 配置时实况运镜依旧用不了"): the native myron config
     * (when no Legendary profile is selected) INHERITS the base C1143#y4 (`instanceof C1214` → false), so
     * `MasterLiveModuleEntry.support()` stays false and mode 231 never registers, no matter
     * what the ordering hooks do. This hook actively pins the gate true.
     *
     * The hook installs the RAISE-ONLY callback on the active config's `y4()` Method
     * ([configDispatchClasses]). Base-class hooks are safe: ONLY classes that do NOT override
     * `y4()` dispatch to the base Method, so a target profile's own override is never touched.
     * The gate is `masterliveEnabled()` ONLY: when the switch is off nothing is raised (the
     * native false on myron is left untouched; a genuinely-capable device's native true is
     * never lowered). `y4()` has seven consumers on 510 (module entry, first-run guide,
     * capture-method settings rows, special-mode description list) and all of them describe a
     * flagship capability the user wants to unlock, so one forced gate keeps them all coherent.
     */
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
            DebugLog.i(TAG, "masterlive support gate hooked on ${method.declaringClass.name}#${method.name}()")
        }
    }

    /** Logs the y4 force ONCE per process, not per dispatch. */
    private val mlGateLogged = AtomicReference(false)

    /**
     * MasterLive (实况运镜) EFFECT TABLE: borrow the REDMI (K100 Pro Max) table for the real
     * config's `q0()` AND inject the 红毯运镜 (type "1") entry
     * ([CameraMasterLiveRedCarpet], `Preferences.KEY_CAMERA_MASTERLIVE_RED_CARPET`).
     *
     * `C4682e0`/`C4673d0` (the MasterLive effect-table consumer, jadx `v2.e0`/`v2.d0`) caches
     * `Collections.unmodifiableMap(config.q0())` once per process (`q()`, :86/:310): the real
     * device config INHERITS the base `q0()` which returns **null** — no effect list — so
     * MasterLive has no usable effect list natively.
     *
     * The hook installs on the `q0()` Method of every active dispatch class ([configDispatchClasses],
     * deduplicated by Method identity), and the after-callback (a) borrows the K100 table when
     * the original returned null (through [resolveK100Config] ONLY — never the
     * Nezha/CommonFlagship fallback, whose 12.9x tele table crashes myron), then (b) merges a
     * synthesized 红毯运镜 type-"1" entry ([injectRedCarpetEntry]) while
     * `KEY_CAMERA_MASTERLIVE_RED_CARPET` is on. The 红毯 entry is a CLONE of the proven-working
     * linear entry with a changed type id and cleared default flag — every decrypted
     * role/range string stays byte-identical to what already resolves, so nothing new is
     * decoded and the panel/guide UI pick the entry up natively (they are type-switch driven).
     * Gated on [Preferences.KEY_CAMERA_MASTERLIVE_ENABLE].
     */
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
                    if (table == null) {
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
            DebugLog.i(TAG, "masterlive effect table hook installed on $hooked q0() Method(s)")
        }
    }

    /**
     * The type-1 red-carpet path is a real slow-motion path in newer camera APKs. On myron's
     * qcom HAL the required HSR capability is absent, so that path never delivers the first
     * frame/video callback and leaves the shutter spinner active forever. Keep the type-1
     * table entry (the UI remains available), but make the capture state machine treat it as a
     * normal movement effect. This also makes U3/p#i() select the safe 36866 session through
     * its existing `!O0(231)` branch. The device/HAL guard is deliberately narrow so a 17U
     * with genuine slow-motion support keeps the native behavior.
     */
    private fun hookMasterLiveSlowMotionFallback() {
        if (!CameraMasterLiveRedCarpet.needsSlowMotionFallback(Build.DEVICE, Build.HARDWARE)) {
            return
        }
        val clazz = CameraFeatureResolver.qualitySettings(
            CameraResolver.Ctx(classLoader, hookParam.appInfo), TAG,
        ) ?: run {
                DebugLog.w(TAG, "camera data component class not resolved; red-carpet fallback skipped")
                return
            }
        val method = runCatching {
            clazz.getDeclaredMethod("O0", Integer.TYPE).takeIf {
                java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    it.returnType == java.lang.Boolean.TYPE
            }
        }.getOrNull() ?: run {
            DebugLog.w(TAG, "data component O0(I)Z not resolved; red-carpet fallback skipped")
            return
        }
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
        DebugLog.i(TAG, "red-carpet slow-motion fallback hooked on ${clazz.name}#O0(I)")
    }

    private val mlRedCarpetFallbackLogged = AtomicReference(false)

    /** Logs the first 红毯运镜 injection ONCE per process, not per dispatch. */
    private val mlRedCarpetLogged = AtomicReference(false)

    /**
     * Real dex field names of the MasterLive effect-entry bean (`Le.a`; the jadx display
     * aliases `f9658a..f9664h` exist only because single letters collide with package names).
     */
    private val ENTRY_FIELD_TYPE = arrayOf("a", "f9658a", "f9655a")
    private val ENTRY_FIELD_ROLES = arrayOf("b")
    private val ENTRY_FIELD_ZOOMS = arrayOf("c", "f9659c", "f9656c")
    private val ENTRY_FIELD_FLAGS = arrayOf("d", "f9660d", "f9657d")
    private val ENTRY_FIELD_RANGES = arrayOf("e", "f9661e", "f9658e")
    private val ENTRY_FIELD_HANDLES = arrayOf("f", "f9662f", "f9659f")
    private val ENTRY_FIELD_DEFAULT = arrayOf("g", "f9663g", "f9660g")
    private val ENTRY_FIELD_LENS = arrayOf("h", "f9664h", "f9661h")

    /**
     * Build a copy of the MasterLive effect [table] (`Map<String, Le.a>`) with an injected
     * 红毯运镜 (`"1"`) entry, or null when nothing changes / the injection is not safe:
     * the table already contains `"1"`; no clone-source entry ("3"/"2") exists; the entry bean
     * fields cannot be resolved; or the cloned segment lists violate
     * [CameraMasterLiveRedCarpet.segmentsConsistent] (which would make the component throw
     * IOOBE mid-capture). The input map is NEVER mutated — consumers wrap it in
     * `unmodifiableMap`, and `C4673d0#r()` writes range strings back into the entries'
     * list fields, so every list is deep-copied per entry.
     */
    private fun injectRedCarpetEntry(table: Any): Any? {
        if (table !is Map<*, *>) return null
        if (table.containsKey(CameraMasterLiveRedCarpet.RED_CARPET_TYPE)) return null
        val source = CameraMasterLiveRedCarpet.CLONE_SOURCE_TYPES.firstNotNullOfOrNull { table[it] }
            ?: return null
        val entryClass = source.javaClass
        val typeField = resolveField(entryClass, *ENTRY_FIELD_TYPE)?.apply { isAccessible = true } ?: return null
        val rolesField = resolveField(entryClass, *ENTRY_FIELD_ROLES)?.apply { isAccessible = true } ?: return null
        val zoomsField = resolveField(entryClass, *ENTRY_FIELD_ZOOMS)?.apply { isAccessible = true } ?: return null
        val flagsField = resolveField(entryClass, *ENTRY_FIELD_FLAGS)?.apply { isAccessible = true }
        val rangesField = resolveField(entryClass, *ENTRY_FIELD_RANGES)?.apply { isAccessible = true } ?: return null
        val handlesField = resolveField(entryClass, *ENTRY_FIELD_HANDLES)?.apply { isAccessible = true }
        val defaultField = resolveField(entryClass, *ENTRY_FIELD_DEFAULT)?.apply { isAccessible = true } ?: return null
        val lensField = resolveField(entryClass, *ENTRY_FIELD_LENS)?.apply { isAccessible = true }

        val read: (Field) -> Any? = { field -> runCatching { field.get(source) }.getOrNull() }
        val roles = read(rolesField) as? List<*>
        val zooms = read(zoomsField) as? List<*>
        val ranges = read(rangesField) as? List<*>
        if (!CameraMasterLiveRedCarpet.segmentsConsistent(
                roles?.size, zooms?.size, ranges?.size
            )
        ) {
            DebugLog.w(TAG, "masterlive red carpet: clone-source segments inconsistent ($roles/$zooms/$ranges); skipped")
            return null
        }

        val entry = runCatching { entryClass.getDeclaredConstructor().newInstance() }.getOrNull() ?: return null
        // Deep-copy every list so C4673d0#r()'s range write-back can never leak across types.
        val set: (Field, Any?) -> Unit = { field, value ->
            runCatching { field.set(entry, value) }
        }
        fun copyList(field: Field): List<*>? = (read(field) as? List<*>)?.let { ArrayList(it) }
        set(typeField, CameraMasterLiveRedCarpet.RED_CARPET_TYPE)
        set(rolesField, roles?.let { ArrayList(it) })
        set(zoomsField, zooms?.let { ArrayList(it) })
        flagsField?.let { f -> set(f, copyList(f)) }
        set(rangesField, ranges?.let { ArrayList(it) })
        handlesField?.let { f -> set(f, copyList(f)) }
        // CRITICAL: the clone must NOT become the DEFAULT effect (that would boot the camera
        // into 红毯 instead of 超清实况 — getDefaultValue picks the g=true entry).
        set(defaultField, false)
        lensField?.let { f -> set(f, read(f)) }

        val ordered = CameraMasterLiveRedCarpet.orderedKeys(table.keys.filterIsInstance<String>())
            ?: (table.keys.filterIsInstance<String>() + CameraMasterLiveRedCarpet.RED_CARPET_TYPE)
        val merged = LinkedHashMap<Any?, Any?>()
        var placed = false
        for (key in ordered) {
            if (key == CameraMasterLiveRedCarpet.RED_CARPET_TYPE) {
                // Place the synthetic entry exactly once, at the canonical position.
                if (!placed) {
                    merged[key] = entry
                    placed = true
                }
                continue
            }
            table[key]?.let { merged[key] = it }
        }
        return merged.takeIf { placed }
    }

    /** Cached K100 config instance + its `q0()` Method for the master-off effect-table borrow. */
    private val k100EffectTableInstance = AtomicReference<Any?>(null)
    private val k100EffectTableMethod = AtomicReference<Method?>(null)
    private val k100EffectTableProbeFinished = AtomicReference(false)

    /** Logs the effect-table borrow / unavailability ONCE per process, not per dispatch. */
    private val mlTableBorrowLogged = AtomicReference(false)
    private val mlTableUnavailableLogged = AtomicReference(false)

    /** Keep mode 231 hidden when the selected config has no usable effect definitions. */
    private fun masterLiveHasEffectTable(): Boolean {
        val profile = hostProfile ?: return false
        val config = activeConfigInstance() ?: return false
        val method = profile.configMethod(config.javaClass, profile.effectTable, Map::class.java)
            ?: return false
        val native = runCatching { method.invoke(config) as? Map<*, *> }.getOrNull()
        return native?.isNotEmpty() == true ||
            (borrowK100EffectTable() as? Map<*, *>)?.isNotEmpty() == true
    }

    /**
     * Resolve + cache the K100 (REDMI) config instance and its `q0()` Method — via
     * [resolveK100Config] only, NEVER the Nezha/CommonFlagship fallback (the 17U table ends
     * 12.9x and crashes myron's camera) — then invoke `q0()` for the borrowed effect table.
     */
    private fun borrowK100EffectTable(): Any? {
        // The K100 table's sensor comparison is only validated for the legacy config ABI.
        // Newer config bases reused several old getter names for boolean capabilities; never
        // interpret that surface as sensor identity.
        if (hostProfile?.family != CameraHostProfile.Family.CAMERA_66) {
            logEffectTableUnavailableOnce("sensor identity contract not verified for this config ABI")
            return null
        }
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
            if (table == null) logEffectTableUnavailableOnce("${k100.javaClass.name}#q0() returned null")
            return table
        }
    }

    private fun logEffectTableUnavailableOnce(reason: String) {
        if (mlTableUnavailableLogged.getAndSet(true) == false) {
            DebugLog.w(TAG, "masterlive effect table borrow unavailable: $reason")
        }
    }

    /**
     * MasterLive (实况运镜) full focal line-up for the zoom-toggle strip
     * (`Preferences.KEY_CAMERA_MASTERLIVE_FULL_FOCAL`, default off; research:
     * RESEARCH_MYRON_12_MASTERLIVE_FOCAL_STRIP.md).
     *
     * The 焦段 strip inside 实况运镜 (`FragmentZoomToggle`'s `ZoomRatioToggleView` row) reads
     * the config's per-mode zoom stops `v1()` keyed by mode id — `j.U(231,false)` → `j.S` →
     * `j.R` → `p723ur.i#q(231,…)` → `v1().get(231)`. The native myron config has NO 231 key, so
     * the camera falls back to the hardcoded `{1.0x, 2.0x}` pair and 超清实况 shows only 1x/2x
     * where a full unlock shows the whole line-up. This hook appends
     * `231 → [CameraIdentity.MASTER_LIVE_FOCAL_STOPS]` (the K100 Pro Max stops {0.7, 1, 2, 5,
     * 10} — bit-identical sensor axis to myron and exactly myron's real optics) to the result
     * whenever the key is absent; an existing key is never touched and no other mode's stops
     * change. Installs on the `v1()` Method of EVERY dispatch class ([configDispatchClasses],
     * dedup by Method identity). Gated on [Preferences.KEY_CAMERA_MASTERLIVE_ENABLE] and the
     * switch.
     */
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
            DebugLog.i(TAG, "masterlive full focal hook installed on $hooked v1() Method(s)")
        }
    }

    /** Logs the first full-focal substitution ONCE per process, not per call. */
    private val fullFocalLogged = AtomicReference(false)

    /**
     * Resolve ComponentModuleList (540 `u2.S`, 510 `u2.P`, 460 `u2.U`) once for
     * both MasterLive list hooks. Validation survives renames:
     *  - L1 candidates: the real dex names of the two verified builds (`p699u2`/`p700u2` are
     *    jadx display aliases for collisions with root packages — the aliases themselves do
     *    not exist in the dex).
     *  - L2 DexKit probe: `"ComponentModuleList"` is the plaintext log tag used by
     *    `Log.d(...)` throughout the class AND `"setAllSupportModeList  = "` its unique
     *    write-site string (`P.java:238`), together pinning exactly one class.
     *  - shape: a declared static int[] field whose contents contain BOTH the 254 更多 marker
     *    and mode 231 ([CameraIdentity.defaultModeListShape] — the default list `f62382k`,
     *    `P.java:51`; smali clinit `{…0xfe…0xe7…}`), plus an instance int[]-returning
     *    single-parameter method (the `y(Q)` funnel shape, `P.java:895`).
     */
    private val componentModuleList = AtomicReference<Class<*>?>()

    private fun resolveComponentModuleList(): Class<*>? {
        componentModuleList.get()?.let { return it }
        synchronized(componentModuleList) {
            componentModuleList.get()?.let { return it }
            val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
            val resolved = CameraResolver.resolveClass(
                scope = TAG, key = "component_module_list", ctx = ctx,
                candidates = emptyList(),
                probe = { bridge ->
                    // Both strings are CONTAINS-matched; the pair pins exactly one class.
                    bridge.findClass {
                        matcher { usingStrings("ComponentModuleList", "setAllSupportModeList") }
                    }.firstOrNull()?.name
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

    /** True when [clazz] declares a static int[] holding both 254 and 231 (see [CameraIdentity.defaultModeListShape]). */
    private fun hasDefaultModeListField(clazz: Class<*>): Boolean =
        clazz.declaredFields.any { field ->
            java.lang.reflect.Modifier.isStatic(field.modifiers) && field.type == IntArray::class.java &&
                runCatching {
                    field.isAccessible = true
                    CameraIdentity.defaultModeListShape(field.get(null) as? IntArray)
                }.getOrDefault(false)
        }

    /**
     * MasterLive (实况运镜) carousel placement, part 2: correct the ORDER FUNNEL itself.
     *
     * ROOT CAUSE this covers (why fixing only `M()` could never work): every consumer of the
     * mode order goes through ComponentModuleList's `y(Q)[I` / its zero-arg wrapper `x()[I`
     * (`p700u2/P.java:895-915/891-893`), which returns whichever source wins:
     *  1. the in-memory sort cache `f62389h` — seeded by the constructor (`P.java:110`) and
     *     rewritten by `K(iArr3,false)` after EVERY render (`o()`, `P.java:822-824`) with the
     *     support-filtered rendered order, so a single session without 231 erases it;
     *  2. the persisted `pref_camera_sort_modes_key` string (written by `H()` when the user
     *     edits modes, `P.java:568-583` + editor call site `S4/f.java#Nq` → `K(iArr,true)`,
     *     and migrated across camera app upgrades by `Ac/e.java:155-246`);
     *  3. only when BOTH are cold, the freshly built `t(Q)` — the sole reader of config `M()`.
     *
     * An after-hook here re-places 231 immediately before the FIRST 254 marker in whatever
     * array leaves the funnel ([CameraIdentity.placeMasterLiveModeBeforeMarker]), which makes
     * `C()`'s carousel/overflow split (`P.java:469-490`) put MasterLive in the strip. Because
     * `K(iArr3,false)` stores back what was rendered from our corrected order, the in-memory
     * cache converges to a corrected layout by itself, and any later user edit persists
     * (`H()` → `I(x())`) an already-corrected order — the stock caches heal instead of
     * fighting us. No-op (returns untouched) whenever 231 already leads the marker (Nezha
     * arrays, prior corrections) or the feature gates are off.
     *
     * NOT covered here and deliberately skipped: `E(int)` prefers the persisted
     * `all_support_mode_list` over `x()` (`P.java:511-550`) — see
     * [hookMasterLiveSupportEntry]. Gated on `KEY_CAMERA_MASTERLIVE_ENABLE` only: this class is
     * config-independent, so the funnel correction applies to the active config's mode list too.
     */
    private fun hookMasterLiveOrderFunnel() {
        val clazz = resolveComponentModuleList() ?: run {
            DebugLog.w(TAG, "ComponentModuleList not resolved; masterlive order funnel skipped")
            return
        }
        val yMethod = CameraResolver.resolveMethod(
            scope = TAG, key = "component_module_list_y", clazz = clazz,
            names = listOf("y"),
            shape = { it.parameterCount == 1 && it.returnType == IntArray::class.java },
        ) ?: run {
            DebugLog.w(TAG, "${clazz.name}#y(Q)[I not found; masterlive order funnel skipped")
            return
        }
        deoptimize(yMethod)
        yMethod.hook("cam_masterlive_order_funnel") {
            after { param ->
                if (!masterliveEnabled() || !masterLiveHasEffectTable()) return@after
                val placed = CameraIdentity.placeMasterLiveModeBeforeMarker(param.result as? IntArray)
                if (placed != null) param.result = placed
            }
        }
        DebugLog.d(TAG, "masterlive order funnel hooked on ${clazz.name}#y(Q)")
    }

    /**
     * MasterLive carousel placement, part 3 (defense-in-depth): force ComponentModuleList#E(231) true.
     *
     * When the config's `L2()` is true (base impl `!(this instanceof C1198)`, C1174.java:488),
     * `E(int)` ignores `x()` and consults the PERSISTED pref string `all_support_mode_list`
     * instead (`p700u2/P.java:511-550`); a list persisted before MasterLive was visible lacks
     * 231 and fails the before-the-marker check. Its only two consumers are the
     * retain-camera-mode decision (`u2.S#a`, smali `Lu2/P;->E(I)Z` call sites) and the
     * new-user mode guide (`com.android.camera.guide.b`) — neither hides the mode, but both
     * misbehave for exactly the mode we inject above. Forcing E(231)=true while our placement
     * is active keeps them consistent; every other id computes normally. Gated on
     * `KEY_CAMERA_MASTERLIVE_ENABLE` only (config-independent hook). `z(231)==231` (no legacy
     * alias maps to 231, `P.java:343-395`), so the id check is exact.
     */
    private fun hookMasterLiveSupportEntry() {
        val clazz = resolveComponentModuleList() ?: run {
            DebugLog.w(TAG, "ComponentModuleList not resolved; masterlive support entry skipped")
            return
        }
        val eMethod = CameraResolver.resolveMethod(
            scope = TAG, key = "component_module_list_e", clazz = clazz,
            names = listOf("E"),
            shape = {
                it.parameterCount == 1 && it.parameterTypes[0] == java.lang.Integer.TYPE &&
                    it.returnType == java.lang.Boolean.TYPE
            },
        ) ?: run {
            DebugLog.w(TAG, "${clazz.name}#E(I)Z not found; masterlive support entry skipped")
            return
        }
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
        DebugLog.d(TAG, "masterlive support entry hooked on ${clazz.name}#E(I)")
    }

    /**
     * MasterLive (实况运镜) role-23 (`Standalone`) fallback. The K100 Pro Max effect table ends
     * its zoom on the `Standalone` role, resolved via `u6/e#M()` (jadx `p703u6/e#M()` =
     * `f62592h.get(23, -1)`; the real dex class is `u6.e` — the jadx alias `p703u6.e` does not
     * exist in the dex and resolved to null on the device). A device whose tele is only labelled
     * role 20 (Samsung JN5) has no role-23 camera -> -1 -> the 15x endpoint never resolves. This
     * hook falls back to the role-20 tele camera (`r()`) only when role 23 is absent
     * (RESEARCH_MYRON_02 §6.2); on a device that really has role 23 (myron: role 23 <-> camera 4
     * and no role 20) it is a no-op.
     *
     * The role adapter is config-independent, so the fallback applies to the active config's
     * MasterLive session; the gate is the user's own `KEY_CAMERA_MASTERLIVE_TELE_FALLBACK`
     * switch (default off) alone.
     *
     * ON-DEVICE RESOLUTION FIX (2026-08-26, RESEARCH_MYRON_ONDEVICE_EVIDENCE §5.1): the
     * DexKit probe previously used `"Camera2CompatAdapterRole"`, but the class's log-tag
     * constant in the dex is `MCAM_Camera2CompatAdapterRole` (MCAM_ prefix) — the probe never
     * matched and the hook was skipped on-device ("Camera2CompatAdapterRole not resolved"). The
     * probe string is now `MCAM_...`; the method-shape validation (int-returning `M()`) is
     * unchanged.
     */
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

    /**
     * MasterLive (实况运镜) video-size probe — experimental
     * (`KEY_CAMERA_MASTERLIVE_VIDEO_SIZE_PROBE`, default off).
     *
     * On-device forensics (2026-08-27, OS4.0.0.19.XPMCNXM; RESEARCH_MYRON_09): the MasterLive
     * live-video stream on myron is sized via the HAL masterlive ratio tag `G()` (reads a
     * myron-native vendor tag, `C3545f#G`) into 16:9 streams (2560x1440, or per-role pairs
     * from the HAL list), and those frames arrive damaged — content squeezed into the bottom
     * ~44%, the rest zero-chroma (renders as pure green after the app's 90° rotation; the
     * still is clean because it uses a different stream). Normal 实况照片 uses the `A()` ratio
     * and a 4:3 stream (source 1728x1296) that is CLEAN on this device (user-verified). This
     * hook forces the mode-231 branch of `getLivePhotoVideoSize` (`C3652n#c(Size, a)`, jadx
     * `p391l6/C3652n`, log strings "getLivePhotoVideoSize roleId = …" survive plaintext) to a
     * PER-EFFECT-TYPE bound size ([CameraMasterLiveSizeBinding]): 16:9 2304x1296 for the
     * movement effects (红毯/主角/自由 — user-verified clean captures), 4:3 1728x1296 for the
     * ultra-pixel 超清实况 effect (a global 16:9 pin broke it with green frames again,
     * 2026-08-28 user round). The current effect type comes from the camera's own MasterLive
     * component value (`j#A(231)`, [currentMasterLiveType]); an unreadable type falls back to
     * the globally verified 16:9. Matching/absent results pass through; only the mode-231
     * result is substituted, and only when the probe switch is on.
     */
    private fun hookMasterLiveVideoSizeProbe() {
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
        // Real dex name verified on 510: `l6.n` (jadx display p391l6/C3652n, "renamed from:
        // l6.n"); older builds use sibling size-base classes. The plaintext log-string probe
        // is backup only — "getLivePhotoVideoSize" is NOT plaintext in the dex (runtime
        // concatenation), so candidates are the primary path.
        val clazz = CameraResolver.resolveClass(
            scope = TAG, key = "livephoto_size_base", ctx = ctx,
            candidates = emptyList(),
            probe = { bridge ->
                bridge.findClass { matcher { usingStrings("getLivePhotoVideoSize") } }
                    .firstOrNull { descriptor ->
                        ctx.loadOrNull(descriptor.name)?.let {
                            resolveLivePhotoVideoSizeMethod(it, quiet = true) != null
                        } == true
                    }?.name
            },
            validate = { c -> resolveLivePhotoVideoSizeMethod(c, quiet = true) != null },
        ) ?: run {
            DebugLog.w(TAG, "live-photo size base not resolved; masterlive video size probe skipped")
            return
        }
        val method = resolveLivePhotoVideoSizeMethod(clazz) ?: run {
            DebugLog.w(TAG, "getLivePhotoVideoSize method not found; masterlive video size probe skipped")
            return
        }
        deoptimize(method)
        method.hook("cam_masterlive_video_size_probe") {
            after { param ->
                if (!videoSizeProbeEnabled()) return@after
                if ((param.result as? Size) == null) return@after
                val receiver = param.args.getOrNull(1) ?: return@after
                val mode = readModuleIndex(receiver) ?: return@after
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

    /**
     * The camera's own MasterLive effect type (`pref_master_live_key` via the static
     * `com.android.camera.data.data.j#A(231)`), or null when it cannot be read. `j#A` returns
     * "" unless mode 231 is the ACTIVE mode — exactly the scope our size hooks run in — and
     * reads the component value live, so switching effects applies without a restart.
     */
    private val masterLiveTypeMethod = AtomicReference<Method?>(null)
    private val masterLiveTypeResolved = AtomicReference(false)

    private fun currentMasterLiveType(): String? {
        val loader = classLoader
        if (!masterLiveTypeResolved.get() || masterLiveTypeMethod.get() == null) {
            synchronized(masterLiveTypeMethod) {
                if (!masterLiveTypeResolved.get() || masterLiveTypeMethod.get() == null) {
                    val ctx = CameraResolver.Ctx(loader, hookParam.appInfo)
                    val owner = CameraResolver.resolveClassByStrings(
                        scope = TAG,
                        key = "masterlive_type_provider",
                        ctx = ctx,
                        anchors = listOf("pref_master_live_key"),
                    ) { type ->
                        type.declaredMethods.any {
                            Modifier.isStatic(it.modifiers) && it.parameterCount == 1 &&
                                it.parameterTypes[0] == Integer.TYPE && it.returnType == String::class.java
                        }
                    }
                    val resolved = owner?.declaredMethods?.singleOrNull {
                        !it.isSynthetic && Modifier.isStatic(it.modifiers) &&
                            it.parameterCount == 1 && it.parameterTypes[0] == Integer.TYPE &&
                            it.returnType == String::class.java
                    }?.apply { isAccessible = true }
                    masterLiveTypeMethod.set(resolved)
                    // The APK is immutable for this process; cache misses as well, so a disabled
                    // optional hook cannot cause repeated DEX scans on its callback path.
                    masterLiveTypeResolved.set(true)
                }
            }
        }
        val method = masterLiveTypeMethod.get() ?: return null
        // j#A can NPE while the component manager is still booting; that reads as "unknown".
        return runCatching { method.invoke(null, CameraIdentity.MASTER_LIVE_MODE_ID) as? String }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    /** Logs the first size substitution ONCE per process, not per size call. */
    private val videoSizeProbeLogged = AtomicReference(false)

    /**
     * The `getLivePhotoVideoSize(Size, camera-size-base)` method: static, first parameter
     * `android.util.Size`, second parameter anything, returns `Size`. Resolved on the
     * declaration or via inherited-public methods.
     */
    private fun resolveLivePhotoVideoSizeMethod(clazz: Class<*>, quiet: Boolean = false): Method? {
        val match: (Method) -> Boolean = { m ->
            !m.isSynthetic && java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                m.parameterCount == 2 && m.parameterTypes[0] == Size::class.java &&
                m.returnType == Size::class.java
        }
        val declared = clazz.declaredMethods.firstOrNull(match)
        val resolved = declared ?: clazz.methods.firstOrNull(match)
        if (resolved == null && !quiet) {
            DebugLog.w(TAG, "${clazz.name}#getLivePhotoVideoSize not found by shape")
        }
        return resolved?.apply { isAccessible = true }
    }

    /**
     * The module index of the size-base instance (real field `d`, the module the size
     * computation runs for; 231 = MasterLive). Walks the receiver's class hierarchy.
     */
    private fun readModuleIndex(receiver: Any): Int? {
        var c: Class<*>? = receiver.javaClass
        while (c != null) {
            val field = runCatching { c.getDeclaredField("d") }.getOrNull()
                ?: runCatching { c.getDeclaredField("f47815d") }.getOrNull()
            if (field != null && field.type == java.lang.Integer.TYPE) {
                field.isAccessible = true
                return runCatching { field.getInt(receiver) }.getOrNull()
            }
            c = c.superclass
        }
        return null
    }

    /**
     * MasterLive (实况运镜) video-surface size — companion of [hookMasterLiveVideoSizeProbe].
     *
     * The video-compose consumer's `c()` (540 `Kj.C`, older builds `Kj.D`) returns the live-photo
     * video stream size from the camera manager's
     * config (`f45848w`) and falls back to a HARDCODED 16:9 `Size(2304, 1296)` when that field
     * is null. With the video-size probe active the stream is bound per effect type
     * ([CameraMasterLiveSizeBinding]), so an un-bound fallback mismatches the stream: when a
     * capture request builds its output buffer from this value the CamX HAL aborts
     * (`CamX::ImageBuffer::Import` SIGABRT, observed on-device: "WxH 2304x1296" in the
     * tombstone, provider `vendor.qti.camera.provider` dies → the app freezes/crashes after
     * the shutter on types 2/3). This hook binds `c()`'s result to the SAME per-type size while
     * the probe is on, keeping every buffer coherent.
     *
     * MODE GATE (2026-08-28): the previous build substituted UNCONDITIONALLY, which also
     * rewrote the 4:3 result of the normal 实况照片 modes (171/188/230) that share this
     * consumer — a latent regression. The receiver's camera-manager handle carries the active
     * module id (`Kj/F.java:125` reads the same `a.g` chain); substitution now happens only
     * when it equals 231, and never when the chain is unreadable.
     */
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

    /** Logs the first surface-size substitution ONCE per process. */
    private val videoSurfaceProbeLogged = AtomicReference(false)

    /** Find exactly one matching module id in a small graph of host objects. */
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

    /**
     * Shutter-sound style-index bounds guard. `f2.c` (jadx `p180f2/c`) builds a 4-entry
     * shutter-sound style list (old/art/default/modern) because the native C1209 (C1199
     * branch) reports `F3()=false` (Leica entries skipped). Its raw getter
     * `a()` reads the stored `key_shutter_sound` (=4 from an old Leica-list migration) with NO
     * bounds check, so `MiuiCameraSound(D3)#g()` → `b().get(a())` throws
     * `IndexOutOfBoundsException: Index 4 out of bounds for length 4` on every shutter-sound
     * preload (CAM-Work) → RxJava onError (no handler) → FATAL → the camera cannot start.
     * This hook clamps an out-of-range `a()` result back to `c()` — the getter that already
     * applies the `F3`-offset and bounds check (returns 0 when out of range).
     *
     * UNCONDITIONAL — deliberately NOT gated on any camera-unlock switch. The persisted
     * `key_shutter_sound` outlives any config era (the `Ac/e` version migration only keeps it,
     * never rewrites it), so a value taken from the Leica 8-entry era keeps crashing the
     * 4-entry list — the "不打开伪装旗舰机相机配置时打开相机闪退" report.
     * `RESEARCH_MYRON_06_IOOBE_ROOTCAUSE.md` §4 anticipated exactly this and §6.3 concluded
     * "该修复与冒充无关、应常驻启用". The clamp only ever re-maps an out-of-range index to the
     * app's own bounds-safe default, so valid selections (0-3 native, 0-7 Leica) pass through
     * untouched in every configuration.
     */
    private fun hookShutterSoundBoundary() {
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
        val clazz = CameraResolver.resolveClass(
            scope = TAG, key = "shutter_cfg", ctx = ctx,
            candidates = emptyList(),
            // `key_shutter_sound` survives as a plaintext dex string on both verified builds,
            // so a renamed shutter-config class can still be found by it; the method-shape
            // filter (int-returning zero-arg `a()`) disambiguates any other string user.
            probe = { bridge ->
                bridge.findClass { matcher { usingStrings("key_shutter_sound") } }
                    .firstOrNull { cd ->
                        cd.methods.any { m -> m.name == "a" && m.returnTypeName == "int" }
                    }?.name
            },
            validate = { c ->
                c.declaredMethods.any {
                    it.name == "a" && it.parameterCount == 0 && it.returnType == java.lang.Integer.TYPE
                }
            },
        ) ?: run {
            DebugLog.w(TAG, "f2.c (shutter sound config) not resolved; shutter-sound guard skipped")
            return
        }
        val aMethod = runCatching {
            clazz.getMethod("a").takeIf {
                it.parameterCount == 0 && it.returnType == java.lang.Integer.TYPE
            }
        }.getOrNull() ?: run {
            DebugLog.w(TAG, "f2.c#a() not found; shutter-sound guard skipped")
            return
        }
        val bMethod = runCatching {
            clazz.getMethod("b").takeIf { it.parameterCount == 0 }
        }.getOrNull()
        val cMethod = runCatching {
            clazz.getMethod("c").takeIf {
                it.parameterCount == 0 && it.returnType == java.lang.Integer.TYPE
            }
        }.getOrNull()

        deoptimize(aMethod)
        aMethod.hook("cam_shutter_sound_bounds") {
            after { param ->
                val idx = (param.result as? Int) ?: return@after
                val size = bMethod?.let { runCatching { (it.invoke(null) as? List<*>)?.size }.getOrNull() }
                    ?: return@after
                if (idx < 0 || idx >= size) {
                    param.result = cMethod?.let { runCatching { it.invoke(null) }.getOrNull() } ?: 0
                }
            }
        }
        DebugLog.d(TAG, "shutter-sound bounds guard hooked on ${clazz.name}#a()")
    }

    /**
     * Whether this device IS a real 17 Ultra (device base == nezha). On a real flagship the
     * tele-role fallback etc. are no-ops anyway, so the fast-path skips the reflective work
     * entirely (these getters run on hot paths).
     */
    private fun deviceIsNezha(): Boolean {
        deviceIsNezhaCache.get()?.let { return it }
        synchronized(deviceIsNezhaCache) {
            deviceIsNezhaCache.get()?.let { return it }
            val isNezha = Build.DEVICE.equals("nezha", ignoreCase = true)
            deviceIsNezhaCache.set(isNezha)
            return isNezha
        }
    }

    /** Active config selected by the profile hook (native when the selector is Off). */
    private fun activeConfigInstance(): Any? =
        runCatching { hostProfile?.configInstance() }.getOrNull()

    /**
     * Config classes the running camera dispatches capability getters to. This is the validated
     * Madrid profile only for Legendary Moment; Leica Moment keeps the native config. Only the
     * active config is hooked.
     */
    private fun configDispatchClasses(): List<Class<*>> {
        val classes = LinkedHashSet<Class<*>>()
        activeConfigInstance()?.javaClass?.let { classes.add(it) }
        return classes.toList()
    }

    // ─── gate helpers ─────────────────────────────────────────────────────────────

    /**
     * Watermark keep is UNCONDITIONAL: the on-picture watermark brand + model always stays on
     * this device's own values (or the user custom override). Unconditional also never shows a
     * wrong model on devices that cannot resolve a real market name.
     */
    private fun keepModel(): Boolean = true

    /** Resolved street unlock mode ([CameraStreetMode] constant), re-read live (100 ms memo). */
    private fun streetMode(): String = Preferences.cameraStreetMode()

    private fun leicaStyle(): Boolean =
        Preferences.getBoolean(Preferences.KEY_CAMERA_LEICA_STYLE, false)

    /** Whether the selected mode entry should be exposed, after its config requirement passed. */
    private fun legendaryMomentUnlock(): Boolean {
        val mode = Preferences.cameraLegendaryMomentMode()
        return mode != CameraLegendaryMomentMode.MODE_OFF && CameraLegendaryProfileState.isApplied(mode)
    }

    /** 智能构图 setting unlock. */
    private fun smartCompositionUnlock(): Boolean =
        Preferences.getBoolean(Preferences.KEY_CAMERA_SMART_COMPOSITION, false)

    /** 内容凭证 setting unlock; default off and applied once per camera process. */
    private fun contentCredentialUnlock(): Boolean =
        Preferences.getBoolean(Preferences.KEY_CAMERA_CONTENT_CREDENTIAL, false)

    /** 自适应镜头 setting unlock. */
    private fun adaptiveLensUnlock(): Boolean =
        Preferences.getBoolean(Preferences.KEY_CAMERA_ADAPTIVE_LENS, false)

    /** 实况运镜 unlock master (default off). */
    private fun masterliveEnabled(): Boolean =
        Preferences.getBoolean(Preferences.KEY_CAMERA_MASTERLIVE_ENABLE, false)

    /** 实况运镜 role-23 tele fallback switch (default off). */
    private fun masterliveTeleFallback(): Boolean =
        Preferences.getBoolean(Preferences.KEY_CAMERA_MASTERLIVE_TELE_FALLBACK, false)

    /** 实况运镜 video-size probe (default off; opt-in on every device). */
    private fun videoSizeProbeEnabled(): Boolean =
        Preferences.getBoolean(Preferences.KEY_CAMERA_MASTERLIVE_VIDEO_SIZE_PROBE, false)

    /** 实况运镜 红毯运镜 (type-1) injection (default off); gated with [masterliveEnabled]. */
    private fun redCarpetEnabled(): Boolean =
        Preferences.getBoolean(Preferences.KEY_CAMERA_MASTERLIVE_RED_CARPET, false)

    /** 实况运镜 full focal line-up (超清实况焦段条, default off); gated with [masterliveEnabled]. */
    private fun fullFocalEnabled(): Boolean =
        Preferences.getBoolean(Preferences.KEY_CAMERA_MASTERLIVE_FULL_FOCAL, false)
}
