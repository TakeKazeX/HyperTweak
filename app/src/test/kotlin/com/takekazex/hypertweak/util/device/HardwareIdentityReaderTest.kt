package com.takekazex.hypertweak.util.device

import org.junit.Assert.*
import org.junit.Test

class HardwareIdentityReaderTest {
    private fun response(body: String) = "schema\t1\n$body\ncomplete\t1\n"

    @Test fun `device evidence decodes big endian manufacturer and active components`() {
        val raw = HardwareIdentityReader.parse(response("""
            panel	panel_name=mdss_dsi_p11u_42_0d_0a_dsc_cmd
            dram_vendor	00000006
            dram_type	00000008
            dram_mr6	00000009
            dram_mr7	00000000
            dram_mr8	0000001a
            flash_vendor	SAMSUNG${"  "}
            flash_model	KLUFG8RHKF-F0H1
            flash_life_a	0x01
            flash_life_b	0x01
            flash_eol	0x01
            touch	Driver Ver:Focaltech V3.4 20250717 IC ID:0x3683 BUS:SPI
            fp_vendor	goodix_us2
            fp_module	QTech
        """.trimIndent()))
        val values = HardwareIdentityReader.format(raw)
        assertEquals("SK hynix · ID 0x06", values["dram_vendor"])
        assertEquals("0x00000008", values["dram_type"])
        assertEquals("SAMSUNG", values["flash_vendor"])
        assertEquals("KLUFG8RHKF-F0H1", values["flash_model"])
        assertEquals("FocalTech", values["touch_vendor"])
        assertEquals("FT3683", values["touch_model"])
        assertEquals("Goodix · goodix_us2", values["fp_vendor"])
        assertEquals("QTech", values["fp_module"])
        assertEquals("LIFE_A=0x01 / LIFE_B=0x01 / EOL=0x01", values["flash_life"])
    }

    @Test fun `unfinished oversized malformed and conflicting responses are rejected`() {
        assertTrue(HardwareIdentityReader.parse("schema\t1\ndram_vendor\t00000006").isEmpty())
        assertTrue(HardwareIdentityReader.parse(response("x".repeat(16_384))).isEmpty())
        assertTrue(HardwareIdentityReader.parse(response("dram_vendor\t00000006\ndram_vendor\t00000001")).isEmpty())
        assertTrue(HardwareIdentityReader.parse(response("fp_module\tunknown\nfp_vendor\tnull")).isEmpty())
        assertTrue(HardwareIdentityReader.parse(response("serial_number\tSECRET")).isEmpty())
    }

    @Test fun `unknown IDs retain raw identity and cannot become a guessed supplier`() {
        val values = HardwareIdentityReader.format(mapOf("dram_vendor" to "00000042", "dram_type" to "8"))
        assertEquals("ID 0x42", values["dram_vendor"])
        assertNull(values["dram_type"])
        assertNull(HardwareIdentityReader.panelVendor("boeing_panel"))
        assertNull(HardwareIdentityReader.panelVendor("mdss_dsi_unknown_42_0d_0a_dsc_cmd"))
        assertNull(HardwareIdentityReader.panelVendor("mdss_dsi_p11u_42_0d_0a_dsc_cmd_other"))
        assertNull(HardwareIdentityReader.format(mapOf("dram_vendor" to "06000000"))["dram_vendor"]?.takeIf { it.contains("hynix") })
    }

    @Test fun `panel model matching is confined to the evidenced hardware family`() {
        assertEquals("TCL CSOT (P11U)", HardwareIdentityReader.panelVendor("mdss_dsi_p11u_42_0d_0a_dsc_cmd"))
        assertEquals("BOE", HardwareIdentityReader.panelVendor("mdss_dsi_boe_panel"))
        assertEquals("TCL CSOT", HardwareIdentityReader.panelVendor("mdss_dsi_csot_panel"))
    }

    @Test fun `chip model is decoded only for its explicit active vendor`() {
        assertNull(HardwareIdentityReader.format(mapOf("touch" to "IC ID:0x3683"))["touch_vendor"])
        assertEquals("Synaptics", HardwareIdentityReader.format(mapOf("touch" to "N: Name=\"synaptics_ts\""))["touch_vendor"])
        assertEquals("other_vendor", HardwareIdentityReader.format(mapOf("fp_vendor" to "other_vendor"))["fp_vendor"])
    }
}
