// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <cmath>
#include <stdint.h>
namespace hypertweak::native {
// Primitive-only, isolate-local gesture state. Never retains a Dart object.
//
// Early detent: a tick fires once the focused card has travelled kTrigger of a
// card toward its neighbour, while the incoming card is still at the side, and
// the neighbour becomes the committed card. Reversing needs kReverse of travel
// back from the furthest point since the last tick, so a tick cannot be undone
// by finger jitter (an early forward threshold alone would immediately satisfy
// the backward one). A frame that skips several cards yields one tick.
struct RecentsHapticPolicy {
    static constexpr double kTrigger = 0.3;
    static constexpr double kReverse = 0.25;
    uintptr_t owner = 0;
    double extent = 0, minimum = 0, maximum = 0;
    double extreme = 0;
    int index = 0;
    int last = 0;  // Direction of the last tick: +1, -1, or 0 before the first.
    bool active = false;
    void Reset() { active = false; owner = 0; last = 0; }
    void Begin(uintptr_t source, double width, double min, double max, double from, int direction) {
        owner = source; extent = width; minimum = min; maximum = max;
        index = static_cast<int>(std::round(from)); extreme = from; last = direction; active = true;
    }
    bool Sample(uintptr_t source, int kind, bool user_drag, double pixels,
                double width, double min, double max, double delta = NAN) {
        if (!std::isfinite(pixels) || !std::isfinite(width) || width <= 0 ||
            !std::isfinite(min) || !std::isfinite(max) || min > max ||
            std::abs(pixels / width) > 100000) { Reset(); return false; }
        const double position = -std::fmax(min, std::fmin(max, pixels)) / width;
        if (kind == 0) {
            Reset();
            if (!user_drag || min == max) return false;
            Begin(source, width, min, max, position, 0);
            return false;
        }
        if (kind == 2) { if (source == owner) Reset(); return false; }
        if (source != owner || !active || extent != width || minimum != min || maximum != max) {
            // A real update may follow a programmatic/empty-range ScrollStart, or
            // lose its initial metrics while the Recents entry layout is settling.
            // Recover from *this update's* previous pixels, not unseen movement.
            Reset();
            if (kind != 1 || !user_drag || !std::isfinite(delta) || min == max ||
                std::abs(delta / width) > 100000) return false;
            const double previous = -std::fmax(min, std::fmin(max, pixels - delta)) / width;
            Begin(source, width, min, max, previous,
                  position > previous ? 1 : position < previous ? -1 : 0);
        }
        if (last > 0) extreme = std::fmax(extreme, position);
        else if (last < 0) extreme = std::fmin(extreme, position);
        if (position >= index + kTrigger && (last >= 0 || position - extreme >= kReverse)) {
            index = static_cast<int>(std::floor(position - kTrigger)) + 1;
            last = 1; extreme = position;
            return true;
        }
        if (position <= index - kTrigger && (last <= 0 || extreme - position >= kReverse)) {
            index = static_cast<int>(std::ceil(position + kTrigger)) - 1;
            last = -1; extreme = position;
            return true;
        }
        return false;
    }
};
}
