// SPDX-License-Identifier: Apache-2.0
#include "native_rule_runtime.h"
#include "native_rule_settings_record.h"
#include "dock_frame_rule.h"
#include "recents_haptic_rule.h"
#include "folder_stack_rule.h"
#include "widget_stack_rule.h"
#include "xiaoai_gesture_rule.h"
#include "icon_scale_rule.h"
#include "icon_scale_policy.h"
#include "native_rule_events.h"
#include "clear_button_rule.h"
#include "folder_columns_rule.h"
#include "assistant_widget_rule.h"
#include "dart_rule_support.h"
#include "native_store.h"
#include "native_runtime_identity.h"
#include "logging.h"
#include <dlfcn.h>
#include <errno.h>
#include <pthread.h>
#include <poll.h>
#include <sys/eventfd.h>
#include <time.h>
#include <string.h>
#include <unistd.h>
#include <stdio.h>
#include <stdlib.h>

extern "C" void HyperTweakSetContextualSearchLongPress(bool enabled);
extern "C" bool HyperTweakIsLauncherProcess();
extern "C" bool HyperTweakNotifyNativeRulePrepared();
namespace hypertweak::native {
namespace {
using SettingsRecord = NativeRuleSettingsRecord;
uint64_t Now() {
    timespec time{};
    clock_gettime(CLOCK_MONOTONIC, &time);
    return static_cast<uint64_t>(time.tv_sec) * 1000000000ull + time.tv_nsec;
}
pthread_mutex_t g_settings_lock = PTHREAD_MUTEX_INITIALIZER;
SettingsRecord g_settings{};
NativeRulePreparationGate g_gate{};
bool g_received_settings = false;
bool g_settings_dirty = false;
bool g_bootstrap_read = false;
int64_t g_systemui_revision = 0;
void* g_dart_handle = nullptr;
uint32_t g_worker_state = 0u;
int g_wakeup = -1;
const char* volatile g_source = "waiting_for_systemui";
bool g_runtime_initialized = false;
uint32_t g_identity_pid = 0u;

void PublishRuntimeIdentity() {
#ifdef HYPERTWEAK_NATIVE_VERSION
    if (!HyperTweakIsLauncherProcess() || getuid() < 10000u) return;
    pthread_mutex_lock(&g_settings_lock);
    if (g_runtime_initialized && g_identity_pid != static_cast<uint32_t>(getpid())) {
        char stat[2048]{};
        FILE* source = fopen("/proc/self/stat", "re");
        if (source != nullptr) { (void)fgets(stat, sizeof(stat), source); fclose(source); }
        char* field = strrchr(stat, ')');
        if (field != nullptr) {
            ++field;
            for (unsigned index = 3u; index < 22u; ++index) {
                while (*field == ' ') ++field;
                while (*field != '\0' && *field != ' ') ++field;
            }
        }
        const uint64_t start_ticks = field != nullptr ? strtoull(field, nullptr, 10) : 0u;
        const auto identity = RuntimeIdentity(HYPERTWEAK_NATIVE_VERSION, getpid(), start_ticks);
        if (start_ticks != 0u && WriteNativeRecord("runtime_identity", &identity, sizeof(identity))) {
            g_identity_pid = getpid();
        } else LogWarn("native runtime identity persistence failed");
    }
    pthread_mutex_unlock(&g_settings_lock);
#endif
}

void ApplySettings(const SettingsRecord& record) {
    SetClearButtonHidden(record.hidden != 0u);
    SetFolderColumns(record.columns);
    HyperTweakSetContextualSearchLongPress((record.options & 1u) != 0u);
    SetAssistantWidgetAllowed((record.options & 2u) != 0u);
    SetRecentsHapticEnabled((record.options & 4u) != 0u);
    SetFolderStackEnabled((record.options & 8u) != 0u);
    SetAndroidWidgetStackEnabled((record.options & 16u) != 0u);
    SetXiaoaiGestureOptionsEnabled((record.options & 32u) != 0u);
    SetDesktopIconPercent(static_cast<int>(record.options >> 6u)-1);
}
bool Prepare() {
    NativeRuleLibraryQuery internal_query;
    // Specialization may have still been root at native_init. Existing event-driven work can
    // publish the acknowledgement once the real app uid/data directory is available.
    PublishRuntimeIdentity();
    pthread_mutex_lock(&g_settings_lock);
    if (!g_received_settings && !g_bootstrap_read) {
        g_bootstrap_read = true; // A missing file is not a reason to poll storage.
        SettingsRecord record{};
        bool migrated = false;
        if (ReadNativeRecord("settings", &record, sizeof(record)) &&
            RestoreNativeRuleSettings(&record, &migrated)) {
            if (migrated) g_settings_dirty = true;
            g_settings = record;
            ApplySettings(record);
            __atomic_store_n(&g_source, "snapshot_restored", __ATOMIC_RELEASE);
            LogInfo("native rules restored last Preferences snapshot");
        }
    }
    if (g_settings_dirty) {
        const bool saved = WriteNativeRecord("settings", &g_settings, sizeof(g_settings));
        // Persistence failure is reported, but must not create a retry loop.
        g_settings_dirty = false;
        LogInfo("native Preferences snapshot saved=%d", saved ? 1 : 0);
    }
    const bool clear = ClearButtonHiddenRequested();
    const bool folder = FolderColumnsRequested() != 3;
    const bool widgets = AssistantWidgetAllowedRequested();
    void* handle = g_dart_handle;
    pthread_mutex_unlock(&g_settings_lock);
    const bool dock = DockFrameRequested();
    const bool haptic = RecentsHapticRequested();
    const bool stack = FolderStackRequested();
    const bool widget_stack = AndroidWidgetStackRequested();
    const bool xiaoai_options = XiaoaiGestureOptionsRequested();
    const bool icon_scale = DesktopIconScaleNeedsPreparation();
    if (!clear && !folder && !widgets && !dock && !haptic && !stack && !widget_stack && !xiaoai_options && !icon_scale) return true;
    if (handle == nullptr) {
        // Fallback for an AOT image loaded before our observer. Keep this one
        // reference for the process lifetime; ready inputs never repeat dlopen.
        handle = dlopen("libapp.so", RTLD_NOW | RTLD_NOLOAD);
        if (handle == nullptr) return false;
        pthread_mutex_lock(&g_settings_lock);
        if (g_dart_handle == nullptr) g_dart_handle = handle;
        else { dlclose(handle); handle = g_dart_handle; }
        pthread_mutex_unlock(&g_settings_lock);
    }
    return PrepareDartRuleTargets(handle, clear, folder, widgets, dock, haptic, stack, widget_stack, xiaoai_options, icon_scale);
}
void* Run(void*) {
    (void)pthread_setname_np(pthread_self(), "HT-RulePrepare");
    const int fd = __atomic_load_n(&g_wakeup, __ATOMIC_ACQUIRE);
    for (;;) {
        pollfd wake{fd, POLLIN, 0};
        if (poll(&wake, 1u, -1) < 0) { if (errno == EINTR) continue; break; }
        uint64_t events = 0u;
        const ssize_t count = read(fd, &events, sizeof(events));
        if (count < 0 && (errno == EINTR || errno == EAGAIN)) continue;
        if (count != sizeof(events)) break;
        pthread_mutex_lock(&g_settings_lock);
        g_gate.Begin();
        pthread_mutex_unlock(&g_settings_lock);
        const bool success = Prepare();
        pthread_mutex_lock(&g_settings_lock);
        const bool notify = success && g_gate.generation == g_gate.running_generation;
        g_gate.Complete(Now(), success);
        pthread_mutex_unlock(&g_settings_lock);
        // One completion acknowledgement per preparation, outside the internal loader-query
        // scope. SystemUI replays its latest ACK on the existing receiver boundary so first
        // activation does not depend on another touch, arbitrary delay or host restart.
        // The broadcast-options arm is process-wide; the existing Dart-state publisher can
        // own it for a few microseconds. Retry finitely instead of dropping the only wake-up.
        if (notify) {
            for (unsigned attempt = 0u; attempt < 3u; ++attempt) {
                if (HyperTweakNotifyNativeRulePrepared()) break;
                usleep(20000u);
            }
        }
        // Only a newer external generation can immediately queue again.
        RequestNativeRulePreparation();
    }
    __atomic_store_n(&g_wakeup, -1, __ATOMIC_RELEASE);
    close(fd);
    __atomic_store_n(&g_worker_state, 0u, __ATOMIC_RELEASE);
    return nullptr;
}
}
const char* NativeRuleRuntimeSource() { return __atomic_load_n(&g_source, __ATOMIC_ACQUIRE); }
void MarkNativeRuntimeInitialized() {
    pthread_mutex_lock(&g_settings_lock);
    g_runtime_initialized = true;
    pthread_mutex_unlock(&g_settings_lock);
    PublishRuntimeIdentity();
}
void* NativeRuleCurrentDartHandle() {
    pthread_mutex_lock(&g_settings_lock);
    void* handle = g_dart_handle;
    pthread_mutex_unlock(&g_settings_lock);
    return handle;
}
void ObserveNativeRuleDartLibrary(void* handle) {
    if (handle == nullptr || NativeRuleLibraryQueryActive()) return;
    pthread_mutex_lock(&g_settings_lock);
    const bool changed = g_dart_handle != handle;
    if (changed) { g_dart_handle = handle; g_gate.Changed(); }
    pthread_mutex_unlock(&g_settings_lock);
    if (changed) { InvalidateFailedDartRuleTargets(); RequestNativeRulePreparation(); }
}
bool ReceiveNativeRuleSettings(bool hidden, int32_t columns, bool contextual_search, int64_t revision, bool assistant_widgets, bool recents_haptic, bool folder_stack, bool widget_stack, bool xiaoai_options, int32_t icon_percent) {
    if (!HyperTweakIsLauncherProcess() || columns < 3 || columns > 5 || revision < 0 || !ValidIconPercent(icon_percent)) return false;
    pthread_mutex_lock(&g_settings_lock);
    // JNI remains a fallback. The authenticated SystemUI publisher owns live
    // state once available; a stale launcher Java instance cannot overwrite it.
    if ((revision == 0 && g_systemui_revision > 0) || revision < g_systemui_revision) {
        pthread_mutex_unlock(&g_settings_lock);
        return false;
    }
    SettingsRecord next{kNativeRuleSettingsSchema, hidden ? 1u : 0u, columns,
        (contextual_search ? 1u : 0u) | (assistant_widgets ? 2u : 0u) | (recents_haptic ? 4u : 0u) | (folder_stack ? 8u : 0u) | (widget_stack ? 16u : 0u) | (xiaoai_options ? 32u : 0u) | (static_cast<uint32_t>(icon_percent+1) << 6u), 0u};
    next.checksum = NativeRuleSettingsChecksum(next);
    const bool changed = !g_received_settings || next.hidden != g_settings.hidden ||
                         next.columns != g_settings.columns || next.options != g_settings.options;
    if (revision > 0 && revision == g_systemui_revision && changed) {
        pthread_mutex_unlock(&g_settings_lock);
        return false;
    }
    g_systemui_revision = revision;
    g_received_settings = true;
    g_settings = next;
    __atomic_store_n(&g_source, revision > 0 ? "systemui_live" : "preferences_jni", __ATOMIC_RELEASE);
    if (changed) {
        g_settings_dirty = true;
        ApplySettings(next);
        g_gate.Changed();
        LogInfo("native settings received revision=%lld hidden=%u columns=%d widgets=%u source=%s",
                static_cast<long long>(revision), next.hidden, next.columns, (next.options >> 1u) & 1u,
                revision > 0 ? "SystemUI" : "JNI");
    }
    pthread_mutex_unlock(&g_settings_lock);
    if (changed) { InvalidateFailedDartRuleTargets(); RequestNativeRulePreparation(); }
    return true;
}
void UpdateNativeRuleSettings(bool hidden, int32_t columns, bool contextual_search, bool assistant_widgets, bool recents_haptic, bool folder_stack, bool widget_stack, bool xiaoai_options, int32_t icon_percent) {
    (void)ReceiveNativeRuleSettings(hidden, columns >= 3 && columns <= 5 ? columns : 3, contextual_search, 0, assistant_widgets, recents_haptic, folder_stack, widget_stack, xiaoai_options, icon_percent);
}
void ApplyPreparedNativeRules() {
    if (!HyperTweakIsLauncherProcess() || NativeRuleLibraryQueryActive()) return;
    void* handle = NativeRuleCurrentDartHandle();
    ApplyClearButtonRule(handle);
    ApplyFolderColumnsRule(handle);
    ApplyAssistantWidgetRule(handle);
    (void)ApplyDockFrameRule(handle);
    (void)ApplyRecentsHapticRule(handle);
    (void)ApplyFolderStackRule(handle);
    (void)ApplyAndroidWidgetStackRule(handle);
    (void)ApplyXiaoaiGestureOptionsRule(handle);
    (void)ApplyDesktopIconScaleRule(handle);
    const bool missing = strcmp(ClearButtonRuleReason(), "dart_preparation_pending") == 0 ||
                         strcmp(FolderColumnsRuleReason(), "dart_preparation_pending") == 0 ||
                         strcmp(AssistantWidgetRuleReason(), "dart_preparation_pending") == 0 ||
                         strcmp(RecentsHapticRuleReason(), "dart_preparation_pending") == 0 ||
                         strcmp(FolderStackRuleReason(), "dart_preparation_pending") == 0 ||
                         strcmp(AndroidWidgetStackRuleReason(), "dart_preparation_pending") == 0 ||
                         strcmp(XiaoaiGestureOptionsReason(), "dart_preparation_pending") == 0 ||
                         strcmp(DesktopIconScaleReason(), "dart_preparation_pending") == 0;
    if (missing) {
        pthread_mutex_lock(&g_settings_lock);
        if (!g_gate.needed) g_gate.Changed(); // Same handle, new image identity.
        pthread_mutex_unlock(&g_settings_lock);
    }
    RequestNativeRulePreparation();
}
void RequestNativeRulePreparation() {
    if (NativeRuleLibraryQueryActive() || !HyperTweakIsLauncherProcess()) return;
    pthread_mutex_lock(&g_settings_lock);
    const bool queue = g_gate.Queue(Now());
    pthread_mutex_unlock(&g_settings_lock);
    if (!queue) return;
    if (__atomic_load_n(&g_worker_state, __ATOMIC_ACQUIRE) == 0u) {
        const int fd = eventfd(0u, EFD_CLOEXEC | EFD_NONBLOCK);
        pthread_t thread{};
        __atomic_store_n(&g_wakeup, fd, __ATOMIC_RELEASE);
        if (fd < 0 || pthread_create(&thread, nullptr, Run, nullptr) != 0) {
            if (fd >= 0) close(fd);
            __atomic_store_n(&g_wakeup, -1, __ATOMIC_RELEASE);
            pthread_mutex_lock(&g_settings_lock);
            g_gate.queued = false;
            pthread_mutex_unlock(&g_settings_lock);
            return;
        }
        pthread_detach(thread);
        __atomic_store_n(&g_worker_state, 1u, __ATOMIC_RELEASE);
    }
    const uint64_t event = 1u;
    const int fd = __atomic_load_n(&g_wakeup, __ATOMIC_ACQUIRE);
    if (fd >= 0) (void)write(fd, &event, sizeof(event));
}
void NotifyDockFramePreparation() {
    pthread_mutex_lock(&g_settings_lock); g_gate.Changed(); pthread_mutex_unlock(&g_settings_lock);
    InvalidateFailedDartRuleTargets(); RequestNativeRulePreparation();
}
void ResetNativeRuleRuntimeAfterFork() {
    ResetDockFrameAfterFork();
    ResetRecentsHapticAfterFork();
    ResetFolderStackAfterFork();
    ResetAndroidWidgetStackAfterFork();
    ResetXiaoaiGestureOptionsAfterFork();
    ResetDesktopIconScaleAfterFork();
    if (g_wakeup >= 0) close(g_wakeup);
    g_wakeup = -1;
    g_worker_state = 0u;
    pthread_mutex_t fresh = PTHREAD_MUTEX_INITIALIZER;
    g_settings_lock = fresh;
    g_bootstrap_read = false;
    g_gate = NativeRulePreparationGate{};
    g_native_rule_query_depth = 0u;
    g_systemui_revision = 0;
    g_runtime_initialized = false;
    g_identity_pid = 0u;
    ResetDartRulePreparationAfterFork();
}
void PrepareNativeRulesForFork() { pthread_mutex_lock(&g_settings_lock); LockDartRulePreparationForFork(); }
void ResumeNativeRulesAfterFork() { UnlockDartRulePreparationAfterFork(); pthread_mutex_unlock(&g_settings_lock); }
}
