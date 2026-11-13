#include "p4g_maxwell.h"
#include <cassert>
#include <iostream>

int main() {
    constexpr size_t c = 0, p = 0x850;
    auto fixture = [] {
        std::vector<uint8_t> d(0x950);
        auto u = [&](size_t o, uint32_t x) { std::memcpy(d.data() + o, &x, 4); };
        u(0, 0x98761234); u(0x704, 0x100); u(0x708, 0x30); u(0x6f8, 0x60);
        u(p, 0x12345678); u(p + 0x30, 1 << 10);
        uint64_t exit = 0xe30000000007000full; std::memcpy(d.data() + p + 0x88, &exit, 8);
        return d;
    };
    auto d = fixture();
    auto b = p4g::maxwell_blocks(d);
    assert(b.size() == 1 && b[0].code == p && b[0].control == c && b[0].size == 0x100);
    // Missing padding, truncated blocks, length underflow and unreasonable lengths are rejected.
    d.back() = 1; assert(p4g::maxwell_blocks(d).empty());
    d = fixture(); d.resize(p + 16); assert(p4g::maxwell_blocks(d).empty());
    d = fixture(); std::memset(d.data() + 0x6f8, 0, 4); assert(p4g::maxwell_blocks(d).empty());
    d = fixture(); std::memset(d.data() + 0x704, 0xff, 4); assert(p4g::maxwell_blocks(d).empty());
    assert(p4g::maxwell_blocks({}).empty());
    // Ambiguous unrelated controls must not manufacture a shader/control association.
    d = fixture(); d.resize(0x2000); std::memcpy(d.data() + 0x1100, d.data(), 0x880);
    auto u = [&](size_t o, uint32_t x) { std::memcpy(d.data() + o, &x, 4); };
    // Adjacent descriptor remains authoritative even if another one differs in work-group state.
    u(0x1100 + 0x748, 2); assert(p4g::maxwell_blocks(d).size() == 1);
    std::cout << "PASS: Maxwell block discovery and malformed inputs\n";
}
