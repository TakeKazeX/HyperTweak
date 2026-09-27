package com.takekazex.hypertweak.hook.rules.camera

internal object CameraIdentity {


    const val MASTER_LIVE_MODE_ID = 231

    const val LEGENDARY_MOMENT_MODE_ID = 256

    val MASTER_LIVE_FOCAL_STOPS = floatArrayOf(0.7f, 1.0f, 2.0f, 5.0f, 10.0f)

    fun masterLiveFocalStops(existingValue: Any?): Any =
        if (existingValue is FloatArray) MASTER_LIVE_FOCAL_STOPS else MASTER_LIVE_FOCAL_STOPS.toTypedArray()

    const val MODE_LIST_MORE_MARKER = 254

    fun frontMasterLiveMode(current: IntArray?): IntArray? {
        if (current == null || current.contains(MASTER_LIVE_MODE_ID)) return null
        return intArrayOf(MASTER_LIVE_MODE_ID) + current
    }

    fun defaultModeListShape(list: IntArray?): Boolean =
        list != null && list.contains(MASTER_LIVE_MODE_ID) && list.contains(MODE_LIST_MORE_MARKER)

    fun placeMasterLiveModeBeforeMarker(order: IntArray?): IntArray? {
        if (order == null || order.isEmpty()) return null
        val firstMarker = order.indexOfFirst { it == MODE_LIST_MORE_MARKER }
        val currentIndex = order.indexOfFirst { it == MASTER_LIVE_MODE_ID }
        if (currentIndex >= 0 && (firstMarker < 0 || currentIndex < firstMarker)) return null

        val mutable = order.toMutableList()
        if (currentIndex >= 0) {
            mutable.removeAt(currentIndex)
        }
        // Re-locate the marker after a removal may have shifted indices (only when 231 sat
        // before the marker, which the guard above already excluded — kept defensive).
        val insertAt = mutable.indexOfFirst { it == MODE_LIST_MORE_MARKER }.takeIf { it >= 0 } ?: 0
        mutable.add(insertAt, MASTER_LIVE_MODE_ID)
        return mutable.toIntArray()
    }

    /** Compare the caller's verified live-host value surface, including fresh arrays. */
    fun sharesImagingIdentity(candidate: Any, original: Any, getters: List<java.lang.reflect.Method>): Boolean =
        getters.isNotEmpty() && getters.all { getter ->
            val candidateValue = runCatching { getter.invoke(candidate) }
            val originalValue = runCatching { getter.invoke(original) }
            candidateValue.isSuccess && originalValue.isSuccess &&
                valueEquals(candidateValue.getOrNull(), originalValue.getOrNull())
        }

    fun valueEquals(a: Any?, b: Any?): Boolean {
        if (a == null || b == null) return a == null && b == null
        if (a.javaClass != b.javaClass) return false
        if (!a.javaClass.isArray) return a == b
        val length = java.lang.reflect.Array.getLength(a)
        if (length != java.lang.reflect.Array.getLength(b)) return false
        for (i in 0 until length) {
            if (!valueEquals(java.lang.reflect.Array.get(a, i), java.lang.reflect.Array.get(b, i))) {
                return false
            }
        }
        return true
    }
}
