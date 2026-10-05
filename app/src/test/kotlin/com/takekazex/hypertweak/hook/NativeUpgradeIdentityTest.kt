package com.takekazex.hypertweak.hook

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NativeUpgradeIdentityTest {
    private fun record(version: Int, pid: Int): ByteArray = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(1).putInt(version).putInt(pid).putInt(1).putLong(500L).putInt(0x48544e53 xor version xor pid xor 500).array()
    @Test fun `previous process acknowledgement cannot validate a new launcher`() {
        val bytes = record(444, 6455)
        assertTrue(NativeUpgradeIdentity.ready(bytes, 444, 6455, 500))
        assertFalse(NativeUpgradeIdentity.ready(bytes, 444, 6456, 500))
        assertFalse(NativeUpgradeIdentity.ready(bytes, 445, 6455, 500))
        assertFalse(NativeUpgradeIdentity.ready(bytes, 444, 6455, 501))
    }
    @Test fun `truncated corrupt or uninitialized records fail closed`() {
        val bytes = record(444, 6455)
        assertFalse(NativeUpgradeIdentity.ready(bytes.copyOf(16), 444, 6455, 500))
        assertFalse(NativeUpgradeIdentity.ready(bytes.clone().apply { this[27] = 0 }, 444, 6455, 500))
        assertFalse(NativeUpgradeIdentity.ready(bytes.clone().apply { this[12] = 0 }, 444, 6455, 500))
    }
    @Test fun `Java only APK changes do not require native activation`() {
        val a = "a".repeat(64); val b = "b".repeat(64)
        assertFalse(NativeUpgradeIdentity.needsUpdate(a, a))
        assertTrue(NativeUpgradeIdentity.needsUpdate(a, b))
        assertThrows(IllegalArgumentException::class.java) { NativeUpgradeIdentity.needsUpdate(a, "") }
    }
    @Test fun `process comm parentheses cannot shift the PID reuse guard`() {
        val fields = MutableList(20) { "0" }.apply { this[0] = "S"; this[19] = "12345" }
        assertEquals(12345, NativeUpgradeIdentity.procStart("12 (a worker) name)) " + fields.joinToString(" ")))
        assertThrows(IllegalStateException::class.java) { NativeUpgradeIdentity.procStart("12 () S") }
    }
}
