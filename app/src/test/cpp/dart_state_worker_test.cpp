#include "dart_state_worker.h"
#include "dart_observer_retirement.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <sys/wait.h>
#include <unistd.h>

using miui_home_dart_state::EventWorker;
using miui_home_dart_state::ObserverRetirement;
namespace {
volatile uint64_t owner = 41, callback = 0, failure = 0;
volatile uint32_t pending = 8, state = 0, calls = 0, retries = 0, change_owner = 0;
volatile int32_t fd = -1;
void Drain(uint64_t epoch) {
    __atomic_fetch_add(&calls, 1u, __ATOMIC_ACQ_REL);
    __atomic_exchange_n(&pending, 0u, __ATOMIC_ACQ_REL);
    if (__atomic_exchange_n(&change_owner, 0u, __ATOMIC_ACQ_REL)) {
        __atomic_store_n(&owner, epoch + 1, __ATOMIC_RELEASE);
        __atomic_store_n(&pending, 8u, __ATOMIC_RELEASE);
    } else if (__atomic_load_n(&retries, __ATOMIC_ACQUIRE) > 0u) {
        __atomic_fetch_sub(&retries, 1u, __ATOMIC_ACQ_REL);
        // No extra eventfd wake: the worker must sustain a failed publication itself.
        __atomic_store_n(&pending, 1u, __ATOMIC_RELEASE);
    }
}
EventWorker worker({&owner, &callback, &failure, &pending, &state, &fd}, Drain);
void Await(uint64_t epoch) {
    for (int i = 0; i < 2000; ++i) {
        if (__atomic_load_n(&callback, __ATOMIC_ACQUIRE) == epoch &&
            __atomic_load_n(&pending, __ATOMIC_ACQUIRE) == 0u &&
            __atomic_load_n(&failure, __ATOMIC_ACQUIRE) == 0u) return;
        usleep(1000);
    }
    assert(false && "worker failed to drain generation");
}
void TestRetirement() {
    volatile uint32_t retiring[4]{}, active[4]{}, installed[4]{3, 3, 3, 6};
    ObserverRetirement fence({{
        {1, &retiring[0], &active[0], &installed[0]},
        {6, &retiring[1], &active[1], &installed[1]},
        {6, &retiring[2], &active[2], &installed[2]},
        {8, &retiring[3], &active[3], &installed[3]},
    }});
    // Either Overview half fences both, and a live observer defers repair.
    active[2] = 1;
    assert(!fence.Prepare(2));
    assert(retiring[0] == 0 && retiring[1] == 1 && retiring[2] == 1);
    active[2] = 0;
    assert(fence.Prepare(4));
    fence.Finish(4);
    assert(retiring[1] == 0 && retiring[2] == 0);
    assert(fence.Prepare(8));
    fence.Finish(8);
    assert(retiring[3] == 1); // rejected/uninstalled patch remains gated
    installed[3] = 3;
    fence.Finish(8);
    assert(retiring[3] == 0);
}
}
int main() {
    TestRetirement();
    assert(!worker.Wake() && failure == owner);
    assert(worker.Ensure());
    Await(41);
    assert(state == 3 && fd >= 0);
    assert(!worker.WaitReady(40));
    const uint32_t idle = __atomic_load_n(&calls, __ATOMIC_ACQUIRE);
    usleep(80000);
    assert(__atomic_load_n(&calls, __ATOMIC_ACQUIRE) == idle);
    __atomic_store_n(&retries, 3u, __ATOMIC_RELEASE);
    __atomic_store_n(&pending, 1u, __ATOMIC_RELEASE);
    assert(worker.Wake());
    Await(41);
    assert(retries == 0 && calls >= idle + 4);
    __atomic_store_n(&change_owner, 1u, __ATOMIC_RELEASE);
    __atomic_store_n(&pending, 1u, __ATOMIC_RELEASE);
    assert(worker.Wake());
    Await(42);
    // The child has no inherited publisher thread; reset the exact shared input cells.
    const pid_t child = fork();
    assert(child >= 0);
    if (child == 0) {
        const int inherited = fd;
        worker.CloseInherited();
        assert(fcntl(inherited, F_GETFD) == -1 && errno == EBADF);
        worker.ResetAfterFork();
        assert(fd == -1 && state == 0 && callback == 0 && failure == 0);
        owner = 77; pending = 8;
        assert(worker.Ensure());
        Await(77);
        _exit(0);
    }
    int status = 0;
    assert(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    assert(owner == 42 && fd >= 0 && worker.Wake());
    puts("production eventfd worker / fork / remap retirement regressions passed");
}
