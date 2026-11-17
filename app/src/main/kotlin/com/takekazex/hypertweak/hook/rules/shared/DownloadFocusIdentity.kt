package com.takekazex.hypertweak.hook.rules.shared

/** Identity of content injected by the download provider, never inferred from a title/view ID. */
internal object DownloadFocusIdentity {
    const val PACKAGE = "com.android.providers.downloads"
    const val NOTIFICATION_ID = 1089
    const val EXTRA_TASK_ID = "hypertweak.download.focus.task_id"

    fun taskId(tag: String?): Long? {
        val parts = tag?.split(':') ?: return null
        val supported = (parts.size == 3 && parts[0] == "1") ||
            (parts.size == 4 && parts[0] == "3" && parts[2] == "success")
        if (!supported || parts[1].isBlank()) return null
        return parts.last().toLongOrNull()?.takeIf { it > 0 }
    }

    fun owns(packageName: String?, notificationId: Int, tag: String?, taskId: Long): Boolean =
        packageName == PACKAGE && notificationId == NOTIFICATION_ID && taskId > 0 && this.taskId(tag) == taskId
}
