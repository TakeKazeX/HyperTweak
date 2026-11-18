// SPDX-License-Identifier: Apache-2.0
#include "recents_haptic_policy.h"
#include <assert.h>
#include <limits>
using hypertweak::native::RecentsHapticPolicy;
int main() {
    RecentsHapticPolicy p;
    auto sample = [&](int kind, double pixels, bool drag = true, uintptr_t owner = 1,
                      double extent = 100, double min = -400, double max = 0) {
        return p.Sample(owner, kind, drag, pixels, extent, min, max);
    };
    // Opening/programmatic scroll has no user gesture, including a mid-scroll enable.
    assert(!sample(1, -100)); assert(!sample(0, 0, false)); assert(!sample(1, -100));
    // Early detent: tick while the incoming card is still at the side.
    assert(!sample(0, 0)); assert(!sample(1, -20)); assert(sample(1, -32));
    assert(!sample(1, -30)); assert(!sample(1, -57)); assert(!sample(1, -100)); // Jitter/arrival: no repeat.
    assert(!sample(1, -96)); assert(!sample(1, -103)); assert(!sample(1, -80));
    assert(sample(1, -68)); // Backing out ticks early too, after enough reverse travel.
    assert(!sample(1, -72)); assert(!sample(1, -60)); // Reversal jitter cannot undo the tick.
    assert(sample(1, -90)); assert(sample(1, -132));
    assert(sample(1, -240)); // A fast frame that skips slots yields one tick, not a burst.
    assert(sample(1, -390));
    assert(!sample(1, -200, true, 2)); // Another task-list owner cannot borrow the session.
    assert(!sample(2, -200)); assert(!sample(1, -300));
    assert(!sample(0, 0)); assert(sample(1, -100));
    assert(!sample(1, -70, true, 1, 101)); assert(!sample(1, -200)); // Rotation/layout change.
    assert(!sample(0, -300)); assert(!sample(1, -300, true, 1, 100, -300));
    assert(!sample(1, -100)); // Deletion invalidates the old list range.
    assert(!sample(0, -400)); assert(!sample(1, -500)); assert(!sample(1, -405)); // Overscroll.
    assert(!sample(2, -400)); assert(!sample(0, -100));
    assert(!sample(1, std::numeric_limits<double>::quiet_NaN())); assert(!sample(1, -200));
    p.Reset(); assert(!sample(1, -200));
    // Entry animation can own ScrollStart; a following real drag update recovers
    // from this sample's scrollDelta without admitting programmatic updates.
    p.Reset();
    assert(!p.Sample(1, 0, false, 0, 100, -400, 0));
    assert(!p.Sample(1, 1, false, -100, 100, -400, 0, -100));
    assert(p.Sample(1, 1, true, -100, 100, -400, 0, -100));
    assert(!p.Sample(1, 1, true, -125, 100, -400, 0, -25));
    assert(p.Sample(1, 1, false, -200, 100, -400, 0, -75)); // Ballistic after a user drag.
    assert(!p.Sample(1, 2, false, -200, 100, -400, 0));
    assert(!p.Sample(1, 1, false, -300, 100, -400, 0, -100));
    assert(!p.Sample(1, 0, true, 0, 100, 0, 0)); // Empty entrance layout.
    assert(p.Sample(1, 1, true, -100, 100, -400, 0, -100));
    assert(p.Sample(1, 1, true, -200, 100, -500, 0, -100)); // Settling range, same real drag.
    p.Reset();
    assert(!p.Sample(1, 1, true, -260, 100, -400, 0, -5)); // No catch-up for unseen cards.
}
