package com.takekazex.hypertweak.util

/**
 * Asks a hooked system process to start an activity the module itself cannot reach.
 *
 * The broadcast carries a target *key*, never a component name: the receiver runs as uid system, so
 * the component it may start is resolved from a hardcoded allow-list on the receiving side.
 */
object ProxyLaunchProtocol {
    const val ACTION = "com.takekazex.hypertweak.action.PROXY_LAUNCH"
    const val PERMISSION = "com.takekazex.hypertweak.permission.RESTART_SCOPE"
    const val EXTRA_TARGET = "target"

    /** GMS's Extend Unlock (Smart Lock) configuration screen, which HyperOS hides. */
    const val TARGET_EXTEND_UNLOCK = "extend_unlock"
}
