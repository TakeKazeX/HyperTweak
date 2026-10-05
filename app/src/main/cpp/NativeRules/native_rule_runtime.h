// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <stdint.h>
namespace hypertweak::native {
const char* NativeRuleRuntimeSource();
void* NativeRuleCurrentDartHandle();
void ObserveNativeRuleDartLibrary(void* handle);
bool ReceiveNativeRuleSettings(bool hidden, int32_t columns, bool contextual_search, int64_t revision, bool assistant_widgets = false);
void ApplyPreparedNativeRules();
void UpdateNativeRuleSettings(bool hidden, int32_t columns, bool contextual_search, bool assistant_widgets = false);
void RequestNativeRulePreparation();
void MarkNativeRuntimeInitialized();
void ResetNativeRuleRuntimeAfterFork();
void PrepareNativeRulesForFork();
void ResumeNativeRulesAfterFork();
}
