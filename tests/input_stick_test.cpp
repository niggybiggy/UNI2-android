#include "input_stick.h"

#include <cassert>
#include <cmath>
#include <limits>

using namespace nx::plat;

double length(StickPosition p) { return std::hypot(double(p.x), double(p.y)); }

int main() {
    const double pi = std::acos(-1.0);
    for (int angle = 0; angle < 360; ++angle) {
        const float x = float(std::cos(angle * pi / 180));
        const float y = float(std::sin(angle * pi / 180));
        for (float deadzone : {0.f, 0.12f}) {
            const StickPosition full = radial_stick(x, y, deadzone, 0.98f);
            assert(std::abs(length(full) - kStickMax) <= 1);
            assert(std::abs(double(full.x) * y - double(full.y) * x) <= 1);
            const StickPosition edge = radial_stick(x * 0.99f, y * 0.99f, deadzone, 0.98f);
            assert(std::abs(length(edge) - kStickMax) <= 1);
            double previous = 0;
            for (int step = 0; step <= 98; ++step) {
                const float radius = float(step) / 100;
                const double current = length(radial_stick(x * radius, y * radius, deadzone, 0.98f));
                assert(current + 1 >= previous);
                assert(current <= kStickMax + 1);
                if (radius < 0.7f) assert(current < kStickMax * 0.8);
                previous = current;
            }
        }
    }
    assert(length(radial_stick(0, 0, 0, 0.98f)) == 0);
    assert(length(radial_stick(0.05f, 0.05f, 0.12f, 0.98f)) == 0);
    assert(length(radial_stick(0.1f, 0.1f, 0.12f, 0.98f)) > 0);
    assert(std::abs(length(radial_stick(0.49f, 0, 0, 0.98f)) - kStickMax * 0.5) <= 1);
    assert(length(radial_stick(std::numeric_limits<float>::quiet_NaN(), 1, 0.12f, 0.98f)) == 0);
    assert(length(radial_stick(1, std::numeric_limits<float>::infinity(), 0.12f, 0.98f)) == 0);
    const auto far = radial_stick(10, -7, 0, 0.98f);
    assert(std::abs(length(far) - kStickMax) <= 1);

    // The old per-axis 12% deadzone cut a circular stick's full diagonal down to about 94%.
    const float diagonal = float(std::sqrt(0.5));
    const double previous = std::hypot((diagonal - 0.12) / 0.88, (diagonal - 0.12) / 0.88);
    assert(previous < 0.95);
    assert(length(radial_stick(diagonal, diagonal, 0.12f, 0.98f)) > kStickMax * 0.999);

    // P4G external gamepad: a radial deadzone, configurable early full scale, and real-time hysteresis.
    auto at = [](uint64_t ms) { return ms * 1'000'000; };
    auto feed = [&](GamepadRunStick& stick, float x, float y, uint64_t ms, float full = 0.8f) {
        stick.update(x, y, x, y, at(ms), full);
        return stick.sample(at(ms));
    };
    GamepadRunStick stick;
    assert(length(feed(stick, .05f, .05f, 0)) == 0);
    assert(length(feed(stick, .10f, 0, 1)) == 0);
    const double slow = length(feed(stick, .30f, 0, 2));
    const double fast = length(feed(stick, .50f, 0, 3));
    assert(slow > 0 && fast > slow && fast < kStickMax * .7 && !stick.running());
    for (int angle = 0; angle < 360; ++angle) {
        stick.reset();
        const float x = float(std::cos(angle * pi / 180));
        const float y = float(std::sin(angle * pi / 180));
        auto full = feed(stick, x * .81f, y * .81f, 0);
        assert(length(full) >= kStickMax - 1 && stick.running());
        assert(std::abs(double(full.x) * y - double(full.y) * x) <= 1);
        // Include the unchanged HID circular clamp's rounding.
        if (length(full) > kStickMax) {
            const double n = length(full);
            full.x = int32_t(double(full.x) * kStickMax / n);
            full.y = int32_t(double(full.y) * kStickMax / n);
        }
        assert(length(full) >= kStickMax - 2);
    }
    stick.reset();
    assert(length(feed(stick, .8f, 0, 0)) >= kStickMax - 1);
    assert(length(feed(stick, .60f, 0, 10)) >= kStickMax - 1);
    assert(length(stick.sample(at(10'000))) >= kStickMax - 1); // hysteresis, no artificial timeout at held tilt
    assert(length(feed(stick, .40f, 0, 10'010)) >= kStickMax - 1);
    assert(length(feed(stick, .45f, 0, 10'060)) >= kStickMax - 1); // low packets do not refresh the timer
    assert(length(stick.sample(at(10'109))) >= kStickMax - 1);
    assert(length(stick.sample(at(10'110))) < kStickMax * .6 && !stick.running());
    assert(length(feed(stick, .60f, 0, 10'120)) < kStickMax * .8); // partial movement does not re-arm
    assert(length(feed(stick, .80f, 0, 10'130)) >= kStickMax - 1);
    assert(length(feed(stick, .40f, 0, 10'140)) >= kStickMax - 1);
    assert(length(feed(stick, .60f, 0, 10'180)) >= kStickMax - 1); // recovery cancels pending release
    assert(length(stick.sample(at(11'000))) >= kStickMax - 1);

    // Current neutral must stop immediately, with no drift from the run latch or old batch peaks.
    feed(stick, 0, 0, 11'010);
    assert(length(stick.sample(at(11'010))) == 0 && !stick.running());
    assert(length(stick.sample(at(11'109))) == 0);
    assert(length(stick.sample(at(11'110))) == 0 && !stick.running());
    assert(length(stick.sample(at(120'000))) == 0);

    // Largest history radius supplies strength, while the latest sample supplies direction.
    stick.reset();
    stick.update(0, .4f, 1, 0, at(0), .8f);
    auto p = stick.sample(at(0));
    assert(p.x == 0 && p.y == kStickMax);
    assert(length(stick.sample(at(100))) < kStickMax * .5 && !stick.running());
    stick.reset();
    stick.update(0, .4f, .7f, 0, at(0), .8f);
    p = stick.sample(at(0));
    assert(p.x == 0 && p.y > kStickMax * .8 && !stick.running());
    assert(length(stick.sample(at(100))) < kStickMax * .5); // sub-full history also expires
    stick.reset();
    stick.update(0, 0, 0, 1, at(0), .8f);
    assert(length(stick.sample(at(0))) == 0 && !stick.running());
    assert(length(stick.sample(at(100))) == 0);

    stick.reset();
    assert(length(feed(stick, .81f, 0, 0, .9f)) < kStickMax && !stick.running());
    assert(length(feed(stick, .91f, 0, 1, .9f)) >= kStickMax - 1 && stick.running());
    stick.update(std::numeric_limits<float>::quiet_NaN(), 1, 1, 1, at(2), .8f);
    assert(length(stick.sample(at(2))) == 0 && !stick.running());
    assert(length(feed(stick, 1, 0, 3)) >= kStickMax - 1);
    stick.reset();
    assert(length(stick.sample(at(4))) == 0);

    // Two-minute full-tilt circles, with 80 ms raw troughs, across the reported frame-rate range.
    for (int fps : {24, 30, 59, 60}) {
        stick.reset();
        for (uint64_t ms = 0; ms < 120'000; ++ms) {
            const double angle = double(ms) * pi / 2000;
            const float r = ms % 500 >= 400 && ms % 500 < 480 ? .4f : .9f;
            if (ms % 5 == 0) feed(stick, float(r * std::cos(angle)), float(r * std::sin(angle)), ms);
            if ((ms * uint64_t(fps)) / 1000 != ((ms ? ms - 1 : 0) * uint64_t(fps)) / 1000)
                assert(length(stick.sample(at(ms))) >= kStickMax - 1);
        }
        feed(stick, 0, 0, 120'000);
        assert(length(stick.sample(at(120'100))) == 0);
    }

    // Precision/overshoot equivalence: all these radii already saturate, including a precise .95 input.
    for (float r : {.80f, .95f, 1.0f, 1.14f}) {
        for (int angle = 0; angle < 360; ++angle) {
            stick.reset();
            const float x = float(std::cos(angle * pi / 180));
            const float y = float(std::sin(angle * pi / 180));
            // Use .801 at the floating-point boundary so normalization noise cannot lower an exact .80 test vector.
            const float edge = r == .80f ? .801f : r;
            assert(length(feed(stick, edge*x, edge*y, 0)) >= kStickMax-1);
            stick.update(.01f, -.01f, x, y, at(1), .8f);
            assert(length(stick.sample(at(1))) == 0 && !stick.running());
        }
    }

}
