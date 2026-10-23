package com.takekazex.hypertweak.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceHardwareInfoTest {
    @Test fun `file system uses exact or nearest parent mount not a path prefix`() {
        val mounts = listOf(
            "/dev/root / erofs ro 0 0",
            "tmpfs /data_mirror tmpfs rw 0 0",
            "/dev/block/dm-8 /data f2fs rw 0 0",
            "invalid",
            "/dev/block/dm-9 /data/media ext4 rw 0 0"
        )
        assertEquals("f2fs", DeviceHardwareInfo.fileSystem(mounts.asSequence(), "/data"))
        assertEquals("ext4", DeviceHardwareInfo.fileSystem(mounts.asSequence(), "/data/media/0"))
        assertEquals("erofs", DeviceHardwareInfo.fileSystem(mounts.asSequence(), "/database"))
        assertNull(DeviceHardwareInfo.fileSystem(sequenceOf("invalid"), "/data"))
    }
}
