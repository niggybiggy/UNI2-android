#pragma once

#include <cstdint>

namespace nx::nvn {

// NVN window coordinates and image rows share the selected origin. Do not
// reflect scissors, clears or texture coordinates a second time: framebuffer
// row zero is the logical bottom row in lower-left mode, and the top row in
// upper-left mode. Only presentation converts those rows to an upright image.
bool window_origin_lower_left();

struct OriginViewport {
    float y, height;
};

constexpr OriginViewport origin_viewport(bool lower_left, float y, float height) {
    return lower_left ? OriginViewport{y, height} : OriginViewport{y + height, -height};
}

// PipelineKey stores the normalized Vulkan winding, so both modes have distinct
// cache keys without reading mutable device state on pipeline worker threads.
constexpr uint32_t origin_front_face(bool lower_left, uint32_t nvn_front) {
    return uint32_t(nvn_front != 0) ^ uint32_t(lower_left);
}

} // namespace nx::nvn
