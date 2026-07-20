package com.takekazex.hypertweak.hook.base

import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionOverridesTest {
    @Test fun copiesImmutableCollectionAndDeduplicatesAdditions() {
        val source = listOf("a", "com.google.android.gms", "a", 42)
        val result = CollectionOverrides.stringSet(source, "com.google.android.gms", "b")
        assertEquals(linkedSetOf("a", "com.google.android.gms", "b"), result)
    }
}
