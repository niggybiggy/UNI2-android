
#include <nx/game.h>
#include <nx/runtime.h>
#include <cstdlib>

namespace nx {

const game::Info game::kInfo = {
    "p4g",
    "Persona 4 Golden",
    0x010062B01525C000ull,
    "io.github.p4grecomp",
    "private/p4g",
    0,
    "C433D980688380A6"
};

void game::after_init() {

#if defined(__ANDROID__)
    // P4G experimental native profile. Keep every switch user-overridable from config.txt:
    // platform_android imports that file into the environment before the guest starts.
    // The profile deliberately leaves NX_ASYNC_PIPELINES off; skipping a draw while a
    // pipeline compiles hides a hitch by showing an incomplete frame, which is not an
    // acceptable default for an official-style port.
    auto default_setting = [](const char* name, const char* value) {
        if (!std::getenv(name)) setenv(name, value, 0);
    };
    // Lighter than 0.4.5 (3 workers, 2 ms, 64 in flight, scan 128): the replay thread is the frame bottleneck.
    default_setting("NX_PIPE_THREADS", "2");
    default_setting("NX_PIPE_PREWARM", "1");
    default_setting("NX_PIPE_PREWARM_BUDGET_US", "1000");
    default_setting("NX_PIPE_PREWARM_INFLIGHT", "32");
    default_setting("NX_PIPE_PREWARM_SCAN", "64");
    default_setting("NX_AFFINITY", "2");
    log("p4g: experimental Android profile: pipeline prewarm 1 ms/submit, 32 in flight, 2 workers");
#endif

    log("p4g: runtime initialized");
    log("p4g: beginning game-specific initialization");
}

void game::renderer_start() {
    log("p4g: renderer started");
}

void game::first_frame() {
    log("p4g: first frame presented");
}

} // namespace nx
