// SPDX-License-Identifier: Apache-2.0
// Executes the actual Bionic worker with a simulated LSPosed dlopen callback.
#include "native_rule_runtime.h"
#include "native_rule_events.h"
#include <atomic>
#include <assert.h>
#include <stdio.h>
#include <unistd.h>
#include <string.h>
namespace {
std::atomic<bool> hidden{false};
std::atomic<int32_t> columns{3};
std::atomic<unsigned> queries{0}, preparations{0}, saves{0};
std::atomic<bool> block{false}, entered{false};
void Wait(std::atomic<unsigned>& value, unsigned expected) {
    for (unsigned i = 0u; i < 200u && value.load() < expected; ++i) usleep(10000);
    assert(value.load() >= expected);
}
}
extern "C" bool HyperTweakIsLauncherProcess() { return true; }
extern "C" void HyperTweakSetContextualSearchLongPress(bool) {}
extern "C" void* __wrap_dlopen(const char*, int) {
    ++queries;
    assert(hypertweak::native::NativeRuleLibraryQueryActive());
    // Exactly the synchronous re-entry that produced the reported busy loop.
    hypertweak::native::ObserveNativeRuleDartLibrary(reinterpret_cast<void*>(1));
    hypertweak::native::RequestNativeRulePreparation();
    return reinterpret_cast<void*>(1);
}
extern "C" int __wrap_dlclose(void*) { return 0; }
namespace hypertweak::native {
void LogInfo(const char*, ...) {}
void LogWarn(const char*, ...) {}
void SetClearButtonHidden(bool value) { hidden.store(value); }
void SetFolderColumns(int32_t value) { columns.store(value); }
bool ClearButtonHiddenRequested() { return hidden.load(); }
int32_t FolderColumnsRequested() { return columns.load(); }
bool ApplyClearButtonRule(void*) { return true; }
bool ApplyFolderColumnsRule(void*) { return true; }
const char* ClearButtonRuleReason() { return "installed"; }
const char* FolderColumnsRuleReason() { return "installed"; }
void InvalidateFailedDartRuleTargets() {}
void ResetDartRulePreparationAfterFork() {}
void LockDartRulePreparationForFork() {}
void UnlockDartRulePreparationAfterFork() {}
bool PrepareDartRuleTargets(void*, bool, bool) {
    ++preparations;
    entered.store(true);
    while (block.load()) usleep(1000);
    return true;
}
bool ReadNativeRecord(const char*, void*, size_t) { return false; }
bool WriteNativeRecord(const char*, const void*, size_t) { ++saves; return true; }
}
int main() {
    using namespace hypertweak::native;
    assert(ReceiveNativeRuleSettings(true, 4, false, 100));
    Wait(preparations, 1);
    usleep(200000);
    for (unsigned i = 0u; i < 10000u; ++i) RequestNativeRulePreparation();
    usleep(200000);
    assert(queries.load() == 1u && preparations.load() == 1u && saves.load() == 1u);
    assert(!ReceiveNativeRuleSettings(false, 3, false, 99));
    assert(!ReceiveNativeRuleSettings(false, 3, false, 100));
    assert(ReceiveNativeRuleSettings(true, 4, false, 100));
    usleep(100000);
    assert(preparations.load() == 1u && hidden.load() && columns.load() == 4);

    block.store(true); entered.store(false);
    assert(ReceiveNativeRuleSettings(true, 5, false, 101));
    Wait(preparations, 2);
    assert(entered.load());
    assert(ReceiveNativeRuleSettings(true, 4, false, 102));
    block.store(false);
    Wait(preparations, 3);
    usleep(200000);
    assert(preparations.load() == 3u && queries.load() == 1u && columns.load() == 4);
    for (unsigned i = 0u; i < 10000u; ++i) ApplyPreparedNativeRules();
    usleep(200000);
    assert(preparations.load() == 3u && queries.load() == 1u);
    puts("PASS: actual worker sleeps after self-callback, ready inputs stay idle, stale/conflicting revisions rejected, concurrent update drains once");
}
