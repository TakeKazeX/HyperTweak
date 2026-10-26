#include "dart_state_publisher.h"
#include "dart_state_publication.h"

namespace miui_home_dart_state {
namespace {
template<class T> auto Load(const T* p) { return __atomic_load_n(p, __ATOMIC_ACQUIRE); }
bool Matches(uint64_t observation, uint64_t epoch, int64_t generation, const Publisher::Receipt& receipt) {
    return (observation >> 2u) == epoch &&
        static_cast<uint32_t>(observation & 3u) == Load(&receipt.state) &&
        Load(&receipt.generation) == generation && Load(&receipt.owner_epoch) == epoch;
}
}
void Publisher::Publish(Channel channel) {
    if (channel >= Owner) return;
    auto& receipt = channels[channel];
    const uint64_t epoch = Load(inputs_.owner);
    const uint64_t observation = Load(inputs_.observations[channel]);
    const uint32_t state = static_cast<uint32_t>(observation & 3u);
    const int64_t generation = Load(inputs_.generation);
    if ((observation >> 2u) != epoch || (state != 1u && state != 2u) || generation <= 0 ||
            Matches(observation, epoch, generation, receipt)) return;
    uint32_t expected = 0;
    if (!__atomic_compare_exchange_n(&receipt.in_flight, &expected, uint32_t{1}, false,
            __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) return;
    const bool sent = transport_.send(channel, state, generation, epoch);
    if (RecordPublication(sent, state, generation, epoch, &receipt.state,
            &receipt.generation, &receipt.owner_epoch)) {
        __atomic_fetch_add(&receipt.count, uint32_t{1}, __ATOMIC_RELAXED);
    }
    transport_.report(channel, sent, state, generation);
    __atomic_store_n(&receipt.in_flight, uint32_t{0}, __ATOMIC_RELEASE);
}
bool Publisher::PublishOwner(uint64_t epoch) {
    const int64_t generation = Load(inputs_.generation);
    if (epoch == 0 || Load(inputs_.owner) != epoch || generation <= 0) return false;
    if (Load(&owner_published_epoch) == epoch && Load(&owner_published_generation) == generation) return true;
    const bool sent = transport_.send(Owner, 0, generation, epoch);
    if (sent && Load(inputs_.owner) == epoch && Load(inputs_.generation) == generation) {
        __atomic_store_n(&owner_published_epoch, epoch, __ATOMIC_RELEASE);
        __atomic_store_n(&owner_published_generation, generation, __ATOMIC_RELEASE);
        return true;
    }
    if (!sent) transport_.report(Owner, false, 0, generation);
    return false;
}
void Publisher::Drain(uint64_t epoch) {
    if (epoch == 0 || Load(inputs_.owner) != epoch) return;
    const uint32_t pending = __atomic_exchange_n(inputs_.pending, uint32_t{0}, __ATOMIC_ACQ_REL);
    if (pending == 0) return;
    if (pending & 8u) PublishOwner(epoch);
    for (uint32_t i = 0; i < 3; ++i) if (pending & (1u << i)) Publish(static_cast<Channel>(i));
    if (Load(inputs_.owner) != epoch) return;
    const int64_t generation = Load(inputs_.generation);
    uint32_t retry = (pending & 8u) &&
        (Load(&owner_published_epoch) != epoch || Load(&owner_published_generation) != generation) ? 8u : 0u;
    for (uint32_t i = 0; i < 3; ++i) {
        const uint64_t observed = Load(inputs_.observations[i]);
        if ((pending & (1u << i)) && (observed >> 2u) == epoch &&
                !Matches(observed, epoch, generation, channels[i])) retry |= 1u << i;
    }
    if (retry) __atomic_fetch_or(inputs_.pending, retry, __ATOMIC_RELEASE);
}
void Publisher::ResetOwner() {
    __atomic_store_n(&owner_published_epoch, uint64_t{0}, __ATOMIC_RELEASE);
    __atomic_store_n(&owner_published_generation, int64_t{0}, __ATOMIC_RELEASE);
}
} // namespace miui_home_dart_state
