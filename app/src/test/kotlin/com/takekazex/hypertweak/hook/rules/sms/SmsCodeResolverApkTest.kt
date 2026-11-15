package com.takekazex.hypertweak.hook.rules.sms

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class SmsCodeResolverApkTest {
    @Test fun `verified host links SMS delivery message identity native recognition and copied OTP`() {
        val path = System.getenv("SMS_APK")
        assumeTrue("Set SMS_APK to verify the real host contract", path != null)
        val digest = MessageDigest.getInstance("SHA-256")
        File(path!!).inputStream().use { input ->
            val bytes = ByteArray(65536)
            while (true) { val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }
        }
        assertEquals("1ebc3b8ece303940202456e3a93f2547e44f524ae48a5d62c75ce1611497cf94",
            digest.digest().joinToString("") { "%02x".format(it) })
        val values = SmsCodeResolver(DexOperandIndex.fixture(path)).resolve()
        assertEquals("Lcom/android/mms/transaction/SmsReceiverService\$f;->handleMessage(Landroid/os/Message;)V", values["receive"])
        assertEquals("Lcom/android/mms/ui/r1;->M(Landroid/content/Context;Lcom/miui/smsextra/sdk/SmsInfo;IIZ)Lcom/miui/smsextra/sdk/ItemExtra;", values["recognize"])
        assertEquals("Lcom/miui/smsextra/sdk/SmsInfo;->getMsgId()J", values["id"])
        assertEquals("Lcom/miui/smsextra/sdk/ItemExtra;->getOTP()Ljava/lang/String;", values["otp"])
    }
}
