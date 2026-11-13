// Pure bounds checks for uploads; kept independent of Vulkan for host regression tests.
#pragma once

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <limits>

namespace nx::nvn {

struct VertexUploadBound {
    uint64_t last = 0;
    bool known = false;
};

inline VertexUploadBound array_upload_bound(uint32_t first, uint32_t count) {
    if (!count || uint64_t(first) + count - 1 > UINT32_MAX) return {};
    return {uint64_t(first) + count - 1, true};
}

// Include restart tokens too: this can retain a larger upload, but never omits a vertex if the
// pipeline's restart mode differs from NVN's. Unaligned index buffers are legal here.
inline VertexUploadBound index_upload_bound(const uint8_t* data, uint32_t count, uint32_t bytes, int32_t base) {
    if (!data || !count || (bytes != 1 && bytes != 2 && bytes != 4)) return {};
    uint32_t lo = UINT32_MAX, hi = 0;
    for (uint32_t i = 0; i < count; ++i) {
        uint32_t v = 0;
        std::memcpy(&v, data + uint64_t(i) * bytes, bytes);
        lo = std::min(lo, v), hi = std::max(hi, v);
    }
    const int64_t first = int64_t(lo) + base, last = int64_t(hi) + base;
    if (first < 0 || last > UINT32_MAX) return {};
    return {uint64_t(last), true};
}

// Keep the original binding's origin, so firstVertex/baseVertex and shader VertexIndex do not change.
// Unknown or out-of-bounds fetches retain the old path. Round up within the source range so a smaller
// later draw can share this prefix without needing different zero padding.
inline uint64_t vertex_upload_prefix(uint64_t bound, uint32_t stride, uint64_t attribute_end,
                                     VertexUploadBound vertices) {
    if (!vertices.known || !attribute_end || attribute_end > bound) return bound;
    if (stride && vertices.last > (bound - attribute_end) / stride) return bound;
    const uint64_t end = vertices.last * stride + attribute_end;
    if (end > std::numeric_limits<uint64_t>::max() - 15) return bound;
    return std::min(bound, (end + 15) & ~uint64_t(15));
}

// Reuse a subrange only if both its data AND its required zero tail match. A larger cached
// prefix may contain nonzero data where a shorter descriptor expects padding: reject that case.
inline bool upload_range_fits(uint64_t old_size, uint64_t old_padded, uint64_t old_offset,
                              uint64_t delta, uint64_t size, uint64_t padded, uint64_t align) {
    if (!align || padded < size || old_padded < old_size || delta > old_size || delta > old_padded ||
        size > old_size - delta || padded > old_padded - delta) return false;
    if (old_offset > UINT64_MAX - delta || (old_offset + delta) % align) return false;
    return padded == size || size == old_size - delta;
}

}  // namespace nx::nvn
