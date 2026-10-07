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
__attribute__((visibility("hidden"))) uint64_t g_dock_frame_samples[8][7]{};
}
namespace hypertweak::native {
namespace {
pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
const uint8_t* installed_base = nullptr;
void* targets[8]{};
uint8_t patches[8][16]{};
int32_t current_port = 0;
volatile uint32_t requested = 0;
bool replay_pending = false;
void ReplaySamples() {
    if (!replay_pending || !g_dock_frame_enabled) return;
    replay_pending = false;
    for(size_t kind=0;kind<8;++kind) {
        uint64_t packet[9]{};
        bool copied=false;
        for(unsigned retry=0;retry<3;++retry) {
            const auto before=__atomic_load_n(&g_dock_frame_samples[kind][0],__ATOMIC_ACQUIRE);
            if(before==0 || (before&1))continue;
            for(size_t field=0;field<5;++field)packet[4+field]=__atomic_load_n(&g_dock_frame_samples[kind][1+field],__ATOMIC_RELAXED);
            packet[3]=__atomic_load_n(&g_dock_frame_samples[kind][6],__ATOMIC_RELAXED);
            __atomic_thread_fence(__ATOMIC_ACQUIRE);
            if(before==__atomic_load_n(&g_dock_frame_samples[kind][0],__ATOMIC_ACQUIRE)){copied=true;break;}
        }
        if(!copied)continue;
        packet[0]=0x444b | (kind<<16) | (static_cast<uint64_t>(g_dock_frame_source_epoch)<<32);packet[1]=g_dock_frame_tokens[0];packet[2]=g_dock_frame_tokens[1];
        (void)write(g_dock_frame_fd,packet,sizeof(packet));
    }
}
}
bool DockFrameRequested() { return __atomic_load_n(&requested, __ATOMIC_ACQUIRE) != 0; }
bool ConfigureDockFrame(int32_t port, int64_t token0, int64_t token1) {
    if (port < 0 || port > 65535 || (port != 0 && (token0 == 0 || token1 == 0))) return false;
    pthread_mutex_lock(&lock);
    if (g_dock_frame_source_epoch == 0) {
        const uint32_t epoch = arc4random();
        __atomic_store_n(&g_dock_frame_source_epoch, epoch == 0 ? 1u : epoch, __ATOMIC_RELEASE);
    }
    const bool changed = port != current_port || static_cast<uint64_t>(token0) != g_dock_frame_tokens[0] ||
        static_cast<uint64_t>(token1) != g_dock_frame_tokens[1];
    if (changed) {
        __atomic_store_n(&g_dock_frame_enabled, 0u, __ATOMIC_RELEASE);
        if (port != 0) {
            if (g_dock_frame_fd < 0) g_dock_frame_fd = socket(AF_INET, SOCK_DGRAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
            sockaddr_in address{}; address.sin_family = AF_INET; address.sin_port = htons(static_cast<uint16_t>(port));
            address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
            if (g_dock_frame_fd < 0 || connect(g_dock_frame_fd, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0) {
                pthread_mutex_unlock(&lock); return false;
            }
        }
        current_port = port;
        replay_pending = port != 0;
        __atomic_store_n(&g_dock_frame_tokens[0], static_cast<uint64_t>(token0), __ATOMIC_RELEASE);
        __atomic_store_n(&g_dock_frame_tokens[1], static_cast<uint64_t>(token1), __ATOMIC_RELEASE);
        __atomic_store_n(&requested, port != 0 ? 1u : 0u, __ATOMIC_RELEASE);
    }
    pthread_mutex_unlock(&lock);
    if (changed) NotifyDockFramePreparation();
    return true;
}
bool ApplyDockFrameRule(void* handle) {
    if (!DockFrameRequested()) return true; // Passive hooks never inspect heap or send when disabled.
    pthread_mutex_lock(&lock);
    DartResolution resolution{};
    if (!ResolveDartSites(handle, dart::kDockFrameTarget, &resolution)) { pthread_mutex_unlock(&lock); return false; }
    if (installed_base == resolution.base) {
        bool owned = true;
        for (size_t i = 0; i < 8; ++i) owned = owned && targets[i] && memcmp(targets[i], patches[i], 16) == 0;
        __atomic_store_n(&g_dock_frame_enabled, owned && DockFrameRequested() ? 1u : 0u, __ATOMIC_RELEASE);
        ReplaySamples();
        pthread_mutex_unlock(&lock); return owned;
    }
    if (installed_base != nullptr) { __atomic_store_n(&g_dock_frame_enabled,0u,__ATOMIC_RELEASE); pthread_mutex_unlock(&lock); return false; } // Do not patch a stale/remapped owner.
    dart::Image image{}; dart::DockFrameLayout layout{};
    const uintptr_t site = resolution.Find("hotseat_motion");
    if (!resolution.MatchVerify("hotseat_motion") || !dart::ParseImage(resolution.base, &image) ||
        !dart::DecodeDockFrameLayout(image, site - reinterpret_cast<uintptr_t>(resolution.base), &layout)) {
        pthread_mutex_unlock(&lock); return false;
    }
    g_dock_frame_offsets[0] = layout.alpha; g_dock_frame_offsets[1] = layout.offset;
    g_dock_frame_offsets[2] = layout.scale; g_dock_frame_offsets[3] = layout.origin;
    g_dock_frame_pool = layout.pool;
    g_dock_frame_double_cid = layout.double_cid;
    const uintptr_t snap = resolution.Find("controller_set");
    const uintptr_t screen = resolution.Find("controller_scale"), alpha = resolution.Find("controller_alpha"), edit = resolution.Find("editing_state");
    uint32_t disabled = 0;
    if (!snap || !screen || !alpha || !edit || !resolution.MatchVerify("controller_scale") ||
        !resolution.MatchVerify("controller_alpha") || !resolution.MatchVerify("editing_state") || !resolution.MatchVerify("controller_set") ||
        !dart::DecodeDockEditingPool(image, edit - reinterpret_cast<uintptr_t>(resolution.base), &disabled)) {
        pthread_mutex_unlock(&lock); return false;
    }
    g_dock_edit_disabled_pool = disabled;
    const uintptr_t tick = resolution.Find("hotseat_scale_tick");
    dart::DockTickLayout tick_layout{};
    if(!tick || !resolution.MatchVerify("hotseat_scale_tick") ||
        !dart::DecodeDockTickLayout(image,tick-reinterpret_cast<uintptr_t>(resolution.base),&tick_layout)) {
        pthread_mutex_unlock(&lock);return false;
    }
    g_dock_tick_layout[0]=tick_layout.receiver;g_dock_tick_layout[1]=tick_layout.scale;
    g_dock_tick_layout[2]=tick_layout.y;g_dock_tick_layout[3]=tick_layout.value;
    const uintptr_t offsets[] = {layout.visible, layout.hidden[0], layout.hidden[1], screen - reinterpret_cast<uintptr_t>(resolution.base),
        alpha - reinterpret_cast<uintptr_t>(resolution.base), edit - reinterpret_cast<uintptr_t>(resolution.base), snap - reinterpret_cast<uintptr_t>(resolution.base), tick - reinterpret_cast<uintptr_t>(resolution.base)};
    for (size_t i = 0; i < 8; ++i) {
        targets[i] = const_cast<uint8_t*>(resolution.base) + offsets[i];
        void* original = nullptr;
        const auto replacement = i == 0 ? &HyperTweakDockVisibleReturn : i < 3 ? &HyperTweakDockHiddenReturn :
            i == 3 ? &HyperTweakDockControllerScaleReturn : i == 4 ? &HyperTweakDockControllerAlphaReturn :
            i == 5 ? &HyperTweakDockEditingReturn : i == 6 ? &HyperTweakDockControllerSetReturn : &HyperTweakDockLocalTickReturn;
        if (InstallInlineHook(targets[i], reinterpret_cast<void*>(replacement), &original) != kHookSuccess || original == nullptr) {
            for (size_t j = 0; j < i; ++j) (void)RemoveInlineHook(targets[j]);
            memset(targets, 0, sizeof(targets)); pthread_mutex_unlock(&lock); return false;
        }
        memcpy(patches[i], targets[i], 16);
    }
    installed_base = resolution.base;
    __atomic_store_n(&g_dock_frame_enabled, DockFrameRequested() ? 1u : 0u, __ATOMIC_RELEASE);
    ReplaySamples();
    LogInfo("Dock native hotseat, animation controller and edit-state observers attached (8 validated sites)");
    pthread_mutex_unlock(&lock); return true;
}
void ResetDockFrameAfterFork() {
    if (g_dock_frame_fd >= 0) close(g_dock_frame_fd);
    g_dock_frame_fd = -1; g_dock_frame_enabled = 0; current_port = 0; requested = 0;
    g_dock_frame_tokens[0] = g_dock_frame_tokens[1] = 0;
    g_dock_frame_sequence = 0; g_dock_frame_source_epoch = 0; replay_pending = false;
    memset(g_dock_frame_samples,0,sizeof(g_dock_frame_samples));
    pthread_mutex_t fresh = PTHREAD_MUTEX_INITIALIZER; lock = fresh;
}
}
