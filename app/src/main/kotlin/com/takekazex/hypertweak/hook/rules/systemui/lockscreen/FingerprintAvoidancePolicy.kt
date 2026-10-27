package com.takekazex.hypertweak.hook.rules.systemui.lockscreen

/** The two verified OEM transforms, evaluated from live native inputs, without writing them. */
internal object FingerprintAvoidancePolicy {
    data class Geometry(
        val normalPadding: Int,
        val fingerprintPadding: Int,
        val remoteViewY: Int,
        val indicationTop: Int,
        val hasUnderDisplaySensor: Boolean,
        val fingerprintY: Int,
        val fingerprintOffset: Int,
        val lowPosition: Boolean,
        val numberHeight: Int,
        val numberPadding: Int,
    )

    /** Null means pass through the latest upstream value, including another module's policy. */
    fun bottom(mode: Int, g: Geometry): Int? {
        if (mode !in 1..2) return null
        val avoids = mode == 2 && g.hasUnderDisplaySensor
        val bottom = if (avoids) {
            g.fingerprintY + g.fingerprintOffset -
                if (g.fingerprintPadding + g.fingerprintOffset > 0) g.fingerprintPadding else 0
        } else {
            (if (g.remoteViewY > 0) g.remoteViewY else g.indicationTop) - g.normalPadding
        }
        if (bottom <= 0) return 0
        return if (avoids && g.lowPosition) bottom - g.numberHeight - g.numberPadding else bottom
    }
}

/** Module publications never become the native baseline; only upstream writes advance it. */
internal class FingerprintBoundProjection(initial: Int) {
    var upstream: Int = initial
        private set
    fun observe(value: Int, override: Int?): Int {
        upstream = value
        return override ?: value
    }
    fun effective(override: Int?): Int = override ?: upstream
}
