#include "dock_frame_rule.h"
#include "dart_rule_support.h"
#include "lsposed_hook_backend.h"
#include "logging.h"
#include "native_rule_runtime.h"
#include <sys/socket.h>
#include <netinet/in.h>
#include <unistd.h>
#include <pthread.h>
#include <string.h>
#include <stdlib.h>
#include <fcntl.h>
extern "C" {
__attribute__((visibility("hidden"))) volatile int g_dock_frame_fd = -1;
__attribute__((visibility("hidden"))) volatile uint32_t g_dock_frame_enabled = 0;
__attribute__((visibility("hidden"))) volatile uint64_t g_dock_frame_tokens[2]{};
__attribute__((visibility("hidden"))) volatile uint64_t g_dock_frame_sequence = 0;
__attribute__((visibility("hidden"))) int64_t g_dock_frame_offsets[4]{};
__attribute__((visibility("hidden"))) uint64_t g_dock_frame_pool = 0;
void HyperTweakDockVisibleReturn();
void HyperTweakDockHiddenReturn();
void HyperTweakDockControllerScaleReturn();
void HyperTweakDockControllerAlphaReturn();
void HyperTweakDockControllerSetReturn();
void HyperTweakDockEditingReturn();
__attribute__((visibility("hidden"))) uint64_t g_dock_edit_disabled_pool = 0;
__attribute__((visibility("hidden"))) uint64_t g_dock_frame_double_cid = 0;
__attribute__((visibility("hidden"))) uint32_t g_dock_frame_source_epoch = 0;
__attribute__((visibility("hidden"))) int64_t g_dock_tick_layout[4]{};
void HyperTweakDockLocalTickReturn();
void HyperTweakDockUnlockReturn();
__attribute__((visibility("hidden"))) uint64_t g_dock_frame_samples[9][7]{};
}
namespace hypertweak::native {
namespace {
constexpr size_t kObserverCount = 9;
struct Observer {
    void* address = nullptr;
    uint8_t original[16]{};
    uint8_t patch[16]{};
    bool installed = false;
};
struct ObserverBank {
    const uint8_t* base = nullptr;
    Observer observers[kObserverCount]{};
    bool complete = false;
};
struct Endpoint {
    int32_t port = 0;
    uint64_t token0 = 0;
    uint64_t token1 = 0;
    bool replay = false;
};
pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
ObserverBank bank;
Endpoint endpoint;
volatile uint32_t requested = 0;
int CreateChannelSocket() {
    const int fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0) return -1;
    if (fcntl(fd, F_SETFD, FD_CLOEXEC) != 0 || fcntl(fd, F_SETFL, O_NONBLOCK) != 0) {
        close(fd);
        return -1;
    }
    return fd;
}
class Guard {
public:
    Guard() { pthread_mutex_lock(&lock); }
    ~Guard() { pthread_mutex_unlock(&lock); }
};
void ReplaySamples() {
    if (!endpoint.replay || !g_dock_frame_enabled) return;
    endpoint.replay = false;
    for (size_t kind = 0; kind < kObserverCount; ++kind) {
        uint64_t packet[9]{};
        bool copied = false;
        for (unsigned retry = 0; retry < 3; ++retry) {
            const auto before = __atomic_load_n(&g_dock_frame_samples[kind][0], __ATOMIC_ACQUIRE);
            if (before == 0 || (before & 1)) continue;
            for (size_t field = 0; field < 5; ++field)
                packet[4 + field] = __atomic_load_n(&g_dock_frame_samples[kind][1 + field], __ATOMIC_RELAXED);
            packet[3] = __atomic_load_n(&g_dock_frame_samples[kind][6], __ATOMIC_RELAXED);
            __atomic_thread_fence(__ATOMIC_ACQUIRE);
            if (before == __atomic_load_n(&g_dock_frame_samples[kind][0], __ATOMIC_ACQUIRE)) {
                copied = true;
                break;
            }
        }
        if (!copied) continue;
        packet[0] = 0x444b | (kind << 16) | (static_cast<uint64_t>(g_dock_frame_source_epoch) << 32);
        packet[1] = endpoint.token0;
        packet[2] = endpoint.token1;
        (void)write(g_dock_frame_fd, packet, sizeof(packet));
    }
}
bool ReleaseOwnedObservers() {
    // A failed installation or remap may leave a partial bank. Never ask the
    // backend to restore a site that another writer changed in the meantime.
    bool clean = true;
    for (auto& observer : bank.observers) {
        if (!observer.installed) continue;
        const bool ours = memcmp(observer.address, observer.patch, 16) == 0;
        const bool restored = memcmp(observer.address, observer.original, 16) == 0;
        if ((!ours && !restored) || RemoveInlineHook(observer.address) != kHookSuccess) {
            clean = false;
            continue;
        }
        observer.installed = false;
    }
    bank.complete = false;
    if (clean) bank = ObserverBank{};
    return clean;
}
bool BuildBank(const DartResolution& resolution, ObserverBank* output,
               dart::DockFrameLayout* row, dart::DockTickLayout* tick, uint32_t* editPool) {
    constexpr const char* names[] = {"hotseat_motion", "controller_scale", "controller_alpha",
        "controller_set", "editing_state", "hotseat_scale_tick", "unlock_transform"};
    for (const auto* name : names) if (!resolution.MatchVerify(name)) return false;
    dart::Image image{};
    if (!dart::ParseImage(resolution.base, &image)) return false;
    const auto base = reinterpret_cast<uintptr_t>(resolution.base);
    if (!dart::DecodeDockFrameLayout(image, resolution.Find(names[0]) - base, row) ||
        !dart::DecodeDockTickLayout(image, resolution.Find(names[5]) - base, tick) ||
        !dart::DecodeDockEditingPool(image, resolution.Find(names[4]) - base, editPool) ||
        !dart::VerifyDockUnlockLayout(image, resolution.Find(names[6]) - base)) return false;
    const uintptr_t offsets[] = {row->visible, row->hidden[0], row->hidden[1],
        resolution.Find(names[1]) - base, resolution.Find(names[2]) - base,
        resolution.Find(names[4]) - base, resolution.Find(names[3]) - base, tick->site, resolution.Find(names[6]) - base};
    ObserverBank planned{};
    planned.base = resolution.base;
    for (size_t i = 0; i < kObserverCount; ++i) {
        if (!dart::Contains(image, offsets[i], 16, dart::kFlagRead | dart::kFlagExec)) return false;
        for (size_t j = 0; j < i; ++j) if (offsets[i] < offsets[j] + 16 && offsets[j] < offsets[i] + 16) return false;
        auto& observer = planned.observers[i];
        observer.address = const_cast<uint8_t*>(resolution.base) + offsets[i];
        memcpy(observer.original, observer.address, 16);
    }
    *output = planned;
    return true;
}
void PublishLayouts(const dart::DockFrameLayout& row, const dart::DockTickLayout& tick, uint32_t editPool) {
    g_dock_frame_offsets[0] = row.alpha;
    g_dock_frame_offsets[1] = row.offset;
    g_dock_frame_offsets[2] = row.scale;
    g_dock_frame_offsets[3] = row.origin;
    g_dock_frame_pool = row.pool;
    g_dock_frame_double_cid = row.double_cid;
    g_dock_edit_disabled_pool = editPool;
    g_dock_tick_layout[0] = tick.receiver;
    g_dock_tick_layout[1] = tick.scale;
    g_dock_tick_layout[2] = tick.y;
    g_dock_tick_layout[3] = tick.value;
}
}
bool DockFrameRequested() { return __atomic_load_n(&requested, __ATOMIC_ACQUIRE) != 0; }
bool ConfigureDockFrame(int32_t port, int64_t token0, int64_t token1) {
    if (port < 0 || port > 65535 || (port != 0 && (token0 == 0 || token1 == 0))) return false;
    bool changed = false;
    {
        Guard guard;
        if (g_dock_frame_source_epoch == 0) {
            const uint32_t epoch = arc4random();
            __atomic_store_n(&g_dock_frame_source_epoch, epoch == 0 ? 1u : epoch, __ATOMIC_RELEASE);
        }
        changed = port != endpoint.port || static_cast<uint64_t>(token0) != endpoint.token0 ||
            static_cast<uint64_t>(token1) != endpoint.token1;
        if (!changed) return true;
        __atomic_store_n(&g_dock_frame_enabled, 0u, __ATOMIC_RELEASE);
        if (port != 0) {
            if (g_dock_frame_fd < 0) g_dock_frame_fd = CreateChannelSocket();
            sockaddr_in address{};
            address.sin_family = AF_INET;
            address.sin_port = htons(static_cast<uint16_t>(port));
            address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
            if (g_dock_frame_fd < 0 || connect(g_dock_frame_fd, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0) return false;
        }
        endpoint = Endpoint{port, static_cast<uint64_t>(token0), static_cast<uint64_t>(token1), port != 0};
        __atomic_store_n(&g_dock_frame_tokens[0], endpoint.token0, __ATOMIC_RELEASE);
        __atomic_store_n(&g_dock_frame_tokens[1], endpoint.token1, __ATOMIC_RELEASE);
        __atomic_store_n(&requested, port != 0 ? 1u : 0u, __ATOMIC_RELEASE);
    }
    NotifyDockFramePreparation();
    return true;
}
bool ApplyDockFrameRule(void* handle) {
    if (!DockFrameRequested()) return true;
    Guard guard;
    DartResolution resolution{};
    if (!ResolveDartSites(handle, dart::kDockFrameTarget, &resolution)) return false;
    if (bank.base != nullptr) {
        if (bank.base != resolution.base) {
            __atomic_store_n(&g_dock_frame_enabled, 0u, __ATOMIC_RELEASE);
            return false; // A different mapping cannot retire the previous owner's memory.
        }
        bool owned = bank.complete;
        for (const auto& observer : bank.observers)
            owned = owned && observer.installed && memcmp(observer.address, observer.patch, 16) == 0;
        if (owned) {
            __atomic_store_n(&g_dock_frame_enabled, DockFrameRequested() ? 1u : 0u, __ATOMIC_RELEASE);
            ReplaySamples();
            return true;
        }
        __atomic_store_n(&g_dock_frame_enabled, 0u, __ATOMIC_RELEASE);
        if (!ReleaseOwnedObservers()) return false;
    }
    ObserverBank planned{};
    dart::DockFrameLayout row{};
    dart::DockTickLayout tick{};
    uint32_t editPool = 0;
    if (!BuildBank(resolution, &planned, &row, &tick, &editPool)) return false;
    PublishLayouts(row, tick, editPool);
    bank = planned;
    void (*const replacements[])() = {HyperTweakDockVisibleReturn, HyperTweakDockHiddenReturn,
        HyperTweakDockHiddenReturn, HyperTweakDockControllerScaleReturn, HyperTweakDockControllerAlphaReturn,
        HyperTweakDockEditingReturn, HyperTweakDockControllerSetReturn, HyperTweakDockLocalTickReturn, HyperTweakDockUnlockReturn};
    for (size_t i = 0; i < kObserverCount; ++i) {
        auto& observer = bank.observers[i];
        void* trampoline = nullptr;
        if (memcmp(observer.address, observer.original, 16) != 0 ||
            InstallInlineHook(observer.address, reinterpret_cast<void*>(replacements[i]), &trampoline) != kHookSuccess) {
            (void)ReleaseOwnedObservers();
            return false;
        }
        memcpy(observer.patch, observer.address, 16);
        observer.installed = true;
        if (trampoline == nullptr) {
            (void)ReleaseOwnedObservers();
            return false;
        }
    }
    bank.complete = true;
    __atomic_store_n(&g_dock_frame_enabled, DockFrameRequested() ? 1u : 0u, __ATOMIC_RELEASE);
    ReplaySamples();
    LogInfo("Dock return observer bank active: row, root pose, editing, local tick and unlock");
    return true;
}
void ResetDockFrameAfterFork() {
    if (g_dock_frame_fd >= 0) close(g_dock_frame_fd);
    g_dock_frame_fd = -1;
    g_dock_frame_enabled = 0;
    requested = 0;
    endpoint = Endpoint{};
    g_dock_frame_tokens[0] = g_dock_frame_tokens[1] = 0;
    g_dock_frame_sequence = 0;
    g_dock_frame_source_epoch = 0;
    memset(g_dock_frame_samples, 0, sizeof(g_dock_frame_samples));
    pthread_mutex_t fresh = PTHREAD_MUTEX_INITIALIZER;
    lock = fresh;
}
}
