import unittest
from check_hook_callbacks import READERS, violations, preferences_api_violations, reader_violations


class HookCallbacksGuardTest(unittest.TestCase):
    def test_direct_and_helper_reads_outside_snapshots_fail(self):
        self.assertEqual(1, len(violations('before { Preferences.getBoolean(KEY, false) }')))
        self.assertEqual(1, len(violations('fun enabled() = Preferences.getBoolean(KEY, false)')))

    def test_new_and_previously_unlisted_readers_fail(self):
        for method in ["getNotificationHeaderTimeScale", "screenshotEditorMode", "xiaoaiGestureOptions",
                       "batteryAutoPowerSaveThreshold", "unlockAdaptiveRefreshPro", "aFutureReader"]:
            with self.subTest(method=method):
                self.assertEqual(1, len(violations(f"before {{ Preferences.{method}() }}")))
                self.assertEqual(1, len(violations(f"before {{ use(Preferences::{method}) }}")))

    def test_only_explicit_effect_operations_are_allowed(self):
        self.assertEqual([], violations('after { Preferences.putStringSynchronous(KEY, value); Preferences.flush() }'))
        self.assertEqual(1, len(violations('after { Preferences.newWriteOperation(KEY, value) }')))

    def test_explicit_defaults_and_nested_factories_are_captures(self):
        source = 'val value by hookSetting(defaultValue(listOf("}"))) { Preferences.futureReader() }'
        self.assertEqual([], violations(source))

    def test_default_expression_cannot_read_storage(self):
        source = 'val value by hookSetting(Preferences.someDefault()) { Preferences.reader() }'
        self.assertEqual(1, len(violations(source)))

    def test_nested_snapshot_and_literals_are_ignored(self):
        source = '''val value by hookSetting { listOf("}").associateWith { Preferences.getInt(it, 0) } }
        // Preferences.getBoolean(KEY, false)
        val text = "Preferences.getBoolean(KEY, false)"'''
        self.assertEqual([], violations(source))

    def test_independent_framework_hook_fails(self):
        self.assertEqual(1, len(violations('HostAccess.requirePublicMethod(View::class.java, "setAlpha", Float::class.java).hook {}')))
        self.assertEqual([], violations('// requirePublicMethod(View::class.java, "setAlpha")'))

    def test_feature_readers_are_settings_sources(self):
        self.assertIn("PowerSavePrefs", READERS)
        self.assertEqual(1, len(violations('before { PowerSavePrefs.keepRefreshRateInPowerSave() }')))
        self.assertEqual([], violations('val keep by hookSetting(false) { PowerSavePrefs.keepRefreshRateInPowerSave() }'))
        self.assertEqual([], violations('before { removeDefaultPrefs.hook("x") }'))

    def test_preferences_surface_is_frozen(self):
        source = """object Preferences {
    fun getBoolean(key: String, default: Boolean = false): Boolean = false
    internal fun iconConfig(): IconConfig = TODO()
    private fun memoGet(key: String): Any? = null
    // fun commentedOut() = Unit
}"""
        self.assertEqual([], preferences_api_violations(source, frozenset({"getBoolean", "iconConfig"})))
        added = preferences_api_violations(source.replace("private fun memoGet", "fun aFeatureFlag"),
                                           frozenset({"getBoolean", "iconConfig"}))
        self.assertEqual(1, len(added))
        self.assertIn("aFeatureFlag", added[0])
        stale = preferences_api_violations(source, frozenset({"getBoolean", "iconConfig", "removedReader"}))
        self.assertEqual(1, len(stale))
        self.assertIn("removedReader", stale[0])

    def test_frozen_surface_sees_every_member_shape(self):
        source = """object Preferences {
    suspend fun awaitFlag(): Boolean = false
    operator fun get(key: String): Any? = null
    internal fun SharedPreferences.Editor.putFlag(value: Boolean) = Unit
    @JvmStatic inline fun <reified T> typed(key: String): T? = null
    object Nested { fun hidden() = Unit }
    private fun memoGet(key: String): Any? = null
}"""
        names = {message.split('`')[1] for message in preferences_api_violations(source, frozenset())}
        self.assertEqual({"awaitFlag", "get", "putFlag", "typed", "Nested"}, names)

    def test_readers_are_named_and_filed_as_readers(self):
        self.assertEqual([], reader_violations({"PowerSavePrefs.kt": ["PowerSavePrefs"]}))
        self.assertEqual(1, len(reader_violations({"Power.kt": ["PowerSaveReader"]})))
        self.assertEqual(1, len(reader_violations({"Misc.kt": ["PowerSavePrefs"]})))
        self.assertEqual([], reader_violations({"Codec.kt": ["Codec"]}, frozenset({"Codec"})))
        self.assertIn("BackGesturePrefs", READERS)
        self.assertNotIn("RuntimeSettingsPreferences", READERS)


if __name__ == '__main__':
    unittest.main()
