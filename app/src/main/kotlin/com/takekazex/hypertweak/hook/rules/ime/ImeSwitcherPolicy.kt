package com.takekazex.hypertweak.hook.rules.ime

/** The queried IME is different from the authenticated keyboard asking for its switcher list. */
internal object ImeSwitcherPolicy {
    fun mayAccess(target: String, enabled: Set<String>, callerPackages: Set<String>, selected: Set<String>): Boolean =
        target in enabled && callerPackages.any { it in selected }
}
