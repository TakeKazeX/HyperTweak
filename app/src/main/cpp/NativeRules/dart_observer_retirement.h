#pragma once
#include <array>
#include <stdint.h>
namespace miui_home_dart_state {
/** Paired observers retire together and remain gated until their patch is installed. */
class ObserverRetirement {
public:
    struct Observer {
        uint32_t mask;
        volatile uint32_t *retiring, *active, *installed;
    };
    constexpr explicit ObserverRetirement(std::array<Observer, 4> observers) : observers_(observers) {}
    bool Prepare(uint32_t mask);
    void Finish(uint32_t mask);
private:
    bool Quiet(uint32_t mask) const;
    std::array<Observer, 4> observers_;
};
}
