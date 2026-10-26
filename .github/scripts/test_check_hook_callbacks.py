import unittest
from check_hook_callbacks import violations


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


if __name__ == '__main__':
    unittest.main()
