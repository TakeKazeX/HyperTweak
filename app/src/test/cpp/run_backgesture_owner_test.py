#!/usr/bin/env python3
"""Run the production load boundary with and without an upstream gesture owner."""
from pathlib import Path
import subprocess
import tempfile

native = Path(__file__).resolve().parents[2] / "main/cpp/NativeRules"
source = (native / "miui_home_native_hook.cpp").read_text()
start = source.index("void OnLsposedLibraryLoaded(const char* name, void* handle) {")
body = source[start:source.index("// HyperTweak: the contextual-search enable gate", start)]
preamble = r'''
#include "standalone_gesture_owner.h"
#include <cassert>
#include <cstdint>
#include <cstring>
#include <iostream>
bool upstream, query_active;
int shared_hooks, rules, settings, observer_calls;
void* g_launcher_handle = nullptr;
void* g_dart_app_handle = nullptr;
constexpr char kHyperRuntimeName[] = "libhyper_os_flutter.so";
constexpr char kLauncherEntrySymbol[] = "app_entry_point";
constexpr char kBroadcastPrivatePath[] = "/system_ext/lib64/libhyper_os_broadcast_private.dylib.so";
constexpr int ANDROID_LOG_INFO = 1, ANDROID_LOG_ERROR = 2;
void Log(int, const char*) {}
bool StandaloneGestureOwnerLoaded() { return upstream; }
bool IsLauncherProcess() { return true; }
bool IsLauncherHookProcess() { return true; }
bool EnsureDartStateOwnerForCurrentProcess() { return true; }
bool StringsEqual(const char* a, const char* b) { return std::strcmp(a,b) == 0; }
bool EndsWith(const char* name, const char* suffix) {
    const size_t n = std::strlen(name), s = std::strlen(suffix);
    return n >= s && std::strcmp(name + n - s, suffix) == 0;
}
bool IsLauncherLibraryPath(const char* n) { return EndsWith(n, "libapp_launcher.so"); }
bool IsDartLibraryPath(const char* n) { return EndsWith(n, "libapp.so"); }
bool EnsureLsposedMadviseGuard(const char* = nullptr) { return true; }
template<class T> void AtomicStore(T* p, T v) { *p = v; }
void ObserveLauncherHandle(const char* name, void* handle) {
    ++observer_calls;
    if (IsLauncherLibraryPath(name)) g_launcher_handle = handle;
}
void ObserveLauncherSymbol(void*, const char*, void*) {}
void* dlsym(void*, const char*) { return reinterpret_cast<void*>(2); }
bool InstallLauncherInputHooksForProfile(void*) { ++shared_hooks; return true; }
void InstallBusinessHooksForProfile(void*) { ++shared_hooks; }
const int* ResolveDartFeatureProfile(void*) { static int profile; return &profile; }
void TryInstallDartDrawerStateHook(void*, const int*) { ++shared_hooks; }
void TryInstallDartOverviewStateHook(void*, const int*) { ++shared_hooks; }
void TryInstallDartEditingStateHook(void*, const int*) { ++shared_hooks; }
void TryInstallNativeRuleSettingsBridge() { ++settings; }
void TryInstallArbiterBridge() { ++shared_hooks; }
void PrepareRuleBroadcastSupport(void* handle) { assert(g_launcher_handle == handle); }
namespace hypertweak::native {
bool NativeRuleLibraryQueryActive() { return query_active; }
void ObserveNativeRuleDartLibrary(void*) { ++observer_calls; }
void OnClearButtonLibraryLoaded(const char*, void*) { ++rules; }
void OnFolderColumnsLibraryLoaded(const char*, void*) { ++rules; }
void OnAssistantWidgetLibraryLoaded(const char*, void*) { ++rules; }
}
'''
tests = r'''
int main() {
    using hypertweak::native::IsStandaloneGestureImage;
    assert(IsStandaloneGestureImage("/data/app/~~id/dev.codex.miuibackgesturehook-hash/base.apk!/lib/arm64-v8a/libmiui_home_hyos_lsp.so"));
    assert(IsStandaloneGestureImage("/data/app/dev.codex.miuibackgesturehook-hash/lib/arm64/libmiui_home_hyos_lsp.so"));
    assert(!IsStandaloneGestureImage(nullptr));
    assert(!IsStandaloneGestureImage("libmiui_home_hyos_lsp.so"));
    assert(!IsStandaloneGestureImage("/data/app/dev.codex.miuibackgesturehook-other/lib/arm64/libother.so"));
    assert(!IsStandaloneGestureImage("/data/app/other.dev.codex.miuibackgesturehook-hash/lib/arm64/libmiui_home_hyos_lsp.so"));
    assert(!IsStandaloneGestureImage("/data/local/tmp/dev.codex.miuibackgesturehook-hash/libmiui_home_hyos_lsp.so"));
    void* handle = reinterpret_cast<void*>(1);
    OnLsposedLibraryLoaded("libapp_launcher.so", handle);
    OnLsposedLibraryLoaded("libapp.so", handle);
    assert(shared_hooks == 7 && rules == 6 && settings == 1);
    assert(g_launcher_handle == handle && g_dart_app_handle == handle);
    upstream = true;
    shared_hooks = rules = settings = observer_calls = 0;
    g_dart_app_handle = nullptr;
    OnLsposedLibraryLoaded("libapp_launcher.so", handle);
    OnLsposedLibraryLoaded("libapp.so", handle);
    OnLsposedLibraryLoaded(kBroadcastPrivatePath, handle);
    assert(shared_hooks == 0); // no input, side, contextual, Dart-state or arbiter patch
    assert(rules == 9 && settings == 2 && g_dart_app_handle == handle);
    assert(observer_calls == 4); // independent image discovery stays alive
    query_active = true;
    OnLsposedLibraryLoaded("libapp.so", handle);
    assert(rules == 9 && settings == 2); // internal queries still cannot re-enter
    std::cout << "production native load boundary preserves independent rules and yields shared hooks\n";
}
'''
with tempfile.TemporaryDirectory(prefix="ht-gesture-owner-") as temp:
    cpp, binary = Path(temp) / "test.cpp", Path(temp) / "test"
    cpp.write_text(preamble + body + tests)
    subprocess.run(["clang++", "-std=c++17", "-Wall", "-Wextra", "-Werror",
                    "-fsanitize=address,undefined", "-I", str(native), str(cpp), "-o", str(binary)], check=True)
    subprocess.run([str(binary)], check=True)
