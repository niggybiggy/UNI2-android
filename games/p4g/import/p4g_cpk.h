#pragma once

#include "nxgame.h"

#include <cstdint>
#include <string>
#include <vector>

namespace p4g {

struct CpkFile {
    std::string path;
    uint64_t offset = 0;
    uint64_t size = 0;
    uint64_t extract_size = 0;
};

// Lista os seis pacotes GXP e seus offsets absolutos no data.cpk.
bool list_cpk_files(
    const nxi::GameImage& game,
    const nxi::GameImage::Entry& cpk,
    std::vector<CpkFile>& files,
    std::string& error
);

// Le e descomprime um pacote (na memoria) respeitando limites.
bool read_cpk_file(
    const nxi::GameImage& game,
    const nxi::GameImage::Entry& cpk,
    const CpkFile& file,
    std::vector<uint8_t>& output,
    std::string& error
);

} // namespace p4g
