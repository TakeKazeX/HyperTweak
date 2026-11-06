package com.takekazex.hypertweak.util.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class CloudDnsSettingsTest {
    @Test fun `blank v2 and modified v1 are both restored including absent values`() = runBlocking {
        val device = mutableMapOf<String, String?>(
            SmartDnsV2 to "", SmartDnsV1 to "off,1000,2500,off,2500,2")
        assertTrue(CloudDnsSettings.restoreBaseline(CloudDnsSettings.SmartDnsBaseline("", v2)) { key, value ->
            device[key] = value
            true
        })
        assertEquals("", device[SmartDnsV2])
        assertEquals(v2, device[SmartDnsV1])
        assertTrue(CloudDnsSettings.restoreBaseline(CloudDnsSettings.SmartDnsBaseline(null, null)) { key, value ->
            device[key] = value
            true
        })
        assertTrue(device.values.all { it == null })
    }

    @Test fun `partial baseline restore reports failure so the capture can be retained`() = runBlocking {
        val attempted = mutableListOf<String>()
        assertFalse(CloudDnsSettings.restoreBaseline(CloudDnsSettings.SmartDnsBaseline(null, v2)) { key, _ ->
            attempted += key
            key != SmartDnsV2
        })
        assertEquals(listOf(SmartDnsV2, SmartDnsV1), attempted)
    }

    private val SmartDnsV2 = "cloud_smartdns_enabled_v2"
    private val SmartDnsV1 = "cloud_smartdns_enabled"
    private val v2 = "on,1000,2500,on,2500,2,1,1,560,420,128"

    @Test
    fun `v2 config reports SmartDNS and IPv4-first state`() {
        val config = CloudDnsSettings.parseSmartDnsConfig(v2, null)
        assertEquals("cloud_smartdns_enabled_v2", config.key)
        assertTrue(config.present)
        assertTrue(config.enabled)
        assertTrue(config.ipv4First)
    }

    @Test
    fun `v1 is used only when v2 is absent`() {
        val v1 = CloudDnsSettings.parseSmartDnsConfig(null, "off,1000,2500,off,2500,2")
        assertEquals("cloud_smartdns_enabled", v1.key)
        assertTrue(v1.present)
        assertFalse(v1.enabled)
        assertFalse(v1.ipv4First)

        val v2Config = CloudDnsSettings.parseSmartDnsConfig(v2, v1.fields.joinToString(","))
        assertEquals("cloud_smartdns_enabled_v2", v2Config.key)
    }

    @Test
    fun `missing config keeps the v2 template but reports no cloud value`() {
        val config = CloudDnsSettings.parseSmartDnsConfig(null, null)
        assertEquals("cloud_smartdns_enabled_v2", config.key)
        assertFalse(config.present)
        assertFalse(config.enabled)
        assertFalse(config.ipv4First)
        assertEquals(11, config.fields.size)
    }

    @Test
    fun `turning SmartDNS off clears IPv4-first from the baseline`() {
        val value = CloudDnsSettings.buildSmartDnsValue(
            v2,
            null,
            CloudDnsSettings.MODE_OFF,
            CloudDnsSettings.MODE_DEFAULT,
        )
        assertEquals("off,1000,2500,off,2500,2,1,1,560,420,128", value.value)
    }

    @Test
    fun `turning SmartDNS on preserves the baseline IPv4 choice`() {
        val value = CloudDnsSettings.buildSmartDnsValue(
            "off,1000,2500,off,2500,2,1,1,560,420,128",
            null,
            CloudDnsSettings.MODE_ON,
            CloudDnsSettings.MODE_DEFAULT,
        )
        assertEquals("on,1000,2500,off,2500,2,1,1,560,420,128", value.value)
    }

    @Test
    fun `IPv4-first can be turned on or off independently while SmartDNS is on`() {
        val on = CloudDnsSettings.buildSmartDnsValue(
            "on,1000,2500,off,2500,2,1,1,560,420,128",
            null,
            CloudDnsSettings.MODE_DEFAULT,
            CloudDnsSettings.MODE_ON,
        )
        assertEquals("on,1000,2500,on,2500,2,1,1,560,420,128", on.value)

        val off = CloudDnsSettings.buildSmartDnsValue(
            v2,
            null,
            CloudDnsSettings.MODE_DEFAULT,
            CloudDnsSettings.MODE_OFF,
        )
        assertEquals("on,1000,2500,off,2500,2,1,1,560,420,128", off.value)
    }

    @Test
    fun `short baseline is padded from the v2 template`() {
        val value = CloudDnsSettings.buildSmartDnsValue(
            "on,1000",
            null,
            CloudDnsSettings.MODE_DEFAULT,
            CloudDnsSettings.MODE_OFF,
        )
        assertEquals("on,1000,2500,off,2500,2,1,1,560,420,128", value.value)
    }
}
