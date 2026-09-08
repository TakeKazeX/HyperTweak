package com.takekazex.hypertweak.util

import com.takekazex.hypertweak.hook.Preferences

/** The two kinds of scope guidance shown on the Home page. */
enum class ScopePromptAction(val storageKey: String) {
    RESTORE("restore"),
    REMOVE("remove")
}

/** A permanently dismissible scope recommendation for one target package. */
data class ScopePrompt(
    val action: ScopePromptAction,
    val packageName: String
) {
    val id: String
        get() = "${action.storageKey}$SEPARATOR$packageName"

    companion object {
        private const val SEPARATOR = ":"

        fun parse(id: String): ScopePrompt? {
            val parts = id.split(SEPARATOR, limit = 2)
            if (parts.size != 2) return null
            val action = ScopePromptAction.entries.firstOrNull { it.storageKey == parts[0] }
                ?: return null
            val packageName = parts[1].trim()
            if (packageName.isEmpty()) return null
            return ScopePrompt(action, packageName)
        }
    }
}

/** Persists scope-prompt dismissals in the module's authoritative preferences. */
object ScopePromptStore {
    private val lock = Any()

    fun ignoredPrompts(): Set<ScopePrompt> = Preferences
        .getStringSet(Preferences.KEY_IGNORED_SCOPE_PROMPTS)
        .mapNotNull(ScopePrompt::parse)
        .toSet()

    fun ignoredIds(): Set<String> = Preferences.getStringSet(Preferences.KEY_IGNORED_SCOPE_PROMPTS)

    fun ignore(prompt: ScopePrompt) {
        update { it + prompt.id }
    }

    fun ignoreAll(prompts: Collection<ScopePrompt>) {
        if (prompts.isEmpty()) return
        update { it + prompts.map(ScopePrompt::id) }
    }

    fun unignore(prompt: ScopePrompt) {
        update { it - prompt.id }
    }

    private fun update(transform: (Set<String>) -> Set<String>) {
        synchronized(lock) {
            val current = Preferences.getStringSet(Preferences.KEY_IGNORED_SCOPE_PROMPTS)
            Preferences.putStringSet(Preferences.KEY_IGNORED_SCOPE_PROMPTS, transform(current))
            // The Home card refreshes immediately after the click; make the dismissal visible to
            // the next read instead of racing the serialized remote-preference writer.
            Preferences.flush()
        }
    }
}
