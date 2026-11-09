#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <atomic>
#include <algorithm>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <mutex>
#include <set>
#include <stdexcept>
#include <string>
#include "nxgame.h"
#include "p4g_maxwell.h"
#include "nvn_local_shader.h"
#include "nvn_shaders.h"

namespace {
std::atomic<int> phase{0}, done{0}, total{0};
std::atomic<bool> cancelled{false};
std::atomic<uint64_t> bytes_done{0}, bytes_total{0};
void check_cancel() { if (cancelled.load()) throw std::runtime_error("Preparation cancelled"); }
std::mutex message_lock, run_lock;
std::string message;
void set_message(const std::string& text) { std::lock_guard guard(message_lock); message = text; }
int fail(const std::string& text) { set_message(text); phase = 0; return 1; }
std::string jstr(JNIEnv* env, jstring text) {
    if (!text) return {};
    const char* p = env->GetStringUTFChars(text, nullptr);
    if (!p) return {};
    std::string s(p); env->ReleaseStringUTFChars(text, p); return s;
}
bool write(const std::filesystem::path& path, const std::string& text) {
    std::ofstream f(path, std::ios::binary | std::ios::trunc);
    f << text; f.flush(); return bool(f);
}
struct OwnedFd {
    int value;
    ~OwnedFd() { if (value >= 0) close(value); }
};
int run(int fd, const std::string& name, int update_fd, const std::string& update_name,
        const std::string& keys, const std::string& title_keys, const std::string& data_dir) {
    std::unique_lock guard(run_lock, std::try_to_lock);
    if (!guard.owns_lock()) return 1;
    phase = 1; done = 0; total = 0; set_message("");
    namespace fs = std::filesystem;
    fs::path staging;
    try {
        check_cancel();
        if (fd < 0) return fail("Invalid game file");
        fs::path root(data_dir);
        if (!root.is_absolute() || root == root.root_path() || root.lexically_normal() != root)
            return fail("Invalid data directory");
        std::vector<nxi::InputFile> inputs{{name, fd}};
        if (update_fd >= 0) inputs.push_back({update_name, update_fd});
        OwnedFd k{open(keys.c_str(), O_RDONLY | O_CLOEXEC)};
        if (k.value < 0) return fail("prod.keys not found");
        inputs.push_back({"prod.keys", k.value});
        OwnedFd t{title_keys.empty() ? -1 : open(title_keys.c_str(), O_RDONLY | O_CLOEXEC)};
        if (t.value >= 0) inputs.push_back({"title.keys", t.value});
        std::string error;
        auto game = nxi::GameImage::open(inputs, 0, error);
        if (!game) return fail(error.empty() ? "Could not open the game" : error);
        if ((game->title_id() & ~0xfffull) != 0x010062B01525C000ull) return fail("Title ID does not match P4G");
        if (!game->exefs().count("main")) return fail("The game has no main executable");
        if (!nx::nvn::local::available(error)) return fail(error);

        fs::create_directories(root);
        // The caller provides a private staging directory. Extract with the existing checked,
        // streaming importer; no game image or keys are required by the installed runtime.
        nxi::Options options;
        options.out_dir = root.string();
        options.title_id = 0x010062B01525C000ull;
        options.keep_exefs = true;
        options.cancel = &cancelled;
        options.on_progress = [](const nxi::Progress& p) {
            if (p.phase == "romfs") { bytes_done = p.done; bytes_total = p.total; }
            phase = 6; total = 1000;
            const double share = p.total ? double(std::min(p.done, p.total)) / double(p.total) : 0;
            done = p.phase == "done" ? 1000 : p.phase == "romfs" ? 150 + int(850 * share) :
                   p.phase == "exefs" ? 50 + int(100 * share) : int(50 * share);
        };
        phase = 6; done = 0; total = 1000;
        if (nxi::run_import(inputs, options, error) != 0)
            return fail(error.empty() ? "Game extraction failed" : error);
        if (!fs::is_regular_file(root / "exefs/main") || !fs::is_regular_file(root / "exefs/sdk"))
            return fail("The extracted game is missing main or sdk");
        phase = 7;
        check_cancel();

        std::string suffix = std::to_string(std::chrono::system_clock::now().time_since_epoch().count());
        staging = root / ("spirv-preparing-" + suffix);
        if (!fs::create_directory(staging)) return fail("Could not create a fresh shader cache");
        // Probe is compiled on the device, then removed. It is never a bundled game shader.
        fs::path probe = staging / "compiler-test";
        if (!nx::nvn::local::probe(probe.string(), error)) throw std::runtime_error(error);
        fs::remove_all(probe);
        if (!write(staging / "results.tsv", "hash\tresources\n")) throw std::runtime_error("Cannot write resource table");
        std::set<std::string> hashes;
        phase = 2;
        std::vector<fs::path> modules;
        for (const auto& entry : fs::directory_iterator(root / "exefs"))
            if (entry.is_regular_file()) modules.push_back(entry.path());
        std::sort(modules.begin(), modules.end());
        total = int(modules.size()); done = 0;
        for (const fs::path& module_path : modules) {
            check_cancel();
            std::ifstream input(module_path, std::ios::binary | std::ios::ate);
            if (!input) throw std::runtime_error("Cannot read extracted module " + module_path.filename().string());
            std::streamsize size = input.tellg();
            if (size < 0 || uint64_t(size) > (1ull << 31)) throw std::runtime_error("Invalid extracted module size");
            std::vector<uint8_t> nso(static_cast<size_t>(size));
            input.seekg(0);
            if (size && !input.read(reinterpret_cast<char*>(nso.data()), size))
                throw std::runtime_error("Cannot read extracted module " + module_path.filename().string());
            if (nso.size() < 4 || std::memcmp(nso.data(), "NSO0", 4)) { ++done; continue; }
            std::vector<uint8_t> flat;
            if (!nxi::nso_to_flat_image(nso, flat, error))
                throw std::runtime_error("Invalid " + module_path.filename().string() + ": " + error);
            auto blocks = p4g::maxwell_blocks(flat);
            phase = 3;
            for (const auto& block : blocks) {
                check_cancel();
                std::string hash = nx::nvn::shader_hash(flat.data() + block.code, block.size);
                if (!hashes.insert(hash).second) continue;
                if (!nx::nvn::local::compile(staging.string(), flat.data() + block.code, block.size,
                        flat.data() + block.control, 0x880, hash, error))
                    throw std::runtime_error("Shader " + hash + ": " + error);
            }
            ++done; phase = 2;
        }
        phase = 4;
        check_cancel();
        std::string marker = std::string("format\t") + nx::nvn::local::kFormat + "\n";
        for (const auto& hash : hashes) {
            if (!nx::nvn::local::valid(staging.string(), hash)) throw std::runtime_error("Incomplete generated cache");
            marker += "seed\t" + hash + "\n";
        }
        if (!write(staging / "prepared.tsv", marker)) throw std::runtime_error("Cannot save preparation status");
        fs::path current = root / "spirv", backup = root / ("spirv-backup-" + suffix);
        bool had_cache = fs::exists(current);
        if (had_cache) fs::rename(current, backup);
        std::error_code commit_error;
        fs::rename(staging, current, commit_error);
        if (commit_error) {
            std::error_code restore_error;
            if (had_cache) fs::rename(backup, current, restore_error);
            throw std::runtime_error("Cannot install generated cache. Previous cache kept at " +
                                     (restore_error ? backup : current).string());
        }
        staging.clear();
        phase = 5;
        set_message("Preparation complete. Additional shaders will be generated on first use.");
        return 0;
    } catch (const std::exception& e) {
        // Only this invocation's private staging directory is removed; saves and old caches stay intact.
        if (!staging.empty()) { std::error_code ignored; fs::remove_all(staging, ignored); }
        return fail(e.what());
    }
}
}
extern "C" {
JNIEXPORT void JNICALL Java_io_github_p4grecomp_Importer_reset(JNIEnv*, jclass) {
    cancelled = false; bytes_done = 0; bytes_total = 0;
    phase = 0; done = 0; total = 0;
}
JNIEXPORT void JNICALL Java_io_github_p4grecomp_Importer_cancel(JNIEnv*, jclass) { cancelled = true; }
JNIEXPORT jlongArray JNICALL Java_io_github_p4grecomp_Importer_bytes(JNIEnv* env, jclass) {
    jlong values[] = {jlong(bytes_done.load()), jlong(bytes_total.load())};
    auto out = env->NewLongArray(2);
    if (out) env->SetLongArrayRegion(out, 0, 2, values);
    return out;
}
JNIEXPORT jint JNICALL Java_io_github_p4grecomp_Importer_run(JNIEnv* env, jclass, jint fd, jstring name,
        jint update_fd, jstring update_name, jstring keys, jstring title_keys, jstring data_dir, jstring, jint) {
    return run(fd, jstr(env, name), update_fd, jstr(env, update_name), jstr(env, keys),
            jstr(env, title_keys), jstr(env, data_dir));
}
JNIEXPORT jintArray JNICALL Java_io_github_p4grecomp_Importer_progress(JNIEnv* env, jclass) {
    jint values[] = {phase.load(), done.load(), total.load()};
    auto out = env->NewIntArray(3); if (out) env->SetIntArrayRegion(out, 0, 3, values); return out;
}
JNIEXPORT jstring JNICALL Java_io_github_p4grecomp_Importer_error(JNIEnv* env, jclass) {
    std::lock_guard guard(message_lock); return env->NewStringUTF(message.c_str());
}
}
