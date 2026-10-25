package com.takekazex.hypertweak.hook

import android.content.Intent
import android.os.Bundle
import com.takekazex.hypertweak.hook.base.RuntimeSettingsGroups

internal object RuntimeSettingsProtocol {
    const val CHANGED = "com.takekazex.hypertweak.action.RUNTIME_SETTINGS_CHANGED"
    const val REQUEST = "com.takekazex.hypertweak.action.RUNTIME_SETTINGS_REQUEST"
    fun encode(snapshot: RuntimeSettingsSnapshot, target: String): Intent {
        val values = Bundle()
        RuntimeSettingsGroups.subset(target, snapshot.values).forEach { (key, value) -> when (value) {
            is Boolean -> values.putBoolean(key, value)
            is Int -> values.putInt(key, value)
            is Long -> values.putLong(key, value)
            is Float -> values.putFloat(key, value)
            is String -> values.putString(key, value)
            is Set<*> -> values.putStringArrayList(key, ArrayList(value.filterIsInstance<String>()))
        } }
        return Intent(CHANGED).setPackage(target).addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            .putExtra("schema", 2).putExtra("revision", snapshot.revision).putExtra("values", values)
            .putStringArrayListExtra("groups", ArrayList(RuntimeSettingsGroups.forTarget(target)))
    }
    fun decode(intent: Intent): RuntimeSettingsSnapshot? = runCatching {
        if (intent.getIntExtra("schema", -1) != 2) return null
        val groups = intent.getStringArrayListExtra("groups")?.toSet() ?: return null
        val values = intent.getBundleExtra("values") ?: return null
        @Suppress("DEPRECATION")
        val map = values.keySet().associateWith { key ->
            val value = values.get(key)
            if (value is ArrayList<*> && value.all { it is String }) value.toSet() else value
        }
        if (map.keys.any { !RuntimeSettingsSnapshot.owns(it) }) return null
        RuntimeSettingsSnapshot.read(intent.getLongExtra("revision", -1), map, groups)
    }.getOrNull()
}
