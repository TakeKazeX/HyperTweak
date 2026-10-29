// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <stdint.h>
namespace hypertweak::native {
const char* NativeRuleRuntimeSource();
void* NativeRuleCurrentDartHandle();
void ObserveNativeRuleDartLibrary(void* handle);
bool ReceiveNativeRuleSettings(bool hidden, int32_t columns, bool contextual_search, int64_t revision, bool assistant_widgets = false, bool recents_haptic = false, bool folder_stack = false, bool widget_stack = false, bool xiaoai_options = false, int32_t icon_percent = -1, int32_t desktop_grid = 0);
void ApplyPreparedNativeRules();
void UpdateNativeRuleSettings(bool hidden, int32_t columns, bool contextual_search, bool assistant_widgets = false, bool recents_haptic = false, bool folder_stack = false, bool widget_stack = false, bool xiaoai_options = false, int32_t icon_percent = -1, int32_t desktop_grid = 0);
void RequestNativeRulePreparation();
bool TryNotifyDesktopGridPreparation();
void NotifyDockFramePreparation();
void MarkNativeRuntimeInitialized();
void ResetNativeRuleRuntimeAfterFork();
void PrepareNativeRulesForFork();
void ResumeNativeRulesAfterFork();
}
