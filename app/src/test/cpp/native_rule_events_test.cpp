// SPDX-License-Identifier: Apache-2.0
#include "native_rule_events.h"
#include <assert.h>
#include <stdio.h>
using namespace hypertweak::native;
int main() {
    NativeRulePreparationGate gate;
    assert(gate.Queue(0u));
    assert(!gate.Queue(0u)); // coalesce duplicate external load/input notifications
    gate.Begin();
    unsigned simulated_loader_callbacks = 0u;
    {
        NativeRuleLibraryQuery no_load_query;
        ++simulated_loader_callbacks; // LSPosed callback from our own dlopen(RTLD_NOLOAD)
        assert(!gate.Queue(1u));
        {
            NativeRuleLibraryQuery nested_symbol_lookup;
            assert(NativeRuleLibraryQueryActive());
            assert(!gate.Queue(1u));
        }
        assert(NativeRuleLibraryQueryActive());
    }
    assert(!NativeRuleLibraryQueryActive());
    gate.Complete(2u, true);
    assert(simulated_loader_callbacks == 1u && !gate.queued && !gate.running);
    for (unsigned i = 0u; i < 10000u; ++i) assert(!gate.Queue(i)); // idle is stable

    gate.Changed(); // a real preference update while active
    assert(gate.Queue(3u));
    gate.Begin();
    gate.Changed(); // newer external snapshot arrives while preparing
    assert(!gate.Queue(4u));
    gate.Complete(5u, true);
    assert(gate.Queue(6u)); // exactly one drain for that newer generation
    gate.Begin();
    gate.Complete(7u, true);
    assert(!gate.Queue(8u));

    gate.Changed(); // unavailable image: finite, externally driven retries
    for (unsigned i = 0u; i < 3u; ++i) {
        const uint64_t time = (i + 1u) * 1000000000ull;
        assert(gate.Queue(time));
        gate.Begin();
        gate.Complete(time, false);
        assert(!gate.Queue(time + 1u));
    }
    assert(!gate.Queue(100000000000ull));
    gate.Changed(); // a real library/config generation restores eligibility
    assert(gate.Queue(100000000000ull));
    puts("PASS: self-notification suppression, idle stability, conflation, generation drain, bounded retries");
}
