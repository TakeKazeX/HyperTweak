package com.takekazex.hypertweak.hook.rules.system

internal object PickerReroutePolicy {
    fun handles(action: String?, explicit: Boolean = false): Boolean = !explicit && when (action) {
        "android.intent.action.GET_CONTENT", "android.provider.action.PICK_IMAGES",
        "android.provider.action.PICK_IMAGES_MAX" -> true
        else -> false
    }

    fun enabled(scoped: Boolean?, legacy: Boolean): Boolean = scoped ?: legacy

    fun <T> filter(candidates: List<T>, packageName: (T) -> String?): List<T> {
        if (candidates.none { packageName(it) == "com.android.photopicker" }) return candidates
        return candidates.filterTo(ArrayList(candidates.size)) { packageName(it) != "com.android.photopicker" }
    }
}
