package com.takekazex.hypertweak.hook.rules.analytics

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeNotNull
import org.junit.Test

class AnalyticsTelemetryApkTest {
    @Test
    fun currentHostResolvesBothUsageFamiliesAndEveryBoundary() {
        val apk = System.getenv("ANALYTICS_APK")
        assumeNotNull(apk)
        val resolved = AnalyticsTelemetryResolver(DexOperandIndex.fixture(requireNotNull(apk))).resolve()
        assertEquals(
            setOf("La/b/a/e/h0/y;->g(JJZ)Lorg/json/JSONObject;", "La/b/a/f/f/m;->f(JJ)Lorg/json/JSONObject;",
                "La/b/a/e/h0/m;->d()Lorg/json/JSONObject;", "La/b/a/f/f/f;->d()Lorg/json/JSONObject;"),
            resolved.getValue("emptyCollectors").lines().toSet(),
        )
        assertEquals(
            setOf("La/b/a/a/a/c/a;->m0()Z", "La/b/a/e/f0/f;->g(La/b/a/e/f0/x;)Z"),
            resolved.getValue("offline").lines().toSet(),
        )
        assertEquals("La/b/a/c/v/j\$a;->a(La/b/a/c/v/j\$a;)Lorg/json/JSONObject;", resolved.getValue("radio"))
        assertEquals(3, resolved.getValue("blockedTasks").lines().size)
        assertEquals("La/b/a/c/v/h;->j(Landroid/content/Context;Ljava/lang/String;)Z", resolved.getValue("legacy"))
    }
}
