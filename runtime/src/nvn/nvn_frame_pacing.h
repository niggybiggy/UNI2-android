#pragma once

#include <cstdint>

namespace nx::nvn {

// The launcher's 60 setting means game default; lower values are explicit host caps.
// An explicit cap also applies to interval zero and diagnostic unpaced mode.
constexpr uint64_t present_period_ns(int interval, uint64_t display_period, bool unpaced, int cap_fps) {
    const uint64_t game_period = !unpaced && interval > 0 ? uint64_t(interval) * display_period : 0;
    const uint64_t cap_period = cap_fps > 0 && cap_fps < 60
            ? (1'000'000'000ull + uint64_t(cap_fps) - 1) / uint64_t(cap_fps) : 0;
    return cap_period && game_period < cap_period ? cap_period : game_period;
}

class PresentPacer {
    uint64_t last_ = 0;
    bool started_ = false;
public:
    uint64_t deadline(uint64_t now, uint64_t period) {
        if (!period) { started_ = false; return now; }
        return started_ ? last_ + period : now;
    }
    void presented(uint64_t now) { last_ = now; started_ = true; }
};

} // namespace nx::nvn
