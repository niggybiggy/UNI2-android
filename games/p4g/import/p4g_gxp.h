
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace p4g {

struct GxpRecord {
    std::string name;
    size_t offset = 0;
    uint32_t size = 0;
};

inline uint32_t gxpRead32(
    const std::vector<uint8_t>& data,
    size_t pos
) {
    return uint32_t(data[pos])
         | (uint32_t(data[pos + 1]) << 8)
         | (uint32_t(data[pos + 2]) << 16)
         | (uint32_t(data[pos + 3]) << 24);
}

inline bool gxpIsHex(uint8_t c) {
    return (c >= '0' && c <= '9') ||
           (c >= 'a' && c <= 'f') ||
           (c >= 'A' && c <= 'F');
}

inline bool gxpIsName(
    const std::vector<uint8_t>& data,
    size_t pos
) {
    if (pos > data.size() ||
        data.size() - pos < 36)
        return false;

    if (data[pos] != 'F' && data[pos] != 'V')
        return false;

    for (size_t i = 1; i <= 8; ++i)
        if (!gxpIsHex(data[pos + i]))
            return false;

    if (data[pos + 9] != '_')
        return false;

    for (size_t i = 10; i <= 17; ++i)
        if (!gxpIsHex(data[pos + i]))
            return false;

    if (data[pos + 18] != '.' ||
        data[pos + 19] != 'g' ||
        data[pos + 20] != 'x' ||
        data[pos + 21] != 'p' ||
        data[pos + 22] != 0)
        return false;

    for (size_t i = 23; i < 32; ++i)
        if (data[pos + i] != 0)
            return false;

    return true;
}

inline bool parseGxp(
    const std::vector<uint8_t>& data,
    std::vector<GxpRecord>& records,
    std::string& error
) {
    records.clear();
    error.clear();

    size_t pos = 0;

    // Procura o primeiro registro após os metadados SDD.
    while (pos < data.size() &&
           !gxpIsName(data, pos))
        ++pos;

    if (pos == data.size()) {
        error = "No GXP record found";
        return false;
    }

    while (pos < data.size()) {
        if (!gxpIsName(data, pos)) {
            error = "Invalid GXP name at offset " +
                    std::to_string(pos);
            return false;
        }

        const uint32_t allocated =
            gxpRead32(data, pos + 32);

        const size_t start = pos + 36;

        if (data.size() - start < 12) {
            error = "Truncated GXP header";
            return false;
        }

        if (data[start] != 'G' ||
            data[start + 1] != 'X' ||
            data[start + 2] != 'P' ||
            data[start + 3] != 0) {
            error = "Invalid GXP signature";
            return false;
        }

        const uint32_t size =
            gxpRead32(data, start + 8);

        if (size < 12 ||
            size > UINT32_MAX - 31u) {
            error = "Invalid GXP size";
            return false;
        }

        const uint32_t aligned =
            (size + 31u) & ~31u;

        if (allocated != aligned ||
            allocated > data.size() - start) {
            error = "Invalid GXP bounds";
            return false;
        }

        GxpRecord record;
        record.name = std::string(
            reinterpret_cast<const char*>(
                data.data() + pos), 22);
        record.offset = start;
        record.size = size;

        records.push_back(record);

        pos = start + allocated;
    }

    return true;
}

} // namespace p4g
