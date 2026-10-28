package com.takekazex.hypertweak.util.update

import org.json.JSONObject
import java.util.Base64

/** Versioned hidden Release-body envelope, returned directly by GitHub's Releases API. */
internal object UpdateReleaseMetadata {
    private const val PREFIX = "<!-- hypertweak-update:v1:"
    private val marker = Regex(Regex.escape(PREFIX) + "([A-Za-z0-9+/=]+) -->")
    private val legacyApk = Regex("^HyperTweak-v(.+)-([0-9]+)-release(?:-debug-signed)?\\.apk$")

    fun parse(body: String): JSONObject? {
        if (!body.contains(PREFIX)) return null // Historical releases have no body metadata.
        val matches = marker.findAll(body).toList()
        if (matches.size != 1 || body.indexOf(PREFIX, body.indexOf(PREFIX) + PREFIX.length) != -1) {
            throw UpdateException.InvalidMetadata("Invalid release metadata marker")
        }
        return runCatching {
            JSONObject(String(Base64.getDecoder().decode(matches.single().groupValues[1]), Charsets.UTF_8))
        }.getOrElse { throw UpdateException.InvalidMetadata("Invalid release metadata payload") }
    }

    fun validateAsset(metadata: JSONObject, asset: JSONObject) {
        val digest = asset.optString("digest", "").removePrefix("sha256:")
        if (metadata.optString("apkAsset") != asset.optString("name") ||
            metadata.optLong("apkSize", -1) != asset.optLong("size", -2) ||
            (digest.isNotBlank() && !digest.equals(metadata.optString("apkSha256"), ignoreCase = true))
        ) {
            throw UpdateException.InvalidMetadata("Release body and APK asset disagree; check again")
        }
    }

    fun legacyVersionName(assetName: String): String? = legacyApk.matchEntire(assetName)?.groupValues?.get(1)

    fun legacyVersionCode(assetName: String): Long? =
        legacyApk.matchEntire(assetName)?.groupValues?.get(2)?.toLongOrNull()?.takeIf { it > 0 }
}
