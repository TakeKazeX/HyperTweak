#!/usr/bin/env python3
"""Exercise production arbiter admission with colliding standalone-module extras."""
from pathlib import Path
import re
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
source = (root / "main/cpp/NativeRules/miui_home_native_hook.cpp").read_text()
java = root / "main/java/com/takekazex/hypertweak/hook/rules/backgesture"
core = (java / "hooks/core/HookRuntimeCore.java").read_text()
protocol = (java / "NativeHookStatusProtocol.java").read_text()
native_actions = set(re.findall(r'"(com\.takekazex\.hypertweak\.backgesture\.action\.[A-Z_]+)"', source))
java_actions = set(re.findall(r'"(com\.takekazex\.hypertweak\.backgesture\.action\.[A-Z_]+)"', core + protocol))
expected_actions = {
    "CONTEXTUAL_SEARCH_TRIGGERED", "MIUI_HOME_OPEN_BREAK", "MIUI_HOME_INPUT_ARBITER_QUERY",
    "SYSTEMUI_INPUT_ARBITER_STATE", "CONTEXTUAL_SEARCH_REQUEST", "RUNTIME_STATUS_QUERY",
    "MIUI_OVERVIEW_STATE_CHANGE", "RUNTIME_STATUS_REPLY",
}
assert java_actions == {"com.takekazex.hypertweak.backgesture.action." + name for name in expected_actions}
assert native_actions < java_actions
assert "dev.codex.miuibackgesturehook.action." not in source + core + protocol
key = re.search(r'kInputArbiterGenerationExtra\[\]\s*=\s*"([^"]+)"', source)[1]
assert re.search(r'EXTRA_INPUT_ARBITER_GENERATION\s*=\s*"([^"]+)"', core)[1] == key

observe = source[source.index("void ObserveArbiterStateIntent("):source.index("bool IntentActionEquals(")]
marker = source[source.index("bool HasArbiterStateMarker("):source.index("__attribute__((noinline))", source.index("bool HasArbiterStateMarker("))]
preamble = r'''
#include <cassert>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <map>
#include <string>
#include <type_traits>
constexpr int ANDROID_LOG_WARN = 1, ANDROID_LOG_INFO = 2;
constexpr char kLogTag[] = "test", kSystemUiPackage[] = "com.android.systemui";
struct BorrowedROptionRString { unsigned tag; const char* data; size_t length; };
struct Intent {
    const char* sender = kSystemUiPackage;
    std::map<std::string, int64_t> extras;
};
using IntentGetSenderPackageFn = BorrowedROptionRString(*)(void*);
using IntentGetExtrasFn = void*(*)(void*);
BorrowedROptionRString Sender(void* p) {
    const auto s = static_cast<Intent*>(p)->sender;
    return {0, s, std::strlen(s)};
}
void* Extras(void* p) { return &static_cast<Intent*>(p)->extras; }
template<class T> T ResolveLauncherSymbol(const char* name) {
    if constexpr (std::is_same_v<T, IntentGetExtrasFn>) {
        assert(std::strcmp(name, "Intent_get_extras") == 0); return &Extras;
    } else {
        assert(std::strcmp(name, "Intent_get_sender_package_name") == 0); return &Sender;
    }
}
bool ReadNativeI64(void* p, const char* key, int64_t* out) {
    const auto& extras = *static_cast<std::map<std::string, int64_t>*>(p);
    auto it = extras.find(key);
    if (it == extras.end()) return false;
    *out = it->second; return true;
}
bool ReadNativeI32(void* p, const char* key, int32_t* out) {
    int64_t value; if (!ReadNativeI64(p, key, &value)) return false;
    *out = static_cast<int32_t>(value); return true;
}
bool ReadNativeBool(void* p, const char* key, bool* out) {
    int64_t value; if (!ReadNativeI64(p, key, &value)) return false;
    *out = value != 0; return true;
}
bool VerifySystemUiUid(int32_t uid) { return uid == 1000; }
size_t ConstStringLength(const char* s) { return std::strlen(s); }
template<class T> T AtomicLoad(T* p) { return *p; }
template<class T> void AtomicStore(T* p, T v) { *p = v; }
void Log(int, const char*) {}
template<class... T> void __android_log_print(T...) {}
int64_t g_systemui_arbiter_generation = 0;
uint32_t g_systemui_arbiter_ready = 0, g_contextual_search_enabled = 1;
'''
tests = r'''
int main() {
    Intent foreign;
    foreign.extras = {{"input_arbiter_generation", 999999},
                      {"input_arbiter_ready", 1}, {"sender_uid", 1000}};
    assert(!HasArbiterStateMarker(&foreign));
    ObserveArbiterStateIntent(&foreign);
    assert(g_systemui_arbiter_generation == 0);
    Intent own;
    own.extras = {{kInputArbiterGenerationExtra, 100},
                  {"input_arbiter_ready", 1}, {"sender_uid", 1000},
                  {"contextual_search_enabled", 0}};
    assert(HasArbiterStateMarker(&own));
    ObserveArbiterStateIntent(&own);
    assert(g_systemui_arbiter_generation == 100 && g_systemui_arbiter_ready == 1);
    assert(g_contextual_search_enabled == 1);
    ObserveArbiterStateIntent(&foreign);
    assert(g_systemui_arbiter_generation == 100 && g_systemui_arbiter_ready == 1);
    own.extras[kInputArbiterGenerationExtra] = 101;
    own.extras["input_arbiter_ready"] = 0;
    own.sender = "dev.codex.miuibackgesturehook";
    ObserveArbiterStateIntent(&own);
    assert(g_systemui_arbiter_generation == 100);
    own.sender = kSystemUiPackage;
    own.extras["sender_uid"] = 12345;
    ObserveArbiterStateIntent(&own);
    assert(g_systemui_arbiter_generation == 100);
    own.extras["sender_uid"] = 1000;
    own.extras[kInputArbiterGenerationExtra] = 99;
    ObserveArbiterStateIntent(&own);
    assert(g_systemui_arbiter_generation == 100 && g_systemui_arbiter_ready == 1);
    own.extras[kInputArbiterGenerationExtra] = 101;
    ObserveArbiterStateIntent(&own);
    assert(g_systemui_arbiter_generation == 101 && g_systemui_arbiter_ready == 0);
    own.extras[kInputArbiterGenerationExtra] = 0;
    assert(!HasArbiterStateMarker(&own));
    assert(!HasArbiterStateMarker(nullptr));
    ObserveArbiterStateIntent(nullptr);
    std::cout << "production arbiter namespace, ownership and generation fencing passed\n";
}
'''
with tempfile.TemporaryDirectory(prefix="ht-backgesture-ipc-") as temp:
    cpp, binary = Path(temp) / "test.cpp", Path(temp) / "test"
    cpp.write_text(preamble + f'constexpr char kInputArbiterGenerationExtra[] = "{key}";\n' + observe + marker + tests)
    subprocess.run(["clang++", "-std=c++17", "-Wall", "-Wextra", "-Werror",
                    "-fsanitize=address,undefined", str(cpp), "-o", str(binary)], check=True)
    subprocess.run([str(binary)], check=True)
