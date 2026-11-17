package com.takekazex.hypertweak.hook.rules.downloads

/** Preserve the native active/complete tag type so its channels, intents and cleanup still run. */
internal object DownloadFocusTaskPolicy {
    fun splitTag(native: String?, id: Long, sourcePackage: String): String? {
        if (id <= 0 || sourcePackage.isBlank()) return null
        return when {
            native?.substringBefore(':') in setOf("1", "2", "5") -> "1:$sourcePackage:$id"
            native == "3:$sourcePackage:success" -> "$native:$id"
            else -> null
        }
    }

    fun completed(tag: String): Boolean = tag.startsWith("3:") && tag.substringBeforeLast(':').endsWith(":success")

    /** Replaying a native notifier is all-or-nothing: unknown metadata must not cancel peers. */
    fun <T : Any> postedTasks(known: Map<Long, T>, postedIds: List<LongArray?>): List<T>? {
        if (postedIds.isEmpty()) return null
        val ids = LinkedHashSet<Long>()
        for (represented in postedIds) {
            if (represented == null || represented.isEmpty() || represented.any { it <= 0 || it !in known }) return null
            ids.addAll(represented.toList())
        }
        return ids.map { known.getValue(it) }
    }
}
