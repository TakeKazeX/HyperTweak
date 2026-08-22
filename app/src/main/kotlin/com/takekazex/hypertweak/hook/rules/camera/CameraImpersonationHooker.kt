package com.takekazex.hypertweak.hook.rules.camera

import android.os.Build
import android.util.Size
import android.util.SparseArray
import com.takekazex.hypertweak.hook.CameraStreetMode
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import com.takekazex.hypertweak.util.StaticFieldWriter
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Impersonates the camera app's per-device capability config as a flagship, so every
 * capability gate (mode visibility, MIVI pipeline switches, `instanceof`-based flagship
 * branches, ...) opens on any device — while keeping the **watermark device model** and the
 * **focal-length line-up** on this device's own values.
 *
 * Architecturally (reverse-engineering notes in
 * `cache/camera-5cd70925b1646cdf/CAMERA_UNLOCK_EVALUATION.md` and
 * `CAMERA_FEATURE_GATES_17ULTRA.md`):
 *
 * 1. The whole capability surface funnels through one object: `Je.c$b.a.e` (jadx renders the
 *    singleton holder as `Je.c.b.f8427a` holding the facade whose config field it shows as
 *    `f8420e`), created by the single static factory `Je/e.q()` (`Class.forName("com.mi.device."+device)`).
 *    Hooking `Je/e#q` to return a flagship instance (`com.mi.device.Nezha`, the 17 Ultra
 *    config) unlocks everything at once.
 * 2. The watermark brand/model strings come from `Je/c#x()` (=`v()[0]`, brand logo), `Je/c#y()`
 *    (=`v()[1]` model text) and `Je/c#w()` (=`v()[2]`). Both `x()/v()` are forced back to THIS
 *    device's brand + market name (or a user custom brand/model) so the on-picture watermark
 *    keeps the local model. The EXIF `Model` tag is `ro.product.marketname` directly
 *    (`Je.d.a` feeds the device-name lazy; the market name is never the capability config), so
 *    EXIF stays correct.
 * 3. The zoom/focal getters (`B1/q0/e1/A1/C1/v1/x1/y0/h1`) are kept on the REAL device config
 *    instance (see `KEY_CAMERA_KEEP_FOCAL`), so 焦段 stays the device's own while every
 *    capability boolean still comes from the flagship.
 * 4. `Je/c#V()` (LCC theme gate) is only impersonated when the user opts in
 *    (`KEY_CAMERA_IMPERSONATE_THEME_LCC`); and `Ox/g#i()` (the LCC customization-provider
 *    toggle that hides the 相机配色 settings entry) is forced true whenever the master switch is
 *    on so the tint-color entry is never lost.
 *
 * Requires a camera app restart after the first enable (the hooks are installed by
 * `HookEntry` on attach). Disabling removes the overrides immediately on the next
 * capability read (the callbacks re-read `Preferences` live).
 */
object CameraImpersonationHooker : StaticHooker() {
    private const val TAG = "CamImpersonate"
    private const val PACKAGE = "com.android.camera"

    /**
     * Name candidates for each hooked class, newest builds first, accumulated across verified
     * versions. Camera releases re-obfuscate a per-build subset of class names and can also
     * REUSE a surviving name for an unrelated class (`Ox.g` was the LCC provider on 460, a
     * state-list helper on 510; `i5.d` was the watermark entry holder on 460, a font-menu
     * ViewModel on 510), so every candidate is validated by method shape before use.
     */

    /** Device-config facade `Je.c` (stable across 460/510): x/y/w/v/V/M + singleton `Je.c$b`. */
    private val CONFIG_FACADE_CANDIDATES = listOf("Je.c")

    /** Device-config factory `Je.e` (class stable; factory method renamed `q` -> `G0` on 510). */
    private val CONFIG_FACTORY_CLASS_CANDIDATES = listOf("Je.e")
    private val CONFIG_FACTORY_METHOD_NAMES = listOf("G0", "q")

    /** Flagship targets resolvable through the app's own `Uf.c.a(sourceName)` channel (version-independent). */
    private const val DEVICE_NEZHA = "com.mi.device.Nezha"
    private const val DEVICE_FLAGSHIP = "com.mi.device.xiaomi.CommonFlagship"

    /**
     * Dex class names of the REDMI K100 Pro Max / POCO F9 Ultra config, newest first
     * (full mapping: `camera-8f41d7b82453cdeb/OLD_TO_NEW_MAPPING.md`):
     *  - 6.6.000510.0: `쌴쌸쌺썹쌺쌾썹쌳쌲쌡쌾쌴쌲썹쌄쌸쌹쌰쌮쌢쌶쌹` (jadx C1200)
     *  - 6.6.000460.0: `峡峭峯岬峯峫岬峦峧峴峫峡峧岬峑峭峬峥峻峷峣峬` (jadx `defpackage/C1151`)
     * The app's own resolver `Uf.c.a` maps `com.mi.device.*` source names onto the per-build
     * obfuscated dex names and is the version-independent channel — the K100 config's SOURCE
     * name `com.mi.device.Songyuan` resolves on both verified builds
     * ([K100_SOURCE_NAME_CANDIDATES], validated by imaging identity against the REAL device
     * config so a wrong pick can never reach the watermark/capability logic).
     */
    private val DEVICE_K100PROMAX_CANDIDATES = listOf(
        "쌴쌸쌺썹쌺쌾썹쌳쌲쌡쌾쌴쌲썹쌄쌸쌹쌰쌮쌢쌶쌹",
        "峡峭峯岬峯峫岬峦峧峴峫峡峧岬峑峭峬峥峻峷峣峬",
    )

    /**
     * Watermark entry holder class used by `S8.d`'s brand/model cache (the value of `zi.b`'s
     * field `a`; fields a/b + (String,String) ctor):
     *  - 6.6.000510.0: `Ft.a` (verified in smali: `zi/b.smali` field `->a:LFt/a;`, ctor
     *    `(String,String)`; jadx misrendered the type as `a`/`zi.a`)
     *  - 6.6.000460.0: `i5.d` (jadx `p288i5.d`, imported type in `zi/b.java`)
     */
    private val WATERMARK_ENTRY_CANDIDATES = listOf("Ft.a", "i5.d", "p288i5.d")

    /** `KEY_CAMERA_IMPERSONATE_TARGET` values. */
    private const val TARGET_K100PROMAX = "k100promax"
    private const val TARGET_NEZHA = "nezha"

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

    /**
     * Focal-length getters to keep on the device's own config instance while impersonating a
     * flagship. All nine are declared (and overridden) on the flagship config hierarchy
     * (`defpackage/C1143` Common -> `C1136` CommonFlagship -> `C1178` Nezha) and are consumed by
     * the zoom line-up / mm labels / SAT route code. Boolean capability getters are deliberately
     * NOT included — those must keep the flagship (impersonated) values.
     */
    private val FOCAL_GETTERS = arrayOf("B1", "e1", "A1", "C1", "v1", "x1", "y0", "h1")

    /**
     * Imaging-identity getters delegated back to the real device config while impersonating.
     * These report the SENSOR / LENS identity (sensor param string, sensor id, lens-id array,
     * lens count, LUT-directory float, per-camera output profile, output-format set, LUT-write
     * mechanism). Keeping them on the flagship feeds 17-Ultra calibration to MIVI/HAL
     * CCM·WB selection, which is why a Leica-Classic + tele RAW turns purple only after gallery
     * re-processing (the RAW is re-rendered with the wrong sensor calibration). Delegating them
     * to the REAL device config fixes the colour while all `instanceof`-based capability gates
     * (and the modes they open) stay flagship. Includes `B1` implicitly via FOCAL_GETTERS and
     * the mode gates (`y4/a3/b6`) separately.
     *
     * REGRESSION HISTORY (do not reintroduce): `M` was listed here from `8b202b7` until
     * 2026-08-21, but the config's `M()[I` is NOT imaging identity — its sole consumer in the
     * whole dex is `u2.P` (ComponentModuleList), which uses it to ORDER the mode carousel
     * against the 更多 (254) marker. Delegating it to the original myron config stripped mode
     * 231 (实况运镜) from the carousel under EVERY impersonation target. See
     * [hookMasterLiveModePlacement].
     */
    private val IDENTITY_GETTERS = arrayOf("O1", "D", "q1", "r1", "o0", "S6", "K2")

    /**
     * Every zero-arg getter we may delegate to the original config instance (focal lengths,
     * imaging identity, and the mode gates). Resolved once per original concrete class and
     * cached — the callbacks fire on hot paths so per-call reflection is avoided.
     */
    private val ORIGINAL_DELEGATE_NAMES =
        FOCAL_GETTERS + IDENTITY_GETTERS + arrayOf("y4", "a3", "b6", "B4", "l2")

    private val flagshipInstance = AtomicReference<Any?>(null)
    private val originalInstance = AtomicReference<Any?>(null)
    private val originalThirdSlot = AtomicReference<String?>(null)
    private val originalDelegates = AtomicReference<Map<String, Method>?>()
    private val deviceIsNezhaCache = AtomicReference<Boolean?>()

    override fun onHook() {
        if (hookParam.packageName != PACKAGE) return
        installHooks()
    }

    private fun installHooks() {
        hookConfigFactory()
        hookWatermarkKeep()
        hookWatermarkConfigCache()
        hookWatermarkRender()
        hookWatermarkBrandText()
        hookLccTheme()
        hookLccCustomizationProvider()
        hookKeepFocal()
        hookImagingIdentity()
        // 兼容模式街拍 MUST install unconditionally: it does not depend on the flagship
        // instance resolving (that is its whole point), so it can never live behind
        // hookModeGuards()'s `flagshipInstance() ?: return`.
        hookCompatStreetSupport()
        hookModeGuards()
        hookLeicaStyle()
        hookLegendarySupport()
        hookSmartComposition()
        hookSmartCompositionTopRow()
        hookSmartCompositionFeatureBar()
        hookContentCredential()
        hookAdaptiveLens()
        hookMasterLiveModePlacement()
        hookMasterLiveSupportGate()
        hookMasterLiveRealEffectTable()
        hookMasterLiveFullFocal()
        hookMasterLiveOrderFunnel()
        hookMasterLiveSupportEntry()
        hookMasterLiveTeleFallback()
        hookMasterLiveOpModeSafe()
        hookMasterLiveCodecPin()
        hookMasterLiveVideoSizeProbe()
        hookMasterLiveVideoSurfaceSize()
        hookMasterLiveAutoZoomCollapse()
        hookShutterSoundBoundary()
    }

    // ─── 1. Replace the per-device capability config with a flagship instance ──────

    private fun hookConfigFactory() {
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
        val clazz = CameraResolver.resolveClass(
            scope = TAG, key = "config_factory_class", ctx = ctx,
            candidates = CONFIG_FACTORY_CLASS_CANDIDATES,
            // A repurposed `Je.e` would not carry the static config-cache field `b`.
            validate = { c ->
                runCatching {
                    c.getDeclaredField("b").let { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                }.getOrDefault(false)
            },
        ) ?: run {
            DebugLog.w(TAG, "Je.e config factory not resolved; impersonation skipped")
            return
        }
        // Method names change between builds (`q` on 460 -> `G0` on 510); the structural
        // fallback recognises the factory purely by shape: static, zero-arg, return type
        // matches the static cache field `b` — independent of any method name.
        val cacheFieldType = runCatching {
            clazz.getDeclaredField("b").takeIf { java.lang.reflect.Modifier.isStatic(it.modifiers) }?.type
        }.getOrNull() ?: run {
            DebugLog.w(TAG, "$CONFIG_FACTORY_CLASS_CANDIDATES has no static cache field b; impersonation skipped")
            return
        }
        val qMethod = CameraResolver.resolveMethod(
            scope = TAG, key = "config_factory_method", clazz = clazz,
            names = CONFIG_FACTORY_METHOD_NAMES,
            // The factory is static, zero-arg AND its return type IS the cached config type —
            // the same invariant the structural fallback uses, so a second unrelated static
            // zero-arg method can never win the name match.
            shape = {
                it.parameterTypes.isEmpty() && java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    it.returnType == cacheFieldType
            },
        ) ?: CameraResolver.findFactoryMethod(clazz) ?: run {
            DebugLog.w(TAG, "${clazz.name} factory method not found (names $CONFIG_FACTORY_METHOD_NAMES; no structural match); impersonation skipped")
            return
        }
        deoptimize(qMethod)
        qMethod.hook("cam_impersonate_q") {
            after { param ->
                if (!enabled()) return@after
                val flagship = flagshipInstance() ?: return@after
                param.result = flagship
                // Help callers that read the private cache field instead of this method.
                patchConfigCacheFields(flagship)
            }
        }
        DebugLog.d(TAG, "impersonation hooked on ${clazz.name}#${qMethod.name}()")
    }

    /**
     * If the config factory already ran (the app initialised `Je.c` before our hook), the
     * singleton's config field and the factory cache `Je.e.b` still hold the original instance.
     * Rewrite both so the impersonation is actually effective; harmless when they already
     * point at our flagship instance.
     */
    private fun patchConfigCacheFields(flagship: Any) {
        runCatching {
            val jeE = resolveClass(*CONFIG_FACTORY_CLASS_CANDIDATES.toTypedArray())
            // The factory cache field is the static field `b` on every verified build.
            val bField = jeE?.getDeclaredField("b")?.apply { isAccessible = true } ?: return
            // Skip the (possibly Unsafe-backed) write when we already own the cache — the
            // after-hook fires on EVERY factory call for the process lifetime.
            if (bField.get(null) === flagship) return
            StaticFieldWriter.set(bField, flagship)
        }.onFailure { t ->
            DebugLog.w(TAG, "Je.e.b cache patch failed", t)
        }
        runCatching {
            // The app's singleton lives on the static inner class `Je.c$b` (jadx renders it as
            // `Je.c.b.f8427a`): its static field `a` (public static final) holds the Je.c
            // instance, whose instance field `e` (jadx alias `f8420e`) is the per-device config.
            // `Je.c` itself has no static singleton field — its `b` is an unrelated Boolean.
            val holderClass = resolveClass("Je.c\$b") ?: return
            val singletonField = resolveField(holderClass, "a", "f8427a") ?: return
            val singleton = singletonField.get(null) ?: return
            val configField = resolveField(singleton.javaClass, "e", "f8420e") ?: return
            configField.isAccessible = true
            if (configField.get(singleton) === flagship) return
            configField.set(singleton, flagship)
        }.onFailure { t ->
            DebugLog.w(TAG, "Je.c singleton cache patch failed (defensive; usually not needed)", t)
        }
    }

    private fun flagshipInstance(): Any? {
        flagshipInstance.get()?.let { return it }
        synchronized(flagshipInstance) {
            flagshipInstance.get()?.let { return it }
            val built = buildFlagshipInstance()
            if (built != null) flagshipInstance.set(built)
            return built
        }
    }

    /**
     * Load the impersonated config instance. The target is chosen by
     * `KEY_CAMERA_IMPERSONATE_TARGET`:
     *  - `"k100promax"` (default): REDMI K100 Pro Max / POCO F9 Ultra (jadx C1200 on 510 /
     *    C1151 on 460), resolved by [resolveK100Config]: known dex names first, then the app's
     *    own `Uf.c.a` source-name channel (`com.mi.device.Songyuan`), all validated against the
     *    REAL device config. Best target for REDMI K-series devices (myron): sensor axis
     *    byte-identical to the device's own config (correct CCM/WB — no purple), `y4()=true` with
     *    a REDMI MasterLive effect table (no 17U tele/12.9x crash path), REDMI watermark strings.
     *  - `"nezha"`: legacy 17 Ultra (old behaviour). Loaded through the host's class-name rewrite
     *    (`Uf.c.a`) exactly as the app does.
     * Both fall back to the generic flagship if the chosen class cannot be instantiated. The
     * instance is host-loaded, so all `instanceof <flagship>` branches work.
     */
    private fun buildFlagshipInstance(): Any? {
        val loader = classLoader ?: return null
        return runCatching {
            val resolver = resolveSourceNameResolver(loader)
                ?: return@runCatching null
            if (targetIsK100Promax()) {
                resolveK100Config(loader, resolver)
                    ?: buildFrom(loader, resolver, DEVICE_NEZHA)
                    ?: buildFrom(loader, resolver, DEVICE_FLAGSHIP)
            } else {
                buildFrom(loader, resolver, DEVICE_NEZHA)
                    ?: buildFrom(loader, resolver, DEVICE_FLAGSHIP)
            }
        }.getOrNull()?.also {
            DebugLog.d(TAG, "flagship config instance created: ${it.javaClass.name}")
        } ?: run {
            DebugLog.e(TAG, "flagship config could not be instantiated")
            null
        }
    }

    /**
     * Version-generic resolution of the REDMI K100 Pro Max / POCO F9 Ultra config.
     *
     * Two channels, both validated against the REAL device config so a wrong pick (a renamed
     * or repurposed class) can never reach the watermark/capability logic:
     *  - L1: known dex names ([DEVICE_K100PROMAX_CANDIDATES], newest first) loaded directly;
     *  - L2: the app's own source-name resolver `Uf.c.a("com.mi.device.<name>")` — the same
     *    channel `Je.e` uses, which maps obfuscated dex names per build (version-independent);
     *    only names that survive the [K100_SOURCE_NAME_CANDIDATES] validation are accepted.
     *
     * Validation: the candidate must (a) expose the flagship getter surface (a3/y4/F3/X2/B1/…),
     * (b) NOT be the device's own config class, and (c) when the real device config is
     * resolvable, share its imaging identity (O1/D/q1/r1) — the exact invariant the original
     * K100 pick was verified on (correct CCM/WB, no purple). Any failure falls through to the
     * `buildFrom` Nezha / CommonFlagship chain at the call site.
     */
    private fun resolveK100Config(loader: ClassLoader, resolver: Method): Any? {
        // Snapshot the original ONCE, before any candidate work: every candidate is validated
        // against the SAME original instance, and the nested `originalInstance` monitor is not
        // acquired inside the flagship lock mid-loop (host newInstance() runs in between).
        val original = originalConfigInstance().get()
        for (name in DEVICE_K100PROMAX_CANDIDATES) {
            val instance = runCatching { loader.loadClass(name).getDeclaredConstructor().newInstance() }
                .getOrNull() ?: continue
            val shaped = isK100Shaped(instance)
            if (shaped && isK100Candidate(instance, original)) {
                DebugLog.i(TAG, "K100 config resolved by dex name $name")
                return instance
            }
            DebugLog.d(TAG, "K100 candidate $name rejected (shaped=$shaped)")
        }
        for (sourceName in K100_SOURCE_NAME_CANDIDATES) {
            val instance = buildFrom(loader, resolver, sourceName) ?: continue
            val shaped = isK100Shaped(instance)
            if (shaped && isK100Candidate(instance, original)) {
                DebugLog.i(TAG, "K100 config resolved via resolver source name $sourceName -> ${instance.javaClass.name}")
                return instance
            }
            DebugLog.d(TAG, "K100 source probe $sourceName -> ${instance.javaClass.name} rejected (shaped=$shaped)")
        }
        DebugLog.w(TAG, "K100 config not resolved by candidates or source-name probes; using built-in fallback")
        return null
    }

    /**
     * Source names probed through the app's own resolver (`Uf.c.a`) when no newer dex name is
     * known. `com.mi.device.Songyuan` is the K100 Pro Max / POCO F9 Ultra source name — the
     * ONLY K100-role key in the 510 resolver table (all 88 entries decoded: the guessed
     * `K100ProMax`/`K100`/`PocoF9Ultra`/... spellings are guaranteed ClassNotFoundException,
     * so they are not probed). A source name the resolver maps onto SOME config must still
     * pass [isK100Candidate].
     */
    private val K100_SOURCE_NAME_CANDIDATES = listOf(
        "com.mi.device.Songyuan",
    )

    /** Shape + identity validation for a K100-role config candidate. */
    private fun isK100Candidate(instance: Any, original: Any?): Boolean {
        if (!isK100Shaped(instance)) return false
        if (original == null) return true // no original -> shape only
        val clazz = instance.javaClass
        if (clazz == original.javaClass || clazz.name == original.javaClass.name) return false
        return CameraIdentity.sharesImagingIdentity(instance, original)
    }

    /** Flagship getter surface (`a3/y4/F3/X2`, all public zero-arg on every verified build). */
    private fun isK100Shaped(instance: Any): Boolean = runCatching {
        val clazz = instance.javaClass
        clazz.getMethod("a3").parameterCount == 0 &&
            clazz.getMethod("y4").parameterCount == 0 &&
            clazz.getMethod("F3").parameterCount == 0 &&
            clazz.getMethod("X2").parameterCount == 0
    }.getOrDefault(false)

    private fun buildFrom(
        loader: ClassLoader,
        resolver: java.lang.reflect.Method,
        name: String
    ): Any? = runCatching {
        val cls = resolver.invoke(null, name) as? Class<*> ?: return@runCatching null
        cls.newInstance()
    }.getOrNull()

    /**
     * Class-name candidates for the app's own source-name resolver — the version-independent
     * channel every `com.mi.device.*` config resolves through (`Uf.c.a(String) -> Class<*>`,
     * the same call the config factory `Je.e.G0()` makes).
     */
    private val SOURCE_RESOLVER_CANDIDATES = listOf("Uf.c")

    /**
     * Resolve + validate the source-name resolver: a STATIC single-`String` method returning
     * `Class`. Shape-only validation (no dex string survives for `Uf.c` to probe), but the
     * shape is narrow and every downstream consumer re-checks the produced instance (the K100
     * identity invariant, or the known flagship source names), so a repurposed `Uf.c` degrades
     * to a logged skip instead of a wrong hook. Without this channel BOTH the impersonation
     * target build AND the original-config rebuild are dead, so the failure is logged loudly.
     */
    private fun resolveSourceNameResolver(loader: ClassLoader): java.lang.reflect.Method? {
        for (name in SOURCE_RESOLVER_CANDIDATES) {
            val clazz = runCatching { loader.loadClass(name) }.getOrNull() ?: continue
            val method = clazz.declaredMethods.firstOrNull {
                java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.returnType == Class::class.java
            } ?: continue
            method.isAccessible = true
            DebugLog.d(TAG, "source-name resolver resolved on $name#${method.name}")
            return method
        }
        DebugLog.w(
            TAG,
            "source-name resolver not resolved (candidates $SOURCE_RESOLVER_CANDIDATES); " +
                "impersonation target + original-config rebuild unavailable"
        )
        return null
    }

    // ─── 2. Keep this device's brand + model on the watermark (or a user custom one) ─

    private fun hookWatermarkKeep() {
        captureOriginalThirdSlot()
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
        val clazz = CameraResolver.resolveClass(
            scope = TAG, key = "wm_keep_facade", ctx = ctx,
            candidates = CONFIG_FACADE_CANDIDATES,
            validate = { c ->
                c.declaredMethods.any {
                    it.name == "x" && it.parameterTypes.isEmpty() && it.returnType == String::class.java
                }
            },
        ) ?: run {
            DebugLog.w(TAG, "Je.c not resolved; watermark keep skipped")
            return
        }
        val xMethod = CameraResolver.resolveMethod(
            scope = TAG, key = "wm_keep_x", clazz = clazz,
            names = listOf("x"),
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
            names = listOf("v"),
            shape = { it.parameterTypes.isEmpty() && it.returnType.isArray },
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
        val original = originalConfigInstance().get() ?: return
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
     * process-lifetime, two problems surfaced: (a) if the singleton is built before `Preferences`
     * is ready in the camera process the keep hooks no-op and Nezha's own strings get baked
     * ("17 Ultra" watermark right after capture, reverting only after a later live re-read), and
     * (b) a custom-watermark change made later never reaches the cached config at all. Hooking
     * the singleton accessor `S8.d.a()` re-asserts the cached entry with the current brand()/
     * model() on every access, so the watermark fires this device's values (or the custom
     * override) at every render.
     */
    private fun hookWatermarkConfigCache() {
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
        val clazz = CameraResolver.resolveClass(
            scope = TAG, key = "wm_config_singleton", ctx = ctx,
            candidates = listOf("S8.d"),
            // `CloudWatermark` survives as a plaintext dex string (classes.dex).
            probe = { bridge ->
                bridge.findClass { matcher { usingStrings("CloudWatermark") } }
                    .firstOrNull { it.name == "S8.d" || it.name.contains("S8") }?.name
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
                if (!enabled()) return@after
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
            val entryClass = WATERMARK_ENTRY_CANDIDATES.asSequence()
                .mapNotNull { ctxLoadOrNull(it) }
                .firstOrNull { c ->
                    // The holder's ctor is `(String,String)` — require BOTH params to be String
                    // so a repurposed 2-arg candidate (non-String) can never be picked.
                    c.declaredConstructors.any {
                        it.parameterTypes.size == 2 &&
                            it.parameterTypes[0] == String::class.java &&
                            it.parameterTypes[1] == String::class.java
                    }
                } ?: run {
                DebugLog.w(TAG, "watermark entry holder not resolved (candidates $WATERMARK_ENTRY_CANDIDATES)")
                return
            }
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

    private fun ctxLoadOrNull(name: String): Class<*>? =
        runCatching { classLoader.loadClass(name) }.getOrNull()

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
            candidates = listOf("com.xiaomi.cam.watermark.a"),
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
        val j0 = CameraResolver.resolveMethod(
            scope = TAG, key = "wm_renderer_j0", clazz = clazz,
            names = listOf("J0"),
            shape = { m ->
                m.parameterTypes.size == 3 &&
                    m.parameterTypes[0] == String::class.java &&
                    m.parameterTypes[1] == String::class.java &&
                    m.parameterTypes[2] == java.lang.Boolean.TYPE
            },
        ) ?: run {
            DebugLog.w(TAG, "${clazz.name}#J0 not found; render keep skipped")
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
            candidates = listOf("fs.m", "p203fs.m"),
            // `WmModelView` survives as a plaintext dex string (classes10.dex).
            probe = { bridge ->
                bridge.findClass { matcher { usingStrings("WmModelView") } }
                    .firstOrNull()?.name
            },
            validate = { c ->
                c.declaredMethods.any {
                    it.name == "o" && it.parameterTypes.size == 4 &&
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
        val formatField = resolvePublicField(clazz, "B", "f40617B")?.apply { isAccessible = true }
            ?: run {
                DebugLog.w(TAG, "${clazz.name} model format field not found; brand logo-line skipped")
                return
            }
        val oMethod = CameraResolver.resolveMethod(
            scope = TAG, key = "wm_model_view_o", clazz = clazz,
            names = listOf("o"),
            shape = { m ->
                m.parameterTypes.size == 4 &&
                    m.parameterTypes[0] == String::class.java &&
                    m.parameterTypes[1] == String::class.java &&
                    m.parameterTypes[2] == java.lang.Boolean.TYPE &&
                    m.parameterTypes[3] == java.lang.Boolean.TYPE
            },
        ) ?: run {
            DebugLog.w(TAG, "${clazz.name}#o not found; brand logo-line skipped")
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
                val receiver = param.thisObject ?: return@before
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
                val receiver = param.thisObject ?: return@after
                runCatching { formatField.set(receiver, saved) }
            }
        }
        DebugLog.d(TAG, "brand logo-line hooked on ${clazz.name}#o()")
    }

    // ─── 6. (Optional) fake the LCC theme so LCC-gated flagship branches open too ──

    private fun hookLccTheme() {
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
        val clazz = CameraResolver.resolveClass(
            scope = TAG, key = "lcc_theme_facade", ctx = ctx,
            candidates = CONFIG_FACADE_CANDIDATES,
            validate = { CameraResolver.hasBooleanMethod(it, listOf("V")) },
        ) ?: run {
            DebugLog.w(TAG, "Je.c not resolved; LCC theme impersonation skipped")
            return
        }
        val vMethod = CameraResolver.resolveMethod(
            scope = TAG, key = "lcc_theme_v", clazz = clazz,
            names = listOf("V"),
            shape = { it.parameterTypes.isEmpty() && it.returnType == java.lang.Boolean.TYPE },
        ) ?: run {
            DebugLog.w(TAG, "${clazz.name}#V() LCC gate not found; theme impersonation skipped")
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
        DebugLog.d(TAG, "LCC theme impersonation hooked on ${clazz.name}#V()")
    }

    // ─── 7. Keep the 相机配色 (tint color) settings entry visible under LCC impersonation ─

    /**
     * `CameraCommonPreferenceFragment.addCustomizationPreferences` gates the 相机配色 entry on
     * `p497o9.a.f53945a.d().s()` (jadx aliases; real names `o9.a.f53945a.d().s()`). The holder
     * picks the provider from `Je/c.V()`: the LCC branch's provider returns false, hiding the
     * entry — on 6.6.000460.0 that provider was `Ox.g` with method `i()`, on 6.6.000510.0 it is
     * `Gt.a` with method `s()` (`Gt.a` implements `p9.f`, whose boolean `s()` is the gate;
     * verified in smali: `y9.c.d()` returns the `Gt.a` instance). Note the obfuscator REUSED
     * the name `Ox.g` for an unrelated state-list helper on 510, so the semantic check (a
     * boolean zero-arg `s`/`i` method) is what rejects the trap; a repurposed name degrades to
     * a logged skip, never a wrong hook. Forcing the gate true whenever the master switch is on
     * restores the entry; a genuinely-LCC device without the master switch keeps stock behaviour.
     */
    private fun hookLccCustomizationProvider() {
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
        val clazz = CameraResolver.resolveClass(
            scope = TAG, key = "lcc_provider", ctx = ctx,
            // Gt.a = 6.6.000510.0 provider; Ox.g = 6.6.000460.0 (repurposed on 510, rejected by
            // the validation).
            candidates = listOf("Gt.a", "Ox.g"),
            validate = { CameraResolver.hasBooleanMethod(it, listOf("s", "i")) },
        ) ?: run {
            DebugLog.w(TAG, "LCC customization provider not resolved; tint-color restore skipped")
            return
        }
        val gateMethod = CameraResolver.resolveMethod(
            scope = TAG, key = "lcc_provider_gate", clazz = clazz,
            names = listOf("s", "i"),
            shape = { it.parameterTypes.isEmpty() && it.returnType == java.lang.Boolean.TYPE },
        ) ?: run {
            DebugLog.w(TAG, "${clazz.name} tint-color gate method not found; restore skipped")
            return
        }
        deoptimize(gateMethod)
        gateMethod.hook("cam_restore_tint_color") {
            before { param ->
                if (enabled()) param.result = true
            }
        }
        DebugLog.d(TAG, "tint-color restore hooked on ${clazz.name}#${gateMethod.name}()")
    }

    // ─── 8. Keep this device's own focal lengths (焦段) while impersonating ─────────

    /**
     * After `Je/e.q()` returns a flagship, the bottom zoom line-up / mm labels come from Nezha.
     * Keep all capability booleans (flagship) but delegate the focal getters to the REAL device
     * config instance, so 焦段 stays the device's own. Gated on `KEY_CAMERA_IMPERSONATE` AND
     * `KEY_CAMERA_KEEP_FOCAL` (default true).
     */
    private fun hookKeepFocal() {
        val flagship = flagshipInstance() ?: return
        // Hook on the actual concrete flagship class so virtual dispatch resolves to our hook
        // (hooking the base C1143 would be skipped by Nezha/CommonFlagship overrides).
        val target = flagship.javaClass
        var hooked = 0
        for (name in FOCAL_GETTERS) {
            val method = runCatching { target.getMethod(name) }.getOrNull()
            if (method == null || method.parameterCount != 0) {
                DebugLog.d(TAG, "focal getter ${target.name}#$name() not found; skipped")
                continue
            }
            deoptimize(method)
            method.hook("cam_keep_focal_$name") {
                before { param ->
                    // SOE protection: never fall through to `proceed` and never let the
                    // reflective delegation throw. ezhooktool's proceed re-enters the still-hooked
                    // getter via Method.invoke -> infinite recursion at process start
                    // (MiviInfoContentProvider.onCreate touches these getters first).
                    if (!keepFocal()) { return@before }
                    val original = originalConfigInstance().get()
                    if (original != null) {
                        val delegated = runCatching {
                            // Delegate to the ORIGINAL device config's getter so 焦段 stays this
                            // device's own. Methods are cached (AGENTS.md hot-path rule).
                            originalFocalMethods(original)[name]?.invoke(original)
                        }.getOrNull()
                        if (delegated != null) { param.result = delegated; return@before }
                    }
                    // Always short-circuit: skipping with the flagship's own value is safe and
                    // guarantees `skipped=true` so `proceed` is never re-entered.
                    param.result = soeSafeFallback(method, param)
                }
            }
            hooked++
        }
        DebugLog.d(TAG, "keep-focal hooked on ${target.name}: $hooked/${FOCAL_GETTERS.size} getters")
    }

    /** Per-getter cached delegation methods resolved off the ORIGINAL device config's concrete class. */
    private val originalFocalMethodsCache = AtomicReference<Map<String, Method>?>()

    private fun originalFocalMethods(original: Any): Map<String, Method> {
        originalFocalMethodsCache.get()?.let { return it }
        synchronized(originalFocalMethodsCache) {
            originalFocalMethodsCache.get()?.let { return it }
            val resolved = FOCAL_GETTERS.associateWith { name ->
                runCatching { original.javaClass.getMethod(name) }
                    .getOrNull()
                    ?.takeIf { it.parameterCount == 0 }
            }.filterValues { it != null }.mapValues { it.value!! }
            originalFocalMethodsCache.set(resolved)
            return resolved
        }
    }

    // ─── 8b. Keep this device's own imaging identity while impersonating (变紫 fix) ───

    /**
     * Delegate the sensor/lens identity getters (O1/D/q1/r1/o0/S6/M/K2) back to the REAL device
     * config while impersonating, so colour-critical pipelines (Leica Classic + tele RAW through
     * gallery re-processing) feed the real sensor identity to MIVI/HAL CCM·WB selection instead of
     * the 17-Ultra calibration the impersonation would otherwise supply. This is the "徕卡经典 +
     * 长焦 → 相册后期处理变紫" fix (RESEARCH_LEICA_CLASSIC_PURPLE.md §6.1). Capability booleans
     * stay flagship, so no mode is lost. Gated on `KEY_CAMERA_IMPERSONATE` AND
     * `KEY_CAMERA_KEEP_IMAGING` (default on). No-op on a real nezha (delegating returns the
     * flagship's own values there) and when impersonation is off.
     */
    private fun hookImagingIdentity() {
        val flagship = flagshipInstance() ?: return
        val target = flagship.javaClass
        var hooked = 0
        for (name in IDENTITY_GETTERS) {
            val method = runCatching { target.getMethod(name) }.getOrNull()
            if (method == null || method.parameterCount != 0) {
                DebugLog.d(TAG, "imaging getter ${target.name}#$name() not found; skipped")
                continue
            }
            deoptimize(method)
            method.hook("cam_keep_imaging_$name") {
                before { param ->
                    // SOE protection: always set result / never throw -> `proceed` is never
                    // re-entered (which would recurse through the still-hooked getter).
                    if (!keepImaging() || deviceIsNezha()) { return@before }
                    val original = originalConfigInstance().get()
                    if (original != null) {
                        val delegated = runCatching {
                            originalDelegateMethods(original)[name]?.invoke(original)
                        }.getOrNull()
                        if (delegated != null) { param.result = delegated; return@before }
                    }
                    param.result = soeSafeFallback(method, param)
                }
            }
            hooked++
        }
        DebugLog.d(
            TAG,
            "keep-imaging hooked on ${target.name}: $hooked/${IDENTITY_GETTERS.size} getters"
        )
    }

    // ─── 8c. Mode gates: close the hardware-impossible ones, open the ones we now want ──

    /**
     * Mode-gate plan per impersonation target (see RESEARCH_MYRON_00_SYNTHESIS.md):
     *  - K100 Pro Max target (default): `y4()` stays true — it is the REGISTRY gate
     *    (`MasterLiveModuleEntry.support()` → mode 231 exists at all), true on C1200 and C1209
     *    alike; CAROUSEL placement is a different mechanism (the `M()[I` order array, see
     *    [hookMasterLiveModePlacement]). 街拍 (225) is unlocked per [Preferences]
     *    `.KEY_CAMERA_STREET_MODE` (see [hookStreetEnable] / [hookCompatStreetSupport]);
     *    `b6()` stays true (= the device's own native main-id scheme). Only 装备街拍
     *    (hard-opens cameras 13/7 that do not exist) and 传奇人像 (RAW+LUT reprocessing)
     *    stay closed.
     *  - Legacy Nezha target: `y4/a3` are delegated to the REAL config (hidden) by
     *    `KEY_CAMERA_GUARD_MODES` — EXCEPT while the user explicitly unlocks them: the
     *    MasterLive switch ([Preferences.KEY_CAMERA_MASTERLIVE_ENABLE], default on) suppresses
     *    the `y4` delegation and [hookMasterLiveSupportGate] forces the gate true, and the
     *    新街拍 mode suppresses the `a3` delegation while [hookStreetEnable] forces it true.
     *    Without that suppression the two hooks would fight over one Method in
     *    registration order. `b6` remains clampable via `KEY_CAMERA_GUARD_CAMERA_ID`.
     */
    private fun hookModeGuards() {
        val flagship = flagshipInstance() ?: run {
            DebugLog.w(TAG, "flagship instance not resolved; config mode guards skipped (compat street stays installed independently)")
            return
        }
        val target = flagship.javaClass
        // Legacy Nezha target only: hide the 17U hardware-dependent modes on non-flagships —
        // unless the corresponding unlock switch wants them (see the KDoc above).
        hookDelegateBoolean(target, "y4", "cam_guard_mode_masterlive") {
            guardModes() && targetIsNezha() && !masterliveEnabled()
        }
        hookDelegateBoolean(target, "a3", "cam_guard_mode_street") {
            guardModes() && targetIsNezha() && streetMode() != CameraStreetMode.MODE_NEW
        }
        hookDelegateBoolean(target, "b6", "cam_guard_camera_id") { guardCameraId() && targetIsNezha() }
        // 装备街拍 can never work without the 17U module-lens cameras (13/7) — always closed on
        // impersonated non-flagships (K100 Pro Max target yields false naturally; Nezha target
        // needs the clamp).
        hookFacadeEquipStreetGate()
        // 徕卡一瞬 guard+unlock moved to installHooks(): the hook must exist even when the
        // flagship instance fails to resolve, because the unlock half is master-independent.
        // 街拍 (225): 新街拍 = force a3() on the impersonated config (needs the master);
        // 兼容模式街拍 = hookCompatStreetSupport(), installed independently in [installHooks].
        hookStreetEnable()
        DebugLog.i(TAG, "mode guards installed on ${target.name}; street mode=${streetMode()}; masterlive unlock=${masterliveEnabled()}")
    }

    /** Delegate a zero-arg boolean getter on the flagship config class to the original config. */
    private fun hookDelegateBoolean(
        target: Class<*>,
        name: String,
        hookId: String,
        gate: () -> Boolean,
    ) {
        val method = runCatching {
            target.getMethod(name).takeIf {
                it.parameterCount == 0 && it.returnType == java.lang.Boolean.TYPE
            }
        }.getOrNull() ?: run {
            DebugLog.d(TAG, "guard getter ${target.name}#$name() not found; skipped")
            return
        }
        deoptimize(method)
        method.hook(hookId) {
            before { param ->
                // SOE protection: always short-circuit (set result) so `proceed` is never
                // re-entered and the reflective delegation never throws out of the callback.
                if (!enabled() || !gate() || deviceIsNezha()) { return@before }
                val original = originalConfigInstance().get()
                if (original != null) {
                    val delegated = runCatching {
                        originalDelegateMethods(original)[name]?.invoke(original)
                    }.getOrNull()
                    if (delegated != null) { param.result = delegated; return@before }
                }
                param.result = soeSafeFallback(method, param)
            }
        }
        DebugLog.d(TAG, "guard hooked on ${target.name}#$name()")
    }

    /**
     * 新街拍 (street unlock mode `"new"`, [Preferences.KEY_CAMERA_STREET_MODE]): force the
     * street-support gate `a3()` true on EVERY dispatch class [configDispatchClasses] reports —
     * the ORIGINAL device config's Method (the dispatch target whenever the impersonation
     * master is off) AND the flagship config's Method (the target while it is on). No REDMI
     * config ships `a3=true` (510: `a3()` = `instanceof C1172`, declared ONCE on the base
     * C1143), so street is invisible until this hook turns it on. The mode then opens the REAL
     * HAL role-0 main camera (myron role 0 = camera 2): `StreetModule` has no camera-id
     * override, so the module framework's role-0 path opens the real main camera; `b6()` is
     * natively true on myron and must NOT be clamped.
     *
     * METHOD UNION (deduplicated by Method identity): under the K100 Pro Max target C1151
     * inherits the base C1143#a3 WITHOUT override, so the original class (myron C1209) and the
     * flagship class resolve the SAME Method — ONE hook covers BOTH the impersonated dispatch
     * AND the master-off real-config dispatch. Under the legacy Nezha target the C1136 branch
     * OVERRIDES `a3()` (`instanceof C1189`), so the flagship class supplies its own C1136#a3
     * hook while the original class still supplies the base C1143#a3 — both are hooked, which
     * is what keeps the master-off path working under the legacy target too.
     *
     * Because `a3()` is the single switch behind BOTH street visibility and the quick-launch
     * re-classification (`p700u2.S` IntentParser: STILL_IMAGE + launch source
     * `launch_camera_and_take_photo` → candidate mode 225 when `a3() && J.f()`), forcing it
     * keeps every consumer consistent with a WORKING street mode — that consistency is exactly
     * what distinguishes this mode from `"compat"`.
     *
     * MASTER-INDEPENDENT (the "修复没打开伪装旗舰相机配置时街拍用不了" fix): the callback does
     * NOT read `enabled()` — 新街拍 works with the impersonation master ON and OFF, because the
     * base C1143#a3 that serves the real config is the SAME Method the K100 impersonation
     * dispatches to. Master-off usage: the entry lands in the 更多 overflow (no config `M()`
     * carries 225) and opens through the module framework's HAL role-0 path = the REAL main
     * camera; a camera restart is needed for visibility because the entry registry caches per
     * process. The callback is RAISE-ONLY: when the mode is off or compat it leaves the native
     * value untouched — it never LOWERS a native `a3()` (a genuinely street-capable device
     * keeps its own entry). While this mode is active, the Nezha-target `a3` delegation guard
     * in [hookModeGuards] suppresses itself, so the hooks on the same Method can never fight
     * in registration order.
     */
    private fun hookStreetEnable() {
        val methods = LinkedHashSet<Method>()
        for (clazz in configDispatchClasses()) {
            val method = runCatching {
                clazz.getMethod("a3").takeIf {
                    it.parameterCount == 0 && it.returnType == java.lang.Boolean.TYPE
                }
            }.getOrNull() ?: run {
                DebugLog.d(TAG, "street-enable getter ${clazz.name}#a3() not found; skipped")
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
                    logStreetApplyOnce("new(a3)")
                }
            }
            hooked++
        }
        DebugLog.i(
            TAG,
            "street-enable hooked on $hooked a3() dispatch method(s): " +
                methods.joinToString { "${it.declaringClass.name}#${it.name}" } + " (mode=new)"
        )
    }

    /**
     * 兼容模式街拍 (street unlock mode `"compat"`): force
     * `com.android.camera.features.mode.street.StreetModuleEntry.support()` true — the REAL
     * registry gate (`p666t3.a.d()` instantiates all module entries and keeps only those whose
     * `support()` is true) — WITHOUT depending on the capability-config impersonation at all.
     *
     * Use case: the flagship swap failed to resolve, the user turned the impersonation master
     * off, or another mod owns the config. Street still registers (it lands in the 更多
     * overflow grid, like on natively street-capable devices — no verified config `M()[I`
     * carries 225), and `StreetModule` has no camera-id override, so it opens through the
     * module framework's HAL role-0 path = the REAL main camera. Nothing else changes: `a3()`
     * stays native (quick-launch keeps its stock CAPTURE classification), 装备街拍 stays closed,
     * colour pipelines stay on the device's own calibration.
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
            candidates = listOf("com.android.camera.features.mode.street.StreetModuleEntry"),
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

    /**
     * Restore the Leica photography style (摄影风格 cv_type 徕卡经典 ↔ 徕卡生动) switcher. The 摄影风格
     * component (`C4164m` F3-gate at :148) and every top-bar / mode style entry gate on the
     * config's `F3()` (`X2()` additionally for the specific-capture path `capture/h0`) — `true`
     * on the CommonFlagship (Nezha) branch, `false` on the REDMI C1199 branch that C1151 (K100
     * Pro Max) inherits. The K100 Pro Max impersonation therefore drops the 徕卡经典/徕卡生动 switcher
     * that the legacy 17-Ultra impersonation had (agent + jadx verified: `C1136#F3()/X2()=true`,
     * `C1199#F3()=instanceof C1156=false`, `C1143#X2()=false`).
     *
     * Works WITH and WITHOUT the impersonation master, gated only on `KEY_CAMERA_LEICA_STYLE`
     * (default on). The hooks install on the UNION of the real device config class and the
     * flagship class ([configDispatchClasses]): while the master is ON those are exactly the
     * Methods the K100 impersonation dispatches to (C1151 inherits C1199#F3 / C1143#X2 without
     * override, so forcing them `true` brings the switcher back); with the master OFF the same
     * Methods resolve from the REAL device's own config class (myron, C1209, inheriting the same
     * C1199/C1143 getters), so the switcher appears without impersonation. The callback is
     * RAISE-ONLY — it never lowers a native value: with the user switch ON it forces `true`;
     * with the switch OFF it returns without touching the result, so a natively-true gate stays
     * native (the CommonFlagship/nezha branch declares its own true `F3()/X2()` overrides, so
     * the nezha impersonation target keeps its native switcher and never reaches this hook's
     * Methods). Legendary stays closed by default ([hookLegendarySupport] guard; unless
     * [Preferences.KEY_CAMERA_LEGENDARY_MOMENT] forces it open) and `M()` is
     * keep-imaging-delegated to C1209 (no 231 LCC-RAW), so no purple/RAW regression. Side effect: `f2.c.b()` adds the four Leica shutter
     * sounds when `F3()` is true — benign (the 8-entry list also removes the IOOBE that the
     * legacy `key_shutter_sound=4` used to hit) and bounded by the resident
     * [hookShutterSoundBoundary] clamp.
     */
    private fun hookLeicaStyle() {
        val dispatchClasses = configDispatchClasses()
        // Resolve F3/X2 from BOTH dispatch classes (original device config class + flagship
        // class), the union covering the getters the real config dispatches to while the
        // impersonation master is off and the (identical, inherited) ones the K100 target
        // dispatches to while it is on. Deduplicate by Method identity: `getMethod` on the two
        // classes returns the SAME base Method for inherited getters (e.g. C1199#F3 inherited
        // unchanged by C1151), which must be hooked exactly once.
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
                    // RAISE-ONLY: with the user switch on force the gate open; with it off
                    // leave the native result untouched (never lower a native true). The
                    // `if (!leicaStyle()) return@before; param.result = true` shape always
                    // either returns or sets the result, so `proceed` is never re-entered
                    // (SOE-safe, same pattern as hookKeepFocal / hookStreetEnable).
                    if (!leicaStyle()) return@before
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

    /** `Je.c#M()` (boolean) = `e.B4() && e.l2()` (jadx shows the config field as `f8420e`) — the 装备街拍 gate. */
    private fun hookFacadeEquipStreetGate() {
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
        val clazz = CameraResolver.resolveClass(
            scope = TAG, key = "equip_street_facade", ctx = ctx,
            candidates = CONFIG_FACADE_CANDIDATES,
            validate = { CameraResolver.hasBooleanMethod(it, listOf("M")) },
        ) ?: run {
            DebugLog.w(TAG, "Je.c not resolved; equip-street guard skipped")
            return
        }
        val mMethod = CameraResolver.resolveMethod(
            scope = TAG, key = "equip_street_gate", clazz = clazz,
            names = listOf("M"),
            shape = { it.parameterTypes.isEmpty() && it.returnType == java.lang.Boolean.TYPE },
        ) ?: run {
            DebugLog.w(TAG, "${clazz.name}#M() not found; equip-street guard skipped")
            return
        }
        deoptimize(mMethod)
        mMethod.hook("cam_guard_mode_equipstreet") {
            before { param ->
                if (!enabled() || deviceIsNezha()) return@before
                val original = originalConfigInstance().get() ?: return@before
                param.result = invokeBoolean(original, "B4") && invokeBoolean(original, "l2")
            }
        }
        DebugLog.d(TAG, "equip-street guard hooked on ${clazz.name}#M()")
    }

    /**
     * 徕卡一瞬 (Leica Moment, camera mode id 256, jadx `LegendaryEnter` — older builds
     * mislabelled it 传奇人像; the zh-CN string resource for its mode item is 徕卡一瞬) — one
     * hook serving two opposite, mutually exclusive behaviours:
     *
     *  - GUARD (stock since the Nezha-target era): with the impersonation master on a
     *    non-Nezha target and [Preferences.KEY_CAMERA_LEGENDARY_MOMENT] off, force
     *    `support()` false so the RAW/re-processing pipeline behind the mode stays closed.
     *  - UNLOCK ([Preferences.KEY_CAMERA_LEGENDARY_MOMENT], default OFF): raise `support()`
     *    to true so the entry registry (`p666t3.a.d()`, support()-filtered and cached per
     *    process) registers mode 256 into the 更多 overflow grid. The stock gate is
     *    `Je.c.W0() && Je.c.V()` = config-is-Nezha && LCC theme (`ro.theme_customize`),
     *    false on every non-flagship non-LCC device regardless of the impersonation master,
     *    which is exactly what this switch overrides. Needs a camera restart after toggling
     *    (the registry caches); the unlock wins over the guard by construction — the two
     *    branches are exclusive in the same callback, so hooks can never fight in order.
     */
    private fun hookLegendarySupport() {
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
        val clazz = CameraResolver.resolveClass(
            scope = TAG, key = "legendary_enter", ctx = ctx,
            candidates = listOf("com.android.camera.features.mode.legendary.LegendaryEnter"),
            // `isSupportLegendaryMode` survives as a plaintext dex string (classes.dex).
            probe = { bridge ->
                bridge.findClass { matcher { usingStrings("isSupportLegendaryMode") } }
                    .firstOrNull()?.name
            },
            validate = { CameraResolver.hasBooleanMethod(it, listOf("support")) },
        ) ?: run {
            DebugLog.w(TAG, "LegendaryEnter not resolved; legendary guard skipped")
            return
        }
        val support = CameraResolver.resolveMethod(
            scope = TAG, key = "legendary_support", clazz = clazz,
            names = listOf("support"),
            shape = { it.parameterTypes.isEmpty() && it.returnType == java.lang.Boolean.TYPE },
        ) ?: run {
            DebugLog.w(TAG, "${clazz.name}#support() not found; legendary guard skipped")
            return
        }
        deoptimize(support)
        support.hook("cam_guard_mode_legendary") {
            after { param ->
                when {
                    legendaryMomentUnlock() -> param.result = true
                    enabled() && !deviceIsNezha() -> param.result = false
                }
            }
        }
        DebugLog.d(TAG, "legendary guard hooked on ${clazz.name}#support()")
    }

    /**
     * 智能构图 setting unlock ([Preferences.KEY_CAMERA_SMART_COMPOSITION], default OFF).
     * The 设置→拍照 entry `pref_camera_crop_preferred_key` is added only while the device
     * config's `D3()` reports true — declared once on the config base as
     * `return this instanceof <REDMI-flagship marker>` and overridden per branch, so devices
     * outside that branch (this one: `com.mi.device.Myron`) ship it false natively AND under
     * the K100 Pro Max impersonation. The hook raises `D3()` on the UNION of dispatch classes
     * ([configDispatchClasses] plus the config BASE class taken from the factory's static
     * cache field type), deduplicated by Method identity — the same pattern as
     * [hookLeicaStyle]. RAISE-ONLY and live-read: with the switch off the native value is
     * untouched; turning it on shows the entry (reopen the settings page) and consistently
     * enables the capture-time consumers of the same gate.
     */
    private fun hookSmartComposition() {
        val classes = LinkedHashSet<Class<*>>()
        classes.addAll(configDispatchClasses())
        // Always include the config BASE class: the factory's static cache field is typed to
        // it (the same invariant [hookConfigFactory]'s structural fallback relies on), and
        // `getMethod` from it resolves the base declaration every non-overriding subclass
        // dispatches to — even when neither instance could be built.
        runCatching {
            resolveClass(*CONFIG_FACTORY_CLASS_CANDIDATES.toTypedArray())
                ?.getDeclaredField("b")
                ?.takeIf { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                ?.type?.let { classes.add(it) }
        }.onFailure { t ->
            DebugLog.w(TAG, "config base class unavailable for smart-composition union", t)
        }
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
    private const val SMART_COMPOSITION_TITLE_RES = "h24"
    private const val SMART_COMPOSITION_SUMMARY_RES = "h23"

    /** Plaintext class name of the capture-settings fragment (stable across verified builds). */
    private const val SETTINGS_CAPTURE_FRAGMENT =
        "com.android.camera.fragment.settings.CameraCapturePreferenceFragment"

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
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
        val clazz = CameraResolver.resolveClass(
            scope = TAG, key = "settings_capture_fragment", ctx = ctx,
            candidates = listOf(SETTINGS_CAPTURE_FRAGMENT),
            validate = { c ->
                c.declaredMethods.any { it.name == "addPhotoPreferences" && it.parameterCount == 0 }
            },
        ) ?: run {
            DebugLog.w(TAG, "$SETTINGS_CAPTURE_FRAGMENT not resolved; top-row smart composition skipped")
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
            val titleRes = res.getIdentifier(SMART_COMPOSITION_TITLE_RES, "string", PACKAGE)
            val summaryRes = res.getIdentifier(SMART_COMPOSITION_SUMMARY_RES, "string", PACKAGE)
            if (titleRes == 0 || summaryRes == 0) {
                DebugLog.w(TAG, "smart-composition string resources unresolved " +
                    "($SMART_COMPOSITION_TITLE_RES=$titleRes, $SMART_COMPOSITION_SUMMARY_RES=$summaryRes); top row skipped")
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
     * the real dex name is the short letter (`u`; jadx renders it `f13393u` because it
     * collides with a root-package name). Validated as a static boolean before use.
     */
    private val CAI_FLAG_FIELD_CANDIDATES = listOf("u", "f13393u")

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
            candidates = listOf("Qa.b"),
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

    /** Shape contract of the adaptive-lens gate pair ([uniqueCapabilityMethod] plus sameness). */
    private fun isAdaptiveLensUtil(clazz: Class<*>): Boolean {
        val near = uniqueCapabilityMethod(clazz, "g5") ?: return false
        val tele = uniqueCapabilityMethod(clazz, "i5") ?: return false
        // Both gates take the SAME capabilities object type; a repurposed name pair would
        // almost certainly diverge here.
        return near.parameterTypes.contentEquals(tele.parameterTypes)
    }

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
        var hooked = 0
        for (name in listOf("g5", "i5")) {
            val method = uniqueCapabilityMethod(clazz, name) ?: continue
            deoptimize(method)
            method.hook("cam_adaptive_lens_$name") {
                after { param ->
                    if (!adaptiveLensUnlock()) return@after
                    param.result = true
                }
            }
            hooked++
        }
        if (hooked == 0) {
            DebugLog.w(TAG, "adaptive-lens gates not resolvable on ${clazz.name}; skipped")
        } else {
            DebugLog.i(TAG, "adaptive-lens hooked on $hooked gate(s) of ${clazz.name}")
        }
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
     * Front-load 实况运镜 (MasterLive, mode id 231) into the impersonated config's mode
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
     * `KEY_CAMERA_MASTERLIVE_ENABLE` only — NOT on the impersonation master (master-off
     * MasterLive must front the base `M()[I` the real config dispatches to, see below);
     * inert on a native nezha device and when the array already contains 231.
     *
     * MASTER-OFF (2026-08-26, "没打开伪装旗舰相机配置时没有能用的实况运镜"): the real device
     * config (C1209 myron) INHERITS the base C1143#M() ({167,175,232,233,234,254} — no 231),
     * so with the impersonation master off the running camera dispatches to that base Method
     * and it must be fronted as well. The hook is therefore installed on the union of
     * [configDispatchClasses] (the ORIGINAL class's `M()` plus the flagship class's `M()`,
     * deduplicated by Method identity so the same Method is never hooked twice). Base-class
     * hooks are safe: ONLY classes that do NOT override `M()` dispatch to the base Method, so
     * a real flagship's own `M()` override is never touched.
     */
    private fun hookMasterLiveModePlacement() {
        // Installed under EITHER target AND with the master off: the nezha config fronts 231
        // natively, and CameraIdentity.frontMasterLiveMode is a no-op for arrays that already
        // contain it, so this is inert there — but it keeps the fronting available whenever a
        // dispatch class's own M() lacks 231 (the K100 C1151 override and the base C1143 the
        // real config inherits both do).
        val methods = LinkedHashSet<Method>()
        for (target in configDispatchClasses()) {
            runCatching { target.getMethod("M") }
                .getOrNull()
                ?.takeIf { it.parameterTypes.isEmpty() && it.returnType == IntArray::class.java }
                ?.let { methods.add(it) }
        }
        if (methods.isEmpty()) {
            DebugLog.w(TAG, "config M()[I not found on dispatch classes; masterlive placement skipped")
            return
        }
        for (method in methods) {
            deoptimize(method)
            method.hook("cam_masterlive_mode_front_${method.declaringClass.name}") {
                after { param ->
                    if (!masterliveEnabled()) return@after
                    val fronted = CameraIdentity.frontMasterLiveMode(param.result as? IntArray)
                    if (fronted != null) param.result = fronted
                }
            }
            DebugLog.d(TAG, "masterlive placement hooked on ${method.declaringClass.name}#M()")
        }
    }

    /**
     * MasterLive (实况运镜) REGISTRY gate: force the config's `y4()` true while
     * [Preferences.KEY_CAMERA_MASTERLIVE_ENABLE] is on — with AND without the impersonation
     * master.
     *
     * WHY THIS EXISTS (2026-08-22, "没开 k100 配置时实况运镜依旧用不了"): under the legacy
     * Nezha target with `KEY_CAMERA_GUARD_MODES` on (its default), `hookModeGuards` delegated
     * `y4()` back to the REAL device config — false on myron — so
     * `MasterLiveModuleEntry.support()` stayed false and mode 231 never registered, no matter
     * what the ordering hooks did. The unlock switch suppresses that delegation (see
     * [hookModeGuards]) and this hook actively pins the gate true, so the mode registers under
     * EVERY target.
     *
     * MASTER-OFF (2026-08-26, "没打开伪装旗舰相机配置时没有能用的实况运镜"): the `y4()`
     * dispatch split is per-class — the K100 flagship OVERRIDES it (C1151, native true), while
     * the real device config (C1209 myron) INHERITS the base C1143#y4 (`instanceof C1214` →
     * false). With the impersonation master off the running camera dispatches to the base
     * Method, so this hook installs the same RAISE-ONLY callback there too (both Methods are
     * hooked; deduplicated by identity when they coincide — the Flagship class's own y4 hook
     * stays as-is for impersonated dispatch). Base-class hooks are safe: ONLY classes that do
     * NOT override `y4()` dispatch to the base Method, so a real flagship's own override is
     * never touched. The gate is `masterliveEnabled()` ONLY (no impersonation master): when
     * the switch is off nothing is raised (the native false on myron is left untouched; a
     * genuinely-capable device's native true is never lowered). `y4()` has seven consumers on
     * 510 (module entry, first-run guide, capture-method settings rows, special-mode
     * description list) and all of them describe a flagship capability the user wants to
     * unlock, so one forced gate keeps them all coherent.
     */
    private fun hookMasterLiveSupportGate() {
        val methods = LinkedHashSet<Method>()
        flagshipInstance()?.javaClass?.let { target ->
            runCatching { target.getMethod("y4") }
                .getOrNull()
                ?.takeIf { it.parameterCount == 0 && it.returnType == java.lang.Boolean.TYPE }
                ?.let { methods.add(it) }
        }
        originalConfigInstance().get()?.javaClass?.let { target ->
            runCatching { target.getMethod("y4") }
                .getOrNull()
                ?.takeIf { it.parameterCount == 0 && it.returnType == java.lang.Boolean.TYPE }
                ?.let { methods.add(it) }
        }
        if (methods.isEmpty()) {
            DebugLog.w(TAG, "masterlive registry getter y4() not found on dispatch classes; support gate skipped")
            return
        }
        for (method in methods) {
            deoptimize(method)
            method.hook("cam_masterlive_support_gate_${method.declaringClass.name}") {
                after { param ->
                    if (!masterliveEnabled()) return@after
                    if ((param.result as? Boolean) == true) return@after
                    param.result = true
                    if (mlGateLogged.getAndSet(true) == false) {
                        DebugLog.i(TAG, "masterlive registry gate y4() forced true on ${method.declaringClass.name}")
                    }
                }
            }
            DebugLog.i(TAG, "masterlive support gate hooked on ${method.declaringClass.name}#y4()")
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
     * `Collections.unmodifiableMap(config.q0())` once per process (`q()`, :86/:310): the K100
     * flagship OVERRIDES `q0()` with the REDMI table (types "0"/"2"/"3", roles
     * ultra/wide/Standalone, ends 15x — SAFE), while the real device config INHERITS the base
     * `q0()` which returns **null** — no effect list. Under the impersonation master the
     * consumer sees the flagship's REDMI table through dispatch; with the master OFF the real
     * config's null table leaves MasterLive without a usable effect list.
     *
     * The hook therefore installs on the `q0()` Method of EVERY dispatch class
     * ([configDispatchClasses]: the original device config class for master-off, the flagship
     * instance's class — whose own override serves master-on — deduplicated by Method
     * identity), and the after-callback (a) borrows the K100 table when the original returned
     * null (through [resolveK100Config] ONLY — never the Nezha/CommonFlagship fallback, whose
     * 12.9x tele table crashes myron), then (b) merges a synthesized 红毯运镜 type-"1" entry
     * ([injectRedCarpetEntry]) while `KEY_CAMERA_MASTERLIVE_RED_CARPET` is on. The 红毯 entry
     * is a CLONE of the proven-working linear entry with a changed type id and cleared default
     * flag — every decrypted role/range string stays byte-identical to what already resolves,
     * so nothing new is decoded and the panel/guide UI pick the entry up natively (they are
     * type-switch driven). Gated on [Preferences.KEY_CAMERA_MASTERLIVE_ENABLE].
     */
    private fun hookMasterLiveRealEffectTable() {
        if (configDispatchClasses().isEmpty()) {
            DebugLog.w(TAG, "no config dispatch classes; masterlive effect table hook skipped")
            return
        }
        val seen = HashSet<Method>()
        var hooked = 0
        for (clazz in configDispatchClasses()) {
            val method = runCatching { clazz.getMethod("q0") }
                .getOrNull()
                ?.takeIf { it.parameterTypes.isEmpty() && it.returnType != java.lang.Void.TYPE }
                ?: continue
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
                            DebugLog.i(TAG, "masterlive effect table borrowed from K100 config (real config q0() is null)")
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
            DebugLog.w(TAG, "no q0() Method resolved on any dispatch class; masterlive effect table hook skipped")
        } else {
            DebugLog.i(TAG, "masterlive effect table hook installed on $hooked q0() Method(s)")
        }
    }

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

    /** Logs the effect-table borrow / unavailability ONCE per process, not per dispatch. */
    private val mlTableBorrowLogged = AtomicReference(false)
    private val mlTableUnavailableLogged = AtomicReference(false)

    /**
     * Resolve + cache the K100 (REDMI) config instance and its `q0()` Method exactly like the
     * k100 target path does — [resolveK100Config] only, NEVER the Nezha/CommonFlagship
     * fallback (the 17U table ends 12.9x and crashes myron's camera) — then invoke `q0()` for
     * the borrowed effect table.
     */
    private fun borrowK100EffectTable(): Any? {
        k100EffectTableInstance.get()?.let { instance ->
            k100EffectTableMethod.get()?.let { method ->
                return runCatching { method.invoke(instance) }.getOrNull()
            }
        }
        synchronized(k100EffectTableInstance) {
            k100EffectTableInstance.get()?.let { instance ->
                k100EffectTableMethod.get()?.let { method ->
                    return runCatching { method.invoke(instance) }.getOrNull()
                }
            }
            val loader = classLoader ?: run {
                logEffectTableUnavailableOnce("no class loader")
                return null
            }
            val resolver = resolveSourceNameResolver(loader) ?: run {
                logEffectTableUnavailableOnce("source-name resolver unavailable")
                return null
            }
            val k100 = resolveK100Config(loader, resolver) ?: run {
                logEffectTableUnavailableOnce("K100 config not resolved by candidates or source probes")
                return null
            }
            val q0Method = runCatching {
                k100.javaClass.getMethod("q0").takeIf {
                    it.parameterTypes.isEmpty() && it.returnType != java.lang.Void.TYPE
                }
            }.getOrNull() ?: run {
                logEffectTableUnavailableOnce("${k100.javaClass.name}#q0() not found")
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
     * (`Preferences.KEY_CAMERA_MASTERLIVE_FULL_FOCAL`, default on; research:
     * RESEARCH_MYRON_12_MASTERLIVE_FOCAL_STRIP.md).
     *
     * The 焦段 strip inside 实况运镜 (`FragmentZoomToggle`'s `ZoomRatioToggleView` row) reads
     * the config's per-mode zoom stops `v1()` keyed by mode id — `j.U(231,false)` → `j.S` →
     * `j.R` → `p723ur.i#q(231,…)` → `v1().get(231)`. The real myron config has NO 231 key, so
     * the camera falls back to the hardcoded `{1.0x, 2.0x}` pair and 超清实况 shows only 1x/2x
     * where a full unlock shows the whole line-up. This hook appends
     * `231 → [CameraIdentity.MASTER_LIVE_FOCAL_STOPS]` (the K100 Pro Max stops {0.7, 1, 2, 5,
     * 10} — bit-identical sensor axis to myron and exactly myron's real optics) to the result
     * whenever the key is absent; an existing key is never touched and no other mode's stops
     * change. Installs on the `v1()` Method of EVERY dispatch class ([configDispatchClasses],
     * dedup by Method identity), which covers master-off AND both impersonation paths: the
     * keep-focal delegation invokes the original class Method, so its result passes through
     * this hook too. Gated on [Preferences.KEY_CAMERA_MASTERLIVE_ENABLE] and the switch.
     */
    private fun hookMasterLiveFullFocal() {
        if (configDispatchClasses().isEmpty()) {
            DebugLog.w(TAG, "no config dispatch classes; masterlive full focal hook skipped")
            return
        }
        val seen = HashSet<Method>()
        var hooked = 0
        for (clazz in configDispatchClasses()) {
            val method = runCatching { clazz.getMethod("v1") }
                .getOrNull()
                ?.takeIf { it.parameterTypes.isEmpty() && it.returnType != java.lang.Void.TYPE }
                ?: continue
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
                    val copy = runCatching { clone.invoke(array) as? SparseArray<Any> }
                        .getOrNull() ?: return@after
                    copy.put(
                        CameraIdentity.MASTER_LIVE_MODE_ID,
                        // Mirror the value type of the existing table (boxed on every verified
                        // build); an empty table gets the boxed default.
                        CameraIdentity.masterLiveFocalStops(
                            if (array.size() > 0) array.valueAt(0) else null
                        )
                    )
                    param.result = copy
                    if (fullFocalLogged.getAndSet(true) == false) {
                        DebugLog.i(TAG, "masterlive full focal: v1()[231] = ${CameraIdentity.MASTER_LIVE_FOCAL_STOPS.contentToString()}")
                    }
                }
            }
            hooked++
        }
        if (hooked == 0) {
            DebugLog.w(TAG, "no v1() Method resolved on any dispatch class; masterlive full focal hook skipped")
        } else {
            DebugLog.i(TAG, "masterlive full focal hook installed on $hooked v1() Method(s)")
        }
    }

    /** Logs the first full-focal substitution ONCE per process, not per call. */
    private val fullFocalLogged = AtomicReference(false)

    /**
     * Resolve `u2.P` (ComponentModuleList; jadx alias `p700u2.P`, 460 build `u2.U`) once for
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
                candidates = listOf("u2.P", "u2.U"),
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
     * mode order goes through `u2.P.y(Q)[I` / its zero-arg wrapper `x()[I`
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
     * [hookMasterLiveSupportEntry]. Gated on `KEY_CAMERA_MASTERLIVE_ENABLE` only — NOT on
     * the impersonation master: `u2.P` is config-independent, so the funnel correction applies
     * under master-off MasterLive as well.
     */
    private fun hookMasterLiveOrderFunnel() {
        val clazz = resolveComponentModuleList() ?: run {
            DebugLog.w(TAG, "u2.P (ComponentModuleList) not resolved; masterlive order funnel skipped")
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
                if (!masterliveEnabled()) return@after
                val placed = CameraIdentity.placeMasterLiveModeBeforeMarker(param.result as? IntArray)
                if (placed != null) param.result = placed
            }
        }
        DebugLog.d(TAG, "masterlive order funnel hooked on ${clazz.name}#y(Q)")
    }

    /**
     * MasterLive carousel placement, part 3 (defense-in-depth): force `u2.P.E(231)` true.
     *
     * When the config's `L2()` is true (base impl `!(this instanceof C1198)`, C1174.java:488 —
     * true on the K100 target), `E(int)` ignores `x()` and consults the PERSISTED pref string
     * `all_support_mode_list` instead (`p700u2/P.java:511-550`); a list persisted before
     * MasterLive was visible (or before impersonation was enabled) lacks 231 and fails the
     * before-the-marker check. Its only two consumers are the retain-camera-mode decision
     * (`u2.S#a`, smali `Lu2/P;->E(I)Z` call sites) and the new-user mode guide
     * (`com.android.camera.guide.b`) — neither hides the mode, but both misbehave for exactly
     * the mode we inject above. Forcing E(231)=true while our placement is active keeps them
     * consistent; every other id computes normally. Gated on `KEY_CAMERA_MASTERLIVE_ENABLE`
     * only — NOT on the impersonation master (config-independent hook, applies under
     * master-off MasterLive too). `z(231)==231` (no legacy alias maps to 231,
     * `P.java:343-395`), so the id check is exact.
     */
    private fun hookMasterLiveSupportEntry() {
        val clazz = resolveComponentModuleList() ?: run {
            DebugLog.w(TAG, "u2.P (ComponentModuleList) not resolved; masterlive support entry skipped")
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
                if (!masterliveEnabled()) return@after
                if ((param.result as? Boolean) != false) return@after
                if ((param.args?.getOrNull(0) as? Int) != CameraIdentity.MASTER_LIVE_MODE_ID) return@after
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
     * MASTER-OFF (2026-08-26): the role adapter is config-independent, so the fallback must
     * also work for master-off MasterLive. The gate is the user's own
     * `KEY_CAMERA_MASTERLIVE_TELE_FALLBACK` switch (default on) alone — no impersonation
     * master.
     *
     * ON-DEVICE RESOLUTION FIX (2026-08-26, RESEARCH_MYRON_ONDEVICE_EVIDENCE §5.1): the
     * DexKit probe previously used `"Camera2CompatAdapterRole"`, but the class's log-tag
     * constant in the dex is `MCAM_Camera2CompatAdapterRole` (MCAM_ prefix) — the probe never
     * matched and the hook was skipped on-device ("Camera2CompatAdapterRole not resolved"). The
     * probe string is now `MCAM_...`; the method-shape validation (int-returning `M()`) is
     * unchanged.
     */
    private fun hookMasterLiveTeleFallback() {
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
        val clazz = CameraResolver.resolveClass(
            scope = TAG, key = "role_adapter", ctx = ctx,
            candidates = listOf("u6.e", "p703u6.e"),
            // `MCAM_Camera2CompatAdapterRole` is the class's plaintext log-tag constant in the
            // dex (verified on-device; the bare `Camera2CompatAdapterRole` probe never matched,
            // see RESEARCH_MYRON_ONDEVICE_EVIDENCE §5.1).
            probe = { bridge ->
                bridge.findClass { matcher { usingStrings("MCAM_Camera2CompatAdapterRole") } }
                    .firstOrNull()?.name
            },
            validate = { c -> c.declaredMethods.any { it.name == "M" && it.returnType == java.lang.Integer.TYPE } },
        ) ?: run {
            DebugLog.w(TAG, "Camera2CompatAdapterRole (u6.e) not resolved; masterlive tele fallback skipped")
            return
        }
        val mMethod = CameraResolver.resolveMethod(
            scope = TAG, key = "role_adapter_m", clazz = clazz,
            names = listOf("M"),
            shape = { it.parameterTypes.isEmpty() && it.returnType == java.lang.Integer.TYPE },
        ) ?: run {
            DebugLog.w(TAG, "${clazz.name}#M() not found; masterlive tele fallback skipped")
            return
        }
        deoptimize(mMethod)
        mMethod.hook("cam_masterlive_tele_fallback") {
            after { param ->
                if (!masterliveTeleFallback() || deviceIsNezha()) return@after
                if ((param.result as? Int) != -1) return@after
                val receiver = param.thisObject ?: return@after
                teleCameraId(receiver)?.let { param.result = it }
            }
        }
        DebugLog.d(TAG, "masterlive tele fallback hooked on ${clazz.name}#M()")
    }

    /** Cached `r()` (role-20 tele) method on the role adapter (`u6.e`, jadx `p703u6/e`). */
    private val teleCameraIdMethod = AtomicReference<Method?>()

    private fun teleCameraId(receiver: Any): Int? {
        val method = teleCameraIdMethod.get() ?: synchronized(teleCameraIdMethod) {
            teleCameraIdMethod.get() ?: runCatching {
                receiver.javaClass.getMethod("r").takeIf { it.parameterCount == 0 }
            }.getOrNull()?.also { teleCameraIdMethod.set(it) }
        } ?: return null
        return runCatching { (method.invoke(receiver) as? Int)?.takeIf { it != -1 } }
            .getOrNull()
    }

    /**
     * MasterLive (实况运镜, module 231) op-mode safety net (`KEY_CAMERA_MASTERLIVE_OPMODE_SAFE`,
     * default off). On Qualcomm myron the MasterLive session would normally run op-mode 1
     * (CONSTRAINED_HIGH_SPEED, `U3/p#i` case 0); if that mode does not produce frames on the HAL
     * the capture never completes and the mode appears frozen. Forcing the MasterLive branch to
     * ALGO_UP_SAT (36866) uses a plain session that always produces frames; the K100 Pro Max effect
     * table has no 120fps type, so no high-speed semantics are lost. Class `U3/p` is a real dex
     * name (verified in the on-device APK). Enable only if on-device logs confirm op-mode 1 stalls.
     *
     * MASTER-OFF (2026-08-26): `U3/p` is config-independent, so the safety net must also apply
     * to master-off MasterLive. The gate is the user's own
     * `KEY_CAMERA_MASTERLIVE_OPMODE_SAFE` switch (default off) + `!deviceIsNezha()` — no
     * impersonation master.
     */
    private fun hookMasterLiveOpModeSafe() {
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
        val clazz = CameraResolver.resolveClass(
            scope = TAG, key = "masterlive_module", ctx = ctx,
            candidates = listOf("U3.p"),
            // `MasterLiveModuleDevice` survives as a plaintext dex string (classes.dex).
            probe = { bridge ->
                bridge.findClass { matcher { usingStrings("MasterLiveModuleDevice") } }
                    .firstOrNull()?.name
            },
            validate = { c -> c.declaredMethods.any { it.name == "i" && it.returnType == java.lang.Integer.TYPE } },
        ) ?: run {
            DebugLog.w(TAG, "U3.p (MasterLiveModuleDevice) not resolved; op-mode safe hook skipped")
            return
        }
        val iMethod = clazz.declaredMethods.firstOrNull {
            it.name == "i" && it.returnType == java.lang.Integer.TYPE
        } ?: run {
            // NOTE: the real method is `U3/p.i(p841y3.w)` — it takes ONE parameter, so match by
            // name + return type only (verified in jadx U3/p.java:168-202). Matching a zero-arg
            // `i()` left the hook uninstalled on-device ("U3/p#i() not found").
            DebugLog.w(TAG, "U3/p#i() not found; op-mode safe hook skipped")
            return
        }
        val bField = runCatching {
            clazz.getDeclaredField("b").apply { isAccessible = true }
        }.getOrNull()
        val masterLiveB = bField != null

        deoptimize(iMethod)
        iMethod.hook("cam_masterlive_opmode_safe") {
            before { param ->
                if (!opModeSafe() || deviceIsNezha()) return@before
                val thisObj = param.thisObject ?: return@before
                // `U3/p.b` is a 0/1 module selector, NOT a module id: `getModuleId()` returns 231
                // for b==0 (MasterLive) and 167 for b==1, so the guard is b==0. (The previous
                // `b != 231` check could never fire and also the hook never installed.)
                val moduleIndex = bField?.let { runCatching { it.getInt(thisObj) }.getOrNull() }
                if (masterLiveB && moduleIndex != 0) return@before
                param.result = 36866
            }
        }
        DebugLog.d(TAG, "masterlive op-mode safety hook installed on ${clazz.name}#i()")
    }

    /**
     * MasterLive (实况运镜) circular-encoder size pin — experimental probe for the
     * "left green / right repeated lines" motion-photo artifact
     * (`KEY_CAMERA_MASTERLIVE_CODEC_PIN`, default off; mechanism [M0] of
     * RESEARCH_MYRON_09_MASTERLIVE_ARTIFACT.md, all line refs below are 6.6.000510.0).
     *
     * The LiveShot circular encoder (real dex names `ym.d` = CircularVideoEncoder, `ym.f` =
     * V2 override — jadx displays them as `p859ym.d`/`p859ym.f`) receives the per-shot
     * preview-snapshot size on every capture: `CircularMediaRecorderV2` (real `xm.c`, jadx
     * `p824xm.c`, `j()/k()` :216-231) calls `dVar.E(size)` (the encoder field `b`), and the
     * base `ym.d#E(Size)` (:134-138, logs "updateCodecSize E size = …") stores it; the V2
     * subclass `ym.f` (override :248-257, MTK branch then `super.E(size)`) reconfigures
     * the codec when the stored size differs from the current format, while the GL render
     * canvas stays at the construction size (`zm.c:342-346`, FBO fixed at `zm/b.g`).
     * Under a forced ALGO_UP_SAT session (`KEY_CAMERA_MASTERLIVE_OPMODE_SAFE`) the preview
     * snapshot size and the construction video size diverge, so every capture rewrites the
     * codec format and the input surface ends up partially unwritten — NV12 zero-fill decodes
     * to pure green — with edge clamp/wrap producing the repeated lines.
     *
     * The hook substitutes the incoming `Size` argument with the encoder's INITIAL format size
     * (final int fields `A`/`B`, `ym.d:23-26`, read from the MediaFormat at
     * construction) whenever it differs ([CameraCodecSizePin.pinnedSize]); the original still
     * runs, so the stored size can never diverge from what the render canvas was built for and
     * the rewrite path becomes a no-op. Matching sizes pass through untouched (normal case).
     *
     * Resolution is build-agnostic: L1 candidates are the verified real dex names (`ym.d`
     * base, `ym.f` V2 override, `ym.e` 460-era; the jadx `p859ym.*` spellings never exist in
     * the dex and are kept only as cheap fallbacks), L2 DexKit iterates the classes carrying
     * the plaintext `updateCodecSize` log string and takes the first whose `E(Size)` shape
     * validates (a sibling class — e.g. the GL render thread — can reference the string
     * without declaring `E`), and the method is matched by shape
     * (one `android.util.Size` parameter, void return). Gated on
     * `KEY_CAMERA_MASTERLIVE_CODEC_PIN` only — NOT on the impersonation master (master-off
     * MasterLive needs the pin too). Requires a camera restart after changing.
     */
    private fun hookMasterLiveCodecPin() {
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
        val clazz = CameraResolver.resolveClass(
            scope = TAG, key = "circular_video_encoder", ctx = ctx,
            // REAL dex names on the verified builds: 510 `ym.d`/`ym.f`/`ym.e`
            // (`p859ym.d` etc. are jadx DISPLAY aliases that never exist in the dex — the
            // 460/510 mapping rule strips the `p<digits>` prefix: p859ym -> ym, p824xm -> xm).
            // The jadx-alias spellings are kept as cheap fallbacks for unverified builds.
            candidates = listOf("ym.d", "ym.f", "ym.e", "p859ym.d", "p859ym.f", "p859ym.e"),
            // "updateCodecSize" is plaintext in the 510 dex string pool (classes8.dex,
            // byte-verified 2026-08-27). Iterate ALL classes carrying it and pick the first
            // whose E(Size) shape validates — the first dex-order match can be a sibling
            // class (e.g. the GL render thread) that references the string but declares no
            // `E(Size)`.
            probe = { bridge ->
                bridge.findClass { matcher { usingStrings("updateCodecSize") } }
                    .firstOrNull { cd ->
                        ctx.loadOrNull(cd.name)?.let { resolveUpdateCodecSizeMethod(it, quiet = true) != null } == true
                    }?.name
            },
            validate = { c -> resolveUpdateCodecSizeMethod(c, quiet = true) != null },
        ) ?: run {
            DebugLog.w(TAG, "CircularVideoEncoder (ym.d) not resolved; masterlive codec pin skipped")
            return
        }
        val method = resolveUpdateCodecSizeMethod(clazz) ?: run {
            DebugLog.w(TAG, "${clazz.name}#E(Size) not found; masterlive codec pin skipped")
            return
        }
        deoptimize(method)
        method.hook("cam_masterlive_codec_pin") {
            before { param ->
                if (!codecPinEnabled()) return@before
                val receiver = param.thisObject ?: return@before
                val initial = codecInitialSize(receiver) ?: return@before
                val current = param.args?.getOrNull(0) as? Size ?: return@before
                val pinned = CameraCodecSizePin.pinnedSize(
                    current.width, current.height, initial.first, initial.second
                ) ?: return@before
                param.args[0] = Size(pinned.first, pinned.second)
                if (codecPinLogged.getAndSet(true) == false) {
                    DebugLog.i(
                        TAG,
                        "masterlive codec size pinned: updateCodecSize $current -> ${pinned.first}x${pinned.second}"
                    )
                }
            }
        }
        DebugLog.i(TAG, "masterlive codec pin hooked on ${method.declaringClass.name}#E(Size)")
    }

    /** Logs the first codec-size pin ONCE per process, not per shot. */
    private val codecPinLogged = AtomicReference(false)

    /** Cached initial-format size fields (`A`/`B`, final ints) of the circular encoder. */
    private val codecInitialSizeFields = AtomicReference<Pair<Field, Field>?>(null)

    /**
     * The encoder's construction-time format size (`A`/`B`, read from the MediaFormat at
     * `ym.d` construction, :81-82; real dex names — jadx shows `f67755A`/`f67756B` display
     * aliases). Resolved once per process and cached; walks the receiver's class hierarchy so
     * V2 receivers (`ym.f` extends `ym.d`) resolve the base fields. null when the fields are
     * unreadable — the pin then does nothing (the encode size is never substituted), which
     * is the safe failure mode.
     */
    private fun codecInitialSize(receiver: Any): Pair<Int, Int>? {
        val cached = codecInitialSizeFields.get()
        val fields = cached ?: synchronized(codecInitialSizeFields) {
            codecInitialSizeFields.get() ?: run {
                val found = resolveSizeFields(receiver.javaClass)
                found?.let { (wField, hField) ->
                    wField.isAccessible = true
                    hField.isAccessible = true
                }
                found?.also { codecInitialSizeFields.set(it) }
            }
        } ?: return null
        val w = runCatching { fields.first.getInt(receiver) }.getOrNull() ?: return null
        val h = runCatching { fields.second.getInt(receiver) }.getOrNull() ?: return null
        if (w <= 0 || h <= 0) return null
        return w to h
    }

    /**
     * Resolve the encoder's initial-format int fields on [clazz] or any superclass: real dex
     * names `A`/`B` (510, `p859ym/d.java:23-26`), with the jadx display aliases as harmless
     * fallbacks. Both fields must be plain `int`s.
     */
    private fun resolveSizeFields(clazz: Class<*>): Pair<Field, Field>? {
        var c: Class<*>? = clazz
        while (c != null) {
            val w = runCatching { c.getDeclaredField("A") }.getOrNull()
            val h = runCatching { c.getDeclaredField("B") }.getOrNull()
            if (w != null && h != null && w.type == java.lang.Integer.TYPE && h.type == java.lang.Integer.TYPE) {
                return w to h
            }
            c = c.superclass
        }
        val w = runCatching { clazz.getDeclaredField("f67755A") }.getOrNull()
        val h = runCatching { clazz.getDeclaredField("f67756B") }.getOrNull()
        return if (w != null && h != null && w.type == java.lang.Integer.TYPE && h.type == java.lang.Integer.TYPE) {
            w to h
        } else {
            null
        }
    }

    /**
     * The `E(Size)` method on a circular-encoder class: declared first (covers the base and
     * the V2 override), then inherited-public as a fallback (older builds may declare it only
     * on a superclass). Shape: one `android.util.Size` parameter, void return, not synthetic.
     */
    private fun resolveUpdateCodecSizeMethod(clazz: Class<*>, quiet: Boolean = false): Method? {
        val match: (Method) -> Boolean = { m ->
            !m.isSynthetic && m.parameterCount == 1 &&
                m.parameterTypes[0] == Size::class.java && m.returnType == java.lang.Void.TYPE
        }
        val declared = clazz.declaredMethods.firstOrNull(match)
        val resolved = declared ?: clazz.methods.firstOrNull(match)
        if (resolved == null && !quiet) {
            DebugLog.w(TAG, "${clazz.name}#E(Size) not found by shape")
        }
        return resolved?.apply { isAccessible = true }
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
            candidates = listOf("l6.n", "l6.q", "l6.p", "l6.o"),
            probe = { bridge ->
                bridge.findClass { matcher { usingStrings("getLivePhotoVideoSize") } }
                    .firstOrNull()?.name
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
                val receiver = param.args?.getOrNull(1) ?: return@after
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
        val loader = classLoader ?: return null
        if (!masterLiveTypeResolved.get() || masterLiveTypeMethod.get() == null) {
            synchronized(masterLiveTypeMethod) {
                if (!masterLiveTypeResolved.get() || masterLiveTypeMethod.get() == null) {
                    val resolved = runCatching {
                        loader.loadClass("com.android.camera.data.data.j")
                    }.getOrNull()?.declaredMethods?.firstOrNull {
                        !it.isSynthetic && java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                            it.name == "A" && it.parameterCount == 1 &&
                            it.parameterTypes[0] == Integer.TYPE && it.returnType == String::class.java
                    }?.apply { isAccessible = true }
                    masterLiveTypeMethod.set(resolved)
                    // Resolution is attempted exactly once per process: a miss stays a miss
                    // (the class/method shape is build-stable), so hot paths never re-scan.
                    masterLiveTypeResolved.set(resolved != null)
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
     * The video-compose consumer `Kj.D#c()` (real dex name `Kj.D`, classes6.dex,
     * `Kj/D.java:43-47`) returns the live-photo video stream size from the camera manager's
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
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
        val clazz = CameraResolver.resolveClass(
            scope = TAG, key = "liveshot_video_surface", ctx = ctx,
            candidates = listOf("Kj.D"),
            validate = { c ->
                c.declaredMethods.any {
                    !it.isSynthetic && it.name == "c" && it.parameterCount == 0 &&
                        it.returnType == Size::class.java
                }
            },
        ) ?: run {
            DebugLog.w(TAG, "Kj.D (liveshot video surface) not resolved; masterlive video surface size skipped")
            return
        }
        val method = runCatching { clazz.getMethod("c") }
            .getOrNull()
            ?.takeIf { it.parameterCount == 0 && it.returnType == Size::class.java }
            ?: run {
                DebugLog.w(TAG, "${clazz.name}#c() not found; masterlive video surface size skipped")
                return
            }
        deoptimize(method)
        method.hook("cam_masterlive_video_surface_size") {
            after { param ->
                if (!videoSizeProbeEnabled()) return@after
                val original = param.result as? Size ?: return@after
                val receiver = param.thisObject ?: return@after
                // Mode gate: only rewrite inside MasterLive (the same consumer also serves the
                // normal live-photo modes' 4:3 geometry).
                if (readSurfaceReceiverModule(receiver) != CameraIdentity.MASTER_LIVE_MODE_ID) {
                    return@after
                }
                val pinned = CameraMasterLiveSizeBinding.boundSize(
                    currentMasterLiveType(), original.width, original.height
                ) ?: return@after
                param.result = Size(pinned.first, pinned.second)
                if (videoSurfaceProbeLogged.getAndSet(true) == false) {
                    DebugLog.i(
                        TAG,
                        "masterlive video surface size: Kj.D#c() $original " +
                            "(type ${currentMasterLiveType() ?: "?"}) -> ${pinned.first}x${pinned.second}"
                    )
                }
            }
        }
        DebugLog.i(TAG, "masterlive video surface size hooked on ${clazz.name}#c()")
    }

    /** Logs the first surface-size substitution ONCE per process. */
    private val videoSurfaceProbeLogged = AtomicReference(false)

    /**
     * The active module id carried by [receiver]'s camera-manager handle: real dex fields
     * `D.a` (`Zg.a`, jadx alias `f9165a`) → `Zg.a#g` (int, jadx alias `f21563g`; the same
     * chain `Kj/F.java:125` reads). null when any step fails — callers then skip instead of
     * guessing.
     */
    private fun readSurfaceReceiverModule(receiver: Any): Int? {
        val managerField = resolveField(receiver.javaClass, "a", "f9165a")?.apply { isAccessible = true }
            ?: return null
        val manager = runCatching { managerField.get(receiver) }.getOrNull() ?: return null
        val modeField = resolveField(manager.javaClass, "g", "f21563g")?.apply { isAccessible = true }
            ?: return null
        if (modeField.type != Integer.TYPE) return null
        return runCatching { modeField.getInt(manager) }.getOrNull()
    }

    /**
     * MasterLive (实况运镜) auto-zoom collapse — experimental
     * (`KEY_CAMERA_MASTERLIVE_AUTO_ZOOM_COLLAPSE`, default off).
     *
     * On-device forensics (2026-08-27): on myron the types-2/3 auto-zoom trip drives the
     * SAT-composite zoom in an endless `MCAM_ZoomManager: onZoomingActionUpdate ... action =
     * 12/13` loop (hundreds of calls per second with a constant value), the zoom never
     * converges and `mIsCaptureZoomCompleted` is never set — the capture freezes after the
     * shutter and the motion photo is never saved. The ValueAnimator's completion IS the
     * completion chain, so the trip cannot simply be skipped. This hook collapses the
     * animator's TARGET zoom to its START value (`startAutoZoom(from, to, …)` → `to = from`):
     * the animator runs its zero-length course and completes normally (unblocking
     * `mIsCaptureZoomCompleted`), but no real zoom driving happens — the capture finishes
     * like type 0 and saves a clean motion photo at the current zoom (the automatic dolly is
     * lost). Gated on the key only; requires a camera restart after changing.
     */
    private fun hookMasterLiveAutoZoomCollapse() {
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
        val clazz = CameraResolver.resolveClass(
            scope = TAG, key = "masterlive_module", ctx = ctx,
            candidates = listOf("com.android.camera.features.mode.masterlive.MasterLiveModule"),
            probe = { bridge ->
                bridge.findClass { matcher { usingStrings("MasterLiveModule") } }
                    .firstOrNull { cd ->
                        ctx.loadOrNull(cd.name)?.let { resolveStartAutoZoom(it, quiet = true) != null } == true
                    }?.name
            },
            validate = { c -> resolveStartAutoZoom(c, quiet = true) != null },
        ) ?: run {
            DebugLog.w(TAG, "MasterLiveModule not resolved; masterlive auto-zoom collapse skipped")
            return
        }
        val method = resolveStartAutoZoom(clazz) ?: run {
            DebugLog.w(TAG, "MasterLiveModule#startAutoZoom not found; masterlive auto-zoom collapse skipped")
            return
        }
        deoptimize(method)
        method.hook("cam_masterlive_auto_zoom_collapse") {
            before { param ->
                if (!autoZoomCollapseEnabled()) return@before
                val args = param.args ?: return@before
                if (args.size < 2) return@before
                val from = args[0] as? Float ?: return@before
                val to = args[1] as? Float ?: return@before
                if (from == to) return@before
                args[1] = from
                if (autoZoomCollapseLogged.getAndSet(true) == false) {
                    DebugLog.i(TAG, "masterlive auto-zoom collapsed: trip $from -> $to becomes $from -> $from")
                }
            }
        }
        DebugLog.i(TAG, "masterlive auto-zoom collapse hooked on ${clazz.name}#startAutoZoom")
    }

    /** Logs the first collapsed trip ONCE per process, not per animator tick. */
    private val autoZoomCollapseLogged = AtomicReference(false)

    /** `startAutoZoom(float from, float to, float speed, int kind, boolean flip)` -> void. */
    private fun resolveStartAutoZoom(clazz: Class<*>, quiet: Boolean = false): Method? {
        val match: (Method) -> Boolean = { m ->
            !m.isSynthetic && m.name == "startAutoZoom" && m.parameterCount == 5 &&
                m.parameterTypes[0] == java.lang.Float.TYPE && m.parameterTypes[1] == java.lang.Float.TYPE &&
                m.parameterTypes[2] == java.lang.Float.TYPE && m.parameterTypes[3] == java.lang.Integer.TYPE &&
                m.parameterTypes[4] == java.lang.Boolean.TYPE && m.returnType == java.lang.Void.TYPE
        }
        val declared = clazz.declaredMethods.firstOrNull(match)
        val resolved = declared ?: clazz.methods.firstOrNull(match)
        if (resolved == null && !quiet) {
            DebugLog.w(TAG, "${clazz.name}#startAutoZoom not found by shape")
        }
        return resolved?.apply { isAccessible = true }
    }

    /**
     * Shutter-sound style-index bounds guard. `f2.c` (jadx `p180f2/c`) builds a 4-entry
     * shutter-sound style list (old/art/default/modern) because neither the native C1209 nor the
     * impersonated C1151 (both C1199) reports `F3()=true` (Leica entries skipped). Its raw getter
     * `a()` reads the stored `key_shutter_sound` (=4 from an old Leica-list migration) with NO
     * bounds check, so `MiuiCameraSound(D3)#g()` → `b().get(a())` throws
     * `IndexOutOfBoundsException: Index 4 out of bounds for length 4` on every shutter-sound
     * preload (CAM-Work) → RxJava onError (no handler) → FATAL → the camera cannot start.
     * This hook clamps an out-of-range `a()` result back to `c()` — the getter that already
     * applies the `F3`-offset and bounds check (returns 0 when out of range).
     *
     * UNCONDITIONAL — deliberately NOT gated on the impersonation master. The persisted
     * `key_shutter_sound` outlives the impersonation (the `Ac/e` version migration only keeps it,
     * never rewrites it), so a value taken from the Leica 8-entry era keeps crashing the NATIVE
     * 4-entry list after the master is turned off — the "不打开伪装旗舰机相机配置时打开相机闪退"
     * report. `RESEARCH_MYRON_06_IOOBE_ROOTCAUSE.md` §4 anticipated exactly this and §6.3
     * concluded "该修复与冒充无关、应常驻启用". The clamp only ever re-maps an out-of-range index
     * to the app's own bounds-safe default, so valid selections (0-3 native, 0-7 Leica) pass
     * through untouched in every configuration.
     */
    private fun hookShutterSoundBoundary() {
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
        val clazz = CameraResolver.resolveClass(
            scope = TAG, key = "shutter_cfg", ctx = ctx,
            candidates = listOf("f2.c", "p180f2.c"),
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

    private fun invokeBoolean(original: Any, name: String): Boolean =
        (originalDelegateMethods(original)[name]?.invoke(original) as? Boolean) ?: false

    /**
     * SOE-safe fallback used by the delegating before-callbacks when the original config getter
     * cannot be delegated (method missing / reflective invoke failed / returned null). Setting
     * `param.result` (never leaving `skipped` false) is what stops ezhooktool's `proceed` from
     * re-entering the still-hooked getter through `Method.invoke` (the 26611-frame SOE). The
     * value is return-type aware so a primitive-returning getter never yields a boxed null that
     * the camera would NPE on while unboxing; a genuinely-null object return is preserved.
     */
    private fun soeSafeFallback(method: Method, param: HookParam): Any? {
        val ret = method.returnType
        return when {
            ret == java.lang.Boolean.TYPE -> false
            ret == java.lang.Byte.TYPE -> 0.toByte()
            ret == java.lang.Short.TYPE -> 0.toShort()
            ret == java.lang.Integer.TYPE -> 0
            ret == java.lang.Long.TYPE -> 0L
            ret == java.lang.Float.TYPE -> 0f
            ret == java.lang.Double.TYPE -> 0.0
            ret == java.lang.Character.TYPE -> '\u0000'
            else -> param.result // preserve an already-set object value, else null
        }
    }

    /** Cached zero-arg reflection methods for every delegatable getter on the original config. */
    private fun originalDelegateMethods(original: Any): Map<String, Method> {
        originalDelegates.get()?.let { return it }
        synchronized(originalDelegates) {
            originalDelegates.get()?.let { return it }
            val resolved = ORIGINAL_DELEGATE_NAMES.associateWith { name ->
                runCatching { original.javaClass.getMethod(name) }
                    .getOrNull()
                    ?.takeIf { it.parameterCount == 0 }
            }.filterValues { it != null }.mapValues { it.value!! }
            originalDelegates.set(resolved)
            return resolved
        }
    }

    /**
     * Whether this device IS a real 17 Ultra (device base == nezha). On a real flagship the
     * guard/identity delegation is a no-op anyway, so the fast-path skips the reflective
     * delegation work entirely (these getters run on hot paths).
     */
    private fun deviceIsNezha(): Boolean {
        deviceIsNezhaCache.get()?.let { return it }
        synchronized(deviceIsNezhaCache) {
            deviceIsNezhaCache.get()?.let { return it }
            val isNezha = readDeviceBaseName()?.equals("nezha", ignoreCase = true) == true
            deviceIsNezhaCache.set(isNezha)
            return isNezha
        }
    }

    /**
     * The REAL device config instance (e.g. `com.mi.device.Myron`), built exactly the way the
     * factory does — NOT read from the `Je.e.b` cache (which now holds the impersonated flagship).
     * Used both as the source of the original watermark third slot and for focal delegation.
     */
    private fun originalConfigInstance(): AtomicReference<Any?> {
        originalInstance.get()?.let { return originalInstance }
        synchronized(originalInstance) {
            originalInstance.get()?.let { return originalInstance }
            val built = buildOriginalConfigInstance()
            if (built != null) originalInstance.set(built)
            return originalInstance
        }
    }

    /**
     * The config classes the running camera may dispatch capability getters to: the ORIGINAL
     * device config class (the dispatch target whenever the impersonation master is off, and
     * whenever the flagship build failed) plus the flagship instance's class (the dispatch
     * target while the master is on).
     *
     * Master-off unlocks hook the Methods the ORIGINAL class resolves — for getters the real
     * config inherits without overriding (F3/X2/a3/M/q0/y4 on C1143/C1199) these are often
     * the SAME Methods the impersonated K100 config dispatches to (C1151 overrides only
     * y4/q0/M), so a hook installed on the union covers both power states with one callback.
     * Callers deduplicate by Method identity and only ever RAISE gates: when the user switch
     * is off they leave the native value untouched (never lower a native true).
     */
    private fun configDispatchClasses(): List<Class<*>> {
        val classes = LinkedHashSet<Class<*>>()
        originalConfigInstance().get()?.javaClass?.let { classes.add(it) }
        flagshipInstance()?.javaClass?.let { classes.add(it) }
        return classes.toList()
    }

    /**
     * Replays `Je/e.q()`'s full fallback chain (jadx `Je/e.java:47-72`) with the REAL device
     * base name, so the ORIGINAL config instance resolves exactly as it would without
     * impersonation — including devices whose per-device class is not shipped in the APK
     * (then it falls back to `com.mi.device.others.<Manufacturer>` and finally the weak
     * default `Ne.a`, mirroring the factory's own catch branches):
     * 1. `com.mi.device.<Capitalize(Build.DEVICE | Je/a.c)>` — e.g. Myron
     * 2. `com.mi.device.others.<Capitalize(Build.MANUFACTURER)>`
     * 3. `new Ne.a()` — the low-spec weak default used by non-flagship Redmis
     *
     * Returns null (focal keep & third-slot capture are skipped) only if even `Ne.a` is
     * unexpectedly unavailable.
     */
    private fun buildOriginalConfigInstance(): Any? {
        val loader = classLoader ?: return null
        return runCatching {
            val resolver = resolveSourceNameResolver(loader)
                ?: return@runCatching null
            val deviceBase = readDeviceBaseName() ?: Build.DEVICE
            buildFrom(loader, resolver, "com.mi.device.${capitalize(deviceBase)}")
                ?: buildFrom(
                    loader, resolver,
                    "com.mi.device.others.${capitalize(Build.MANUFACTURER)}"
                )
                ?: runCatching {
                    loader.loadClass("Ne.a").getDeclaredConstructor().newInstance()
                }.getOrNull()
        }.getOrNull()?.also {
            DebugLog.d(TAG, "original device config instance created: ${it.javaClass.name}")
        } ?: run {
            DebugLog.w(TAG, "original device config could not be instantiated; focal keep unavailable")
            null
        }
    }

    /** Capitalise the first ASCII letter (mirrors `Je/e.f(String)`). */
    private fun capitalize(name: String): String {
        if (name.isEmpty()) return name
        val first = name[0]
        return if (first in 'a'..'z') first.uppercaseChar() + name.substring(1) else name
    }

    /**
     * Device base name used to resolve the ORIGINAL (non-impersonated) config class. The app's own
     * factory `Je/e.q()` reads it from the `Je/a` static lazy field `c` (a `Pu.n` lazy whose value
     * is keyed off `Build.DEVICE`, `Je/d.a`); the jadx alias `f8410c` does not exist in the dex and
     * previously threw `NoSuchFieldException: No field f8410c in class LJe/a`. `Build.DEVICE` is
     * the same value (verified on the device: myron == ro.product.device), so it is used directly;
     * the reflective lazy read is kept only as a silent best-effort fallback for builds where
     * `Build.DEVICE` is blank.
     */
    private fun readDeviceBaseName(): String? {
        val fromBuild = Build.DEVICE.takeIf { it.isNotBlank() }
        val fromLazy = runCatching {
            val jeA = "Je.a".toClassOrNull() ?: return@runCatching null
            val field = resolveField(jeA, "c", "f8410c")?.apply { isAccessible = true }
                ?: return@runCatching null
            val lazy = field.get(null) ?: return@runCatching null
            val getValue = lazy.javaClass.getMethod("getValue")
            (getValue.invoke(lazy) as? String)?.takeIf { it.isNotEmpty() }
        }.getOrNull()
        return fromBuild ?: fromLazy
    }

    // ─── gate helpers ─────────────────────────────────────────────────────────────

    private fun enabled(): Boolean =
        Preferences.getBoolean(Preferences.KEY_CAMERA_IMPERSONATE, false)

    /**
     * Watermark keep is UNCONDITIONAL: the on-picture watermark brand + model always stays on
     * this device's own values (or the user custom override), independent of the impersonation
     * master. Gating it on the master read made the very first watermark reads (before
     * `Preferences` is initialized in the camera process) return the impersonated flagship's
     * strings, which the `S8.d` watermark-config singleton cached — the "17 Ultra" watermark
     * shown right after capture. Unconditional also never shows a wrong model on devices that
     * cannot resolve a real market name.
     */
    private fun keepModel(): Boolean = true

    private fun keepFocal(): Boolean =
        enabled() && Preferences.getBoolean(Preferences.KEY_CAMERA_KEEP_FOCAL, true)

    private fun keepImaging(): Boolean =
        enabled() && Preferences.getBoolean(Preferences.KEY_CAMERA_KEEP_IMAGING, true)

    private fun guardModes(): Boolean =
        enabled() && Preferences.getBoolean(Preferences.KEY_CAMERA_GUARD_MODES, true)

    private fun guardCameraId(): Boolean =
        enabled() && Preferences.getBoolean(Preferences.KEY_CAMERA_GUARD_CAMERA_ID, false)

    private fun targetIsK100Promax(): Boolean =
        Preferences.getString(Preferences.KEY_CAMERA_IMPERSONATE_TARGET, TARGET_K100PROMAX) ==
            TARGET_K100PROMAX

    private fun targetIsNezha(): Boolean = !targetIsK100Promax()

    /** Resolved street unlock mode ([CameraStreetMode] constant), re-read live (100 ms memo). */
    private fun streetMode(): String = Preferences.cameraStreetMode()

    private fun leicaStyle(): Boolean =
        Preferences.getBoolean(Preferences.KEY_CAMERA_LEICA_STYLE, true)

    /** 徕卡一瞬 (mode 256) unlock; read WITHOUT the impersonation master — master-independent. */
    private fun legendaryMomentUnlock(): Boolean =
        Preferences.getBoolean(Preferences.KEY_CAMERA_LEGENDARY_MOMENT, false)

    /** 智能构图 setting unlock; read WITHOUT the impersonation master — master-independent. */
    private fun smartCompositionUnlock(): Boolean =
        Preferences.getBoolean(Preferences.KEY_CAMERA_SMART_COMPOSITION, false)

    /** 内容凭证 setting unlock; applied once per camera process at attach (restart to change). */
    private fun contentCredentialUnlock(): Boolean =
        Preferences.getBoolean(Preferences.KEY_CAMERA_CONTENT_CREDENTIAL, false)

    /** 自适应镜头 setting unlock; read WITHOUT the impersonation master — master-independent. */
    private fun adaptiveLensUnlock(): Boolean =
        Preferences.getBoolean(Preferences.KEY_CAMERA_ADAPTIVE_LENS, false)

    /** 实况运镜 unlock master (default ON); suppresses the Nezha-target `y4` guard. */
    private fun masterliveEnabled(): Boolean =
        Preferences.getBoolean(Preferences.KEY_CAMERA_MASTERLIVE_ENABLE, true)

    /**
     * 实况运镜 role-23 tele fallback switch (default ON) — read WITHOUT the impersonation
     * master: master-off MasterLive needs the fallback too. Only the key, never `enabled()`.
     */
    private fun masterliveTeleFallback(): Boolean =
        Preferences.getBoolean(Preferences.KEY_CAMERA_MASTERLIVE_TELE_FALLBACK, true)

    /**
     * 实况运镜 op-mode safety net switch (default OFF) — read WITHOUT the impersonation
     * master: master-off MasterLive needs the safety net too, gated only on this key.
     */
    private fun opModeSafe(): Boolean =
        Preferences.getBoolean(Preferences.KEY_CAMERA_MASTERLIVE_OPMODE_SAFE, false)

    /**
     * 实况运镜 circular-encoder size pin (experimental probe, default OFF) — read WITHOUT the
     * impersonation master: master-off MasterLive needs the pin too. Only the key, never
     * `enabled()`.
     */
    private fun codecPinEnabled(): Boolean =
        Preferences.getBoolean(Preferences.KEY_CAMERA_MASTERLIVE_CODEC_PIN, false)

    /**
     * 实况运镜 video-size probe (experimental, default OFF) — read WITHOUT the impersonation
     * master: master-off MasterLive needs the probe too. Only the key, never `enabled()`.
     */
    private fun videoSizeProbeEnabled(): Boolean =
        Preferences.getBoolean(Preferences.KEY_CAMERA_MASTERLIVE_VIDEO_SIZE_PROBE, false)

    /**
     * 实况运镜 auto-zoom collapse (experimental, default OFF) — read WITHOUT the impersonation
     * master: master-off MasterLive needs the collapse too. Only the key, never `enabled()`.
     */
    private fun autoZoomCollapseEnabled(): Boolean =
        Preferences.getBoolean(Preferences.KEY_CAMERA_MASTERLIVE_AUTO_ZOOM_COLLAPSE, false)

    /**
     * 实况运镜 红毯运镜 (type-1) injection (default ON) — read WITHOUT the impersonation
     * master: master-off MasterLive needs it too, so only this key plus [masterliveEnabled],
     * never `enabled()`.
     */
    private fun redCarpetEnabled(): Boolean =
        Preferences.getBoolean(Preferences.KEY_CAMERA_MASTERLIVE_RED_CARPET, true)

    /**
     * 实况运镜 full focal line-up (超清实况焦段条, default ON) — read WITHOUT the impersonation
     * master: master-off MasterLive needs it too, so only this key plus [masterliveEnabled],
     * never `enabled()`.
     */
    private fun fullFocalEnabled(): Boolean =
        Preferences.getBoolean(Preferences.KEY_CAMERA_MASTERLIVE_FULL_FOCAL, true)
}
