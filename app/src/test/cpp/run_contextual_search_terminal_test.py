#!/usr/bin/env python3
"""Execute the production terminal under ASan/UBSan; callback storage is not cleanup."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[4]
source = (root / 'app/src/main/cpp/NativeRules/miui_home_native_hook.cpp').read_text()
start = source.index('void HookContextualLongPressHandler(')
end = source.index('\nbool InstallContextualSearchLongPressHook(', start)
body = source[start:end]
preamble = r'''
#include <cstdint>
#include <cassert>
#include <cstdio>
using ContextualLongPressHandlerFn = void (*)(void*, uint32_t);
using ContextualSearchInvokeFn = uint8_t (*)(uint32_t);
void* g_original_contextual_long_press_handler = nullptr;
void* g_contextual_search_invoke = nullptr;
uint32_t g_contextual_long_press_trigger_count = 0;
uint32_t g_contextual_long_press_passthrough_count = 0;
uint32_t g_contextual_long_press_upward_cancel_count = 0;
uint32_t g_contextual_search_enabled = 1;
uint32_t g_contextual_search_invoke_count = 0;
uint32_t g_contextual_search_invoke_last_result = 0;
uint32_t g_contextual_motion_snapshot_valid = 0;
uint32_t g_contextual_motion_snapshot_down_y_bits = 0;
uint32_t g_contextual_motion_snapshot_current_y_bits = 0;
constexpr int ANDROID_LOG_INFO = 4;
constexpr char kLogTag[] = "test";
int __android_log_print(int, const char*, const char*, ...) { return 0; }
template<class T> T AtomicLoad(T* p) { return __atomic_load_n(p, __ATOMIC_ACQUIRE); }
float BitsFloat(uint32_t) { return 0; }
bool upward = false;
bool ContextualLongPressHasUpwardIntent() { return upward; }
int originals = 0, invocations = 0;
uint32_t last_mode = 0;
uint8_t response = 1;
void Original(void*, uint32_t mode) { ++originals; last_mode = mode; }
uint8_t Invoke(uint32_t entry) { assert(entry == 1); ++invocations; return response; }
'''
tests = r'''
int main() {
    g_original_contextual_long_press_handler = reinterpret_cast<void*>(Original);
    g_contextual_search_invoke = reinterpret_cast<void*>(Invoke);
    alignas(void*) uint8_t completion[24]{};
    // These pointers are deliberately unreadable: they belong to the stock
    // Fn(trigger_mode), which must never be dereferenced or called as cleanup.
    void* closure[] = {completion, reinterpret_cast<void*>(1), reinterpret_cast<void*>(1)};
    for (int i = 0; i < 8; ++i) {
        completion[16] = 0;
        HookContextualLongPressHandler(closure, 1);
        assert(completion[16] == 1 && invocations == i + 1 && originals == 0);
    }
    upward = true;
    completion[16] = 0;
    HookContextualLongPressHandler(closure, 1);
    assert(completion[16] == 1 && invocations == 8 && originals == 0);
    upward = false;
    g_contextual_search_enabled = 0;
    HookContextualLongPressHandler(closure, 7);
    assert(originals == 1 && last_mode == 7 && invocations == 8);
    g_contextual_search_enabled = 1;
    response = 0;
    HookContextualLongPressHandler(closure, 1);
    assert(invocations == 9 && originals == 1 && g_contextual_search_invoke_last_result == 0);
    HookContextualLongPressHandler(nullptr, 3);
    closure[0] = nullptr;
    HookContextualLongPressHandler(closure, 4);
    assert(originals == 3 && last_mode == 4 && invocations == 9);
    closure[0] = completion;
    g_contextual_search_invoke = nullptr;
    HookContextualLongPressHandler(closure, 5);
    assert(originals == 4 && last_mode == 5 && invocations == 9);
    std::puts("production CTS terminal: single request, no callback-as-cleanup, cancellation and native passthrough OK");
}
'''
with tempfile.TemporaryDirectory(prefix='ht-cts-terminal-') as temp:
    cpp, binary = Path(temp) / 'test.cpp', Path(temp) / 'test'
    cpp.write_text(preamble + body + tests)
    subprocess.run(['clang++', '-std=c++17', '-Wall', '-Wextra', '-Werror',
                    '-fsanitize=address,undefined', str(cpp), '-o', str(binary)], check=True)
    subprocess.run([str(binary)], check=True)
