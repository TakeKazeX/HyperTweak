#!/usr/bin/env python3
"""Execute the production terminal under ASan/UBSan; callback storage is not cleanup."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[4]
source = (root / 'app/src/main/cpp/NativeRules/miui_home_native_hook.cpp').read_text()
start = source.index('void HookContextualLongPressHandler(')
end = source.index('\n// SfAnim CLOSE_TO_HOME', start)
body = source[start:end]
preamble = r'''
#include "contextual_motion_gate.h"
#include <cstdint>
#include <cinttypes>
#include <cassert>
#include <cstdio>
#include <unistd.h>
using ContextualLongPressHandlerFn = void (*)(void*, uint32_t);
using ContextualSearchInvokeFn = uint8_t (*)(uint32_t);
void* g_original_contextual_long_press_handler = nullptr;
void* g_contextual_search_invoke = nullptr;
uint32_t g_contextual_long_press_trigger_count, g_contextual_long_press_passthrough_count;
uint32_t g_contextual_long_press_upward_cancel_count, g_contextual_search_enabled = 1;
uint32_t g_contextual_search_invoke_count, g_contextual_search_invoke_last_result;
void* g_launcher_base = reinterpret_cast<void*>(0x1000);
struct Slot { uintptr_t base, contextual_input_entry; void* original_contextual_input; };
Slot g_launcher_input_slots[] = {{0x1000, 1, reinterpret_cast<void*>(1)}};
miui_home_contextual::MotionGate g_contextual_motion_gate;
constexpr int ANDROID_LOG_INFO = 4, ANDROID_LOG_WARN = 5;
constexpr char kLogTag[] = "test";
int __android_log_print(int, const char*, const char*, ...) { return 0; }
void Log(int, const char*) {}
bool SendNativeBroadcast(const char*, void*) { return true; }
template<class T> T AtomicLoad(T* p) { return __atomic_load_n(p, __ATOMIC_ACQUIRE); }
int originals = 0, invocations = 0;
uint32_t last_mode = 0;
uint8_t response = 1;
void Original(void* p, uint32_t mode) {
    ++originals; last_mode = mode;
    if (p) *static_cast<uint8_t*>(*static_cast<void**>(p)) = 1;
}
uint8_t Invoke(uint32_t entry) { assert(entry == 1 && last_mode == 0); ++invocations; return response; }
'''
tests = r'''
int main() {
    g_original_contextual_long_press_handler = reinterpret_cast<void*>(Original);
    g_contextual_search_invoke = reinterpret_cast<void*>(Invoke);
    uint8_t completion = 0;
    void* closure[] = {&completion, reinterpret_cast<void*>(1), reinterpret_cast<void*>(1)};
    g_contextual_motion_gate.observe(1, getpid(), 100, 2, 0x1002, 0, 10, 20);
    HookContextualLongPressHandler(closure, 1);
    assert(completion == 1 && originals == 1 && invocations == 1 && last_mode == 0);
    g_contextual_motion_gate.observe(1, getpid(), 100, 2, 0x1002, 2, 10, 0);
    HookContextualLongPressHandler(closure, 1);
    assert(originals == 2 && invocations == 1 && last_mode == 0);
    g_contextual_motion_gate.observe(1, getpid(), 200, 2, 0x1002, 0, 10, 20);
    g_contextual_motion_gate.observe(1, getpid(), 200, 2, 0x1002, 1, 10, 20);
    HookContextualLongPressHandler(closure, 1);
    assert(originals == 3 && invocations == 1);
    g_contextual_search_enabled = 0;
    HookContextualLongPressHandler(closure, 7);
    assert(originals == 4 && last_mode == 7 && invocations == 1);
    g_contextual_search_enabled = 1;
    g_launcher_input_slots[0].original_contextual_input = nullptr;
    HookContextualLongPressHandler(closure, 1);
    assert(originals == 5 && last_mode == 1 && invocations == 1);
    g_launcher_input_slots[0].original_contextual_input = reinterpret_cast<void*>(1);
    g_contextual_motion_gate.observe(1, getpid(), 300, 2, 0x1002, 0, 10, 20);
    response = 0;
    HookContextualLongPressHandler(closure, 1);
    assert(originals == 6 && invocations == 2 && g_contextual_search_invoke_last_result == 0);
    puts("production CTS: native Fn completion, physical cancellation, released stream and unavailable observer passed");
}
'''
with tempfile.TemporaryDirectory(prefix='ht-cts-terminal-') as temp:
    cpp, binary = Path(temp) / 'test.cpp', Path(temp) / 'test'
    cpp.write_text(preamble + body + tests)
    subprocess.run(['clang++', '-std=c++17', '-Wall', '-Wextra', '-Werror',
                    '-fsanitize=address,undefined', '-I', str(root / 'app/src/main/cpp/NativeRules/runtime'),
                    str(cpp), '-o', str(binary)], check=True)
    subprocess.run([str(binary)], check=True)
