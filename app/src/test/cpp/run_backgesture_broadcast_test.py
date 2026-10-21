#!/usr/bin/env python3
"""Exercise production options-GOT ownership checks and forwarding rollback."""
from pathlib import Path
import subprocess
import tempfile

native = Path(__file__).resolve().parents[2] / "main/cpp/NativeRules"
source = (native / "miui_home_native_hook.cpp").read_text()
start = source.index("bool InstallBroadcastIntentWithFeatureGot(")
body = source[start:source.index("bool SendNativeBroadcast(const char* action, void* extras) {", start)]
preamble = r'''
#include "standalone_gesture_owner.h"
#include <cassert>
#include <cstdint>
#include <cstring>
#include <cinttypes>
#include <iostream>
constexpr int PROT_READ = 1, PROT_WRITE = 2, ANDROID_LOG_INFO = 4;
constexpr char kLogTag[] = "test";
constexpr char kBroadcastPrivatePath[] = "/system_ext/lib64/libhyper_os_broadcast_private.dylib.so";
struct Dl_info { const char* dli_fname; void* dli_fbase; };
void Original() {}
void Upstream() {}
void Foreign() {}
void MiuiHomeHyosBroadcastOptionsTailHook() {}
char private_base[8];
int dladdr(void* p, Dl_info* info) {
    if (p == reinterpret_cast<void*>(Original)) {
        *info = {kBroadcastPrivatePath, private_base}; return 1;
    }
    if (p == reinterpret_cast<void*>(Upstream)) {
        *info = {"/data/app/~~id/dev.codex.miuibackgesturehook-id/base.apk!/lib/arm64-v8a/libmiui_home_hyos_lsp.so", nullptr}; return 1;
    }
    *info = {"/data/app/other/lib/arm64/libmiui_home_hyos_lsp.so", nullptr}; return 1;
}
bool StringsEqual(const char* a, const char* b) { return std::strcmp(a,b) == 0; }
template<class T> T AtomicLoad(T* p) { return *p; }
template<class T> void AtomicStore(T* p, T value) { *p = value; }
uint32_t g_native_receiver_state;
void* miui_home_hyos_broadcast_original;
void** g_broadcast_intent_with_feature_slot;
int writes, fail_write;
bool race;
bool SetBroadcastPrivateGotWritable(void** slot, int) {
    if (race && ++writes == 1) *slot = reinterpret_cast<void*>(Foreign);
    else if (!race) ++writes;
    return writes != fail_write;
}
template<class... T> void __android_log_print(T...) {}
uint32_t g_rule_broadcast_state = 3u, g_rule_broadcast_installing = 0u;
void* options_slot;
constexpr char kBroadcastIntentWithFeatureSymbol[] = "options";
struct NativeSymbolResolver {};
NativeSymbolResolver resolver;
NativeSymbolResolver* NewNativeSymbolResolver(const char*, void*) { return &resolver; }
void* GetNativeBaseAddress(NativeSymbolResolver*) { return private_base; }
void* LookupNativeSymbol(NativeSymbolResolver*, const char*, bool, size_t*) { return reinterpret_cast<void*>(Original); }
void** LookupNativePltSlot(NativeSymbolResolver*, const char*) { return &options_slot; }
void FreeNativeSymbolResolver(NativeSymbolResolver*) {}
namespace hypertweak::native { struct NativeRuleLibraryQuery { NativeRuleLibraryQuery() {} }; }
void Reset() {
    writes = fail_write = 0; race = false;
    miui_home_hyos_broadcast_original = nullptr;
    g_broadcast_intent_with_feature_slot = nullptr;
}
'''
tests = r'''
int main() {
    void* slot = reinterpret_cast<void*>(Original);
    Reset();
    assert(InstallBroadcastIntentWithFeatureGot(slot, private_base, &slot));
    assert(slot == reinterpret_cast<void*>(MiuiHomeHyosBroadcastOptionsTailHook));
    assert(miui_home_hyos_broadcast_original == reinterpret_cast<void*>(Original));
    Reset(); slot = reinterpret_cast<void*>(Original);
    assert(!InstallBroadcastIntentWithFeatureGot(slot, private_base, &slot, true));
    assert(slot == reinterpret_cast<void*>(Original)); // let upstream install first
    Reset(); slot = reinterpret_cast<void*>(Foreign);
    assert(!InstallBroadcastIntentWithFeatureGot(reinterpret_cast<void*>(Original), private_base, &slot, true));
    assert(slot == reinterpret_cast<void*>(Foreign));
    Reset(); slot = reinterpret_cast<void*>(Upstream);
    assert(InstallBroadcastIntentWithFeatureGot(reinterpret_cast<void*>(Original), private_base, &slot, true));
    assert(miui_home_hyos_broadcast_original == reinterpret_cast<void*>(Upstream));
    Reset(); slot = reinterpret_cast<void*>(Upstream); race = true;
    assert(!InstallBroadcastIntentWithFeatureGot(reinterpret_cast<void*>(Original), private_base, &slot, true));
    assert(slot == reinterpret_cast<void*>(Foreign)); // never overwrite a changed owner
    Reset(); slot = reinterpret_cast<void*>(Upstream); fail_write = 2;
    assert(!InstallBroadcastIntentWithFeatureGot(reinterpret_cast<void*>(Original), private_base, &slot, true));
    assert(slot == reinterpret_cast<void*>(Upstream)); // rollback retains upstream chain
    Reset(); options_slot = reinterpret_cast<void*>(Upstream);
    g_rule_broadcast_installing = 1;
    assert(!EnsureRuleBroadcastOptions());
    assert(options_slot == reinterpret_cast<void*>(Upstream));
    g_rule_broadcast_installing = 0;
    assert(EnsureRuleBroadcastOptions());
    const int installed_writes = writes;
    assert(EnsureRuleBroadcastOptions() && writes == installed_writes);
    options_slot = reinterpret_cast<void*>(Foreign);
    assert(!EnsureRuleBroadcastOptions());
    assert(options_slot == reinterpret_cast<void*>(Foreign));
    std::cout << "production broadcast GOT chain identity, race rejection and rollback passed\n";
}
'''
with tempfile.TemporaryDirectory(prefix="ht-gesture-broadcast-") as temp:
    cpp, binary = Path(temp) / "test.cpp", Path(temp) / "test"
    cpp.write_text(preamble + body + tests)
    subprocess.run(["clang++", "-std=c++17", "-Wall", "-Wextra", "-Werror",
                    "-fsanitize=address,undefined", "-I", str(native), str(cpp), "-o", str(binary)], check=True)
    subprocess.run([str(binary)], check=True)
