package com.takekazex.hypertweak.util.update

import org.json.JSONArray
import org.json.JSONObject

object UpdateJsonCodec {
    fun encode(info: UpdateInfo): String {
        val json = JSONObject()
            .put("schema", 1)
            .put("channel", info.channel.wireValue)
            .put("tagName", info.tagName)
            .put("versionName", info.versionName)
            .put("shortCommit", info.shortCommit)
            .put("apkAsset", info.apkAsset)
            .put("releaseUrl", info.releaseUrl)
            .put("downloadUrl", info.downloadUrl)
            .put("metadataPresent", info.metadataPresent)
        info.versionCode?.let { json.put("versionCode", it) }
        info.commit?.let { json.put("commit", it) }
        info.builtAt?.let { json.put("builtAt", it) }
        info.compareBase?.let { json.put("compareBase", it) }
        info.apkSize?.let { json.put("apkSize", it) }
        info.apkSha256?.let { json.put("apkSha256", it) }
        info.minSdk?.let { json.put("minSdk", it) }
        info.targetSdk?.let { json.put("targetSdk", it) }
        info.publishedAt?.let { json.put("publishedAt", it) }
        info.distance?.let { json.put("distance", it) }
        info.notes?.let { notes ->
            json.put("notes", JSONObject().apply {
                notes.zh?.let { put("zh", it) }
                notes.en?.let { put("en", it) }
            })
        }
        json.put("commits", JSONArray().apply {
            info.commits.forEach { commit ->
                put(JSONObject().put("hash", commit.hash).put("subject", commit.subject).put("type", commit.type))
            }
        })
        return json.toString()
    }

    fun decode(raw: String): UpdateInfo? = runCatching {
        val json = JSONObject(raw)
        val channel = UpdateChannel.fromValue(json.optString("channel", null))
        val versionName = json.optString("versionName", "").takeIf { it.isNotBlank() } ?: return null
        val apkAsset = json.optString("apkAsset", "").takeIf { it.isNotBlank() } ?: return null
        val releaseUrl = json.optString("releaseUrl", "").takeIf { it.isNotBlank() } ?: return null
        val downloadUrl = json.optString("downloadUrl", "").takeIf { it.isNotBlank() } ?: return null
        val commits = mutableListOf<CommitChange>()
        json.optJSONArray("commits")?.let { array ->
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                commits += CommitChange(
                    hash = item.optString("hash", ""),
                    subject = item.optString("subject", ""),
                    type = item.optString("type", "other")
                )
            }
        }
        val notes = json.optJSONObject("notes")?.let {
            UpdateNotes(
                zh = it.optString("zh", "").takeIf(String::isNotBlank),
                en = it.optString("en", "").takeIf(String::isNotBlank)
            )
        }
        UpdateInfo(
            channel = channel,
            tagName = json.optString("tagName", channel.defaultTag),
            versionName = versionName,
            versionCode = json.longOrNull("versionCode"),
            commit = json.optString("commit", "").takeIf(String::isNotBlank),
            shortCommit = json.optString("shortCommit", ""),
            builtAt = json.optString("builtAt", "").takeIf(String::isNotBlank),
            compareBase = json.optString("compareBase", "").takeIf(String::isNotBlank),
            apkAsset = apkAsset,
            apkSize = json.longOrNull("apkSize"),
            apkSha256 = json.optString("apkSha256", "").takeIf(String::isNotBlank),
            minSdk = json.intOrNull("minSdk"),
            targetSdk = json.intOrNull("targetSdk"),
            notes = notes,
            releaseUrl = releaseUrl,
            downloadUrl = downloadUrl,
            publishedAt = json.optString("publishedAt", "").takeIf(String::isNotBlank),
            distance = json.intOrNull("distance"),
            commits = commits,
            metadataPresent = json.optBoolean("metadataPresent", true)
        )
    }.getOrNull()

    private fun JSONObject.longOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key).takeIf { it > 0L } else null

    private fun JSONObject.intOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key).takeIf { it > 0 } else null
}
