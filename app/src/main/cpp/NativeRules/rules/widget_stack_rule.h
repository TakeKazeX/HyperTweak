// SPDX-License-Identifier: Apache-2.0
#pragma once
namespace hypertweak::native {
void SetAndroidWidgetStackEnabled(bool);
bool AndroidWidgetStackRequested();
bool ApplyAndroidWidgetStackRule(void* handle);
const char* AndroidWidgetStackRuleReason();
void ResetAndroidWidgetStackAfterFork();
}
