// Bounds-checked discovery of Maxwell blocks embedded in a flat ExeFS NSO.
#pragma once
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <set>
#include <string>
#include <utility>
#include <vector>

namespace p4g {
struct MaxwellBlock { size_t code, control; uint32_t size; };
inline std::vector<MaxwellBlock> maxwell_blocks(const std::vector<uint8_t>& data) {
    auto in = [&](size_t p, size_t n) { return p <= data.size() && n <= data.size() - p; };
    auto u32 = [&](size_t p) { uint32_t v = 0; if (in(p, 4)) std::memcpy(&v, data.data() + p, 4); return v; };
    auto zero = [&](size_t p, size_t end) {
        return end >= p && in(p, end - p) && std::all_of(data.begin() + p, data.begin() + end, [](uint8_t c) { return c == 0; });
    };
    auto has_exit = [&](size_t p, size_t end) {
        for (; p <= end && end - p >= 8; p += 8) {
            uint64_t v = 0; if (!in(p, 8)) return false;
            std::memcpy(&v, data.data() + p, 8);
            if (v == 0xe30000000007000full) return true;
        }
        return false;
    };
    auto up = [](size_t v) { return (v + 255) & ~size_t(255); };
    std::vector<size_t> controls, codes;
    for (size_t c = 0; in(c, 0x880); c += 4)
        if (u32(c) == 0x98761234 && u32(c + 0x704) >= 8 && u32(c + 0x704) <= (1u << 24)) controls.push_back(c);
    for (size_t p = 0; in(p, 0x30); ++p)
        if ((u32(p) == 0x12345678 || u32(p) == 0x12345679) && zero(p + 4, p + 0x30)) codes.push_back(p);
    auto fits = [&](size_t p, size_t c) {
        size_t n = u32(c + 0x704), start = u32(c + 0x708), plen = u32(c + 0x6f8);
        size_t cs = u32(c + 0x6fc), co = u32(c + 0x700);
        bool compute = u32(p) == 0x12345679;
        if (!in(p, n) || start != (compute ? 0x100u : 0x30u) || plen < 8 || start > n || plen > n - start) return false;
        if (!compute && plen < 0x58) return false;
        if (!has_exit(p + start, p + start + plen) || zero(p + start + plen - 8, p + start + plen)) return false;
        if (cs) {
            if (co != up(start + plen) || co > n || cs > n - co || n != up(co + cs) || !zero(p + co + cs, p + n)) return false;
        } else if (n != up(start + plen)) return false;
        if (!zero(p + start + plen, p + up(start + plen))) return false;
        uint32_t type = (u32(p + start) >> 10) & 15;
        return compute || type == 1 || type == 5;
    };
    std::vector<MaxwellBlock> found;
    for (size_t i = 0; i < codes.size(); ++i) {
        size_t p = codes[i], room = (i + 1 < codes.size() ? codes[i + 1] : data.size()) - p;
        std::vector<size_t> candidates;
        for (size_t c : controls) if (u32(c + 0x704) <= room && fits(p, c)) candidates.push_back(c);
        auto adjacent = std::find_if(candidates.begin(), candidates.end(), [&](size_t c) { return p >= c && p - c == 0x850; });
        if (adjacent != candidates.end()) candidates = {*adjacent};
        std::set<std::string> keys;
        for (size_t c : candidates) keys.insert(std::string(reinterpret_cast<const char*>(data.data() + c + 0x6f8), 0x14) +
                                               std::string(reinterpret_cast<const char*>(data.data() + c + 0x748), 0x10));
        // Ambiguous associations are deferred to first use, when the game supplies the exact control.
        if (keys.size() == 1) found.push_back({p, candidates[0], u32(candidates[0] + 0x704)});
    }
    for (size_t c : controls) {
        size_t p = c + 0x850, n = u32(c + 0x704), plen = u32(c + 0x6f8);
        if (u32(c + 0x708) || !u32(c + 0x748) || !in(p, n) || plen < 8 || plen > n) continue;
        if (u32(p) == 0x12345678 || u32(p) == 0x12345679 || !has_exit(p, p + plen)) continue;
        found.push_back({p, c, uint32_t(n)});
    }
    return found;
}
}
