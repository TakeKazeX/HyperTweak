package com.takekazex.hypertweak.hook.rules.phrase

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.hook.rules.ime.ImeClipboardRetentionResolver
import com.takekazex.hypertweak.settings.contract.PhraseSettingsKeys
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class PhraseLimitResolverApkTest {
    @Test
    fun resolvesSaveAndBothPhraseLimitsOnTheVerifiedHost() {
        val path = System.getenv("PHRASE_APK")
        assumeTrue("Set PHRASE_APK to the verified MIUIFrequentPhrase APK", !path.isNullOrBlank())
        val dex = DexOperandIndex.fixture(path!!)
        val contract = PhraseLimitResolver(dex).resolve()
        assertEquals(
            "LN1/c;->e(Landroid/content/Context;Landroid/database/sqlite/SQLiteDatabase;Lcom/miui/inputmethod/ClipboardContentModel;Ljava/lang/String;)Ljava/lang/String;",
            contract["writer"],
        )
        assertEquals(
            "Lcom/miui/inputmethod/MiuiClipboardManager;->jsonToBeanList(Ljava/lang/String;)Ljava/util/ArrayList;",
            contract["parse"],
        )
        assertEquals("Lcom/miui/phrase/PhraseEditActivity;->onClick(Landroid/view/View;)V", contract["click"])
        assertEquals("Lcom/miui/phrase/g;->a()I", contract["count"])
        assertEquals(0x7f0a0148.toString(), contract["button"])
        assertEquals("Lcom/miui/phrase/AddPhraseActivity;->onCreate(Landroid/os/Bundle;)V", contract["editor"])
        assertEquals("Lcom/miui/phrase/AddPhraseActivity;->s:Landroid/widget/EditText;", contract["input"])
        assertEquals("LA0/c;-><init>(LN1/c;Landroid/content/Context;Landroid/database/sqlite/SQLiteDatabase;Ljava/util/List;)V", contract["cleanupTask"])
        val provider = ClipboardProviderResolver(dex).resolve()
        assertEquals("Lcom/miui/provider/InputProvider;->f()Z", provider["gate"])
        assertEquals("Lcom/miui/provider/InputProvider;->g:Ljava/util/List;", provider["signatureList"])
        assertEquals("LB0/h;->b(Ljava/lang/String;)Ljava/util/ArrayList;", provider["queryParser"])
        val ime = ImeClipboardRetentionResolver.resolve(dex)
        assertEquals("Lcom/miui/inputmethod/MiuiClipboardManager;->getNoExpiredClipboardData(Landroid/content/Context;Ljava/lang/String;J)Ljava/util/ArrayList;", ime["reader"])
    }

    @Test
    fun retentionChoicesNormalizeUnsupportedPersistedValues() {
        assertEquals(listOf(10, 20, 50, 100, 250, 500), PhraseSettingsKeys.clipboardCounts.map(PhraseSettingsKeys::normalizeCount))
        assertEquals(20, PhraseSettingsKeys.normalizeCount(Int.MAX_VALUE))
        assertEquals(listOf(259_200_000L, 604_800_000L, 2_592_000_000L, 7_776_000_000L),
            PhraseSettingsKeys.clipboardDays.map(PhraseSettingsKeys::retentionMillis))
        assertEquals(259_200_000L, PhraseSettingsKeys.retentionMillis(0))
    }
}
