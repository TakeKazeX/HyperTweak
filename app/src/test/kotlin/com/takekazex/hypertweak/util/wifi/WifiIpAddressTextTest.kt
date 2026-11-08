package com.takekazex.hypertweak.util.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiIpAddressTextTest {

    @Test
    fun testIpv4Validation() {
        assertTrue(WifiIpAddressText.isIpv4("192.168.1.1"))
        assertTrue(WifiIpAddressText.isIpv4("10.0.0.1"))
        assertTrue(WifiIpAddressText.isIpv4("172.16.254.1"))
        assertFalse(WifiIpAddressText.isIpv4("256.0.0.1"))
        assertFalse(WifiIpAddressText.isIpv4("192.168.1"))
        assertFalse(WifiIpAddressText.isIpv4("fe80::1"))
        assertFalse(WifiIpAddressText.isIpv4("not_an_ip"))
    }

    @Test
    fun testIpv6Validation() {
        assertTrue(WifiIpAddressText.isIpv6("fe80::1"))
        assertTrue(WifiIpAddressText.isIpv6("fe80::1%wlan0"))
        assertTrue(WifiIpAddressText.isIpv6("240e:390:1234:5678:abcd:ef01:2345:6789"))
        assertTrue(WifiIpAddressText.isIpv6("2001:db8::ff00:42:8329"))
        assertFalse(WifiIpAddressText.isIpv6("192.168.1.1"))
        assertFalse(WifiIpAddressText.isIpv6("fe80"))
        assertFalse(WifiIpAddressText.isIpv6(":1"))
        assertFalse(WifiIpAddressText.isIpv6("invalid:ip:text:xyzg"))
    }

    @Test
    fun testParse() {
        val sample = """
            192.168.31.121
            fe80::20c:29ff:fe4a:1234
            240e:390:1234::1
            192.168.31.121
            invalid_ip_text

            fe80::20c:29ff:fe4a:1234%wlan0
        """.trimIndent()

        val parsed = WifiIpAddressText.parse(sample)
        assertEquals(4, parsed.size)
        assertEquals("192.168.31.121", parsed[0])
        assertEquals("fe80::20c:29ff:fe4a:1234", parsed[1])
        assertEquals("240e:390:1234::1", parsed[2])
        assertEquals("fe80::20c:29ff:fe4a:1234%wlan0", parsed[3])
    }

    @Test
    fun testIsIpList() {
        assertTrue(WifiIpAddressText.isIpList("192.168.1.1"))
        assertTrue(WifiIpAddressText.isIpList("192.168.1.1\nfe80::1"))
        assertFalse(WifiIpAddressText.isIpList("192.168.1.1\nhello"))
        assertFalse(WifiIpAddressText.isIpList(""))
        assertFalse(WifiIpAddressText.isIpList(null))
    }

    @Test
    fun testFormat() {
        val ipv4s = listOf("192.168.31.121", "192.168.31.121", "10.0.0.2")
        val ipv6s = listOf("fe80::1", "240e::1", "fe80::1")
        val formatted = WifiIpAddressText.format(ipv4s, ipv6s)
        val expected = "192.168.31.121\n10.0.0.2\nfe80::1\n240e::1"
        assertEquals(expected, formatted)
    }
}
