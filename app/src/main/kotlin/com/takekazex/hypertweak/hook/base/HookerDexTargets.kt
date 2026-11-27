package com.takekazex.hypertweak.hook.base

import android.os.Handler
import android.os.Looper
import com.takekazex.hypertweak.hook.base.dex.DexContractMembers
import com.takekazex.hypertweak.hook.base.dex.DexFeature
import com.takekazex.hypertweak.hook.base.dex.DexPreparationOwner
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.util.logging.DebugLog
import org.luckypray.dexkit.DexKitBridge

/**
 * One hooker's DEX target resolution: cached contracts, queued background preparation and the
 * generation fence around publication. [BaseHooker] exposes it through its `prepare*` helpers.
 */
internal class HookerDexTargets(private val owner: BaseHooker) {
    private companion object {
        val preparationMain by lazy { Handler(Looper.getMainLooper()) }
    }

    private val preparations = DexPreparationOwner()

    val isRetired: Boolean get() = preparations.isRetired
    val hasPreparations: Boolean get() = preparations.hasPreparations

    fun activate() = preparations.activate()
    fun cancel() = preparations.cancel()

    val cacheDirectory: java.io.File?
        get() = DexRuntime.cacheDirectory(owner.hookParam.appInfo)

    fun <T : Any> resolveContract(
        id: String,
        apk: String,
        schema: Int,
        compute: (DexKitBridge) -> T?,
        encode: (T) -> Map<String, String>,
        decode: (DexContractMembers) -> T?,
        cacheOnly: Boolean,
    ): T? = DexRuntime.resolve(
        feature = object : DexFeature {
            override val id = id
            override val schema = schema
        }, apkPath = apk, cacheDir = cacheDirectory, cacheOnly = cacheOnly,
        compute = { session -> session.dexKit(compute)?.let(encode).orEmpty() },
        decode = { values, _ -> if (values.isEmpty()) null else decode(
            DexContractMembers(values, owner.classLoader))?.takeIf { encode(it) == values } },
    )

    fun <T : Any> prepareContract(
        id: String,
        apk: String,
        schema: Int,
        compute: (DexKitBridge) -> T?,
        encode: (T) -> Map<String, String>,
        decode: (DexContractMembers) -> T?,
        install: (T) -> Unit,
    ) {
        val cached = resolveContract(id, apk, schema, compute, encode, decode, cacheOnly = true)
        if (cached != null) install(cached)
        else prepare(name = id, resolve = { resolveContract(id, apk, schema, compute, encode, decode, cacheOnly = false) }, install = install, low = false)
    }

    /** Roll back only handles installed by this publication, including synchronous cache replay. */
    fun <T> installContract(install: () -> T): T {
        val before = owner.collectManagedHookHandles().toSet()
        return try { install() } catch (error: Throwable) {
            owner.collectManagedHookHandles().filterNot { it in before }.forEach { handle ->
                runCatching { handle.unhook() }.onFailure(error::addSuppressed)
            }
            throw error
        }
    }

    fun <T : Any> prepareCached(name: String, resolve: () -> T?, install: (T) -> Unit) {
        val cached = DexRuntime.replayOnly(resolve)
        if (cached != null) installContract { install(cached) }
        else prepare(name, resolve, install, low = false)
    }

    fun <T : Any> prepare(
        name: String,
        resolve: () -> T?,
        install: (T) -> Unit,
        low: Boolean,
    ) {
        val hookerName = owner.hookerName
        preparations.prepare(name,
            create = { failure -> DexRuntime.preparation(
                apkPath = owner.hookParam.appInfo?.sourceDir,
                // Resolution is queued straight onto the background worker instead of being
                // posted to the main looper first. The post looked harmless, but it made every
                // preparation wait for the main thread to go idle: on a boot where the dispatch is
                // still installing other hookers, a hooker attached at 20.281 s did not start
                // resolving until 21.372 s. Publication still runs on the main thread, because hook
                // registration requires it.
                afterStartup = { action -> action() },
                publish = { action -> check(preparationMain.post { action() }) },
                failure = failure,
                low = low,
            ) },
            failure = { DebugLog.hookFailed(hookerName, "prepare targets: $name", it) },
            // A resolution without a usable target leaves the feature uninstalled. It is reported
            // here once for every feature, so no resolver can make a missing host contract silent.
            resolve = {
                resolve().also {
                    if (it == null && !preparations.isRetired) {
                        DebugLog.hookSkipped(hookerName, "prepare targets: $name", "no usable target on this host build")
                    }
                }
            },
        ) { target ->
            val started = android.os.SystemClock.uptimeMillis()
            installContract { install(target) }
            DebugLog.i("DexRuntime", "published hooker=$hookerName name=$name " +
                "installMs=${android.os.SystemClock.uptimeMillis() - started}")
        }
    }

    /**
     * Resolve an unobfuscated host class by its name, using the DexKit [queries] only when the name
     * does not load. Every caller's query matches that same class name inside the same package, so a
     * scan can only find a class the direct lookup already finds; running it first cost up to 1.1 s
     * of host main-thread time on a cold cache (SystemUI fingerprint views, Settings headers).
     */
    fun resolveAppClass(direct: Class<*>?, queries: Map<String, (DexKitBridge) -> String?>): Class<*>? {
        direct?.let { return it }
        val appInfo = owner.hookParam.appInfo ?: return null
        val baseDir = appInfo.deviceProtectedDataDir ?: appInfo.dataDir ?: return null
        val apkPath = appInfo.sourceDir ?: return null
        return DexRuntime.resolveClasses(
            cacheDir = java.io.File(baseDir, "cache"),
            apkPath = apkPath,
            classLoader = owner.classLoader,
            queries = queries
        ).values.firstOrNull()
    }
}
