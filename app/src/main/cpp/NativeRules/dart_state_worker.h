#pragma once
#include <stdint.h>

namespace miui_home_dart_state {
/** Eventfd scheduling over the exact assembly-visible cells; never owns a duplicate epoch. */
class EventWorker {
public:
    struct Inputs {
        volatile uint64_t *owner, *callback, *failure;
        volatile uint32_t *pending, *state;
        volatile int32_t* event_fd;
    };
    constexpr EventWorker(Inputs inputs, void (*drain)(uint64_t)) : inputs_(inputs), drain_(drain) {}
    bool Ensure();
    bool Wake();
    bool WaitReady(uint64_t epoch);
    // Child-only: the parent's worker vanished at fork; inherited fd must be closed first.
    void CloseInherited();
    void ResetAfterFork();
private:
    void* Run();
    Inputs inputs_;
    void (*drain_)(uint64_t);
    int starting_fd_ = -1;
};
}
