// SPDX-License-Identifier: Apache-2.0
#include "rules_entry.h"

#include "clear_button_rule.h"
#include "hook_bridge.h"
#include "image.h"
#include "logging.h"
#include "native_config.h"
#include "page_guard.h"

#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

namespace hypertweak::native {
namespace {

constexpr char kLauncherProcessName[] = "com.miui.home";
// Key the module process writes into the shared config file.
constexpr char kHideClearButtonKey[] = "hide_recents_clear";
constexpr uint32_t kConfigPollMillis = 2000u;
constexpr uint32_t kMaxInstallAttempts = 40u;
constexpr uint32_t kInstallIntervalMillis = 500u;

// Set by StartInstaller once the process gate has run, so CurrentStage can
// distinguish "not the launcher" from "still waiting".
volatile uint32_t g_installer_started = 0u;
volatile int32_t g_stage = static_cast<int32_t>(Stage::kIdle);
volatile uint32_t g_library_load_count = 0u;
volatile uint32_t g_install_attempts = 0u;
volatile uint32_t g_hook_api_ready = 0u;
volatile uint32_t g_config_poll_started = 0u;

void SetStage(Stage stage) {
    __atomic_store_n(&g_stage, static_cast<int32_t>(stage), __ATOMIC_RELEASE);
}

void SleepMillis(uint32_t millis) {
    struct timespec request {};
    request.tv_sec = static_cast<time_t>(millis / 1000u);
    request.tv_nsec = static_cast<long>(millis % 1000u) * 1000000L;
    while (nanosleep(&request, &request) != 0 && errno == EINTR) {
    }
}

bool IsLauncherProcess() {
    const int fd = open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    char command_line[64];
    ssize_t result;
    do {
        result = read(fd, command_line, sizeof(command_line));
    } while (result < 0 && errno == EINTR);
    close(fd);
    if (result <= 0) return false;
    constexpr size_t expected = sizeof(kLauncherProcessName) - 1u;
    if (static_cast<size_t>(result) <= expected) return false;
    // The launcher's child processes share the prefix (`com.miui.home:remote`),
    // so the name must terminate exactly at the package name.
    if (command_line[expected] != '\0') return false;
    return memcmp(command_line, kLauncherProcessName, expected) == 0;
}

// The module's Java never runs in the launcher process, so the switch reaches
// this payload as a file the module writes. Polling it is what makes the toggle
// take effect without restarting the launcher.
void* ConfigPollThread(void*) {
    bool current = ClearButtonHiddenRequested();
    const char* logged_reason = nullptr;
    for (;;) {
        const char* channel = ConfigChannelPath();
        if (channel == nullptr || strcmp(channel, "unprobed") == 0 ||
                strcmp(channel, "none_readable") == 0) {
            // The launcher can start before the module UI has ever published its
            // first config file. Retry discovery so the first toggle is not lost.
            ProbeConfigChannel();
        }
        const bool desired = ReadConfigFlag(kHideClearButtonKey, current);
        if (desired != current) {
            LogInfo("clear button rule changed by module config: hidden=%d", desired ? 1 : 0);
            SetClearButtonHidden(desired);
            current = desired;
        }
        ApplyClearButtonRule();
        // Report each distinct outcome once. Without this a rule that can never
        // resolve is indistinguishable from one that is simply idle.
        const char* reason = ClearButtonRuleReason();
        if (reason != logged_reason) {
            LogInfo("clear button rule state: %s", reason);
            logged_reason = reason;
        }
        SleepMillis(kConfigPollMillis);
    }
}

void* InstallerThread(void*) {
    // Establish, once, which shared file the launcher process can actually read.
    ProbeConfigChannel();
    for (uint32_t attempt = 1u; attempt <= kMaxInstallAttempts; ++attempt) {
        __atomic_store_n(&g_install_attempts, attempt, __ATOMIC_RELEASE);
        // The guard hooks madvise in the Flutter runtime, which is mapped before
        // the launcher's own libraries; nothing may be patched until it is in
        // place. Its failure is terminal rather than something to retry.
        if (!EnsurePageGuard()) {
            if (PageGuardFailed()) {
                LogError("page guard is unavailable; the payload will not patch");
                return nullptr;
            }
            SleepMillis(kInstallIntervalMillis);
            continue;
        }
        // The rule resolves its own target; doing it here as well just lets the
        // first attempt land as early as possible.
        ApplyClearButtonRule();
        SetStage(Stage::kReady);
        LogInfo("native rules ready after %u attempt(s)", attempt);
        return nullptr;
    }
    LogWarn("gave up after %u attempts; the page guard never became available",
            kMaxInstallAttempts);
    return nullptr;
}

}  // namespace

void StartInstaller() {
    if (__atomic_exchange_n(&g_installer_started, uint32_t{1}, __ATOMIC_ACQ_REL) != 0u) {
        return;
    }
    __atomic_store_n(&g_hook_api_ready, HookApiReady() ? uint32_t{1} : uint32_t{0},
                     __ATOMIC_RELEASE);
    if (!HookApiReady()) {
        LogError("the hook API is not available; not starting the installer");
        return;
    }
    if (!IsLauncherProcess()) {
        SetStage(Stage::kWrongProcess);
        return;
    }
    SetStage(Stage::kWaitingForLauncherImage);
    pthread_t thread;
    // A detached thread keeps the payload off the launcher's startup path; the
    // retry loop outlives native_init by design.
    if (pthread_create(&thread, nullptr, InstallerThread, nullptr) != 0) {
        LogError("failed to start the installer thread");
        return;
    }
    pthread_detach(thread);
    if (__atomic_exchange_n(&g_config_poll_started, uint32_t{1}, __ATOMIC_ACQ_REL) == 0u &&
        pthread_create(&thread, nullptr, ConfigPollThread, nullptr) == 0) {
        pthread_detach(thread);
    }
}

void OnLibraryLoaded(const char* name, void* handle) {
    (void)name;
    (void)handle;
    // LSPosed calls this for every library the process loads. Logging here
    // produced ~80 lines of noise per launcher start, so it stays silent and
    // only counts.
    __atomic_fetch_add(&g_library_load_count, uint32_t{1}, __ATOMIC_RELAXED);
}

Stage CurrentStage() {
    return static_cast<Stage>(__atomic_load_n(&g_stage, __ATOMIC_ACQUIRE));
}

size_t FormatStatus(char* buffer, size_t size) {
    if (buffer == nullptr || size == 0u) return 0u;
    const Stage stage = CurrentStage();
    const char* stage_name = "idle";
    switch (stage) {
        case Stage::kIdle: stage_name = "idle"; break;
        case Stage::kWrongProcess: stage_name = "wrong_process"; break;
        case Stage::kWaitingForLauncherImage: stage_name = "waiting_launcher"; break;
        case Stage::kReady: stage_name = "ready"; break;
    }
    const int written = snprintf(
        buffer, size,
        "stage=%s hookApi=%u pageGuard=%u attempts=%u loads=%u clearButton=%s/%s "
        "target=0x%zx hits=%u config=%s",
        stage_name, __atomic_load_n(&g_hook_api_ready, __ATOMIC_RELAXED),
        PageGuardReady() ? 1u : 0u,
        __atomic_load_n(&g_install_attempts, __ATOMIC_RELAXED),
        __atomic_load_n(&g_library_load_count, __ATOMIC_RELAXED),
        ClearButtonHiddenRequested() ? "hide" : "keep", ClearButtonRuleReason(),
        static_cast<size_t>(ClearButtonTargetAddress()), ClearButtonHookHits(),
        ConfigChannelPath());
    if (written < 0) {
        buffer[0] = '\0';
        return 0u;
    }
    buffer[size - 1u] = '\0';
    return strnlen(buffer, size - 1u);
}

}  // namespace hypertweak::native
