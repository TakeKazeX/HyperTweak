package com.takekazex.hypertweak.hook.rules.downloads

import com.takekazex.hypertweak.hook.rules.shared.DownloadFocusIdentity
import org.junit.Assert.*
import org.junit.Test

class DownloadFocusIdentityTest {
    @Test fun `only the marked provider task can own a title`() {
        assertTrue(DownloadFocusIdentity.owns(DownloadFocusIdentity.PACKAGE, 1089, "1:source.app:42", 42))
        assertFalse(DownloadFocusIdentity.owns("other.app", 1089, "1:source.app:42", 42))
        assertFalse(DownloadFocusIdentity.owns(DownloadFocusIdentity.PACKAGE, 1090, "1:source.app:42", 42))
        assertFalse(DownloadFocusIdentity.owns(DownloadFocusIdentity.PACKAGE, 1089, "1:source.app:42", 43))
        assertFalse(DownloadFocusIdentity.owns(DownloadFocusIdentity.PACKAGE, 1089, "1:source.app:42", -1))
    }

    @Test fun `native aggregate complete and absent identities remain native`() {
        listOf(null, "1:source.app", "3:source.app:success", "2:source.app:42", "other.providers.downloads:42")
            .forEach { assertFalse(DownloadFocusIdentity.owns(DownloadFocusIdentity.PACKAGE, 1089, it, 42)) }
    }

    @Test fun `marked per-file completion is isolated from aggregate and failure notifications`() {
        assertTrue(DownloadFocusIdentity.owns(DownloadFocusIdentity.PACKAGE, 1089, "3:source.app:success:42", 42))
        assertFalse(DownloadFocusIdentity.owns(DownloadFocusIdentity.PACKAGE, 1089, "3:source.app:failed:42", 42))
        assertFalse(DownloadFocusIdentity.owns("other.app", 1089, "3:source.app:success:42", 42))
        assertNull(DownloadFocusIdentity.taskId("3:source.app:success"))
        assertNull(DownloadFocusIdentity.taskId("3:source.app:success:0"))
        assertNull(DownloadFocusIdentity.taskId("1::42"))
    }
}
