#include "dart_state_worker.h"
#include <errno.h>
#include <poll.h>
#include <pthread.h>
#include <sys/eventfd.h>
#include <time.h>
#include <unistd.h>

namespace miui_home_dart_state {
namespace {
template <typename T> T AtomicLoad(const T* value) { return __atomic_load_n(value, __ATOMIC_ACQUIRE); }
}
void* EventWorker::Run() {
    const int fd = starting_fd_;
    const uint64_t initial_epoch = AtomicLoad(inputs_.owner);
    if (fd < 0) {
        __atomic_store_n(inputs_.state,
                         uint32_t{6}, __ATOMIC_RELEASE);
        __atomic_store_n(inputs_.failure, initial_epoch,
                         __ATOMIC_RELEASE);
        return nullptr;
    }
    __atomic_store_n(inputs_.state, uint32_t{3},
                     __ATOMIC_RELEASE);
    __atomic_store_n(inputs_.failure, uint64_t{0},
                     __ATOMIC_RELEASE);

    pollfd descriptor{fd, POLLIN, 0};
    for (;;) {
        descriptor.revents = 0;
        const int polled = poll(&descriptor, 1u, -1);
        if (polled < 0 && errno == EINTR) continue;
        if (polled <= 0 || (descriptor.revents & POLLIN) == 0) {
            __atomic_store_n(inputs_.failure,
                             AtomicLoad(inputs_.owner),
                             __ATOMIC_RELEASE);
            __atomic_store_n(inputs_.state,
                             uint32_t{6}, __ATOMIC_RELEASE);
            // Keep the terminal fd allocated until process exit. A Dart hook
            // can have loaded it immediately before this failure; closing it
            // here could redirect that raw write into a reused descriptor.
            return nullptr;
        }
        uint64_t wake_count = 0u;
        while (read(fd, &wake_count, sizeof(wake_count)) ==
                static_cast<ssize_t>(sizeof(wake_count))) {
        }

        // Keep publication outside the triggering Dart frame. State changes
        // within one display interval coalesce into the latest immutable
        // observation, while the thread remains fully asleep when idle.
        timespec delay{0, 16 * 1000 * 1000};
        while (nanosleep(&delay, &delay) != 0 && errno == EINTR) {
        }
        while (read(fd, &wake_count, sizeof(wake_count)) ==
                static_cast<ssize_t>(sizeof(wake_count))) {
        }
        uint32_t retry_delay_ms = 16u;
        for (;;) {
            const uint64_t owner_epoch =
                    AtomicLoad(inputs_.owner);
            // Mark this publication cycle unresolved before exchanging the
            // pending mask. Readiness must not observe a transient zero while
            // a broadcast attempt owns those bits locally.
            __atomic_store_n(inputs_.failure,
                             owner_epoch, __ATOMIC_RELEASE);
            drain_(owner_epoch);
            if (AtomicLoad(inputs_.owner) == owner_epoch &&
                    AtomicLoad(inputs_.pending) == 0u) {
                __atomic_store_n(inputs_.callback,
                                 owner_epoch, __ATOMIC_RELEASE);
                uint64_t failed_epoch = owner_epoch;
                __atomic_compare_exchange_n(
                        inputs_.failure, &failed_epoch,
                        uint64_t{0}, false, __ATOMIC_RELEASE,
                        __ATOMIC_RELAXED);
                // A new Dart observation racing the success publication owns
                // a raw eventfd wake. Do not suppress that next cycle.
                if (AtomicLoad(inputs_.pending) == 0u) break;
            }

            // A re-armed bit is self-sustaining work. Drain any coalesced
            // eventfd writes and retry with bounded backoff instead of
            // returning to an indefinite poll or spinning on a broken
            // broadcast runtime.
            while (read(fd, &wake_count, sizeof(wake_count)) ==
                    static_cast<ssize_t>(sizeof(wake_count))) {
            }
            timespec retry_delay{
                    static_cast<time_t>(retry_delay_ms / 1000u),
                    static_cast<long>((retry_delay_ms % 1000u) *
                                      1000u * 1000u)};
            while (nanosleep(&retry_delay, &retry_delay) != 0 &&
                    errno == EINTR) {
            }
            retry_delay_ms = retry_delay_ms < 128u
                    ? retry_delay_ms * 2u : 250u;
        }
    }
}

bool EventWorker::Wake() {
    const uint64_t owner_epoch = AtomicLoad(inputs_.owner);
    const int fd = AtomicLoad(inputs_.event_fd);
    if (fd < 0 ||
            AtomicLoad(inputs_.state) !=
                    uint32_t{3}) {
        __atomic_store_n(inputs_.failure, owner_epoch,
                         __ATOMIC_RELEASE);
        return false;
    }
    const uint64_t one = 1u;
    const ssize_t written = write(fd, &one, sizeof(one));
    if (written == static_cast<ssize_t>(sizeof(one)) ||
            (written < 0 && errno == EAGAIN)) {
        return true;
    }
    __atomic_store_n(inputs_.failure, owner_epoch,
                     __ATOMIC_RELEASE);
    return false;
}

bool EventWorker::WaitReady(uint64_t owner_epoch) {
    for (uint32_t attempt = 0u; attempt < 50u; ++attempt) {
        if (AtomicLoad(inputs_.owner) != owner_epoch) {
            return false;
        }
        if (AtomicLoad(inputs_.callback) == owner_epoch &&
                AtomicLoad(inputs_.pending) == 0u &&
                AtomicLoad(inputs_.failure) !=
                        owner_epoch) {
            return true;
        }
        if (AtomicLoad(inputs_.state) !=
                uint32_t{3}) {
            return false;
        }
        timespec delay{0, 1000 * 1000};
        nanosleep(&delay, nullptr);
    }
    __atomic_store_n(inputs_.failure, owner_epoch,
                     __ATOMIC_RELEASE);
    return false;
}

bool EventWorker::Ensure() {
    const uint64_t owner_epoch = AtomicLoad(inputs_.owner);
    if (AtomicLoad(inputs_.state) == uint32_t{3} &&
            AtomicLoad(inputs_.event_fd) >= 0) {
        return (AtomicLoad(inputs_.callback) ==
                        owner_epoch &&
                AtomicLoad(inputs_.pending) == 0u &&
                AtomicLoad(inputs_.failure) !=
                        owner_epoch) ||
                (Wake() &&
                 WaitReady(owner_epoch));
    }
    uint32_t expected = AtomicLoad(inputs_.state);
    while (expected != uint32_t{1}) {
        if (expected == uint32_t{3}) {
            return AtomicLoad(inputs_.event_fd) >= 0 &&
                    Wake() &&
                    WaitReady(owner_epoch);
        }
        if (expected == uint32_t{6}) return false;
        if (__atomic_compare_exchange_n(
                    inputs_.state, &expected,
                    uint32_t{1}, false, __ATOMIC_ACQ_REL,
                    __ATOMIC_ACQUIRE)) {
            break;
        }
    }
    if (expected == uint32_t{1}) {
        for (uint32_t attempt = 0u; attempt < 50u &&
                AtomicLoad(inputs_.state) ==
                        uint32_t{1}; ++attempt) {
            timespec delay{0, 1000 * 1000};
            nanosleep(&delay, nullptr);
        }
        if (AtomicLoad(inputs_.state) !=
                    uint32_t{3} ||
                AtomicLoad(inputs_.event_fd) < 0) {
            return false;
        }
        return Wake() &&
                WaitReady(owner_epoch);
    }

    const int fd = eventfd(0u, EFD_CLOEXEC | EFD_NONBLOCK);
    if (fd < 0) {
        __atomic_store_n(inputs_.state, uint32_t{6},
                         __ATOMIC_RELEASE);
        __atomic_store_n(inputs_.failure,
                         AtomicLoad(inputs_.owner),
                         __ATOMIC_RELEASE);
        return false;
    }
    pthread_attr_t attributes{};
    pthread_t thread{};
    const bool attributes_ready = pthread_attr_init(&attributes) == 0;
    if (attributes_ready) {
        pthread_attr_setdetachstate(&attributes, PTHREAD_CREATE_DETACHED);
    }
    starting_fd_ = fd;
    const int created = attributes_ready
            ? pthread_create(&thread, &attributes,
                             [](void* self) -> void* { return static_cast<EventWorker*>(self)->Run(); },
                             this)
            : EINVAL;
    if (attributes_ready) pthread_attr_destroy(&attributes);
    if (created != 0) {
        close(fd);
        __atomic_store_n(inputs_.state, uint32_t{6},
                         __ATOMIC_RELEASE);
        __atomic_store_n(inputs_.failure,
                         AtomicLoad(inputs_.owner),
                         __ATOMIC_RELEASE);
        return false;
    }
    // Publish only after pthread_create succeeds; a failed setup must never
    // expose an fd that can be closed and reused under a raw Dart write.
    __atomic_store_n(inputs_.event_fd, fd, __ATOMIC_RELEASE);
    for (uint32_t attempt = 0u; attempt < 50u &&
            AtomicLoad(inputs_.state) ==
                    uint32_t{1}; ++attempt) {
        timespec delay{0, 1000 * 1000};
        nanosleep(&delay, nullptr);
    }
    if (AtomicLoad(inputs_.state) != uint32_t{3}) {
        return false;
    }
    return Wake() &&
            WaitReady(owner_epoch);
}


void EventWorker::CloseInherited() {
    const int fd = AtomicLoad(inputs_.event_fd);
    if (fd >= 0) close(fd);
}
void EventWorker::ResetAfterFork() {
    __atomic_store_n(inputs_.event_fd, int32_t{-1}, __ATOMIC_RELEASE);
    __atomic_store_n(inputs_.callback, uint64_t{0}, __ATOMIC_RELEASE);
    __atomic_store_n(inputs_.failure, uint64_t{0}, __ATOMIC_RELEASE);
    __atomic_store_n(inputs_.state, uint32_t{0}, __ATOMIC_RELEASE);
    starting_fd_ = -1;
}
} // namespace miui_home_dart_state
