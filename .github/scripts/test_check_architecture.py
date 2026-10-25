import unittest
from check_architecture import violations


class ArchitectureGuardTest(unittest.TestCase):
    def test_import_alias_wildcard_and_fully_qualified_bypasses(self):
        for code in ("import com.takekazex.hypertweak.hook.Preferences as Store",
                     "import com.takekazex.hypertweak.hook.*;",
                     "val services: com.takekazex.hypertweak.hook.Manager"):
            self.assertEqual([(1, "hook")], violations("ui", code))

    def test_comments_literals_and_documentation_do_not_create_dependencies(self):
        source = '''// com.takekazex.hypertweak.hook.Preferences
/* com.takekazex.hypertweak.hook.Preferences */
val a = "com.takekazex.hypertweak.hook.Preferences"
val b = """com.takekazex.hypertweak.hook.Preferences"""
import com.takekazex.hypertweak.hook.Preferences
'''
        self.assertEqual([(5, "hook")], violations("ui", source))

    def test_contracts_are_independent_but_composition_can_wire_implementations(self):
        self.assertEqual([(1, "util")], violations("settings", "import com.takekazex.hypertweak.util.RestartUtils"))
        self.assertEqual([], violations("app", "import com.takekazex.hypertweak.hook.Preferences"))
        self.assertEqual([], violations("ui", "import com.takekazex.hypertweak.settings.contract.SettingsValues"))
