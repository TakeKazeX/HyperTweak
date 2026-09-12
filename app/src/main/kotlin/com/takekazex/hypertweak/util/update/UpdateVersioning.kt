package com.takekazex.hypertweak.util.update

object UpdateVersioning {
    fun isRemoteNewer(localVersionCode: Long, remoteVersionCode: Long): Boolean =
        remoteVersionCode > localVersionCode

    /**
     * The single source of truth for "is there an update" (decision D22).
     *
     * A release published before `build-info.json` existed carries no `versionCode`, so those are
     * compared by semantic version instead. An equal version code is never an update: Android
     * refuses to install a lower `versionCode`, and offering a same-code rebuild would show the user
     * an "update" that changes nothing.
     */
    fun isUpdateAvailable(
        localVersionCode: Long,
        localVersionName: String,
        remoteVersionCode: Long?,
        remoteVersionName: String
    ): Boolean = if (remoteVersionCode != null) {
        isRemoteNewer(localVersionCode, remoteVersionCode)
    } else {
        isRemoteVersionNewer(localVersionName, remoteVersionName)
    }

    fun distance(localVersionCode: Long, remoteVersionCode: Long): Int? =
        (remoteVersionCode - localVersionCode).takeIf { it > 0L }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()

    /**
     * Small semver fallback used only for releases published before build-info.json existed.
     * It intentionally ignores build metadata and compares prerelease identifiers in the usual
     * semver order; normal releases therefore remain independent of APK file-name formatting.
     */
    fun compareSemanticVersions(left: String, right: String): Int {
        val a = parse(left)
        val b = parse(right)
        if (a == null || b == null) return left.trim().compareTo(right.trim(), ignoreCase = true)

        for (index in 0..2) {
            val result = a.numbers[index].compareTo(b.numbers[index])
            if (result != 0) return result
        }
        return comparePrerelease(a.prerelease, b.prerelease)
    }

    fun isRemoteVersionNewer(localVersionName: String, remoteVersionName: String): Boolean =
        compareSemanticVersions(remoteVersionName, localVersionName) > 0

    private data class ParsedVersion(
        val numbers: List<Int>,
        val prerelease: List<String>
    )

    private fun parse(value: String): ParsedVersion? {
        val normalized = value.trim().removePrefix("v").removePrefix("V")
        val coreAndPre = normalized.split('-', limit = 2)
        val core = coreAndPre.firstOrNull() ?: return null
        val numbers = core.split('.', limit = 4).take(3).map { it.toIntOrNull() ?: return null }
        if (numbers.size != 3) return null
        val padded = numbers + List(3 - numbers.size) { 0 }
        val pre = coreAndPre.getOrNull(1)?.split('.') ?: emptyList()
        return ParsedVersion(padded, pre)
    }

    private fun comparePrerelease(left: List<String>, right: List<String>): Int {
        if (left.isEmpty() && right.isEmpty()) return 0
        if (left.isEmpty()) return 1
        if (right.isEmpty()) return -1
        val count = maxOf(left.size, right.size)
        for (index in 0 until count) {
            val a = left.getOrNull(index) ?: return -1
            val b = right.getOrNull(index) ?: return 1
            if (a == b) continue
            val aNumber = a.toIntOrNull()
            val bNumber = b.toIntOrNull()
            return when {
                aNumber != null && bNumber != null -> aNumber.compareTo(bNumber)
                aNumber != null -> -1
                bNumber != null -> 1
                else -> a.compareTo(b)
            }
        }
        return 0
    }
}
