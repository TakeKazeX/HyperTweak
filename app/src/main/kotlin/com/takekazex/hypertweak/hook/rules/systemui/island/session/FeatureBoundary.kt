package com.takekazex.hypertweak.hook.rules.systemui.island.session

import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandFeature
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandSession
import com.takekazex.hypertweak.util.DebugLog

/**
 * Feature-scoped failure boundary. Core failures mark the session failed and restore native
 * scheduling; spectrum/lyric failures only degrade their own feature.
 */
internal class FeatureBoundary(
    private val context: CallbackContext,
    private val logger: (String, String, Throwable?, Boolean) -> Unit = { tag, message, error, soft ->
        when {
            error == null -> DebugLog.w(tag, message)
            soft -> DebugLog.w(tag, message, error)
            else -> DebugLog.e(tag, message, error)
        }
    },
) {
    var restorer: ((IslandSession) -> Unit)? = null

    fun <T> core(session: IslandSession, feature: IslandFeature, operation: String, block: () -> T): T? =
        run(session, feature, operation, block, restore = true)

    fun <T> soft(session: IslandSession, feature: IslandFeature, operation: String, block: () -> T): T? =
        run(session, feature, operation, block, restore = false)

    private inline fun <T> run(
        session: IslandSession,
        feature: IslandFeature,
        operation: String,
        block: () -> T,
        restore: Boolean,
    ): T? = try {
        block()
    } catch (error: Throwable) {
        if (restore) {
            session.health.fail(feature)
            context.transaction?.settled = true
            logger(TAG, "$operation failed; restoring native scheduling", error, false)
            runCatching { restorer?.invoke(session) }
                .onFailure { logger(TAG, "native restoration failed", it, false) }
        } else {
            session.health.degrade(feature)
            logger(TAG, "$operation degraded; native presentation retained", error, true)
        }
        null
    }

    private companion object { const val TAG = "ThreeIslands" }
}
