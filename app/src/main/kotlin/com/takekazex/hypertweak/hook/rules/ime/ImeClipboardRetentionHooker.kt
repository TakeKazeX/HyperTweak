package com.takekazex.hypertweak.hook.rules.ime

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.dex.DexContractMembers
import com.takekazex.hypertweak.hook.base.dex.DexFeature
import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.PhraseSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import org.jf.dexlib2.iface.reference.MethodReference

internal object ImeClipboardRetentionResolver : DexFeature {
    override val id = "phrase.ime_retention"
    override val schema = 1
    const val NATIVE_RETENTION = 259_200_000L

    fun resolve(dex: DexOperandIndex): Map<String, String> {
        val reader = dex.strings("getNoExpiredClipboardData end size = : ").single {
            it.returnType == "Ljava/util/ArrayList;" && it.parameterTypes.map(CharSequence::toString) ==
                listOf("Landroid/content/Context;", "Ljava/lang/String;", "J") &&
                dex.hasNumber(it, NATIVE_RETENTION)
        }
        val caller = dex.strings("content://com.miui.phrase.input.provider/getClipboardList").single { method ->
            val calls = dex.code(method).calls
            method.definingClass == reader.definingClass &&
                calls.any { id(it.method) == id(reader) } &&
                calls.singleOrNull { site ->
                    site.method.definingClass == "Ljava/lang/System;" && site.method.name == "currentTimeMillis" &&
                        site.arguments.isEmpty() && site.method.returnType == "J"
                } != null
        }
        return mapOf("reader" to id(reader), "caller" to id(caller))
    }

    private fun id(method: MethodReference) = MethodDescriptorCodec.descriptor(method)
}

/** The phrase APK is loaded by MIUI inside the selected keyboard's process. */
internal class ImeClipboardRetentionHooker(private val phraseApk: String) : StaticHooker() {
    private val enabled by hookSetting(false) {
        Preferences.getBoolean(PhraseSettingsKeys.KEY_CLIPBOARD_LIMITS, false)
    }
    private val days by hookSetting(PhraseSettingsKeys.DEFAULT_DAYS) {
        PhraseSettingsKeys.normalizeDays(Preferences.getInt(PhraseSettingsKeys.KEY_CLIPBOARD_DAYS, PhraseSettingsKeys.DEFAULT_DAYS))
    }

    override fun onHook() {
        prepareDexTargets(ImeClipboardRetentionResolver.id, ::resolve, ::install)
    }

    private fun resolve(): DexContractMembers? = DexRuntime.resolve(
        feature = ImeClipboardRetentionResolver,
        apkPath = phraseApk,
        cacheDir = dexCacheDirectory,
        compute = { ImeClipboardRetentionResolver.resolve(DexRuntime.operands(phraseApk)) },
        decode = { values, _ -> DexContractMembers(values, classLoader) },
    )

    private fun install(members: DexContractMembers) {
        check(deoptimize(members.method("caller"))) { "Cannot prepare clipboard reader caller" }
        members.method("reader").hook("ime_clipboard_retention") {
            intercept { chain ->
                val now = chain.args.getOrNull(2) as? Long
                val extension = PhraseSettingsKeys.retentionMillis(days) - ImeClipboardRetentionResolver.NATIVE_RETENTION
                if (!enabled || now == null || extension == 0L) chain.proceed()
                else chain.proceed(chain.args.toTypedArray().also { it[2] = now - extension })
            }
        }
        DebugLog.hookRegistered("ImeClipboardRetention", "clipboard reader retention ready")
    }
}
