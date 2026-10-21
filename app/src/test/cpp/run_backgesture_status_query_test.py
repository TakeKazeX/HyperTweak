#!/usr/bin/env python3
"""Run production query admission and receiver ordering with a consuming OEM receiver."""
from pathlib import Path
import subprocess
import tempfile

source = (Path(__file__).resolve().parents[2] / "main/cpp/NativeRules/miui_home_native_hook.cpp").read_text()
a = source.index("int64_t CaptureRuntimeStatusQuery(void* intent) {")
b = source.index("bool HandleRuntimeStatusQuery(int64_t nonce) {", a)
capture = source[a:b]
a = source.index("void HookBroadcastReceiverOnReceive(void* receiver, void* context, void* intent) {")
b = source.index("// Settings transport uses the receiver", a)
receiver = source[a:b]
preamble = r'''
#include <cassert>
#include <cstdint>
#include <cstddef>
#include <cstring>
constexpr int ANDROID_LOG_WARN = 1;
const char* kLogTag = "test";
const char* kSystemUiPackage = "com.android.systemui";
const char* kRuntimeStatusQueryExtra = "status_query";
const char* kRuntimeStatusNonceExtra = "status_nonce";
const char* kArbiterStateAction = "arbiter";
const char* kArbiterStateCarrierAction = "carrier";
struct Intent {
    bool alive = true, arbiter = true, marked = true;
    bool present = true, query = true, valid_bool = true;
    bool uid_present = true, nonce_present = true;
    const char* sender = kSystemUiPackage;
    int32_t uid = 10228;
    int64_t nonce = 77;
};
struct BorrowedROptionRString { uint32_t tag; const char* data; size_t length; };
using IntentGetSenderPackageFn = BorrowedROptionRString (*)(void*);
using IntentGetExtrasFn = void* (*)(void*);
int warnings = 0, reads = 0, originals = 0, replies = 0;
int64_t replied_nonce = 0;
bool owner = true, original_ready = true, sender_api = true, extras_api = true;
Intent* current;
void Log(int, const char*) { ++warnings; }
int __android_log_print(int, const char*, const char*, ...) { ++warnings; return 0; }
size_t ConstStringLength(const char* s) { return std::strlen(s); }
void* GetExtras(void* p) { assert(static_cast<Intent*>(p)->alive); ++reads; return p; }
BorrowedROptionRString GetSender(void* p) {
    auto* i = static_cast<Intent*>(p); assert(i->alive); ++reads;
    return {0, i->sender, i->sender ? std::strlen(i->sender) : 0};
}
template<class T> T ResolveLauncherSymbol(const char* name);
template<> IntentGetExtrasFn ResolveLauncherSymbol<IntentGetExtrasFn>(const char* name) {
    assert(std::strcmp(name, "Intent_get_extras") == 0);
    return extras_api ? GetExtras : nullptr;
}
template<> IntentGetSenderPackageFn ResolveLauncherSymbol<IntentGetSenderPackageFn>(const char* name) {
    assert(std::strcmp(name, "Intent_get_sender_package_name") == 0);
    return sender_api ? GetSender : nullptr;
}
bool ReadNativeBool(void* p, const char*, bool* value) {
    if (!p) return false;
    auto* i = static_cast<Intent*>(p); assert(i->alive);
    if (!i->present || !i->valid_bool) return false;
    *value = i->query; return true;
}
bool ReadNativeI32(void* p, const char*, int32_t* value) {
    auto* i = static_cast<Intent*>(p); assert(i->alive);
    if (!i->uid_present) return false;
    *value = i->uid; return true;
}
bool ReadNativeI64(void* p, const char*, int64_t* value) {
    auto* i = static_cast<Intent*>(p); assert(i->alive);
    if (!i->nonce_present) return false;
    *value = i->nonce; return true;
}
bool VerifySystemUiUid(int32_t uid) { return uid == 10228; }
bool IsLauncherProcess() { return true; }
bool EnsureDartStateOwnerForCurrentProcess() { return owner; }
bool IntentActionEquals(void* p, const char*) { return static_cast<Intent*>(p)->arbiter; }
bool HasArbiterStateMarker(void* p) { return static_cast<Intent*>(p)->marked; }
void ObserveArbiterStateIntent(void* p) { assert(static_cast<Intent*>(p)->alive); }
bool TryCallOriginalBroadcastReceiverOnReceive(void*, void*, void* p) {
    if (!original_ready) return false;
    ++originals;
    if (p) { auto* i = static_cast<Intent*>(p); assert(i->alive); i->alive = false; i->nonce = 0; i->uid = -1; }
    return true;
}
uint32_t g_arbiter_state_marked_count, g_arbiter_state_passthrough_count, g_dart_state_publish_pending;
constexpr uint32_t kDartAllStatePending = 7;
void EnsureDartStatePublisherThread() {}
void PublishXiaoAiStateForCurrentGeneration() {}
bool HandleRuntimeStatusQuery(int64_t nonce) {
    assert(current && !current->alive && originals > 0);
    ++replies; replied_nonce = nonce; return true;
}
'''
tests = r'''
void dispatch(Intent& intent) { current = &intent; HookBroadcastReceiverOnReceive(nullptr, nullptr, &intent); }
int main() {
    Intent normal; normal.present = false; dispatch(normal);
    assert(warnings == 0 && originals == 1 && replies == 0);
    Intent disabled; disabled.query = false; dispatch(disabled);
    assert(warnings == 0 && originals == 2 && replies == 0);
    Intent valid; dispatch(valid);
    assert(originals == 3 && replies == 1 && replied_nonce == 77 && valid.nonce == 0);
    Intent foreign; foreign.sender = "untrusted"; dispatch(foreign);
    assert(warnings == 1 && replies == 1 && originals == 4);
    Intent uid; uid.uid = 42; dispatch(uid);
    assert(warnings == 2 && replies == 1);
    Intent nonce; nonce.nonce = 0; dispatch(nonce);
    assert(warnings == 3 && replies == 1);
    Intent missing; missing.nonce_present = false; dispatch(missing);
    assert(warnings == 4 && replies == 1);
    Intent malformed; malformed.valid_bool = false; dispatch(malformed);
    assert(warnings == 4 && replies == 1);
    sender_api = false; Intent no_sender; dispatch(no_sender);
    assert(warnings == 5 && replies == 1); sender_api = true;
    original_ready = false; Intent retired; dispatch(retired);
    assert(retired.alive && replies == 1); original_ready = true;
    owner = false; int before_reads = reads; Intent wrong_owner; dispatch(wrong_owner);
    assert(reads == before_reads && replies == 1); owner = true;
    Intent unrelated; unrelated.arbiter = false; before_reads = reads; dispatch(unrelated);
    assert(reads == before_reads && replies == 1);
    extras_api = false; Intent no_extras; dispatch(no_extras);
    assert(replies == 1); extras_api = true;
    HookBroadcastReceiverOnReceive(nullptr, nullptr, nullptr);
}
'''
with tempfile.TemporaryDirectory(prefix="hypertweak-status-query-") as directory:
    path = Path(directory)
    (path / "test.cpp").write_text(preamble + capture + receiver + tests)
    subprocess.run(["clang++", "-std=c++17", "-Wall", "-Wextra", "-Werror",
                    "-fsanitize=address,undefined", str(path / "test.cpp"), "-o", str(path / "test")], check=True)
    subprocess.run([str(path / "test")], check=True)
print("native status query admission, authentication and borrowed Intent lifetime passed")
