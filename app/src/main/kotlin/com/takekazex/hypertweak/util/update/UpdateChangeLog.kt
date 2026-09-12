package com.takekazex.hypertweak.util.update

data class ChangeGroup(
    val type: String,
    val items: List<String>
)

object UpdateChangeLog {
    private val conventionalPrefix = Regex("^([A-Za-z0-9_-]+)(?:\\([^)]*\\))?:")

    fun group(commits: List<CommitChange>): List<ChangeGroup> {
        val order = listOf("feat", "fix", "perf", "refactor", "style", "docs", "build", "ci", "chore", "other")
        val grouped = linkedMapOf<String, MutableList<String>>()
        commits.forEach { commit ->
            val type = commit.type.lowercase().ifBlank { "other" }
            grouped.getOrPut(type) { mutableListOf() }.add(commit.subject.trim())
        }
        return grouped
            .toList()
            .sortedWith(compareBy({ order.indexOf(it.first).let { index -> if (index < 0) Int.MAX_VALUE else index } }, { it.first }))
            .map { ChangeGroup(it.first, it.second.toList()) }
    }

    fun typeForSubject(subject: String): String =
        conventionalPrefix.find(subject.trim())?.groupValues?.getOrNull(1)?.lowercase() ?: "other"

    fun parseCommit(hash: String, subject: String): CommitChange =
        CommitChange(hash = hash, subject = subject, type = typeForSubject(subject))
}
