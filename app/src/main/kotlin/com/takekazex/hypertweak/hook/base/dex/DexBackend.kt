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

    /**
     * DexSQL ships as an external CLI/MCP binary (closed source, no embeddable library), so it
     * cannot answer queries inside a hooked process today. It is registered as an unavailable
     * backend so feature code and cache plumbing are already backend-aware; an adapter can be
     * dropped in when a library build exists.
     */
    DEXSQL("dexsql"),
}

internal interface DexBackend {
    val id: DexBackendId
    fun available(): Boolean
}

internal object DexKitBackend : DexBackend {
    override val id = DexBackendId.DEXKIT
    override fun available(): Boolean = true
}

internal object DexSqlBackend : DexBackend {
    override val id = DexBackendId.DEXSQL
    override fun available(): Boolean = false
}

internal object DexBackends {
    private val all = listOf(DexKitBackend, DexSqlBackend)

    fun available(): List<DexBackend> = all.filter { it.available() }

    /** First available backend; DexKit remains the only in-process engine. */
    fun preferred(): DexBackendId = available().firstOrNull()?.id ?: DexBackendId.DEXKIT

    fun summary(): String = all.joinToString(",") { "${it.id.wireName}=${it.available()}" }
}
