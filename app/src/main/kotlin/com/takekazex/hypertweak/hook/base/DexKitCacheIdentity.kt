package com.takekazex.hypertweak.hook.base

import java.util.Properties

/** One identity owns every key in the shared cache, including keys not requested this time. */
internal data class DexKitCacheIdentity(val modified: Long, val size: Long, val sha256: String) {
    fun matches(properties: Properties): Boolean =
        properties.getProperty("cache_schema") == SCHEMA &&
            properties.getProperty("apk_last_modified") == modified.toString() &&
            properties.getProperty("apk_file_size") == size.toString() &&
            properties.getProperty("apk_sha256") == sha256

    fun prepare(properties: Properties): Boolean {
        val valid = matches(properties)
        if (!valid) properties.clear()
        return valid
    }

    fun stamp(properties: Properties) {
        properties.setProperty("cache_schema", SCHEMA)
        properties.setProperty("apk_last_modified", modified.toString())
        properties.setProperty("apk_file_size", size.toString())
        properties.setProperty("apk_sha256", sha256)
    }

    private companion object { const val SCHEMA = "2" }
}
