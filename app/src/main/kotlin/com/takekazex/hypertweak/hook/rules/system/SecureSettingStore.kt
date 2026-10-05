package com.takekazex.hypertweak.hook.rules.system

import android.content.Context
import android.provider.Settings

/**
 * Backup slots for settings that [DefaultAssistantHooker] and [CircleToSearchGestureHooker]
 * rewrite, stored in `Settings.Secure` next to the settings themselves.
 *
 * ## Why not `Preferences`
 *
 * Those two hookers run in **system_server**, and `Preferences` is not a reliable store there.
 * `Preferences.write` early-returns unless the daemon's remote backend initialised, and
 * `Preferences.getLocalCache()` deliberately returns null in system_server (there is no data
 * directory for the `android` context it can reach). Verified on OS4.0.0.30: keys written by these
 * hookers were absent from the daemon's `modules_config.db` (including its WAL) while the same
 * keys written by the module's own app process were present. A backup that silently fails to
 * persist is worse than none — the hooker would then believe it never recorded the original and
 * "restore" a value it had itself already overwritten.
 *
 * `Settings.Secure` is the right home: these hookers already write secure settings (that is why
 * they run in system_server), it survives reboots, and a user or a support session can inspect and
 * repair a stuck value with plain `settings get/put secure`.
 *
 * Keys are prefixed so they are self-identifying in `settings list secure`.
 *
 * ## Contract
 *
 * [restore] is fail-safe by design: **when no backup was recorded, it changes nothing.** It never
 * writes an empty value over a live setting, so a missing backup can never clear a user's setting.
 */
object SecureSettingStore {
    private const val PREFIX = "hypertweak_backup_"
    /** `Settings.Secure.assistant` as this module found it, before aligning it. */
    const val ASSISTANT = "${PREFIX}assistant"

    /** `Settings.Secure.voice_interaction_service` as this module found it. */
    const val VOICE_INTERACTION_SERVICE = "${PREFIX}voice_interaction_service"

    /** `Settings.Secure.NavLongPress` as this module found it. */
    const val NAV_LONG_PRESS = "${PREFIX}nav_long_press"

    private fun transaction(context: Context) = SettingBackupTransaction(
        read = { Settings.Secure.getString(context.contentResolver, it) },
        write = { key, value -> Settings.Secure.putString(context.contentResolver, key, value) }
    )

    // Callers isolate exceptions at the alignment/recovery boundary. A provider read failure must
    // propagate there rather than masquerading as an unset original setting.
    fun has(context: Context, key: String): Boolean = transaction(context).has(key)
    fun read(context: Context, key: String): String? = transaction(context).original(key)
    fun recordIfAbsent(context: Context, key: String, value: String?): Boolean = transaction(context).record(key, value)
    fun restore(context: Context, backupKey: String, liveKey: String): Boolean = transaction(context).restore(backupKey, liveKey)
}
