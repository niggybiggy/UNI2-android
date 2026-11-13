#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <atomic>
#include <cctype>
#include <cstring>
#include <vector>
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

// UNI2 keeps its NVN shaders in RomFS Shader/*.bnsh archives (88 files): each holds control sections and Maxwell code
// blocks, found by the same magics as the programs built into the ExeFS modules (p4g::maxwell_blocks).
std::string lower(std::string s) {
    for (char& c : s) c = char(std::tolower(static_cast<unsigned char>(c)));
    return s;
}
std::vector<std::filesystem::path> bnsh_files(const std::filesystem::path& romfs) {
    namespace fs = std::filesystem;
    std::vector<fs::path> out;
    std::error_code ec;
    for (const auto& top : fs::directory_iterator(romfs, ec)) {
        if (!top.is_directory(ec) || lower(top.path().filename().string()) != "shader") continue;
        for (fs::recursive_directory_iterator it(top.path(), ec), end; !ec && it != end; it.increment(ec))
            if (it->is_regular_file(ec) && lower(it->path().extension().string()) == ".bnsh") out.push_back(it->path());
    }
    std::sort(out.begin(), out.end());
    return out;
}
// The whole RomFS as "path<TAB>size" lines (and the ExeFS modules), to see where the game keeps its files.
void write_listing(const std::filesystem::path& root, const std::string& title, const std::string& build) {
    namespace fs = std::filesystem;
    std::ofstream f(root / "romfs_listing.txt", std::ios::binary | std::ios::trunc);
    if (!f) return;
    f << "title\t" << title << "\nbuild\t" << build << "\n";
    std::error_code ec;
    for (const auto& e : fs::directory_iterator(root / "exefs", ec))
        if (e.is_regular_file(ec)) f << "exefs/" << e.path().filename().string() << "\t" << e.file_size(ec) << "\n";
    const fs::path romfs = root / "extract" / "romfs";
    long n = 0;
    for (fs::recursive_directory_iterator it(romfs, ec), end; !ec && it != end; it.increment(ec)) {
        if (!it->is_regular_file(ec)) continue;
        if (n++ < 300000) f << fs::relative(it->path(), romfs, ec).generic_string() << "\t" << it->file_size(ec) << "\n";
    }
    f << "files\t" << n << "\n";
}
int run(int fd, const std::string& name, int update_fd, const std::string& update_name,
        const std::string& keys, const std::string& title_keys, const std::string& data_dir) {
    std::unique_lock guard(run_lock, std::try_to_lock);
    if (!guard.owns_lock()) return 1;
    phase = 1; done = 0; total = 0; set_message("");
    namespace fs = std::filesystem;
    fs::path staging;
    std::string build_id;
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
        if ((game->title_id() & ~0xfffull) != 0x0100BD401D18A000ull)
            return fail("Title ID does not match UNDER NIGHT IN-BIRTH II Sys:Celes (0100BD401D18A000)");
        if (!game->exefs().count("main")) return fail("The game has no main executable");
        if (!nx::nvn::local::available(error)) return fail(error);

        fs::create_directories(root);
        // The caller provides a private staging directory. Extract with the existing checked,
        // streaming importer; no game image or keys are required by the installed runtime.
        nxi::Options options;
        options.out_dir = root.string();
        options.title_id = 0x0100BD401D18A000ull;
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
        {
            std::string build;  // main's Build ID (NSO header +0x40), reported only: any version is accepted
            std::ifstream main_file(root / "exefs/main", std::ios::binary);
            char id[8] = {};
            if (main_file.seekg(0x40) && main_file.read(id, 8)) {
                static const char* kHex = "0123456789ABCDEF";
                for (char c : id) build += kHex[(c >> 4) & 15], build += kHex[c & 15];
            }
            write_listing(root, "0100BD401D18A000", build);
            build_id = build;
        }
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
        // RomFS Shader/*.bnsh. A shader that does not translate here is not fatal: the renderer translates what is
        // missing at first use, so it is only counted (and written to import.txt).
        size_t bnsh_found = 0, bnsh_failed = 0, exefs_found = hashes.size();
        std::string first_failure;
        {
            const std::vector<fs::path> files = bnsh_files(root / "extract" / "romfs");
            phase = 3; total = int(files.size()); done = 0;
            for (const fs::path& file : files) {
                check_cancel();
                std::ifstream input(file, std::ios::binary | std::ios::ate);
                std::streamsize size = input ? std::streamsize(input.tellg()) : -1;
                if (size <= 0 || uint64_t(size) > (1ull << 31)) { ++done; continue; }
                std::vector<uint8_t> raw(static_cast<size_t>(size));
                input.seekg(0);
                if (!input.read(reinterpret_cast<char*>(raw.data()), size)) { ++done; continue; }
                for (const auto& block : p4g::maxwell_blocks(raw)) {
                    check_cancel();
                    std::string hash = nx::nvn::shader_hash(raw.data() + block.code, block.size);
                    if (hashes.count(hash)) continue;
                    std::string compile_error;
                    if (nx::nvn::local::compile(staging.string(), raw.data() + block.code, block.size,
                            raw.data() + block.control, 0x880, hash, compile_error)) {
                        hashes.insert(hash);
                        ++bnsh_found;
                    } else if (++bnsh_failed == 1) {
                        first_failure = file.filename().string() + " " + hash + ": " + compile_error;
                    }
                }
                ++done;
            }
        }
        {
            std::string note = "build\t" + build_id + "\nshaders\t" + std::to_string(hashes.size()) + "\nfrom_exefs\t" +
                               std::to_string(exefs_found) + "\nfrom_bnsh\t" + std::to_string(bnsh_found) +
                               "\nbnsh_failed\t" + std::to_string(bnsh_failed) + "\n";
            if (!first_failure.empty()) note += "first_bnsh_failure\t" + first_failure + "\n";
            write(staging / "import.txt", note);
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
