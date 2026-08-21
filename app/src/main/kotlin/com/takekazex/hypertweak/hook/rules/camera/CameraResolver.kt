package com.takekazex.hypertweak.hook.rules.camera

import android.content.pm.ApplicationInfo
import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.util.DebugLog
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Version-generic class/method resolution for the Xiaomi camera (`com.android.camera`).
 *
 * The camera APK is re-obfuscated on every release (6.6.000460.0 -> 6.6.000510.0 renamed a
 * non-deterministic subset of class names), and the obfuscator also **reuses** names for
 * unrelated classes (`Ox.g` was the LCC customization provider on 460, a state-list helper on
 * 510; `i5.d` was the watermark entry holder on 460, a font-menu ViewModel on 510). Method
 * names can be renamed as well (`Je.e#q` -> `Je.e#G0`, LCC provider `i() ` -> `s()`), and most
 * dex string constants are encrypted except a handful of survivors
 * (`camera.cloud.watermark.debug`, `key_shutter_sound`, ...).
 *
 * Every target therefore resolves through three layers, in order:
 *  1. [candidates] - known dex names accumulated across verified versions (newest first);
 *     each candidate is validated so a *repurposed* name (the `Ox.g` trap) is rejected.
 *  2. [probe]     - a DexKit query (plaintext-string or structural matcher) when the APK
 *     carries a stable discriminator; results are cached by [DexKitManager] and re-run
 *     automatically when the camera APK's mtime changes (i.e. after every camera update).
 *  3. fall back to the target-specific behavioural chain in the hooker (e.g. the
 *     `Uf.c.a(sourceName)` resolver for `com.mi.device.*` configs, or semantic getter
 *     comparison for the flagship config role).
 *
 * A failure at every layer must skip only the affected sub-feature with a clear log line,
 * never throw, and never disturb the other hooks (see AGENTS.md).
 */
object CameraResolver {

    /** Minimal context needed for resolution (the camera process's loader + app info). */
    class Ctx(
        val classLoader: ClassLoader,
        val appInfo: ApplicationInfo?,
    ) {
        fun loadOrNull(name: String): Class<*>? {
            return runCatching { classLoader.loadClass(name) }.getOrNull()
        }
    }

    /**
     * Resolve a host class through candidate names (L1) and DexKit probes (L2).
     *
     * [validate] is deliberately REQUIRED with no default: an un-validated name match is the
     * exact repurposed-name failure mode this resolver exists to prevent (`Ox.g` / `i5.d`).
     */
    fun resolveClass(
        scope: String,
        key: String,
        ctx: Ctx,
        candidates: List<String>,
        probe: ((DexKitBridge) -> String?)? = null,
        validate: (Class<*>) -> Boolean,
    ): Class<*>? {
        // L1: known dex names, newest first. Each candidate must pass the semantic check:
        // a surviving name may now belong to an unrelated class (Ox.g / i5.d traps).
        for (name in candidates) {
            val clazz = ctx.loadOrNull(name) ?: continue
            if (!validate(clazz)) {
                DebugLog.w(scope, "$key: candidate $name exists but failed semantic check; skipped")
                continue
            }
            DebugLog.d(scope, "$key resolved by candidate name $name")
            return clazz
        }
        // L2: DexKit probes (results cached by DexKitManager; re-scanned after camera updates).
        if (probe != null) {
            val info = ctx.appInfo ?: run {
                DebugLog.w(scope, "$key: appInfo unavailable, cannot run DexKit probe")
                return null
            }
            val baseDir = info.deviceProtectedDataDir ?: info.dataDir ?: run {
                DebugLog.w(scope, "$key: no data dir, cannot run DexKit probe")
                return null
            }
            val apkPath = info.sourceDir ?: run {
                DebugLog.w(scope, "$key: no source dir, cannot run DexKit probe")
                return null
            }
            val resolved = DexKitManager.resolveClasses(
                cacheDir = File(baseDir, "cache"),
                apkPath = apkPath,
                classLoader = ctx.classLoader,
                queries = mapOf(key to { bridge -> probe(bridge) }),
                logMissingQueries = false,
            )
            val clazz = resolved[key]
            if (clazz != null && validate(clazz)) {
                DebugLog.d(scope, "$key resolved by DexKit probe -> ${clazz.name}")
                return clazz
            }
            DebugLog.w(scope, "$key: DexKit probe found nothing usable")
            return null
        }
        DebugLog.w(scope, "$key: L1 candidates exhausted (no probe configured)")
        return null
    }

    /**
     * Resolve a method by name candidates + signature shape. Method names are renamed between
     * builds too (`q` -> `G0`, `i` -> `s`), so each call site supplies the names observed on
     * every verified build and a shape predicate.
     */
    fun resolveMethod(
        scope: String,
        key: String,
        clazz: Class<*>,
        names: List<String>,
        shape: (Method) -> Boolean = { true },
    ): Method? {
        val method = clazz.declaredMethods.firstOrNull { m ->
            m.name in names && shape(m) && !m.isSynthetic
        } ?: run {
            DebugLog.w(scope, "$key: no method named $names (matching shape) on ${clazz.name}")
            return null
        }
        method.isAccessible = true
        DebugLog.d(scope, "$key resolved -> ${clazz.name}#${method.name}()")
        return method
    }

    /** True when [names] contains a static, zero-arg method returning [returnType]-compatible value. */
    fun hasStaticZeroArgMethod(clazz: Class<*>, names: Collection<String>, returnType: Class<*>? = null): Boolean {
        return clazz.declaredMethods.any { m ->
            m.name in names && Modifier.isStatic(m.modifiers) && m.parameterTypes.isEmpty() &&
                (returnType == null || returnType.isAssignableFrom(m.returnType))
        }
    }

    /** True when [names] contains a zero-arg method returning primitives/values of [returnType]. */
    fun hasBooleanMethod(clazz: Class<*>, names: Collection<String>): Boolean {
        return clazz.declaredMethods.any {
            it.name in names && it.parameterCount == 0 && it.returnType == java.lang.Boolean.TYPE
        }
    }

    /**
     * Generic structural fallback for the device-config factory method: on every verified build
     * the factory holds `public static <T> b` (the cached config instance) and a single static
     * zero-arg method whose return type is exactly that field's type (the factory itself).
     * This survives method renames (`q` -> `G0`) without knowing any name.
     */
    fun findFactoryMethod(clazz: Class<*>): Method? {
        val cacheField = runCatching {
            clazz.getDeclaredField("b").takeIf { Modifier.isStatic(it.modifiers) }
        }.getOrNull() ?: return null
        return clazz.declaredMethods.firstOrNull { m ->
            !m.isSynthetic &&
                Modifier.isStatic(m.modifiers) &&
                m.parameterTypes.isEmpty() &&
                m.returnType == cacheField.type
        }?.apply { isAccessible = true }
    }
}