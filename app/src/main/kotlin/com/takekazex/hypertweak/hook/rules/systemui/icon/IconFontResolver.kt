package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.graphics.Typeface
import com.takekazex.hypertweak.util.DebugLog
import io.github.libxposed.api.XposedModule

/**
 * Resolves an icon-only font from the module's Xposed remote-file channel. A user-selected path is
 * never opened in SystemUI; an unavailable remote asset falls back to an explicit system family
 * and numeric weight.
 */
object IconFontResolver {
    private const val TAG = "IconTuner"

    fun resolve(
        module: XposedModule,
        remoteFileName: String,
        weight: Int,
        fallbackFamily: String = "sans-serif"
    ): Typeface {
        val safeWeight = weight.coerceIn(100, 900)
        runCatching {
            val descriptor = module.openRemoteFile(remoteFileName)
            try {
                return Typeface.Builder(descriptor.fileDescriptor)
                    .setFontVariationSettings("'wght' $safeWeight")
                    .build()
            } finally {
                descriptor.close()
            }
        }.onFailure { DebugLog.d(TAG, "icon font unavailable; using $fallbackFamily (${it.javaClass.simpleName})") }
        return Typeface.create(Typeface.create(fallbackFamily, Typeface.NORMAL), safeWeight, false)
    }
}
