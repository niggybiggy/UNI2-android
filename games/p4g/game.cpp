// UNDER NIGHT IN-BIRTH II Sys:Celes (0100BD401D18A000, base game) on the P4G Android port's runtime and app.
//
// The internal id stays "p4g" on purpose: the CMake target (libp4g.so), the log tag (adb logcat -s p4g:V), the Java
// package (io.github.p4grecomp), the installed-data mode and the touch layout table (runtime/src/touch_layout.cpp)
// are keyed by it. Rename once the build is proven.
//
// Only what UNI2 needs lives here: P4G's own hooks are not here (they pointed at offsets inside P4G's main). UNI2's
// own hooks are added as the boot logs show what it needs.
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

#include <nx/game.h>
#include <nx/runtime.h>

namespace nx {

const game::Info game::kInfo = {
    "p4g",
    "Under Night In-Birth II",
    0x0100BD401D18A000ull,
    "io.github.p4grecomp",
    "private/p4g",
    0,        // main.npdm system resource size: unknown, 0 until a log says
    nullptr   // main build ID: any version is accepted for now
};

namespace {

// glslc, NVN's run-time GLSL compiler. Initialize fails, so nothing is ever compiled at run time: if UNI2 calls
// it, the log line below says so.
void glslc_fail(CPU& c) {
    log("uni2: %s called: run-time shader compilation is not supported", hle_current_name());
    c.x[0] = 0;
}

void glslc_nop(CPU& c) { c.x[0] = 0; }

// Engines format their assertion/fatal messages and fputs them to stderr, which goes nowhere on this runtime. Copy
// every fputs line into the log, then do the real one. Useful for reading why the game stopped.
void fputs_logged(CPU& c) {
    static const uint64_t real = find_symbol("fputs");
    const char* s = reinterpret_cast<const char*>(c.x[0]);
    if (s) {
        std::string line(s, strnlen(s, 1024));
        while (!line.empty() && (line.back() == '\n' || line.back() == '\r')) line.pop_back();
        log("[guest fputs] %s", line.c_str());
    }
    if (real) call(c, real);
    else c.x[0] = 0;
}

// nn::fs::Commit(const char*) after saving. The game's call reached the SDK's own copy, which looks the mount up in
// the SDK's mount table (the runtime's save: mount is not in it) and aborts with fs 2-6905 when "Continue" is
// pressed. The files are written straight to the save folder, so there is nothing to commit.
void commit_ok(CPU& c) { c.x[0] = 0; }

// movie::Extractor (Nintendo's movie library, not part of the sdk module) is unresolved here: its constructor did
// nothing and the game then called a virtual function on garbage and crashed (the opening movie, Movie/UNI_OP.mp4).
// Make the object an empty one: its virtual table is all zero-returning functions, which log where they are called
// from. There is no video decoder behind it: this only stops the crash, the movie itself is not played.
void movie_zero(CPU& c) {
    static int n = 0;
    if (n++ < 30) log("uni2: movie call from %s (empty movie, returns 0)", describe(c.x[30]).c_str());
    c.x[0] = 0;
}

void movie_extractor_ctor(CPU& c) {  // x0 = this
    static uint64_t table[64];
    const uint64_t zero = hle_address("uni2_movie_zero");
    for (uint64_t& e : table) e = zero;
    *reinterpret_cast<uint64_t*>(c.x[0]) = reinterpret_cast<uint64_t>(table);
    log("uni2: movie::Extractor created at %llx: empty, no video decoder", (unsigned long long)c.x[0]);
}

}  // namespace

NX_HLE("uni2_movie_zero", movie_zero);
NX_HLE("_ZN5movie9ExtractorC1ENS_13ContainerTypeE", movie_extractor_ctor);
NX_HLE("_ZN5movie9ExtractorC2ENS_13ContainerTypeE", movie_extractor_ctor);
NX_HLE("fputs", fputs_logged);

NX_HLE("glslcInitialize", glslc_fail);
NX_HLE("glslcCompile", glslc_fail);
NX_HLE("glslcCompilePreSpecialized", glslc_fail);
NX_HLE("glslcCompileSpecialized", glslc_fail);
NX_HLE("glslcFinalize", glslc_nop);
NX_HLE("glslcSetAllocator", glslc_nop);
NX_HLE("glslcGetDefaultOptions", glslc_nop);
NX_HLE("glslcGetVersion", glslc_nop);

void game::after_init() {
    auto default_setting = [](const char* name, const char* value) {
        if (!std::getenv(name)) setenv(name, value, 0);
    };
    // This port has no video decoder for UNI2: the game's .mp4 files look missing, so it skips them (the opening
    // movie). Every setting below can be overridden from config.txt (NX_SKIP_MOVIES=0 plays the movies).
    default_setting("NX_SKIP_MOVIES", "1");
#if defined(__ANDROID__)
    // The P4G port's Android profile (pipeline prewarm and thread affinity), kept as the starting point.
    default_setting("NX_PIPE_THREADS", "2");
    default_setting("NX_PIPE_PREWARM", "1");
    default_setting("NX_PIPE_PREWARM_BUDGET_US", "1000");
    default_setting("NX_PIPE_PREWARM_INFLIGHT", "32");
    default_setting("NX_PIPE_PREWARM_SCAN", "64");
    default_setting("NX_AFFINITY", "2");
    default_setting("NX_VERTEX_UPLOAD_RANGES", "0");
#endif
    log("uni2: build 10 (on the P4G runtime)");  // tells which build a log came from
    // Every public nn::fs function with "Commit" in its name (the overloads too, whatever their types): all succeed.
    int hooked = 0;
    for (const auto& [name, addr] : find_symbols("_ZN2nn2fs", "Commit")) {
        // Not the internals, and not fsa::Register (it only mentions IMultiCommitTarget: it registers file systems).
        if (name.find("6detail") != std::string::npos || name.find("8Register") != std::string::npos) continue;
        if (hook_function(addr, commit_ok)) {
            hooked++;
            log("uni2: hooked %s (always succeeds)", name.c_str());
        } else {
            log("uni2: could not hook %s", name.c_str());
        }
    }
    if (!hooked) log("uni2: no nn::fs Commit function found");
}

void game::renderer_start() { log("uni2: renderer started"); }

void game::first_frame() { log("uni2: first frame presented"); }

}  // namespace nx
