import unittest
from check_architecture import hook_unit, hook_violations, renderer_violations, violations


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


class HookBoundaryTest(unittest.TestCase):
    def test_units_follow_hook_directories(self):
        self.assertEqual("root", hook_unit(("HookEntry.kt",)))
        self.assertEqual("base", hook_unit(("base", "dex", "DexRuntime.kt")))
        self.assertEqual("rules.systemui", hook_unit(("rules", "systemui", "aod", "AODHooker.kt")))

    def test_infrastructure_and_entry_files_reach_features_only_through_bootstrap(self):
        feature = "import com.takekazex.hypertweak.hook.rules.systemui.aod.AODHooker"
        self.assertEqual([(1, "rules.systemui")], hook_violations("root", feature))
        self.assertEqual([(1, "rules.system")], hook_violations(
            "preferences", "val x = com.takekazex.hypertweak.hook.rules.system.Policy.enabled()"))
        self.assertEqual([(1, "rules")], hook_violations("base", "import com.takekazex.hypertweak.hook.rules.*"))
        self.assertEqual([(1, "bootstrap")], hook_violations(
            "runtime", "import com.takekazex.hypertweak.hook.bootstrap.SystemUiHookPlan"))
        self.assertEqual([], hook_violations("root", "import com.takekazex.hypertweak.hook.bootstrap.ProcessLifecycleHooks"))
        self.assertEqual([], hook_violations("bootstrap", feature))
        self.assertEqual([], hook_violations("runtime", "import com.takekazex.hypertweak.hook.Preferences"))

    def test_feature_families_use_shared_capabilities_or_declared_collaborations(self):
        self.assertEqual([(1, "rules.systemui")], hook_violations(
            "rules.aod", "import com.takekazex.hypertweak.hook.rules.systemui.AODHooker"))
        self.assertEqual([(1, "rules.bluetooth")], hook_violations(
            "rules.shared", "import com.takekazex.hypertweak.hook.rules.bluetooth.AirPodsScope"))
        self.assertEqual([(1, "bootstrap")], hook_violations(
            "rules.camera", "import com.takekazex.hypertweak.hook.bootstrap.SystemUiHookPlan"))
        self.assertEqual([], hook_violations("rules.settings", "import com.takekazex.hypertweak.hook.rules.shared.AirPodsScope"))
        self.assertEqual([], hook_violations("rules.freeform", "import com.takekazex.hypertweak.hook.rules.aicr.AicrCopyDirectAction"))
        self.assertEqual([], hook_violations("rules.systemui", "import com.takekazex.hypertweak.hook.rules.systemui.icon.Duo"))
        self.assertEqual([], hook_violations("rules.camera", "import com.takekazex.hypertweak.hook.base.BaseHooker"))
        self.assertEqual([], hook_violations("rules.camera", "// com.takekazex.hypertweak.hook.rules.systemui.X"))


class IconRendererBoundaryTest(unittest.TestCase):
    icon = ("rules", "systemui", "icon")

    def test_renderers_stay_inside_their_owner(self):
        self.assertEqual([], renderer_violations(self.icon + ("duo", "DuoComposition.kt"), "DuoRenderer.TAG"))
        self.assertEqual([], renderer_violations(self.icon + ("StackedPanelMotion.kt",), "StackedRenderer.x"))
        self.assertEqual([(1, "LeftRenderer")], renderer_violations(
            self.icon + ("duo", "DuoNativeViews.kt"), "LeftRenderer.onDuoOwnershipChanged()"))
        self.assertEqual([(1, "CarrierRenderer")], renderer_violations(
            self.icon + ("SlotMaskApplier.kt",), "import com.takekazex.hypertweak.hook.rules.systemui.icon.carrier.CarrierRenderer"))

    def test_composition_root_comments_and_declared_collaborations_are_allowed(self):
        self.assertEqual([], renderer_violations(("bootstrap", "SystemUiIconPorts.kt"), "LeftRenderer.homeOwnedSlots()"))
        self.assertEqual([], renderer_violations(self.icon + ("SlotMaskApplier.kt",), "// CarrierRenderer owns rows"))
        self.assertEqual([], renderer_violations(("rules", "systemui", "aod", "AodStatusIconHooker.kt"), "DuoRenderer.createAodDuoView(c)"))
        self.assertEqual([(1, "LeftRenderer")], renderer_violations(
            ("rules", "systemui", "aod", "AodStatusIconHooker.kt"), "LeftRenderer.x"))
