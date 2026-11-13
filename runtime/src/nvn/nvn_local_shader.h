// Compile the player's Maxwell programs; this module contains no game code.
#pragma once
#include <cstddef>
#include <cstdint>
#include <string>

namespace nx::nvn::local {
inline constexpr const char* kFormat = "maxwell-local-v1-a82350bb77";
bool available(std::string& error);
// Fresh directory during preparation; on-demand calls are serialized by the renderer.
bool compile(const std::string& directory, const uint8_t* code, uint32_t size,
             const uint8_t* control, size_t control_size, std::string& hash, std::string& error);
bool valid(const std::string& directory, const std::string& hash);
bool probe(const std::string& directory, std::string& error);
}
