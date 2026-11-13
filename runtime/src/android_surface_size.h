#pragma once

#include <algorithm>
#include <cstdint>

namespace nx::plat {

struct AndroidSurfaceSize { uint32_t width, height; };

// The compositor fills the native window with the buffer. Its aspect must
// therefore follow that window, not the game's 16:9 render target. Keep the
// chosen short-side resolution; the present pass handles fit versus stretch.
inline AndroidSurfaceSize android_surface_size(uint32_t screen_w, uint32_t screen_h,
                                               uint32_t render_w, uint32_t render_h) {
    if (!screen_w || !screen_h || !render_w || !render_h) return {0, 0};
    const uint32_t screen_short = std::min(screen_w, screen_h);
    const uint32_t short_side = std::max(2u, std::min(render_w, render_h) & ~1u);
    const uint64_t scaled = (uint64_t(std::max(screen_w, screen_h)) * short_side + screen_short / 2) / screen_short;
    const uint32_t long_side = std::max(2u, uint32_t(scaled) & ~1u);
    return screen_w >= screen_h ? AndroidSurfaceSize{long_side, short_side}
                               : AndroidSurfaceSize{short_side, long_side};
}

} // namespace nx::plat
