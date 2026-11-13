#pragma once

#include <atomic>
#include <cmath>
#include <stdint.h>

namespace miui_home_contextual {

// Cancellation evidence only. Xiaomi still owns detection, its deadline and input.
// The validated native long-press input monitor has one writer on the main Looper;
// the detector's worker reads a coherent receipt without waiting on that Looper.
class MotionGate {
public:
    struct Snapshot {
        bool coherent = false;
        bool valid = false;
        bool cancelled = false;
        int32_t owner = 0, device = 0, source = 0, action = -1;
        int64_t down_time = 0;
        uint32_t epoch = 0;
        float down_x = 0, down_y = 0, x = 0, y = 0;

        bool allows(int32_t process) const {
            return coherent && valid && !cancelled && owner == process
                    && (action == 0 || action == 2);
        }
    };

    Snapshot read() const {
        Snapshot s;
        const uint32_t before = sequence_.load();
        const uint32_t epoch = epoch_.load();
        s.epoch = epoch;
        if ((before & 1u) != 0u) return s;
        s.valid = valid_.load() && captured_epoch_.load() == epoch;
        s.cancelled = cancelled_.load();
        s.owner = owner_.load(); s.device = device_.load(); s.source = source_.load();
        s.action = action_.load(); s.down_time = down_time_.load();
        s.down_x = down_x_.load(); s.down_y = down_y_.load();
        s.x = x_.load(); s.y = y_.load();
        s.coherent = before == sequence_.load() && epoch == epoch_.load();
        return s;
    }

    void invalidate() { epoch_.fetch_add(1); }

    void observe(uintptr_t receiver, int32_t process, int64_t down_time, int32_t device,
                 int32_t source, int32_t action, float x, float y) {
        // Only events admitted by the native long-press input monitor are evidence.
        // Unrelated window copies can be cancelled during redirection while
        // Xiaomi's actual detector stream is still held.
        if (receiver != 1u) return;
        Snapshot s = read();
        const bool same = s.coherent && s.owner == process && s.down_time == down_time
                && s.device == device && s.source == source;
        if (action == 0 && s.coherent && s.owner == process && down_time < s.down_time) return;
        // A duplicate delivery can inspect the original DOWN again after a MOVE.
        // That must not erase cancellation or reopen a released physical stream.
        if (action == 0 && same) return;
        if (action != 0 && !same) return;
        if (action == 0) {
            s.owner = process; s.down_time = down_time;
            s.device = device; s.source = source;
            s.down_x = x; s.down_y = y;
            s.valid = process > 0 && down_time > 0 && source == 0x1002
                    && std::isfinite(x) && std::isfinite(y);
            s.cancelled = !s.valid;
        }
        s.action = action; s.x = x; s.y = y;
        const float dx = x - s.down_x, dy = y - s.down_y;
        if (!std::isfinite(x) || !std::isfinite(y) || dx * dx + dy * dy > 64.0f
                || (action != 0 && action != 2)) s.cancelled = true;
        sequence_.fetch_add(1);
        if (action == 0) captured_epoch_.store(s.epoch);
        valid_.store(s.valid); cancelled_.store(s.cancelled);
        owner_.store(s.owner); device_.store(s.device); source_.store(s.source);
        action_.store(s.action); down_time_.store(s.down_time);
        down_x_.store(s.down_x); down_y_.store(s.down_y); x_.store(x); y_.store(y);
        sequence_.fetch_add(1);
    }

private:
    std::atomic<uint32_t> sequence_{0};
    std::atomic<uint32_t> epoch_{0}, captured_epoch_{0};
    std::atomic<bool> valid_{false}, cancelled_{false};
    std::atomic<int32_t> owner_{0}, device_{0}, source_{0}, action_{-1};
    std::atomic<int64_t> down_time_{0};
    std::atomic<float> down_x_{0}, down_y_{0}, x_{0}, y_{0};
};

}  // namespace miui_home_contextual
