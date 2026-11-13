#pragma once

#include <algorithm>
#include <cmath>

#include "platform.h"

namespace nx::plat {

struct StickPosition {
    int32_t x = 0, y = 0;
};

// Apply the deadzone to the stick's radius, preserving its direction. An axial deadzone shrinks a circular
// controller's full diagonal below full strength. The outer radius allows a small tolerance at full deflection.
inline StickPosition radial_stick(float x, float y, float deadzone, float outer) {
    if (!std::isfinite(x) || !std::isfinite(y)) return {};
    const double length = std::hypot(double(x), double(y));
    if (length <= deadzone) return {};
    const double strength = std::clamp((length - deadzone) / (outer - deadzone), 0.0, 1.0);
    const double scale = strength * kStickMax / length;
    return {int32_t(std::lround(double(x) * scale)), int32_t(std::lround(double(y) * scale))};
}

// Only the P4G Android gamepad path uses this filter. Poll sample() as well as update() to expire a non-neutral
// low-input grace even without another event. A current neutral sample always stops immediately.
class GamepadRunStick {
public:
    static constexpr float kDeadzone = 0.10f, kRelease = 0.55f;
    static constexpr uint64_t kHoldNs = 100'000'000;

    void reset() { *this = GamepadRunStick{}; }

    // The batch peak controls strength/latching; current x/y controls direction, so an older historical peak
    // cannot turn the player back. Current neutral overrides historical peaks, avoiding release drift.
    void update(float x, float y, float peak_x, float peak_y, uint64_t now, float full) {
        if (!std::isfinite(x) || !std::isfinite(y)) { reset(); return; }
        full_ = std::isfinite(full) ? std::clamp(full, 0.60f, 1.0f) : 0.80f;
        radius_ = std::hypot(double(x), double(y));
        if (radius_ <= kDeadzone) { reset(); return; }
        const double peak = std::isfinite(peak_x) && std::isfinite(peak_y)
                ? std::hypot(double(peak_x), double(peak_y)) : 0;
        batch_peak_ = std::max(radius_, peak);
        batch_at_ = now;
        dx_ = x / radius_, dy_ = y / radius_;
        if (batch_peak_ >= full_) running_ = true, below_ = false;
        if (running_) {
            if (radius_ >= kRelease) below_ = false;
            else if (!below_) below_ = true, below_since_ = now;
        }
    }

    StickPosition sample(uint64_t now) {
        if (running_ && below_ && now >= below_since_ && now - below_since_ >= kHoldNs) running_ = false;
        const double radius = now >= batch_at_ && now - batch_at_ < kHoldNs ? batch_peak_ : radius_;
        const double strength = running_ ? 1.0 : std::clamp((radius - kDeadzone) / (full_ - kDeadzone), 0.0, 1.0);
        return {int32_t(std::lround(dx_ * strength * kStickMax)),
                int32_t(std::lround(dy_ * strength * kStickMax))};
    }

    bool running() const { return running_; }
    double batch_peak() const { return batch_peak_; }

private:
    float full_ = 0.80f;
    double radius_ = 0, batch_peak_ = 0, dx_ = 0, dy_ = 0;
    uint64_t below_since_ = 0, batch_at_ = 0;
    bool running_ = false, below_ = false;
};

}  // namespace nx::plat
