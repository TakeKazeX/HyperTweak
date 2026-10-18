package com.takekazex.hypertweak.hook.base.dex

import com.takekazex.hypertweak.util.DebugLog
import java.io.File
import java.util.Properties

/**
 * Shared on-disk cache for [DexFeature] results.
 *
 * One file per feature under the target package's cache directory. Identity is the module cache
 * format, the feature id/schema, and the APK path/size/mtime; PackageManager changes the install
 * path and mtime when an update lands, so a stale descriptor set can never be replayed against a
 * different build. Values are plain strings (`v.<key>`), which also makes the file importable from
 * an external DexSQL run.
 */
internal class DexCacheStore(
    private val feature: DexFeature,
    cacheDir: File?,
) {
    private val file: File? = cacheDir?.let {
        File(it, "$FILE_PREFIX${feature.id.replace(UNSAFE, "_")}.properties")
    }

    fun read(apk: File): Map<String, String>? {
        val target = file ?: return null
        if (!target.isFile) return null
        val properties = Properties()
        runCatching { target.inputStream().use(properties::load) }.onFailure {
            DebugLog.d(TAG, "cannot read ${target.name}: ${it.message}")
            return null
        }
        if (properties.getProperty("format") != FORMAT) return null
        if (properties.getProperty("feature") != feature.id) return null
        if (properties.getProperty("schema") != feature.schema.toString()) return null
        if (properties.getProperty("apk_path") != apk.absolutePath) return null
        if (properties.getProperty("apk_size") != apk.length().toString()) return null
        if (properties.getProperty("apk_modified") != apk.lastModified().toString()) return null
        return buildMap {
            for ((key, value) in properties) {
                if (key !is String || value !is String) continue
                if (!key.startsWith(VALUE_PREFIX)) continue
                put(key.removePrefix(VALUE_PREFIX), value)
            }
        }
    }

    fun write(apk: File, values: Map<String, String>) {
        val target = file ?: return
        runCatching {
            val directory = target.parentFile ?: return
            if (!directory.isDirectory && !directory.mkdirs()) return
            val properties = Properties().apply {
                setProperty("format", FORMAT)
                setProperty("feature", feature.id)
                setProperty("schema", feature.schema.toString())
                setProperty("backend", DexBackends.preferred().wireName)
                setProperty("apk_path", apk.absolutePath)
                setProperty("apk_size", apk.length().toString())
                setProperty("apk_modified", apk.lastModified().toString())
                values.forEach { (key, value) -> setProperty(VALUE_PREFIX + key, value) }
            }
            val temporary = File(directory, "${target.name}.${System.nanoTime()}.tmp")
            temporary.outputStream().use {
                properties.store(it, "HyperTweak DEX cache: ${feature.id} schema=${feature.schema}")
            }
            if (!temporary.renameTo(target)) {
                target.delete()
                check(temporary.renameTo(target)) { "could not publish ${target.absolutePath}" }
            }
        }.onFailure {
            DebugLog.d(TAG, "cannot write ${target.name}: ${it.message}")
        }
    }

    private companion object {
        const val TAG = "DexCache"
        const val FORMAT = "1"
        const val FILE_PREFIX = "hypertweak_dex_"
        const val VALUE_PREFIX = "v."
        val UNSAFE = Regex("[^A-Za-z0-9_.-]")
    }
}
