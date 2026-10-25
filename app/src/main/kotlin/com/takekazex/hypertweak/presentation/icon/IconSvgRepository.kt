package com.takekazex.hypertweak.presentation.icon

import com.takekazex.hypertweak.settings.contract.SignalSvgStyle

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.LinkedHashMap

/** Built-in source assets copied from the verified XiaomiHelper 26.08.3 package. */
enum class IconSvgAsset(val fileName: String, val kind: SvgKind) {
    SIGNAL_HYPEROS3_SINGLE("Signal-HyperOS3-Single.svg", SvgKind.SINGLE_SIGNAL),
    SIGNAL_IOS27_SINGLE("Signal-iOS27-Single.svg", SvgKind.SINGLE_SIGNAL),
    SIGNAL_HYPEROS3_STACKED("Signal-HyperOS3-Stacked.svg", SvgKind.STACKED_SIGNAL),
    SIGNAL_IOS27_STACKED("Signal-iOS27-Stacked.svg", SvgKind.STACKED_SIGNAL),

    /** Wi-Fi artwork for the two-line control-center carrier block (same HyperOS 3 family). */
    WIFI_HYPEROS3("Wifi-HyperOS3.svg", SvgKind.WIFI)
}

/** A validated source and its parsed renderer document. */
data class IconSvgSnapshot(
    val kind: SvgKind,
    val source: String,
    val sourceHash: String,
    val displayName: String,
    val document: IconSvgRenderer.Document
)

/** Bounded reader and validated document cache for bundled signal and Wi-Fi artwork. */
class IconSvgRepository(private val context: Context) {
    companion object {
        private const val ASSET_ROOT = "svg/"
        private const val CACHE_SIZE = 16

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

    fun loadSignalSingle(style: SignalSvgStyle): Result<IconSvgSnapshot> = loadBuiltIn(when (style) {
        SignalSvgStyle.HYPEROS3 -> IconSvgAsset.SIGNAL_HYPEROS3_SINGLE
        SignalSvgStyle.IOS27 -> IconSvgAsset.SIGNAL_IOS27_SINGLE
    })

    fun loadSignalStacked(style: SignalSvgStyle): Result<IconSvgSnapshot> = loadBuiltIn(when (style) {
        SignalSvgStyle.HYPEROS3 -> IconSvgAsset.SIGNAL_HYPEROS3_STACKED
        SignalSvgStyle.IOS27 -> IconSvgAsset.SIGNAL_IOS27_STACKED
    })

    fun loadWifi(): Result<IconSvgSnapshot> = loadBuiltIn(IconSvgAsset.WIFI_HYPEROS3)

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
}
