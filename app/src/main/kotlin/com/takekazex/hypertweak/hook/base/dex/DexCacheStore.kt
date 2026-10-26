package com.takekazex.hypertweak.hook.base.dex

import com.takekazex.hypertweak.util.logging.DebugLog
import java.io.File
import java.util.Properties
import com.takekazex.hypertweak.hook.base.DexKitCacheIdentity

/**
 * Shared on-disk cache for [DexFeature] results.
 *
 * One file per feature under the target package's cache directory. Identity is the module cache
 * format, the feature id/schema, and the APK identity (metadata plus every split). Values are plain
 * strings (`v.<key>`), which also makes the file importable from an external DexSQL run.
 *
 * [read] and [write] take the identity explicitly. They used to default it to a fresh capture,
 * which silently re-hashed a multi-hundred-megabyte APK whenever a caller forgot to pass one;
 * removing the default makes every such call site a compile error instead of a hidden cost.
 */
internal class DexCacheStore(
    private val feature: DexFeature,
    cacheDir: File?,
) {
    private val file: File? = cacheDir?.let {
        File(it, FILE_PREFIX + feature.id.replace(UNSAFE, "_") + ".properties")
    }

    fun read(apk: File, identity: DexKitCacheIdentity): Map<String, String>? {
        val target = file ?: return null
        if (!target.isFile || target.length() > MAX_BYTES) return null
        val properties = Properties()
        runCatching { target.inputStream().use(properties::load) }.onFailure {
            DebugLog.d(TAG, "cannot read " + target.name + ": " + it.message)
            return null
        }
        if (properties.getProperty("format") != FORMAT) return null
        if (properties.getProperty("feature") != feature.id) return null
        if (properties.getProperty("schema") != feature.schema.toString()) return null
        if (properties.getProperty("apk_path") != apk.absolutePath) return null
        if (!identity.matches(properties)) return null
        return buildMap {
            for ((key, value) in properties) {
                if (key !is String || value !is String) continue
                if (!key.startsWith(VALUE_PREFIX)) continue
                put(key.removePrefix(VALUE_PREFIX), value)
            }
        }
    }

    fun write(
        apk: File,
        values: Map<String, String>,
        identity: DexKitCacheIdentity,
        backend: DexBackendId = DexBackendId.DEXKIT,
    ) {
        if (apk.length() != identity.size || apk.lastModified() != identity.modified) return
        val target = file ?: return
        var temporary: File? = null
        runCatching {
            val directory = target.parentFile ?: return
            if (!directory.isDirectory && !directory.mkdirs()) return
            val properties = Properties().apply {
                setProperty("format", FORMAT)
                setProperty("feature", feature.id)
                setProperty("schema", feature.schema.toString())
                setProperty("backend", backend.wireName)
                setProperty("apk_path", apk.absolutePath)
                setProperty("writer_pid", android.os.Process.myPid().toString())
                setProperty("written_at", System.currentTimeMillis().toString())
                identity.stamp(this)
                values.forEach { (key, value) -> setProperty(VALUE_PREFIX + key, value) }
            }
            val staged = File.createTempFile(target.name, ".tmp", directory)
            temporary = staged
            staged.outputStream().use {
                properties.store(it, "HyperTweak DEX cache: " + feature.id + " schema=" + feature.schema)
            }
            check(staged.length() <= MAX_BYTES) { "DEX cache too large" }
            // rename replaces atomically on Android/Linux. Never delete a valid cache on failure.
            check(staged.renameTo(target)) { "could not publish " + target.absolutePath }
        }.onFailure {
            DebugLog.d(TAG, "cannot write " + target.name + ": " + it.message)
        }
        temporary?.takeIf(File::exists)?.delete()
    }

    /** True when a readable cache file exists for this feature, regardless of identity. */
    fun exists(): Boolean = file?.let { it.isFile && it.length() <= MAX_BYTES } == true

    private companion object {
        const val TAG = "DexCache"
        const val FORMAT = "4"
        const val MAX_BYTES = 1024 * 1024L
        const val FILE_PREFIX = "hypertweak_dex_"
        const val VALUE_PREFIX = "v."
        val UNSAFE = Regex("[^A-Za-z0-9_.-]")
    }
}
