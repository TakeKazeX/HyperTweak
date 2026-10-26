// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <stdint.h>
namespace hypertweak::native {
void SetAssistantWidgetAllowed(bool allowed);
bool AssistantWidgetAllowedRequested();
bool ApplyAssistantWidgetRule(void* handle = nullptr);
uint32_t AssistantWidgetHookHits();
const char* AssistantWidgetRuleReason();
uintptr_t AssistantWidgetTargetAddress();
void OnAssistantWidgetLibraryLoaded(const char* name, void* handle);
void MaintainAssistantWidgetRuleOnActionDown(void* handle);
void ResetAssistantWidgetStateAfterFork();
void RefreshAssistantWidgetConfig();
}
