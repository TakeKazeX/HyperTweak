package com.takekazex.hypertweak.hook.base.dex

/**
 * A named DEX resolution that can be cached and replayed across process starts.
 *
 * Implementations live next to the hooker that consumes them. [schema] must be bumped whenever the
 * query/evidence changes; the cache identity embeds it, so old entries are ignored rather than
 * decoded into a different target. `id` is also the cache namespace, so two features never share
 * a value map.
 */
internal interface DexFeature {
    val id: String
    val schema: Int
}

/**
 * Resolution backend identity.
 *
 * The cached values are plain method/class descriptors, deliberately backend-neutral: a result
 * produced by DexKit today can be validated and consumed after a future DexSQL import, as long as
 * the APK identity and [DexFeature.schema] still match.
 */
internal enum class DexBackendId(val wireName: String) {
    DEXKIT("dexkit"),

    /** Identity for externally produced descriptor caches; there is no in-process DexSQL backend. */
    DEXSQL("dexsql"),
}
