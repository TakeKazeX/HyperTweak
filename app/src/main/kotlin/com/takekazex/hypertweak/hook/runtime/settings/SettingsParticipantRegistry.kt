package com.takekazex.hypertweak.hook.runtime.settings

/** Validate the complete process plan before mutating the live dispatcher. */
internal class SettingsParticipantRegistry(private val expected: Set<String>) {
    private val participants = linkedMapOf<String, (Set<String>) -> Boolean>()

    fun register(id: String, apply: (Set<String>) -> Boolean) {
        check(id !in participants) { "duplicate settings participant: $id" }
        participants[id] = apply
    }

    /**
     * [always] participants are registered even when the current process target does not own
     * their group yet. The dispatcher still filters each delivery by the live target set, so a
     * keyboard selected after its process started can be refreshed without restarting it.
     */
    fun install(always: Set<String> = emptySet(), register: (String, (Set<String>) -> Boolean) -> Unit) {
        check(participants.keys.containsAll(expected)) {
            "missing settings participants: ${expected - participants.keys}"
        }
        check(participants.keys.containsAll(always)) {
            "missing always-registered settings participants: ${always - participants.keys}"
        }
        participants.filterKeys { it in expected || it in always }.forEach(register)
    }
}
