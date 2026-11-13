// Detached Bionic benchmark of the actual production EventWorker, with a
// deliberately unavailable publication endpoint. No launcher/IPC is modified.
#include "dart_state_worker.h"
#include <atomic>
#include <cassert>
#include <cstdio>
#include <ctime>
#include <cstdlib>
#include <unistd.h>
using miui_home_dart_state::EventWorker;
volatile uint64_t owner=1, callback=0, failure=0;
volatile uint32_t pending=1, state=0, calls=0, fail=0;
volatile int32_t fd=-1;
static uint64_t Ns(clockid_t clock) {
    timespec t{}; clock_gettime(clock,&t);
    return uint64_t(t.tv_sec)*1000000000ULL+uint64_t(t.tv_nsec);
}
static void Drain(uint64_t) {
    __atomic_fetch_add(&calls,1u,__ATOMIC_ACQ_REL);
    __atomic_store_n(&pending,__atomic_load_n(&fail,__ATOMIC_ACQUIRE),__ATOMIC_RELEASE);
}
int main(int argc, char** argv) {
    const unsigned long window_ms = argc > 1 ? std::strtoul(argv[1], nullptr, 10) : 3200ul;
    assert(window_ms >= 200ul && window_ms <= 60000ul);
    EventWorker worker({&owner,&callback,&failure,&pending,&state,&fd},Drain);
    assert(worker.Ensure());
    const auto idleCpu=Ns(CLOCK_PROCESS_CPUTIME_ID);
    const uint32_t idleCalls=calls;
    usleep(200000);
    printf("idle,200,%u,%llu,0\n",calls-idleCalls,(unsigned long long)(Ns(CLOCK_PROCESS_CPUTIME_ID)-idleCpu));
    const uint32_t before=calls;
    __atomic_store_n(&fail,1u,__ATOMIC_RELEASE);
    __atomic_store_n(&pending,1u,__ATOMIC_RELEASE);
    const auto start=Ns(CLOCK_MONOTONIC), cpu=Ns(CLOCK_PROCESS_CPUTIME_ID);
    assert(worker.Wake());
    // Sustained failure, no producer event flood: compares actual retry policy.
    usleep(static_cast<useconds_t>(window_ms * 1000ul));
    const auto failedCpu=Ns(CLOCK_PROCESS_CPUTIME_ID)-cpu;
    printf("failure,%llu,%u,%llu,0\n",(unsigned long long)((Ns(CLOCK_MONOTONIC)-start)/1000000),calls-before,(unsigned long long)failedCpu);
    assert(failure==owner && pending!=0u);
    __atomic_store_n(&fail,0u,__ATOMIC_RELEASE);
    const auto recovery=Ns(CLOCK_MONOTONIC);
    assert(worker.Wake());
    for(int i=0;i<1000 && (failure!=0u || pending!=0u);++i) usleep(1000);
    assert(failure==0u && pending==0u);
    printf("recovery,0,0,0,%llu\n",(unsigned long long)(Ns(CLOCK_MONOTONIC)-recovery));
}
