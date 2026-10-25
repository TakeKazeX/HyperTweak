#pragma once
#include <stdint.h>

namespace miui_home_dart_state {
// Assembly still owns the observation cells; this owner exclusively owns publication receipts.
class Publisher {
public:
    enum Channel : uint32_t { Drawer, Overview, Editing, Owner };
    struct Receipt {
        volatile uint32_t state = 0, in_flight = 0, count = 0;
        volatile int64_t generation = 0;
        volatile uint64_t owner_epoch = 0;
    };
    struct Inputs {
        volatile uint64_t* owner;
        int64_t* generation;
        volatile uint32_t* pending;
        volatile uint64_t* observations[3];
    };
    struct Transport {
        bool (*send)(Channel, uint32_t, int64_t, uint64_t);
        void (*report)(Channel, bool, uint32_t, int64_t);
    };
    constexpr Publisher(Inputs inputs, Transport transport) : inputs_(inputs), transport_(transport) {}
    void Publish(Channel channel);
    bool PublishOwner(uint64_t epoch);
    void Drain(uint64_t epoch);
    void ResetOwner();
    Receipt channels[3]{};
    volatile uint64_t owner_published_epoch = 0;
    volatile int64_t owner_published_generation = 0;
private:
    Inputs inputs_;
    Transport transport_;
};
} // namespace miui_home_dart_state
