// SPDX-License-Identifier: Apache-2.0
#include "native_rule_settings_dispatch.h"
#include <assert.h>
#include <stdio.h>
using namespace hypertweak::native;
namespace {
struct Intent { bool trusted; NativeRuleSettingsSnapshot snapshot; };
unsigned captures = 0, forwarded = 0, accepted = 0;
void* expected_receiver = reinterpret_cast<void*>(1);
void* expected_context = reinterpret_cast<void*>(2);
bool Capture(void* intent, NativeRuleSettingsSnapshot* snapshot) {
    ++captures;
    auto* value = static_cast<Intent*>(intent);
    if (!value || !value->trusted) return false;
    *snapshot = value->snapshot;
    return true;
}
void Forward(void* receiver, void* context, void* intent) {
    assert(receiver == expected_receiver && context == expected_context);
    ++forwarded;
    assert(captures == forwarded);
    delete static_cast<Intent*>(intent); // Original consumes the boxed argument.
}
void Accept(const NativeRuleSettingsSnapshot& snapshot) {
    assert(forwarded == 1 && snapshot.widgets && snapshot.columns == 4 && snapshot.revision == 31);
    ++accepted;
}
}
int main() {
    DispatchNativeRuleSettings(expected_receiver, expected_context,
        new Intent{true,{true,4,false,true,31}}, Capture, Forward, Accept);
    assert(captures == 1 && forwarded == 1 && accepted == 1);
    captures = forwarded = accepted = 0;
    DispatchNativeRuleSettings(expected_receiver, expected_context,
        new Intent{false,{}}, Capture, Forward, Accept);
    assert(captures == 1 && forwarded == 1 && accepted == 0);
    captures = forwarded = 0;
    DispatchNativeRuleSettings(expected_receiver, expected_context, nullptr, Capture, Forward, Accept);
    assert(captures == 1 && forwarded == 1 && accepted == 0);
    puts("PASS: primitive snapshot before consuming receiver, unchanged forwarding and untrusted/null rejection (ASan/UBSan)");
}
