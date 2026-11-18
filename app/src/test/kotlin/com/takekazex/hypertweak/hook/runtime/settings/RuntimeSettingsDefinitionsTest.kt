package com.takekazex.hypertweak.hook.runtime.settings

import com.takekazex.hypertweak.settings.contract.debug.DebugSettingsCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSettingsDefinitionsTest {
    /** A strict key of another type would reject every snapshot that carries it. */
    @Test fun strictKeysAreBooleanSettings() {
        val wrong = RuntimeSettingsGroups.strictBooleanKeys.filter { DebugSettingsCatalog.entries[it]?.type != "Boolean" }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test fun ownersFollowDeclarationOrderAcrossKeyListsAndMatchers() {
        val order = RuntimeSettingsDefinitions.groups.keys.toList()
        DebugSettingsCatalog.entries.keys.forEach { key ->
            val owners = RuntimeSettingsGroups.owners(key).toList()
            assertEquals(key, owners.sortedBy(order::indexOf), owners)
            owners.forEach { assertTrue("$key/$it", RuntimeSettingsDefinitions.groups.getValue(it).owns(key)) }
        }
    }
}
