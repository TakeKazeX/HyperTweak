package com.takekazex.hypertweak.hook.rules.securitycenter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSuPredicateFamilyResolverTest {
    private val paths = setOf("/system/bin/", "/system/xbin/", "/system/sbin/", "/sbin/", "/vendor/bin/", "su")
    private val loop = setOf("filled-new-array", "aget-object", "add-int/lit8", "if-eqz", "goto")

    private fun predicate(descriptor: String) = LocalSuPredicateEvidence(
        descriptor = descriptor,
        parameterCount = 0,
        returnTypeName = "boolean",
        hasFileExists = true,
        strings = paths,
        opNames = loop,
    )

    @Test fun `all structurally equivalent predicates survive a changing family size`() {
        val three = listOf("Lrenamed/A;->a()Z", "Lrenamed/B;->b()Z", "Lrenamed/C;->c()Z").map(::predicate)
        val five = (0 until 5).map { predicate("Lrenamed/$it;->x()Z") }
        assertEquals(3, LocalSuPredicateFamilyResolver.select(three).size)
        assertEquals(5, LocalSuPredicateFamilyResolver.select(five).size)
    }

    @Test fun `incomplete loop and non-file predicates are excluded and duplicates collapse`() {
        val incomplete = predicate("Lrenamed/Incomplete;->x()Z").copy(opNames = setOf("aget-object", "if-eqz"))
        val wrongProbe = predicate("Lrenamed/Other;->x()Z").copy(hasFileExists = false)
        val repeated = predicate("Lrenamed/Root;->x()Z")
        val selected = LocalSuPredicateFamilyResolver.select(listOf(incomplete, wrongProbe, repeated, repeated))
        assertEquals(listOf(repeated), selected)
        assertTrue(LocalSuPredicateFamilyResolver.select(emptyList()).isEmpty())
    }
}
