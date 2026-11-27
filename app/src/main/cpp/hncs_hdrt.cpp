#include "hncs_hdrt.h"

#include <android/log.h>
#include <errno.h>
#include <linux/dma-buf.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

#include "master_core.h"
#include "master_math.h"

bool masterColorMasterMode();
bool masterColorLook(std::string &look, float &toneWeight, float &colourWeight, mastercolor::Params &params);

namespace hncs_hdrt {
namespace {

constexpr const char *kTag = "HncsHdrt";
constexpr double kSdrShoulderKnee = 0.45;
constexpr double kSdrShoulderPower = 0.45;
constexpr double kGainMapMax = 5.0;

struct NativeHandle {
    int version, numFds, numInts;
    int data[0];
};

template <typename T>
T fieldAt(const void *base, size_t offset) {
    T v{};
    std::memcpy(&v, static_cast<const uint8_t *>(base) + offset, sizeof(v));
    return v;
}

struct Geometry {
    int w = 0, h = 0, stride = 0, slice = 0;
};

Geometry geometryAt(const void *image) {
    Geometry g;
    if (!image) return g;
    g.w = fieldAt<int>(image, 0x20);
    g.h = fieldAt<int>(image, 0x24);
    g.stride = fieldAt<int>(image, 0x28);
    g.slice = fieldAt<int>(image, 0x2c);
    return g;
}

struct MappedDma {
    int fd = -1;
    void *base = nullptr;
    size_t size = 0;
    bool writable = false;

    bool map(const NativeHandle *handle, bool write) {
        if (!handle || handle->version != int(sizeof(NativeHandle)) || handle->numFds < 1 || handle->numFds > 8) {
            return false;
        }
        fd = handle->data[0];
        off_t len = ::lseek(fd, 0, SEEK_END);
        if (len <= 0) return false;
        size = size_t(len);
        writable = write;
        base = ::mmap(nullptr, size, write ? (PROT_READ | PROT_WRITE) : PROT_READ, MAP_SHARED, fd, 0);
        if (base == MAP_FAILED) {
            base = nullptr;
            return false;
        }
        dma_buf_sync sync{static_cast<uint64_t>(DMA_BUF_SYNC_START | (write ? DMA_BUF_SYNC_WRITE : DMA_BUF_SYNC_READ))};
        ::ioctl(fd, DMA_BUF_IOCTL_SYNC, &sync);
        return true;
    }

    ~MappedDma() {
        if (base) {
            dma_buf_sync sync{static_cast<uint64_t>(DMA_BUF_SYNC_END | (writable ? DMA_BUF_SYNC_WRITE : DMA_BUF_SYNC_READ))};
            ::ioctl(fd, DMA_BUF_IOCTL_SYNC, &sync);
            ::munmap(base, size);
        }
    }
};

bool isHncsFullActive() {
    if (!masterColorMasterMode()) return false;
    std::string look;
    float tw = 1.0f, cw = 1.0f;
    mastercolor::Params params;
    if (!masterColorLook(look, tw, cw, params)) return false;
    bool isHncs = (look == "hncs" || look.rfind("hncs_", 0) == 0);
    return isHncs && tw >= 0.999f && cw >= 0.999f;
}

int detectP010Shift(const uint8_t *yBase, int w, int h, int stride) {
    for (int r = 0; r < h; r += 97) {
        const auto *row = reinterpret_cast<const uint16_t *>(yBase + size_t(r) * stride);
        for (int x = 0; x < w; x += 13) {
            if ((row[x] & 0x3fu) != 0) return 0;
        }
    }
    return 6;
}

}  // namespace

void afterMasterTransform(void *in, void *out, void *param, void *bufs) {
    if (!in || !out || !param || !bufs) return;
    if (!isHncsFullActive()) return;

    const void *inImg = fieldAt<const void *>(in, 0);
    Geometry gi = geometryAt(inImg);
    Geometry go = geometryAt(out);
    if (gi.w <= 0 || gi.h <= 0 || gi.w != go.w || gi.h != go.h || gi.stride < gi.w * 2 || go.stride < go.w) {
        return;
    }

    MappedDma inMap, outMap, gainMap;
    if (!inMap.map(fieldAt<const NativeHandle *>(param, 0x130), false) ||
        !outMap.map(fieldAt<const NativeHandle *>(bufs, 0x28), true)) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "dma-buf map failed (errno %d)", errno);
        return;
    }

    const size_t minInBytes = size_t(gi.slice + (gi.h + 1) / 2) * size_t(gi.stride);
    const size_t minOutBytes = size_t(go.slice + (go.h + 1) / 2) * size_t(go.stride);
    if (inMap.size < minInBytes || outMap.size < minOutBytes) return;

    void *gainImg = fieldAt<void *>(bufs, 0x38);
    const NativeHandle *gainHandle = fieldAt<const NativeHandle *>(bufs, 0x40);
    Geometry gg{};
    bool hasGain = false;
    if (gainImg && gainHandle) {
        gg = geometryAt(gainImg);
        if (gg.w > 0 && gg.h > 0 && gg.stride >= gg.w * 3 && gainMap.map(gainHandle, true) &&
            gainMap.size >= size_t(gg.h) * size_t(gg.stride)) {
            hasGain = true;
        }
    }

    auto start = std::chrono::steady_clock::now();
    const uint8_t *inBase = static_cast<const uint8_t *>(inMap.base);
    const uint8_t *inUvBase = inBase + size_t(gi.slice) * size_t(gi.stride);
    uint8_t *outYBase = static_cast<uint8_t *>(outMap.base);
    uint8_t *outVuBase = outYBase + size_t(go.slice) * size_t(go.stride);
    uint8_t *gainBase = hasGain ? static_cast<uint8_t *>(gainMap.base) : nullptr;

    const int shift = detectP010Shift(inBase, gi.w, gi.h, gi.stride);
    const double invMax = 1.0 / 1023.0;
    const double invLogGainMax = 1.0 / std::log(kGainMapMax);
    const int bxCount = gi.w / 2;
    const int byCount = gi.h / 2;

    auto processRows = [&](int byBegin, int byEnd) {
        using namespace mastercolor::detail;
        for (int by = byBegin; by < byEnd; ++by) {
            const int y0 = by * 2, y1 = y0 + 1;
            const auto *inY0 = reinterpret_cast<const uint16_t *>(inBase + size_t(y0) * gi.stride);
            const auto *inY1 = reinterpret_cast<const uint16_t *>(inBase + size_t(y1) * gi.stride);
            const auto *inUv = reinterpret_cast<const uint16_t *>(inUvBase + size_t(by) * gi.stride);

            uint8_t *outY0 = outYBase + size_t(y0) * go.stride;
            uint8_t *outY1 = outYBase + size_t(y1) * go.stride;
            uint8_t *outVu = outVuBase + size_t(by) * go.stride;
            uint8_t *gainRow = (hasGain && by < gg.h) ? (gainBase + size_t(by) * gg.stride) : nullptr;

            for (int bx = 0; bx < bxCount; ++bx) {
                const int x0 = bx * 2, x1 = x0 + 1;
                const double u = double(inUv[bx * 2] >> shift) * invMax - 0.5;
                const double v = double(inUv[bx * 2 + 1] >> shift) * invMax - 0.5;
                const double rOff = 1.402 * v;
                const double bOff = 1.772 * u;

                const uint16_t yRaw[4] = {inY0[x0], inY0[x1], inY1[x0], inY1[x1]};
                double hdrSum[3] = {0.0, 0.0, 0.0};
                double sdrSum[3] = {0.0, 0.0, 0.0};
                double sdrEncSum[3] = {0.0, 0.0, 0.0};

                for (int p = 0; p < 4; ++p) {
                    const double yVal = double(yRaw[p] >> shift) * invMax;
                    const double hR = std::clamp(yVal + rOff, 0.0, 1.0);
                    const double hB = std::clamp(yVal + bOff, 0.0, 1.0);
                    const double hG = std::clamp((yVal - 0.299 * hR - 0.114 * hB) * 1.703578, 0.0, 1.0);

                    const double hdr[3] = {
                        hlgInv(hR) * kHncsHdrPeak,
                        hlgInv(hG) * kHncsHdrPeak,
                        hlgInv(hB) * kHncsHdrPeak,
                    };

                    const double lum = hdr[0] * kP3Luma[0] + hdr[1] * kP3Luma[1] + hdr[2] * kP3Luma[2];
                    Vec3 sdrLin{0.0, 0.0, 0.0};
                    if (lum > 0.0) {
                        double lumSdr = lum;
                        if (lum > kSdrShoulderKnee) {
                            const double r = 1.0 - kSdrShoulderKnee;
                            const double u = lum - kSdrShoulderKnee;
                            lumSdr = kSdrShoulderKnee +
                                     r * (1.0 - std::pow(1.0 + u / (kSdrShoulderPower * r), -kSdrShoulderPower));
                        }
                        const double scale = lumSdr / lum;
                        sdrLin = pullToP3Gamut({hdr[0] * scale, hdr[1] * scale, hdr[2] * scale}, 1.0);
                    }

                    const double enc[3] = {
                        srgbEncodeLin(sdrLin[0]),
                        srgbEncodeLin(sdrLin[1]),
                        srgbEncodeLin(sdrLin[2]),
                    };
                    const double outY = std::clamp(0.299 * enc[0] + 0.587 * enc[1] + 0.114 * enc[2], 0.0, 1.0);
                    const uint8_t yByte = uint8_t(std::lround(outY * 255.0));
                    if (p == 0) outY0[x0] = yByte;
                    else if (p == 1) outY0[x1] = yByte;
                    else if (p == 2) outY1[x0] = yByte;
                    else outY1[x1] = yByte;

                    for (int c = 0; c < 3; ++c) {
                        hdrSum[c] += hdr[c];
                        sdrSum[c] += sdrLin[c];
                        sdrEncSum[c] += enc[c];
                    }
                }

                const double avgEnc[3] = {sdrEncSum[0] * 0.25, sdrEncSum[1] * 0.25, sdrEncSum[2] * 0.25};
                const double avgY = 0.299 * avgEnc[0] + 0.587 * avgEnc[1] + 0.114 * avgEnc[2];
                const double outU = std::clamp(0.5 + (avgEnc[2] - avgY) / 1.772, 0.0, 1.0);
                const double outV = std::clamp(0.5 + (avgEnc[0] - avgY) / 1.402, 0.0, 1.0);
                outVu[bx * 2] = uint8_t(std::lround(outV * 255.0));
                outVu[bx * 2 + 1] = uint8_t(std::lround(outU * 255.0));

                if (gainRow && bx < gg.w) {
                    for (int c = 0; c < 3; ++c) {
                        const double hdrAvg = hdrSum[c] * 0.25;
                        const double sdrAvg = sdrSum[c] * 0.25;
                        const double ratio = hdrAvg > 1e-6 ? hdrAvg / std::max(sdrAvg, 1e-6) : 1.0;
                        const double g = ratio > 1.0 ? std::clamp(std::log(ratio) * invLogGainMax, 0.0, 1.0) : 0.0;
                        gainRow[bx * 3 + c] = uint8_t(std::lround(g * 255.0));
                    }
                }
            }
        }
    };

    const unsigned threads = std::min(8u, std::max(2u, std::thread::hardware_concurrency()));
    std::vector<std::thread> pool;
    pool.reserve(threads);
    const int chunk = (byCount + int(threads) - 1) / int(threads);
    for (unsigned t = 0; t < threads; ++t) {
        int b0 = int(t) * chunk;
        int b1 = std::min(b0 + chunk, byCount);
        if (b0 < b1) pool.emplace_back(processRows, b0, b1);
    }
    for (auto &th : pool) th.join();

    long ms = long(std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count());
    __android_log_print(ANDROID_LOG_INFO, kTag, "%dx%d full HDR transform in %ld ms (gainMap=%d, shift=%d)",
                        gi.w, gi.h, ms, hasGain ? 1 : 0, shift);
}

}  // namespace hncs_hdrt
