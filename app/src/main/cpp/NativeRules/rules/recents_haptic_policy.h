// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <cmath>
#include <stdint.h>
namespace hypertweak::native {
// Primitive-only, isolate-local gesture state. Never retains a Dart object.
struct RecentsHapticPolicy {
    uintptr_t owner = 0;
    double extent = 0, minimum = 0, maximum = 0;
    int index = 0;
    bool active = false;
    void Reset() { active = false; owner = 0; }
    bool Sample(uintptr_t source, int kind, bool user_drag, double pixels,
                double width, double min, double max, double delta = NAN) {
        if (!std::isfinite(pixels) || !std::isfinite(width) || width <= 0 ||
            !std::isfinite(min) || !std::isfinite(max) || min > max ||
            std::abs(pixels / width) > 100000) { Reset(); return false; }
        const double position = -std::fmax(min, std::fmin(max, pixels)) / width;
        if (kind == 0) {
            Reset();
            if (!user_drag || min == max) return false;
            owner = source; extent = width; minimum = min; maximum = max;
            index = static_cast<int>(std::round(position)); active = true;
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
            owner = source; extent = width; minimum = min; maximum = max;
            index = static_cast<int>(std::round(previous)); active = true;
        }
        // A small dead band prevents repeated ticks at a stationary card boundary.
        if (std::abs(position - index) < 0.56) return false;
        const int next = static_cast<int>(std::round(position));
        if (next == index) return false;
        index = next;
        return true;
    }
};
}
