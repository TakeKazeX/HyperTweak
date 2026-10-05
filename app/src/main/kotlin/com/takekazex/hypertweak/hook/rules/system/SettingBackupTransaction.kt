package com.takekazex.hypertweak.hook.rules.system

/** A failed read is never an absent setting; failed writes never release the original value. */
internal class SettingBackupTransaction(
    private val read: (String) -> String?,
    private val write: (String, String?) -> Boolean
) {
    fun has(key: String): Boolean = read(key) != null
    fun original(key: String): String? = read(key)?.takeUnless { it == ABSENT }
    fun record(key: String, value: String?): Boolean {
        if (has(key)) return true
        val encoded = value?.takeIf(String::isNotEmpty) ?: ABSENT
        return write(key, encoded) && read(key) == encoded
    }
    fun restore(backup: String, live: String): Boolean {
        val encoded = read(backup) ?: return true
        if (!write(live, encoded.takeUnless { it == ABSENT })) return false
        return write(backup, null) && read(backup) == null
    }
    companion object { private const val ABSENT = "__hypertweak_absent__" }
}
