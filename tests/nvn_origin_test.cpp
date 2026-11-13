#include "nvn/nvn_origin.h"

#include <cassert>
#include <cmath>
#include <initializer_list>

using namespace nx::nvn;

static float map_y(OriginViewport v, float ndc) {
    return v.y + (ndc + 1.0f) * v.height * 0.5f;
}

int main() {
    for (float y : {0.0f, 37.0f}) for (float height : {113.0f, 540.0f, 1080.0f}) {
        const auto upper = origin_viewport(false, y, height);
        const auto lower = origin_viewport(true, y, height);
        assert(map_y(upper, 1) == y);
        assert(map_y(upper, -1) == y + height);
        assert(map_y(lower, -1) == y);
        assert(map_y(lower, 1) == y + height);
        for (float ndc : {-1.0f, -0.25f, 0.0f, 0.75f, 1.0f}) {
            const float u = (map_y(upper, ndc) - y) / height;
            const float l = (map_y(lower, ndc) - y) / height;
            // A lower-left texture's V=0 is its bottom row. The final flip
            // produces the same upright screen point as upper-left mode.
            assert(std::abs(u - (1.0f - l)) < 1e-5f);
        }
        // Window-space winding reverses with viewport sign. Normalizing the
        // front-face bit must preserve the classification for either NVN mode.
        for (uint32_t front : {0u, 1u}) {
            assert(origin_front_face(false, front) != origin_front_face(true, front));
            const float area_upper = upper.height;
            const float area_lower = lower.height;
            const float sign_upper = origin_front_face(false, front) ? 1.0f : -1.0f;
            const float sign_lower = origin_front_face(true, front) ? 1.0f : -1.0f;
            assert((area_upper * sign_upper > 0) == (area_lower * sign_lower > 0));
        }
    }
}
