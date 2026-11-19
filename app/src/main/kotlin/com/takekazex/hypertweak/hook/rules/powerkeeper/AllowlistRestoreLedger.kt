package com.takekazex.hypertweak.hook.rules.powerkeeper

/**
 * Packages PowerKeeper removed from the user allowlist while they were on it. Each record allows
 * exactly one later add of that package; every add PowerKeeper makes consumes the record, so a
 * native restore while blocking is off leaves nothing behind.
 */
internal class AllowlistRestoreLedger {
    private val restorable = HashSet<String>()

    @Synchronized fun recordRemoval(pkg: String) { restorable += pkg }

    /** The packages to pass through; equals [requested] when [blocking] is off. */
    @Synchronized fun filterAdds(requested: List<String>, blocking: Boolean, alwaysAllowed: (String) -> Boolean): List<String> =
        requested.filter { pkg -> restorable.remove(pkg) || !blocking || alwaysAllowed(pkg) }

    @Synchronized fun snapshot(): HashSet<String> = HashSet(restorable)

    @Synchronized fun restore(saved: Collection<*>) { saved.forEach { if (it is String) restorable += it } }
}
