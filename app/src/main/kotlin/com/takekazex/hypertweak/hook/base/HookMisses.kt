package com.takekazex.hypertweak.hook.base

import com.takekazex.hypertweak.util.logging.DebugLog

/**
 * Reports a missing optional host member before an optional install chain skips it.
 *
 * `lookup()?.hook { ... }` otherwise leaves the feature partly uninstalled without any record.
 */
internal fun <T : Any> T?.orSkip(scope: String, target: String): T? = also {
    if (it == null) DebugLog.hookSkipped(scope, target, "not found on host")
}
