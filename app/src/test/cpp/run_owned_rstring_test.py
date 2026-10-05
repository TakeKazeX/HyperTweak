#!/usr/bin/env python3
"""Compile the real allocation and pre-setter failure path with failing malloc/setter stubs."""
from pathlib import Path
import subprocess
import tempfile

source = (Path(__file__).resolve().parents[2] / "main/cpp/NativeRules/miui_home_native_hook.cpp").read_text()
structs = source[source.index("struct RString {"):source.index("struct BorrowedROptionRString {")]
helpers = source[source.index("bool MakeOwnedRString("):source.index("template <typename T>", source.index("bool MakeOwnedRString("))]
start = source.index("    ROptionRString owned_action{};", source.index("IntentSetStringFn set_action"))
end = source.index("    if (extras != nullptr)", start)
allocation_path = source[start:end]
preamble = r'''
#include <cassert>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <set>
#include <iostream>
static int attempt, fail_at, freed, dropped, consumed;
static std::set<void*> live;
void* TestMalloc(size_t n) {
    if (++attempt == fail_at) return nullptr;
    void* p = std::malloc(n);
    assert(p); live.insert(p); return p;
}
void TestFree(void* p) {
    if (!p) return;
    assert(live.erase(p) == 1); ++freed; std::free(p);
}
#define malloc TestMalloc
#define free TestFree
struct Profile { uintptr_t rstring_vtable_offset = 1; };
static Profile profile;
static uint8_t base[8];
static uint8_t* g_launcher_base = base;
const Profile* CurrentLauncherProfile() { return &profile; }
size_t ConstStringLength(const char* s) { return std::strlen(s); }
static uint32_t g_native_broadcast_send_state;
template<class T> void AtomicStore(T* p, T v) { *p = v; }
static const char* kSystemUiPackage = "com.android.systemui";
'''
stubs = r'''
void intent_drop(void*) { ++dropped; }
void set_action(void*, ROptionRString* s) { ++consumed; TestFree(s->value.data); }
void set_package(void*, ROptionRString* s) { ++consumed; TestFree(s->value.data); }
bool Send(const char* action) {
    void* intent = base;
'''
tests = r'''
    return true;
}
void Check(int failure, const char* action, bool ok, int allocations, int frees, int setters) {
    attempt = freed = dropped = consumed = 0;
    fail_at = failure;
    assert(Send(action) == ok);
    assert(attempt == allocations);
    assert(freed == frees);
    assert(consumed == setters);
    assert(dropped == (ok ? 0 : 1));
    assert(live.empty());
}
int main() {
    Check(1, "action", false, 1, 0, 0);
    Check(2, "action", false, 2, 1, 0);
    Check(0, "action", true, 2, 2, 2);
    Check(2, "", false, 2, 1, 0);
    Check(0, "", true, 2, 2, 2);
    Check(0, nullptr, false, 0, 0, 0);
    std::cout << "owned RString production allocation paths passed\n";
}
'''
with tempfile.TemporaryDirectory(prefix="ht-rstring-") as temp:
    cpp = Path(temp) / "test.cpp"
    binary = Path(temp) / "test"
    cpp.write_text(preamble + structs + helpers + stubs + allocation_path + tests)
    subprocess.run(["clang++", "-std=c++17", "-Wall", "-Wextra", "-Werror", "-fsanitize=address,undefined", str(cpp), "-o", str(binary)], check=True)
    subprocess.run([str(binary)], check=True)
