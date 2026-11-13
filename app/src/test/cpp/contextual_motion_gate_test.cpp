#include "contextual_motion_gate.h"
#include <cassert>
#include <cstdio>
#include <initializer_list>
#include <limits>

using miui_home_contextual::MotionGate;

int main() {
    constexpr int pid = 123, device = 4, source = 0x1002;
    MotionGate gate;
    auto event = [&](int64_t down, int action, float x, float y) {
        gate.observe(1, pid, down, device, source, action, x, y);
    };
    assert(!gate.read().allows(pid));
    event(1, 0, 600, 2590);
    gate.observe(0, pid, 1, device, source, 3, 600, 2590);
    assert(gate.read().allows(pid)); // Flutter redirect CANCEL is not physical cancellation.
    assert(gate.read().allows(pid)); // Native stationary long press remains eligible.
    assert(!gate.read().allows(pid + 1)); // No inherited receipt in a forked owner.
    event(1, 2, 600, 2550);
    assert(!gate.read().allows(pid)); // Fullscreen upward navigation.
    event(1, 2, 600, 2590);
    event(1, 0, 600, 2590);
    assert(!gate.read().allows(pid)); // Returning/re-reading DOWN cannot rearm it.
    event(2, 0, 600, 2590);
    event(1, 0, 600, 2590);
    assert(gate.read().down_time == 2); // A cached older DOWN must never replace native input.
    event(1, 1, 600, 2590);
    assert(gate.read().allows(pid)); // Old stream's UP cannot retire a replacement.
    event(2, 1, 600, 2590);
    event(2, 0, 600, 2590);
    assert(!gate.read().allows(pid)); // A terminal Fn after physical UP cannot invoke.
    for (int action : {3, 5, 6}) {
        event(10 + action, 0, 600, 2590);
        event(10 + action, action, 600, 2590);
        assert(!gate.read().allows(pid));
    }
    event(20, 0, 600, 2590);
    event(20, 2, 620, 2590);
    assert(!gate.read().allows(pid)); // Horizontal movement also cancels native hold intent.
    event(21, 0, 600, 2590);
    event(21, 2, 602, 2588);
    assert(gate.read().allows(pid)); // Finger jitter within the native distance is retained.
    gate.invalidate();
    assert(!gate.read().allows(pid));
    event(21, 0, 600, 2590);
    assert(!gate.read().allows(pid)); // Preference/generation changes require a fresh DOWN.
    event(22, 0, 600, 2590);
    assert(gate.read().allows(pid));
    event(23, 0, 600, std::numeric_limits<float>::quiet_NaN());
    assert(!gate.read().allows(pid));
    gate.observe(1, pid, 24, device, 0x2002, 0, 600, 2590);
    assert(!gate.read().allows(pid));
    std::puts("Contextual motion cancellation, identity and generation checks passed.");
}
