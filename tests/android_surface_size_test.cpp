#include "android_surface_size.h"

#include <cassert>
#include <cmath>
#include <initializer_list>

using namespace nx::plat;

int main() {
    for (uint32_t height : {360u, 480u, 720u, 1080u}) {
        for (AndroidSurfaceSize screen : {AndroidSurfaceSize{2340, 1080}, {2400, 1080}, {1920, 1080},
                                          {1080, 2340}, {1600, 2560}}) {
            const auto size = android_surface_size(screen.width, screen.height, height * 16 / 9, height);
            assert(size.width && size.height && !(size.width % 2) && !(size.height % 2));
            assert(std::min(size.width, size.height) == height);
            // Rounding may lose less than two pixels on the long side.
            const double ideal = double(std::max(screen.width, screen.height)) * height /
                                 std::min(screen.width, screen.height);
            assert(std::abs(double(std::max(size.width, size.height)) - ideal) < 2.0);
            const double buffer_aspect = double(std::max(size.width, size.height)) / height;
            const double physical_aspect = double(std::max(screen.width, screen.height)) /
                                          std::min(screen.width, screen.height);
            // Reproduce fit in the present pass, then Android compositor scaling.
            const double box_width = std::min(1.0, (16.0 / 9.0) / buffer_aspect);
            const double box_height = std::min(1.0, buffer_aspect / (16.0 / 9.0));
            assert(std::abs(physical_aspect * box_width / box_height - 16.0 / 9.0) < 0.01);
            if (physical_aspect > 2.0) assert(box_width < 0.9); // Side bars when fit is selected.
            // Stretch uses the full box, hence the physical window aspect.
            assert(std::abs(buffer_aspect - physical_aspect) < 0.01);
        }
    }
    const auto landscape = android_surface_size(2340, 1080, 1280, 720);
    assert(landscape.width == 1560 && landscape.height == 720);
    const auto portrait = android_surface_size(1080, 2340, 1280, 720);
    assert(portrait.width == 720 && portrait.height == 1560);
    assert(android_surface_size(0, 1080, 1280, 720).width == 0);
    assert(android_surface_size(2340, 1080, 0, 720).height == 0);
}
