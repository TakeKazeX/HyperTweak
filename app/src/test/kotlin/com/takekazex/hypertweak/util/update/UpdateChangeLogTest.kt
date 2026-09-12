package com.takekazex.hypertweak.util.update

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateChangeLogTest {
    @Test
    fun `the conventional type is read from the subject and anything else is other`() {
        assertEquals("feat", UpdateChangeLog.typeForSubject("feat(ui): add a thing"))
        assertEquals("fix", UpdateChangeLog.typeForSubject("fix: repair a thing"))
        assertEquals("chore", UpdateChangeLog.typeForSubject("CHORE: bump deps"))
        assertEquals("other", UpdateChangeLog.typeForSubject("no conventional prefix here"))
        assertEquals("other", UpdateChangeLog.typeForSubject(""))
    }

    @Test
    fun `parse commit keeps the short hash and the derived type`() {
        val commit = UpdateChangeLog.parseCommit("abc1234567", "fix(hooks): harden reflection")
        assertEquals("abc1234567", commit.hash)
        assertEquals("fix(hooks): harden reflection", commit.subject)
        assertEquals("fix", commit.type)
    }

    /**
     * Group order drives what a user reads first, so features and fixes must precede build noise
     * regardless of the order the compare API returned the commits in.
     */
    @Test
    fun `groups are ordered by importance, not by input order`() {
        val groups = UpdateChangeLog.group(
            listOf(
                UpdateChangeLog.parseCommit("1", "chore: tidy"),
                UpdateChangeLog.parseCommit("2", "docs: explain"),
                UpdateChangeLog.parseCommit("3", "fix: repair"),
                UpdateChangeLog.parseCommit("4", "feat: add"),
                UpdateChangeLog.parseCommit("5", "random subject")
            )
        )
        assertEquals(listOf("feat", "fix", "docs", "chore", "other"), groups.map { it.type })
        // Items keep the full commit subject; only the type is derived from it.
        assertEquals(listOf("feat: add"), groups.first().items)
        assertEquals(listOf("random subject"), groups.last().items)
    }

    @Test
    fun `commits of the same type are collected in one group and keep their order`() {
        val groups = UpdateChangeLog.group(
            listOf(
                UpdateChangeLog.parseCommit("1", "fix: first"),
                UpdateChangeLog.parseCommit("2", "fix: second"),
                UpdateChangeLog.parseCommit("3", "fix: third")
            )
        )
        assertEquals(1, groups.size)
        assertEquals("fix", groups.single().type)
        assertEquals(listOf("fix: first", "fix: second", "fix: third"), groups.single().items)
    }

    @Test
    fun `an empty commit list produces no groups`() {
        assertEquals(emptyList<ChangeGroup>(), UpdateChangeLog.group(emptyList()))
    }
}
