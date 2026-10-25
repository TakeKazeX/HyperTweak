#include "dart_state_publisher.h"
#include <cassert>
#include <vector>
#include <atomic>
#include <thread>
using miui_home_dart_state::Publisher;
namespace {
volatile uint64_t epoch = 1, observations[3]{};
volatile uint32_t pending;
int64_t generation = 7;
bool send_ok = true;
int mutate = 0, reports = 0;
Publisher* owner;
std::atomic<bool> block_send{false}, entered{false}, resume_send{false};
struct Sent { Publisher::Channel channel; uint32_t state; int64_t generation; uint64_t epoch; };
std::vector<Sent> sent;
bool Send(Publisher::Channel channel, uint32_t state, int64_t gen, uint64_t e) {
    sent.push_back({channel, state, gen, e});
    if (block_send.load()) { entered.store(true); while (!resume_send.load()) std::this_thread::yield(); }
    if (mutate == 1 && channel == Publisher::Drawer) { mutate = 0; observations[0] = (epoch << 2u) | 1u; }
    if (mutate == 2) { mutate = 0; epoch++; }
    if (mutate == 3) { mutate = 0; owner->Publish(channel); } // in-flight channel must not re-enter.
    return send_ok;
}
void Report(Publisher::Channel, bool, uint32_t, int64_t) { reports++; }
Publisher Make() { return Publisher({&epoch, &generation, &pending, {&observations[0], &observations[1], &observations[2]}}, {Send, Report}); }
void Reset() { epoch = 1; generation = 7; pending = 0; for (auto& o : observations) o = 0; send_ok = true; mutate = reports = 0; sent.clear(); }
}
int main() {
    Reset(); auto p = Make(); owner = &p;
    observations[0] = 6; pending = 9; p.Drain(1);
    assert(sent.size() == 2 && sent[0].channel == Publisher::Owner && sent[1].state == 2);
    assert(p.channels[0].count == 1 && pending == 0);
    p.Publish(Publisher::Drawer); assert(sent.size() == 2);
    generation = 8; p.Publish(Publisher::Drawer); assert(sent.size() == 3);
    // A sent B snapshot remains recorded even when the observation flips back to A during send.
    observations[0] = 6; generation = 9; pending = 1; mutate = 1; p.Drain(1);
    assert(p.channels[0].state == 2 && pending == 1);
    p.Drain(1); assert(sent.back().state == 1 && pending == 0);
    // Failed sends keep receipts untouched and re-arm only requested channels.
    send_ok = false; observations[1] = 6; pending = 2; p.Drain(1);
    assert(p.channels[1].count == 0 && pending == 2);
    send_ok = true; p.Drain(1); assert(p.channels[1].count == 1 && pending == 0);
    // Foreign owner observations, unknown state, and nonpositive generations do not send.
    const auto before = sent.size(); observations[2] = 10; p.Publish(Publisher::Editing);
    observations[2] = 4; p.Publish(Publisher::Editing); generation = 0; observations[2] = 6; p.Publish(Publisher::Editing);
    assert(sent.size() == before);
    // Owner changes during send cannot acknowledge that reset or re-arm the retired generation.
    generation = 10; pending = 8; mutate = 2; p.Drain(1);
    assert(p.owner_published_epoch == 1 && pending == 0 && epoch == 2);
    pending = 8; p.Drain(2); assert(p.owner_published_epoch == 2);
    p.ResetOwner(); assert(p.owner_published_epoch == 0 && p.owner_published_generation == 0);
    observations[0] = 10; mutate = 3; p.Publish(Publisher::Drawer);
    assert(p.channels[0].in_flight == 0 && p.channels[0].owner_epoch == 2);
    // A blocked publisher fences a simultaneous drain; the latest observation is retried later.
    Reset(); auto concurrent = Make(); owner = &concurrent;
    observations[0] = 6; block_send.store(true); entered.store(false); resume_send.store(false);
    std::thread worker([&] { concurrent.Publish(Publisher::Drawer); });
    while (!entered.load()) std::this_thread::yield();
    __atomic_store_n(&observations[0], uint64_t{5}, __ATOMIC_RELEASE);
    pending = 1; concurrent.Drain(1);
    assert(pending == 1);
    resume_send.store(true); worker.join(); block_send.store(false);
    assert(sent.size() == 1 && concurrent.channels[0].state == 2);
    concurrent.Drain(1);
    assert(sent.size() == 2 && sent.back().state == 1 && pending == 0);
}
