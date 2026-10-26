#include "dart_observer_retirement.h"
#include <time.h>
namespace miui_home_dart_state {
bool ObserverRetirement::Quiet(uint32_t mask) const {
    for (const auto& observer : observers_) {
        if ((mask & observer.mask) != 0u &&
            __atomic_load_n(observer.active, __ATOMIC_ACQUIRE) != 0u) return false;
    }
    return true;
}
bool ObserverRetirement::Prepare(uint32_t mask) {
    for (const auto& observer : observers_) {
        if ((mask & observer.mask) != 0u)
            __atomic_store_n(observer.retiring, uint32_t{1}, __ATOMIC_RELEASE);
    }
    for (uint32_t attempt = 0u; attempt < 8u; ++attempt) {
        if (Quiet(mask)) {
            timespec stable{0, 1000 * 1000};
            nanosleep(&stable, nullptr);
            if (Quiet(mask)) return true;
        } else {
            timespec wait{0, 1000 * 1000};
            nanosleep(&wait, nullptr);
        }
    }
    return false;
}
void ObserverRetirement::Finish(uint32_t mask) {
    for (const auto& observer : observers_) {
        if ((mask & observer.mask) != 0u &&
            __atomic_load_n(observer.installed, __ATOMIC_ACQUIRE) == 3u)
            __atomic_store_n(observer.retiring, uint32_t{0}, __ATOMIC_RELEASE);
    }
}
}
