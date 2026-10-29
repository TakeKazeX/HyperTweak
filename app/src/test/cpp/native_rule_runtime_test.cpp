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
std::atomic<bool> hidden{false}, recents_haptic{false}, folder_stack{false}, widget_stack{false}, xiaoai_options{false};
std::atomic<int32_t> columns{3}, icon_percent{-1};
std::atomic<unsigned> queries{0}, preparations{0}, saves{0}, ready{0};
std::atomic<int> grid_stage{0};
std::atomic<unsigned> readbacks{0};
std::atomic<bool> block{false}, entered{false};
void Wait(std::atomic<unsigned>& value, unsigned expected) {
    for (unsigned i = 0u; i < 200u && value.load() < expected; ++i) usleep(10000);
    assert(value.load() >= expected);
}
}
extern "C" bool HyperTweakIsLauncherProcess() { return true; }
extern "C" void HyperTweakSetContextualSearchLongPress(bool) {}
extern "C" bool HyperTweakNotifyNativeRulePrepared() {
    assert(!hypertweak::native::NativeRuleLibraryQueryActive());
    ++ready;
    hypertweak::native::ApplyPreparedNativeRules(); // Completion/replay cannot requeue itself.
    return true;
}
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
bool DockFrameRequested() { return false; }
bool ApplyDockFrameRule(void*) { return true; }
void ResetDockFrameAfterFork() {}
void SetAndroidWidgetStackEnabled(bool value) { widget_stack.store(value); }
bool AndroidWidgetStackRequested() { return widget_stack.load(); }
bool ApplyAndroidWidgetStackRule(void*) { return true; }
const char* AndroidWidgetStackRuleReason() { return "disabled"; }
void ResetAndroidWidgetStackAfterFork() {}
void SetDesktopGrid(int) {}
bool DesktopGridNeedsPreparation(){return false;}
bool PrepareDesktopGridTargets(void*){return true;}
bool PrepareDesktopGridLayout(void*){return true;}
void PrepareDesktopGridStorage(){if(grid_stage.load()==5 && ++readbacks==3)grid_stage.store(0);}
int DesktopGridReadbackDelay(){return grid_stage.load()==5?20:-1;}
int DesktopGridStage(){return grid_stage.load();}
bool ApplyDesktopGridRule(void*){return true;}
void ResetDesktopGridAfterFork(){}
void SetDesktopIconPercent(int v) { icon_percent.store(v); }
int DesktopIconPercentRequested() { return icon_percent.load(); }
bool DesktopIconScaleNeedsPreparation() { return icon_percent.load()!=-1; }
bool ApplyDesktopIconScaleRule(void*) { return true; }
const char* DesktopIconScaleReason() { return "disabled"; }
void ResetDesktopIconScaleAfterFork() {}
void SetXiaoaiGestureOptionsEnabled(bool v) { xiaoai_options.store(v); }
bool XiaoaiGestureOptionsRequested() { return xiaoai_options.load(); }
bool ApplyXiaoaiGestureOptionsRule(void*) { return true; }
const char* XiaoaiGestureOptionsReason() { return "disabled"; }
void ResetXiaoaiGestureOptionsAfterFork() {}
void SetFolderStackEnabled(bool value) { folder_stack.store(value); }
bool FolderStackRequested() { return folder_stack.load(); }
bool ApplyFolderStackRule(void*) { return true; }
const char* FolderStackRuleReason() { return "disabled"; }
void ResetFolderStackAfterFork() {}
void SetRecentsHapticEnabled(bool value) { recents_haptic.store(value); }
bool RecentsHapticRequested() { return recents_haptic.load(); }
bool ApplyRecentsHapticRule(void*) { return true; }
const char* RecentsHapticRuleReason() { return "disabled"; }
void ResetRecentsHapticAfterFork() {}
bool ApplyFolderColumnsRule(void*) { return true; }
void SetAssistantWidgetAllowed(bool) {}
bool AssistantWidgetAllowedRequested() { return false; }
bool ApplyAssistantWidgetRule(void*) { return true; }
const char* AssistantWidgetRuleReason() { return "disabled"; }
const char* ClearButtonRuleReason() { return "installed"; }
const char* FolderColumnsRuleReason() { return "installed"; }
void InvalidateFailedDartRuleTargets() {}
void ResetDartRulePreparationAfterFork() {}
void LockDartRulePreparationForFork() {}
void UnlockDartRulePreparationAfterFork() {}
bool PrepareDartRuleTargets(void*, bool, bool, bool, bool, bool, bool, bool, bool, bool) {
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
    Wait(ready, 1);
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
    assert(ReceiveNativeRuleSettings(true, 4, false, 103, false, true));
    Wait(preparations, 4);
    assert(recents_haptic.load());
    assert(!ReceiveNativeRuleSettings(true, 4, false, 103, false, false));
    assert(recents_haptic.load());
    assert(ReceiveNativeRuleSettings(true, 4, false, 104, false, false));
    Wait(preparations, 5);
    assert(!recents_haptic.load());
    Wait(ready, 4);
    usleep(100000);
    assert(ready.load() == 4 && preparations.load() == 5);
    assert(ReceiveNativeRuleSettings(true, 4, false, 105, false, false, true));
    Wait(preparations, 6); assert(folder_stack.load());
    assert(!ReceiveNativeRuleSettings(true, 4, false, 105, false, false, false));
    assert(folder_stack.load());
    assert(ReceiveNativeRuleSettings(true, 4, false, 106, false, false, false));
    Wait(preparations, 7); assert(!folder_stack.load());
    // The widget-stack bit rides the same schema and revision rules: an accepted
    // snapshot enables it, a stale revision is rejected without changing it.
    assert(ReceiveNativeRuleSettings(true, 4, false, 107, false, false, false, true));
    Wait(preparations, 8); assert(widget_stack.load());
    assert(!ReceiveNativeRuleSettings(true, 4, false, 107, false, false, false, false));
    assert(widget_stack.load());
    assert(ReceiveNativeRuleSettings(true, 4, false, 108, false, false, false, false));
    Wait(preparations, 9); assert(!widget_stack.load());
    assert(ReceiveNativeRuleSettings(true, 4, false, 109, false, false, false, false, true));
    Wait(preparations, 10); assert(xiaoai_options.load());
    assert(!ReceiveNativeRuleSettings(true, 4, false, 109, false, false, false, false, false));
    assert(ReceiveNativeRuleSettings(true, 4, false, 110, false, false, false, false, false));
    Wait(preparations, 11); assert(!xiaoai_options.load());
    usleep(200000);
    const auto before_wake=preparations.load(),before_ready=ready.load();
    assert(TryNotifyDesktopGridPreparation());
    Wait(preparations,before_wake+1);Wait(ready,before_ready+1);usleep(200000);
    assert(preparations.load()==before_wake+1);
    for(unsigned i=0;i<10000;++i)ApplyPreparedNativeRules();
    usleep(200000);assert(preparations.load()==before_wake+1);
    const auto poll_preparations=preparations.load(),poll_ready=ready.load();
    grid_stage.store(5);
    assert(TryNotifyDesktopGridPreparation());
    Wait(readbacks,3);Wait(ready,poll_ready+2);
    usleep(200000);
    assert(grid_stage.load()==0 && readbacks.load()==3);
    assert(preparations.load()==poll_preparations+1);
    assert(ready.load()==poll_ready+2);
    puts("PASS: actual worker sleeps after self-callback, ready inputs stay idle, stale/conflicting revisions rejected, concurrent update drains once, recents haptic, folder stack and widget stack enable/conflict/disable; pending migration readback polls finitely without rescans and returns to idle");
}
