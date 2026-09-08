package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.XposedServiceManager
import com.takekazex.hypertweak.util.DebugLog
import io.github.libxposed.service.XposedService
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.LinkedHashMap
import java.util.Locale

/** Built-in source assets copied from the verified XiaomiHelper 26.08.3 package. */
enum class IconSvgAsset(val fileName: String, val kind: SvgKind) {
    SIGNAL_HYPEROS3_SINGLE("Signal-HyperOS3-Single.svg", SvgKind.SINGLE_SIGNAL),
    SIGNAL_IOS26_SINGLE("Signal-iOS26-Single.svg", SvgKind.SINGLE_SIGNAL),
    SIGNAL_IOS27_SINGLE("Signal-iOS27-Single.svg", SvgKind.SINGLE_SIGNAL),
    SIGNAL_HYPEROS3_STACKED("Signal-HyperOS3-Stacked.svg", SvgKind.STACKED_SIGNAL),
    SIGNAL_IOS26_STACKED("Signal-iOS26-Stacked.svg", SvgKind.STACKED_SIGNAL),
    SIGNAL_IOS27_STACKED("Signal-iOS27-Stacked.svg", SvgKind.STACKED_SIGNAL)
}

/** A validated source and its parsed renderer document. */
data class IconSvgSnapshot(
    val kind: SvgKind,
    val source: String,
    val sourceHash: String,
    val displayName: String,
    val remoteFileName: String? = null,
    val document: IconSvgRenderer.Document
)

/**
 * Module-side SVG content store.
 *
 * Imported content is validated before a hash-named remote file is written. Preferences publish
 * the new filename/hash only after the write is complete, so SystemUI never opens an app-private
 * path or a partially written file. The same bounded reader is usable from a hooked process by
 * passing XposedInterface/XposedModule's read-only opener.
 */
class IconSvgRepository(private val context: Context) {
    companion object {
        private const val TAG = "IconTuner"
        private const val ASSET_ROOT = "svg/"
        private const val FILE_KEY_PREFIX = "icon_svg_"
        private const val FILE_KEY_SUFFIX = "_remote_file"
        private const val HASH_KEY_SUFFIX = "_hash"
        private const val NAME_KEY_SUFFIX = "_name"
        private const val CACHE_SIZE = 16

        /** Reads a remote file through either the module-app or hooked-process API. */
        fun readRemoteFile(
            name: String,
            opener: (String) -> ParcelFileDescriptor
        ): Result<String> = runCatching {
            require(validRemoteName(name)) { "invalid remote SVG filename" }
            ParcelFileDescriptor.AutoCloseInputStream(opener(name)).use { input ->
                decodeUtf8(readBounded(input))
            }
        }

        private fun validRemoteName(name: String): Boolean = name.isNotBlank() &&
            name.length <= 192 && name.none { it == '/' || it == '\\' || it == '.' }

        private fun readBounded(input: InputStream): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= IconSvgRenderer.MAX_SOURCE_BYTES) {
                    "SVG exceeds ${IconSvgRenderer.MAX_SOURCE_BYTES} bytes"
                }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }

        private fun decodeUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }

    private val parsedCache = object : LinkedHashMap<String, IconSvgSnapshot>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IconSvgSnapshot>?): Boolean =
            size > CACHE_SIZE
    }

    fun loadBuiltIn(asset: IconSvgAsset): Result<IconSvgSnapshot> {
        return runCatching {
            context.assets.open(ASSET_ROOT + asset.fileName).use { input ->
                decodeUtf8(readBounded(input))
            }
        }.fold(
            onSuccess = { source -> parseSnapshot(asset.kind, source, asset.fileName) },
            onFailure = { Result.failure(it) }
        )
    }

    /** Loads a user snapshot when valid, otherwise returns the supplied built-in fallback. */
    fun load(kind: SvgKind, fallback: IconSvgAsset): Result<IconSvgSnapshot> {
        val fileName = Preferences.getString(fileKey(kind), "")
        if (fileName.isNotBlank()) {
            val remote = readRemote(kind, fileName)
            if (remote.isSuccess) return remote
            DebugLog.w(TAG, "invalid remote SVG; falling back to built-in kind=$kind", remote.exceptionOrNull())
        }
        return loadBuiltIn(fallback)
    }

    /**
     * Host-process variant of [load]. SystemUI cannot use the module-app Xposed service object;
     * callers pass the hooked process' read-only remote-file opener instead.
     */
    fun loadForHost(
        kind: SvgKind,
        fallback: IconSvgAsset,
        opener: (String) -> ParcelFileDescriptor
    ): Result<IconSvgSnapshot> {
        val fileName = Preferences.getString(fileKey(kind), "")
        if (fileName.isNotBlank()) {
            val remote = readRemote(kind, fileName, opener)
            if (remote.isSuccess) return remote
            DebugLog.w(TAG, "invalid host SVG; falling back to built-in kind=$kind", remote.exceptionOrNull())
        }
        return loadBuiltIn(fallback)
    }

    /**
     * Resolves the single-row cellular SVG from the same t32 style key used by the source app.
     * Style 2 is the imported snapshot; the other values select only the matching built-in asset.
     */
    fun loadSignalSingle(
        style: Int,
        hostOpener: ((String) -> ParcelFileDescriptor)? = null
    ): Result<IconSvgSnapshot> = loadSignal(
        style = style,
        kind = SvgKind.SINGLE_SIGNAL,
        builtIn = when (style.coerceIn(0, 3)) {
            1 -> IconSvgAsset.SIGNAL_IOS26_SINGLE
            3 -> IconSvgAsset.SIGNAL_IOS27_SINGLE
            else -> IconSvgAsset.SIGNAL_HYPEROS3_SINGLE
        },
        hostOpener = hostOpener
    )

    /** Resolves the two-row cellular SVG from the same t32 style key used by the source app. */
    fun loadSignalStacked(
        style: Int,
        hostOpener: ((String) -> ParcelFileDescriptor)? = null
    ): Result<IconSvgSnapshot> = loadSignal(
        style = style,
        kind = SvgKind.STACKED_SIGNAL,
        builtIn = when (style.coerceIn(0, 3)) {
            1 -> IconSvgAsset.SIGNAL_IOS26_STACKED
            3 -> IconSvgAsset.SIGNAL_IOS27_STACKED
            else -> IconSvgAsset.SIGNAL_HYPEROS3_STACKED
        },
        hostOpener = hostOpener
    )

    private fun loadSignal(
        style: Int,
        kind: SvgKind,
        builtIn: IconSvgAsset,
        hostOpener: ((String) -> ParcelFileDescriptor)?
    ): Result<IconSvgSnapshot> {
        // Hyper Helper treats every non-built-in value as the imported style and falls back to
        // HyperOS3 when no valid imported snapshot exists. Keep that recovery behaviour while
        // allowing the host process to read the remote file through its own Xposed interface.
        return if (style.coerceIn(0, 3) == 2 && hostOpener != null) {
            loadForHost(kind, builtIn, hostOpener)
        } else if (style.coerceIn(0, 3) == 2) {
            load(kind, builtIn)
        } else {
            loadBuiltIn(builtIn)
        }
    }

    /** Imports a content URI in the module app and publishes a complete validated snapshot. */
    fun importFromUri(uri: Uri, kind: SvgKind, displayName: String? = null): Result<IconSvgSnapshot> =
        runCatching {
            val source = context.contentResolver.openInputStream(uri)?.use { input ->
                decodeUtf8(readBounded(input))
            } ?: error("could not open SVG content")
            importSource(source, kind, displayName ?: uri.toString().substringAfterLast('/'))
        }.fold(
            onSuccess = { it },
            onFailure = { Result.failure(it) }
        )

    /** Validates and publishes raw XML. The previous remote snapshot remains on any failure. */
    fun importSource(source: String, kind: SvgKind, displayName: String): Result<IconSvgSnapshot> {
        val parsed = parseSnapshot(kind, source, displayName)
        if (parsed.isFailure) return parsed
        val snapshot = parsed.getOrThrow()
        val service = XposedServiceManager.currentService
            ?: return Result.failure(IllegalStateException("Xposed service unavailable"))
        if (!Preferences.isInitialized) {
            return Result.failure(IllegalStateException("remote preferences unavailable"))
        }

        val remoteName = "ht_icon_svg_${kind.name.lowercase(Locale.US)}_${snapshot.sourceHash}"
        val oldName = Preferences.getString(fileKey(kind), "")
        runCatching {
            writeRemoteFile(service, remoteName, source)
            // Publish metadata only after the descriptor has been fully written and closed.
            Preferences.putStringSynchronous(fileKey(kind), remoteName)
            Preferences.putStringSynchronous(hashKey(kind), snapshot.sourceHash)
            Preferences.putStringSynchronous(nameKey(kind), displayName.ifBlank { "Imported SVG" })
            if (oldName.isNotBlank() && oldName != remoteName && validRemoteName(oldName)) {
                runCatching { service.deleteRemoteFile(oldName) }
                    .onFailure { DebugLog.w(TAG, "old SVG cleanup failed", it) }
            }
        }.onFailure { failure ->
            runCatching { service.deleteRemoteFile(remoteName) }
            return Result.failure(failure)
        }
        val published = snapshot.copy(
            displayName = displayName.ifBlank { "Imported SVG" },
            remoteFileName = remoteName
        )
        synchronized(parsedCache) { parsedCache[cacheKey(kind, snapshot.sourceHash)] = published }
        return Result.success(published)
    }

    /** Returns a bounded cached preview using the exact renderer used by the host hook. */
    fun preview(
        snapshot: IconSvgSnapshot,
        levelA: Int,
        levelB: Int = 0,
        config: IconSvgRenderConfig
    ): Result<android.graphics.Bitmap> = runCatching {
        when (snapshot.kind) {
            SvgKind.SINGLE_SIGNAL -> IconSvgRenderer.renderSingle(snapshot.document, levelA, config)
            SvgKind.STACKED_SIGNAL -> IconSvgRenderer.renderStacked(snapshot.document, levelA, levelB, config)
        }
    }

    private fun readRemote(kind: SvgKind, name: String): Result<IconSvgSnapshot> {
        val service = XposedServiceManager.currentService ?:
            return Result.failure(IllegalStateException("Xposed service unavailable"))
        return readRemote(kind, name) { service.openRemoteFile(it) }
    }

    private fun readRemote(
        kind: SvgKind,
        name: String,
        opener: (String) -> ParcelFileDescriptor
    ): Result<IconSvgSnapshot> {
        val hash = Preferences.getString(hashKey(kind), "")
        if (hash.isBlank()) return Result.failure(IllegalStateException("remote SVG hash missing"))
        synchronized(parsedCache) { parsedCache[cacheKey(kind, hash)]?.let { return Result.success(it) } }
        return readRemoteFile(name, opener)
            .mapCatching { parseSnapshot(kind, it, Preferences.getString(nameKey(kind), "Imported SVG")).getOrThrow() }
            .map { snapshot ->
                require(snapshot.sourceHash == hash) { "remote SVG hash mismatch" }
                val result = snapshot.copy(remoteFileName = name)
                synchronized(parsedCache) { parsedCache[cacheKey(kind, hash)] = result }
                result
            }
    }

    private fun writeRemoteFile(service: XposedService, name: String, source: String) {
        require(validRemoteName(name)) { "invalid remote SVG filename" }
        val bytes = source.toByteArray(Charsets.UTF_8)
        require(bytes.size <= IconSvgRenderer.MAX_SOURCE_BYTES) { "SVG exceeds size limit" }
        val descriptor = service.openRemoteFile(name)
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
            output.channel.truncate(0)
            output.write(bytes)
            output.flush()
        }
    }

    private fun parseSnapshot(kind: SvgKind, source: String, displayName: String): Result<IconSvgSnapshot> {
        return IconSvgRenderer.parse(source).fold(
            onSuccess = { document ->
                IconSvgRenderer.validate(document, kind).fold(
                    onSuccess = {
                        val key = cacheKey(kind, document.sourceHash)
                        val snapshot = IconSvgSnapshot(
                            kind,
                            source,
                            document.sourceHash,
                            displayName,
                            document = document
                        )
                        synchronized(parsedCache) { parsedCache[key] = snapshot }
                        Result.success(snapshot)
                    },
                    onFailure = { Result.failure(it) }
                )
            },
            onFailure = { Result.failure(it) }
        )
    }

    private fun cacheKey(kind: SvgKind, hash: String): String = "${kind.name}:$hash"
    private fun keyBase(kind: SvgKind): String = FILE_KEY_PREFIX + kind.name.lowercase(Locale.US)
    private fun fileKey(kind: SvgKind): String = keyBase(kind) + FILE_KEY_SUFFIX
    private fun hashKey(kind: SvgKind): String = keyBase(kind) + HASH_KEY_SUFFIX
    private fun nameKey(kind: SvgKind): String = keyBase(kind) + NAME_KEY_SUFFIX
}
