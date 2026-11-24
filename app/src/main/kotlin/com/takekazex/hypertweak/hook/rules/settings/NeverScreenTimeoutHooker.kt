package com.takekazex.hypertweak.hook.rules.settings

import android.os.Looper
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.util.platform.PlatformLevel
import com.takekazex.hypertweak.util.platform.StaticFieldWriter
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap

/**
 * Keeps the resource-provided "never" screen timeout on OLED panels.
 *
 * The filter runs with the panel flag cleared, so it takes the native LCD path: device-admin
 * limits and the admin-disabled state are still applied by the host. Each preference's
 * unfiltered entries are remembered the first time the filter sees them, so turning the switch
 * on restores "never" on a page that was already filtered. Turning it off lets the native filter
 * drop "never" again; the host then rewrites a stored "never" timeout to the longest remaining
 * entry when it refreshes the summary.
 */
internal object NeverScreenTimeoutHooker : StaticHooker() {
    private const val TAG = "NeverScreenTimeout"
    private val enabled by hookSetting(false) {
        Preferences.getBoolean(PlatformSettingsKeys.KEY_ALLOW_NEVER_SCREEN_TIMEOUT, false)
    }

    private class Entries(val labels: Array<CharSequence>, val values: Array<CharSequence>)
    private val unfiltered = generationMainResource(WeakHashMap<Any, Entries>()) { it.clear() }

    private data class Targets(
        val filter: Method,
        val oled: Field,
        val entries: Method,
        val values: Method,
        val setEntries: Method,
        val setValues: Method,
    )

    override fun onHook() {
        if (!PlatformLevel.isOs4) return
        val apk = hookParam.appInfo?.sourceDir ?: return
        prepareDexTargets(TAG, resolve = {
            val index = DexRuntime.operands(apk)
            val contract = NeverScreenTimeoutContract.resolve(index) ?: return@prepareDexTargets null
            fun method(ref: org.jf.dexlib2.iface.reference.MethodReference) =
                index.method(ref, classLoader)?.apply { isAccessible = true }
            Targets(
                filter = method(contract.filter) ?: return@prepareDexTargets null,
                oled = index.field(contract.oled, classLoader)?.apply { isAccessible = true }
                    ?.takeIf { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == Boolean::class.javaPrimitiveType }
                    ?: return@prepareDexTargets null,
                entries = method(contract.entries) ?: return@prepareDexTargets null,
                values = method(contract.values) ?: return@prepareDexTargets null,
                setEntries = method(contract.setEntries) ?: return@prepareDexTargets null,
                setValues = method(contract.setValues) ?: return@prepareDexTargets null,
            )
        }) { targets ->
            targets.filter.hook("never_screen_timeout_filter") {
                intercept { chain ->
                    val preference = chain.thisObject
                    if (generationRetired || preference == null || Looper.myLooper() != Looper.getMainLooper()) {
                        return@intercept chain.proceed()
                    }
                    HookFailurePolicy.open(TAG, "remember timeout entries", Unit) { remember(targets, preference) }
                    if (!enabled) return@intercept chain.proceed()
                    HookFailurePolicy.open(TAG, "restore timeout entries", Unit) { restore(targets, preference) }
                    val native = targets.oled.getBoolean(null)
                    if (!native) return@intercept chain.proceed()
                    // Main-thread only: the flag has no other reader in the host.
                    StaticFieldWriter.setBoolean(targets.oled, false)
                    try {
                        chain.proceed()
                    } finally {
                        StaticFieldWriter.setBoolean(targets.oled, native)
                    }
                }
            }
        }
    }

    private fun remember(targets: Targets, preference: Any) {
        val pages = unfiltered
        if (preference in pages) return
        val labels = targets.entries.invoke(preference) as? Array<*> ?: return
        val values = targets.values.invoke(preference) as? Array<*> ?: return
        if (labels.size != values.size || labels.isEmpty()) return
        pages[preference] = Entries(
            labels.map { it as? CharSequence ?: return }.toTypedArray(),
            values.map { it as? CharSequence ?: return }.toTypedArray(),
        )
    }

    private fun restore(targets: Targets, preference: Any) {
        val saved = unfiltered[preference] ?: return
        val values = targets.values.invoke(preference) as? Array<*>
        if (values != null && values.size == saved.values.size) return
        targets.setEntries.invoke(preference, saved.labels.clone())
        targets.setValues.invoke(preference, saved.values.clone())
    }
}
