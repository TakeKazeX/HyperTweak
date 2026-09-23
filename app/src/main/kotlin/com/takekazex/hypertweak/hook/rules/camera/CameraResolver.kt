package com.takekazex.hypertweak.hook.rules.camera

import android.content.pm.ApplicationInfo
import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.util.DebugLog
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Version-agnostic resolver for camera classes and methods.
 *
 * Obfuscated owners are discovered from semantic DEX strings, signatures, and numeric behavior;
 * every result is loaded, checked again against its runtime shape, and cached against the target
 * APK fingerprint. Unique-match helpers fail closed when a new build introduces ambiguity.
 * A miss disables only the affected feature, while a false match can break the camera process
 * or route a capture through the wrong hardware path.
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
                // A stale name is an expected version-fallback condition. Report it only at
                // debug level; the terminal resolver result below is the actionable warning.
                DebugLog.d(scope, "$key: candidate $name failed semantic check; trying fallback")
                continue
            }
            DebugLog.d(scope, "$key resolved by candidate name $name")
            return clazz
        }
        // L2: DexKit probes (content-fingerprint cache; re-scanned after updates or validation
        // failures).
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
                // A cached obfuscated name may still load after an update while now referring
                // to an unrelated class. Validate it inside the cache manager so that a semantic
                // mismatch automatically triggers a fresh DexKit scan for this key.
                validators = mapOf(key to validate),
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

    /** Resolve a semantic anchor without a class-name candidate list. Ambiguity fails closed. */
    fun resolveClassByStrings(
        scope: String,
        key: String,
        ctx: Ctx,
        anchors: List<String>,
        validate: (Class<*>) -> Boolean,
    ): Class<*>? = resolveClass(
        scope = scope,
        key = key,
        ctx = ctx,
        candidates = emptyList(),
        probe = { bridge ->
            bridge.findClass { matcher { usingStrings(*anchors.toTypedArray()) } }
                .mapNotNull { data -> runCatching { data.getInstance(ctx.classLoader) }.getOrNull() }
                .filter { clazz -> runCatching { validate(clazz) }.getOrDefault(false) }
                .distinctBy { it.name }
                .singleOrNull()?.name
        },
        validate = validate,
    )

    /** Resolve one method from its semantic DEX strings plus a strict runtime signature. */
    fun resolveMethodByStrings(
        scope: String,
        key: String,
        ctx: Ctx,
        anchors: List<String>,
        shape: (Method) -> Boolean,
    ): Method? {
        val apkPath = ctx.appInfo?.sourceDir ?: return null
        return DexKitManager.withBridge(apkPath) { bridge ->
            val matches = runCatching {
                bridge.findMethod { matcher { usingStrings(*anchors.toTypedArray()) } }
                    .mapNotNull { data -> runCatching { data.getMethodInstance(ctx.classLoader) }.getOrNull() }
                    .filter { method -> !method.isSynthetic && runCatching { shape(method) }.getOrDefault(false) }
                    .distinctBy { it.toGenericString() }
            }.getOrElse { emptyList() }
            matches.singleOrNull()?.apply { isAccessible = true }
                ?: run {
                    DebugLog.w(scope, "$key did not resolve to one method by semantic anchor")
                    null
                }
        }
    }

    /** Resolve a method from stable numeric behavior and a strict runtime signature. */
    fun resolveMethodByNumbers(
        scope: String,
        key: String,
        ctx: Ctx,
        numbers: List<Number>,
        shape: (Method) -> Boolean,
    ): Method? {
        val apkPath = ctx.appInfo?.sourceDir ?: return null
        return DexKitManager.withBridge(apkPath) { bridge ->
            val matches = runCatching {
                bridge.findMethod { matcher { usingNumbers(*numbers.toTypedArray()) } }
                    .mapNotNull { data -> runCatching { data.getMethodInstance(ctx.classLoader) }.getOrNull() }
                    .filter { method -> !method.isSynthetic && runCatching { shape(method) }.getOrDefault(false) }
                    .distinctBy { it.toGenericString() }
            }.getOrElse { emptyList() }
            matches.singleOrNull()?.apply { isAccessible = true }
                ?: run {
                    DebugLog.w(scope, "$key did not resolve to one method by numeric behavior")
                    null
                }
        }
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
        val method = clazz.declaredMethods.filter { m ->
            m.name in names && shape(m) && !m.isSynthetic
        }.singleOrNull() ?: run {
            DebugLog.w(scope, "$key: method names $names plus signature did not identify one target on ${clazz.name}")
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
     * Resolve the concrete implementation of an instance method from the provider object reached
     * at runtime. This keeps hooks tied to the call graph instead of a class name that the camera
     * obfuscator can change or reuse on the next APK.
     */
    fun findConcreteImplementation(receiver: Any, contract: Method): Method? {
        if (Modifier.isStatic(contract.modifiers) || !contract.declaringClass.isInstance(receiver)) {
            return null
        }

        var type: Class<*>? = receiver.javaClass
        while (type != null && type != Any::class.java) {
            val matches = type.declaredMethods.filter { method ->
                method.name == contract.name &&
                    method.parameterTypes.contentEquals(contract.parameterTypes) &&
                    method.returnType == contract.returnType &&
                    !Modifier.isStatic(method.modifiers) &&
                    !Modifier.isAbstract(method.modifiers) &&
                    !method.isBridge &&
                    !method.isSynthetic
            }
            if (matches.size > 1) return null
            matches.singleOrNull()?.let { return it.apply { isAccessible = true } }
            type = type.superclass
        }

        // A concrete interface default method is executable without a class override.
        return contract.takeIf { !Modifier.isAbstract(it.modifiers) }
            ?.apply { isAccessible = true }
    }

    /**
     * Find the first uniquely resolvable pair of static boolean capability gates.
     *
     * Capability helpers contain many one-argument boolean methods, so a name-only lookup is
     * unsafe after an APK update: a renamed/overloaded method can silently target another gate.
     * Each pair must resolve exactly once, both methods must take the same argument type, and
     * synthetic bridge methods are ignored. Pairs are tried in the caller's priority order.
     */
    fun findUniqueStaticBooleanPair(
        clazz: Class<*>,
        pairs: List<Pair<String, String>>,
    ): Pair<Method, Method>? {
        for ((firstName, secondName) in pairs) {
            val first = clazz.declaredMethods.filter {
                it.name == firstName && Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == 1 && it.returnType == java.lang.Boolean.TYPE &&
                    !it.isSynthetic
            }.singleOrNull() ?: continue
            val second = clazz.declaredMethods.filter {
                it.name == secondName && Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == 1 && it.returnType == java.lang.Boolean.TYPE &&
                    !it.isSynthetic
            }.singleOrNull() ?: continue
            if (!first.parameterTypes.contentEquals(second.parameterTypes)) continue
            first.isAccessible = true
            second.isAccessible = true
            return first to second
        }
        return null
    }

    /**
     * Resolve the legacy model-mismatch gate when the host reuses its old method names for
     * unrelated flags. The real gate is the static boolean facade method that asks the host's
     * config factory for a config instance and returns that factory's fallback/mismatch state.
     */
    fun resolveConfigFallbackGate(
        ctx: Ctx,
        facade: Class<*>,
        configType: Class<*>,
        scope: String,
    ): Method? {
        val apkPath = ctx.appInfo?.sourceDir ?: return null
        return DexKitManager.withBridge(apkPath) { bridge ->
            val methods = runCatching {
                bridge.findMethod {
                    matcher {
                        declaredClass(facade.name, StringMatchType.Equals)
                        paramCount(0)
                        returnType(java.lang.Boolean.TYPE)
                    }
                }.filter { data ->
                    data.className == facade.name && Modifier.isStatic(data.modifiers) &&
                        data.paramCount == 0 && data.returnTypeName == "boolean" &&
                        data.invokes.any { invoked ->
                            invoked.isMethod && invoked.paramCount == 0 &&
                                invoked.returnTypeName == configType.name
                        }
                }.mapNotNull { runCatching { it.getMethodInstance(ctx.classLoader) }.getOrNull() }
                    .filter { method ->
                        Modifier.isStatic(method.modifiers) && method.parameterCount == 0 &&
                            method.returnType == java.lang.Boolean.TYPE && facade == method.declaringClass
                    }
                    .distinctBy { it.toGenericString() }
            }.getOrElse { emptyList() }
            methods.singleOrNull()?.apply { isAccessible = true }
                ?: run {
                    DebugLog.w(scope, "config-fallback gate was not uniquely identified on ${facade.name}")
                    null
                }
        }
    }

    /** Locate the camera's model-source resolver by its cache/decode/Class.forName call graph. */
    fun resolveSourceNameResolver(ctx: Ctx, scope: String): Method? {
        val info = ctx.appInfo ?: return null
        val apkPath = info.sourceDir ?: return null
        return DexKitManager.withBridge(apkPath) { bridge ->
            val methods = runCatching {
                bridge.findMethod {
                    matcher {
                        returnType(Class::class.java)
                        paramTypes(String::class.java)
                    }
                }.filter { data ->
                    Modifier.isStatic(data.modifiers) && data.paramCount == 1 &&
                        data.paramTypeNames == listOf(String::class.java.name) &&
                        data.returnTypeName == Class::class.java.name &&
                        data.invokes.any { invoked ->
                            invoked.declaredClassName == Class::class.java.name &&
                                invoked.methodName == "forName" &&
                                invoked.paramTypeNames == listOf(String::class.java.name) &&
                                invoked.returnTypeName == Class::class.java.name
                        } &&
                        data.invokes.any { invoked ->
                            invoked.declaredClassName == String::class.java.name &&
                                invoked.methodName == "hashCode" && invoked.paramCount == 0 &&
                                invoked.returnTypeName == Integer.TYPE.name
                        } &&
                        data.invokes.any { invoked ->
                            invoked.declaredClassName in setOf("java.util.Map", "java.util.HashMap") &&
                                invoked.methodName == "get" &&
                                invoked.paramTypeNames == listOf(Any::class.java.name)
                        } &&
                        data.invokes.any { invoked ->
                            invoked.declaredClassName == "java.lang.Integer" &&
                                invoked.methodName == "valueOf" &&
                                invoked.paramTypeNames == listOf(Integer.TYPE.name)
                        } &&
                        data.invokes.any { invoked ->
                            invoked.isMethod && Modifier.isStatic(invoked.modifiers) &&
                                invoked.paramTypeNames == listOf(Integer.TYPE.name, String::class.java.name) &&
                                invoked.returnTypeName == String::class.java.name
                        }
                }.mapNotNull { runCatching { it.getMethodInstance(ctx.classLoader) }.getOrNull() }
                    .filter { method ->
                        Modifier.isStatic(method.modifiers) && method.parameterTypes.contentEquals(
                            arrayOf(String::class.java)
                        ) && method.returnType == Class::class.java
                    }
                    .distinctBy { it.toGenericString() }
                }.getOrElse { emptyList() }
            methods.singleOrNull()?.apply { isAccessible = true }
                ?: run {
                    DebugLog.w(scope, "source-name resolver was not uniquely identified by its cache/decode call graph (matches=${methods.size})")
                    null
                }
        }
    }
}
