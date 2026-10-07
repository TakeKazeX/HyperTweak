// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <stdint.h>
namespace hypertweak::native {
struct NativeRuleSettingsSnapshot {
    bool hidden = false;
    int32_t columns = 3;
    bool contextual = false;
    bool widgets = false;
    int64_t revision = 0;
    bool dock_channel = false;
    int32_t dock_port = 0;
    int64_t dock_token0 = 0, dock_token1 = 0;
};
// Intent/Context belong to the native receiver callback. Capture only primitive
// values before forwarding: the downstream receiver may consume its arguments.
// Never inspect the borrowed Intent after the original dispatch returns.
using CaptureRuleSettings = bool (*)(void*, NativeRuleSettingsSnapshot*);
using ForwardRuleReceiver = void (*)(void*, void*, void*);
using AcceptRuleSettings = void (*)(const NativeRuleSettingsSnapshot&);
inline void DispatchNativeRuleSettings(void* receiver, void* context, void* intent,
        CaptureRuleSettings capture, ForwardRuleReceiver original, AcceptRuleSettings accept) {
    if (original == nullptr) return;
    NativeRuleSettingsSnapshot snapshot{};
    const bool captured = capture != nullptr && capture(intent, &snapshot);
    original(receiver, context, intent);
    if (captured && accept != nullptr) accept(snapshot);
}
}
