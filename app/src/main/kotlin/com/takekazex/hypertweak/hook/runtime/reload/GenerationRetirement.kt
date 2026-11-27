package com.takekazex.hypertweak.hook.runtime.reload

import com.takekazex.hypertweak.hook.base.HostMembers
import com.takekazex.hypertweak.util.logging.DebugLog

/**
 * Process-wide, feature-independent retirement of one module generation.
 *
 * A retired generation is freed only when nothing in the host reaches its module loader. These
 * steps release what features do not own, in an order that matters:
 * 1. the replacement-dex worker of this generation stops;
 * 2. host `Class`-keyed caches drop this generation's classes (owners have unregistered by now);
 * 3. host `ClassValue` reflection caches retire (earlier restoration still reflects through them);
 * 4. the logger retires and never recreates its worker;
 * 5. the bundled kotlinx scheduler is shut down only if something started it (hook code uses
 *    `HookDispatchers.IO`, which needs no teardown); see `ModuleDispatchers`.
 *
 * Add a new process-wide retirement here, never directly in `HotReloadOrchestrator`.
 */
internal object GenerationRetirement {
    private const val SKIPPED_STATE = "HyperTweak.SkippedGeneration"

    /** Saved-state entry the replacement passes back to [adopt]. Holds only boot-class objects. */
    fun savedState(): Pair<String, Any?> = RetiredDexFiles.STATE_KEY to RetiredDexFiles.saveForReplacement()

    /** Runs after feature owners and process publishers have retired. */
    fun finalSteps(): List<() -> Unit> = listOf(
        RetiredDexFiles::stop,
        HostClassCaches::purge,
        HostMembers::retire,
        DebugLog::prepareForHotReload,
        ModuleDispatchers::retire,
    )

    /** A rejected preflight keeps this generation: undo the freeze [savedState] applied. */
    fun cancel() = RetiredDexFiles.resume()

    fun adopt(hookerStates: Map<String, Any?>?) = RetiredDexFiles.adopt(hookerStates?.get(RetiredDexFiles.STATE_KEY))

    /** A process this module never hooks still loads every generation; it must free them too. */
    fun retireSkipped(): Any {
        val state = arrayOf<Any?>(SKIPPED_STATE, savedState().second)
        finalSteps().forEach { step -> runCatching(step) }
        return state
    }

    fun adoptSkipped(state: Any?) {
        val array = state as? Array<*> ?: return
        if (array.getOrNull(0) == SKIPPED_STATE) RetiredDexFiles.adopt(array.getOrNull(1))
    }
}
