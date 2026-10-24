package com.takekazex.hypertweak.hook.rules.system

import org.junit.Assert.*
import org.junit.Test
import java.util.Collections

class PickerReroutePolicyTest {
    @Test fun `filter preserves immutable inputs order and unrelated handlers`() {
        val source = Collections.unmodifiableList(listOf(
            "com.google.android.documentsui", "com.android.photopicker",
            "com.android.providers.media.module", null, "com.miui.gallery", "com.android.photopicker"
        ))
        val result = PickerReroutePolicy.filter(source) { it }
        assertEquals(listOf("com.google.android.documentsui", "com.android.providers.media.module", null, "com.miui.gallery"), result)
        assertEquals(6, source.size)
        assertSame(result, PickerReroutePolicy.filter(result) { it })
        assertTrue(PickerReroutePolicy.filter(listOf("com.android.photopicker")) { it }.isEmpty())
    }

    @Test fun `unrelated queries incur no candidate inspection`() {
        for (action in listOf(null, "android.intent.action.PICK", "android.intent.action.VIEW", "android.intent.action.CREATE_DOCUMENT", "android.intent.action.OPEN_DOCUMENT")) {
            assertFalse(PickerReroutePolicy.handles(action))
        }
        for (action in listOf("android.intent.action.GET_CONTENT",
            "android.provider.action.PICK_IMAGES", "android.provider.action.PICK_IMAGES_MAX")) {
            assertTrue(PickerReroutePolicy.handles(action))
            assertFalse(PickerReroutePolicy.handles(action, explicit = true))
        }
    }
}
