package com.takekazex.hypertweak.settings

/** Validate the complete process plan before mutating the live dispatcher. */
internal class SettingsParticipantRegistry(private val expected: Set<String>) {
    private val participants = linkedMapOf<String, (Set<String>) -> Boolean>()

    fun register(id: String, apply: (Set<String>) -> Boolean) {
        check(id !in participants) { "duplicate settings participant: $id" }
        participants[id] = apply
    }

    fun install(register: (String, (Set<String>) -> Boolean) -> Unit) {
        check(participants.keys.containsAll(expected)) {
            "missing settings participants: ${expected - participants.keys}"
        }
        participants.filterKeys { it in expected }.forEach(register)
    }
}
