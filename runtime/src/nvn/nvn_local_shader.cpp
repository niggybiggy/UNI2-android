#include "nvn_local_shader.h"
#include "nvn_shaders.h"
#include <dlfcn.h>
#include <filesystem>
#include <fstream>
#include <mutex>
#include <vector>
#include <cstring>
#include <functional>
#include <pthread.h>

namespace nx::nvn::local {
namespace {
using Convert = int (*)(const uint8_t*, int, const uint8_t*, int, const char*, const char*, char*, int);
std::mutex lock;
Convert translator = nullptr;
// NativeAOT libraries must stay loaded for the lifetime of the process.
void* handle = nullptr;
bool worker(const std::function<bool()>& fn, std::string& error) {
    // NVN callbacks may be running on an NCE guest stack. NativeAOT must attach to
    // a real host thread with a known stack; give its recursive compiler 8 MiB.
    struct Task { const std::function<bool()>& fn; std::string& error; bool result = false; } task{fn, error};
    pthread_attr_t attr;
    if (pthread_attr_init(&attr)) { error = "Cannot initialize shader worker"; return false; }
    int setup = pthread_attr_setstacksize(&attr, 8 * 1024 * 1024);
    pthread_t thread{};
    int rc = setup ? setup : pthread_create(&thread, &attr, [](void* p) -> void* {
        auto& t = *static_cast<Task*>(p);
        try { t.result = t.fn(); }
        catch (const std::exception& e) { t.error = e.what(); }
        catch (...) { t.error = "Unexpected shader worker failure"; }
        return nullptr;
    }, &task);
    pthread_attr_destroy(&attr);
    if (rc) { error = "Cannot start shader worker: " + std::to_string(rc); return false; }
    pthread_join(thread, nullptr);
    return task.result;
}
bool load(std::string& error) {
    if (translator) return true;
    if (!handle) handle = dlopen("libspirvharness.so", RTLD_NOW | RTLD_LOCAL);
    if (!handle) { const char* e = dlerror(); error = e ? e : "Cannot load Maxwell translator"; return false; }
    auto version = reinterpret_cast<int (*)()>(dlsym(handle, "spirv_harness_api_version"));
    auto fn = reinterpret_cast<Convert>(dlsym(handle, "spirv_harness_translate"));
    if (!version || !fn || version() != 1) { error = "Incompatible Maxwell translator"; return false; }
    translator = fn;
    return true;
}
uint32_t word(const uint8_t* p) { uint32_t v; std::memcpy(&v, p, 4); return v; }
}

bool available(std::string& error) {
    std::lock_guard guard(lock);
    return worker([&] { return load(error); }, error);
}

bool valid(const std::string& dir, const std::string& hash) {
    if (hash.size() != 16 || hash.find_first_not_of("0123456789abcdef") != std::string::npos) return false;
    std::ifstream resources(dir + "/" + hash + ".resources", std::ios::binary);
    if (!resources) return false; // Empty is valid for a shader with no descriptors.
    std::ifstream f(dir + "/" + hash + ".spv", std::ios::binary | std::ios::ate);
    if (!f) return false;
    auto n = f.tellg();
    if (n < 20 || n > 32 * 1024 * 1024 || n % 4) return false;
    std::vector<uint8_t> data(static_cast<size_t>(n));
    f.seekg(0);
    if (!f.read(reinterpret_cast<char*>(data.data()), n) || word(data.data()) != 0x07230203 ||
        !word(data.data() + 12) || word(data.data() + 16)) return false;
    size_t at = 5, words = data.size() / 4;
    while (at < words) {
        size_t count = word(data.data() + at * 4) >> 16;
        if (!count || count > words - at) return false;
        at += count;
    }
    return at == words;
}

bool compile(const std::string& dir, const uint8_t* code, uint32_t size, const uint8_t* ctrl,
             size_t ctrl_size, std::string& hash, std::string& error) {
    std::lock_guard guard(lock);
    return worker([&] {
    if (!code || !ctrl || size < 8 || size > 16u * 1024 * 1024 || ctrl_size < 0x758 || ctrl_size > 0x880) {
        error = "Invalid Maxwell shader length"; return false;
    }
    if (!load(error)) return false;
    hash = shader_hash(code, size);
    std::error_code ec;
    std::filesystem::create_directories(dir, ec);
    if (ec) { error = "Cannot create shader cache: " + ec.message(); return false; }
    char message[2048] = {};
    const std::string output = dir + "/" + hash + ".spv", metadata = dir + "/" + hash + ".resources";
    if (translator(code, int(size), ctrl, int(ctrl_size), output.c_str(), metadata.c_str(), message, sizeof message)) {
        error = message[0] ? message : "Maxwell translation failed"; return false;
    }
    if (!valid(dir, hash)) { error = "Generated shader failed structural validation"; return false; }
    // Keep the familiar TSV for inspection. Sidecars are authoritative: unlike an append,
    // they are atomically committed alongside each module, including first-use modules.
    const std::string table = dir + "/results.tsv";
    bool header = !std::filesystem::exists(table);
    std::ifstream r(metadata, std::ios::binary);
    std::string resources((std::istreambuf_iterator<char>(r)), {});
    std::ofstream t(table, std::ios::app);
    if (header) t << "hash\tresources\n";
    t << hash << '\t' << resources << '\n';
    t.flush();
    if (!t) { error = "Cannot save generated resource mappings"; return false; }
    return true;
    }, error);
}

bool probe(const std::string& dir, std::string& error) {
    // Original no-op compute program: scheduler word followed by EXIT. Tests the actual
    // translator and disk writes on this device even when ExeFS has no embedded shaders.
    uint8_t code[16] = {}, ctrl[0x880] = {};
    const uint64_t exit = 0xe30000000007000full;
    const uint32_t magic = 0x98761234, size = sizeof code, one = 1;
    std::memcpy(code + 8, &exit, 8);
    std::memcpy(ctrl, &magic, 4);
    std::memcpy(ctrl + 0x704, &size, 4);
    for (size_t off : {0x748u, 0x74cu, 0x750u}) std::memcpy(ctrl + off, &one, 4);
    std::string hash;
    return compile(dir, code, sizeof code, ctrl, sizeof ctrl, hash, error);
}
}
