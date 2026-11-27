// Master LMT baker. Mirrors tools/mastercolor/master_lmt.py stage by stage; see that file for the
// node evidence behind each stage. Platform independent so it can be checked on a host.
//
// Per lattice node: decode the LMT input code to scene light, the scene front stage (lens profile, master_core.cpp),
// the look's style plugin (master_style.h), then the output stage inverting display colour into LMT codes.
#include "master_core.h"
#include "master_engine.h"
#include "master_style.h"
#include "native_hooks.h"
#ifdef __ANDROID__
#include <jni.h>
#endif

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <map>
#include <memory>
#include <mutex>
#include <thread>
#ifdef __ANDROID__
#include <sys/resource.h>
#include <unistd.h>
#endif
#include <vector>

namespace mastercolor {
namespace {

using namespace detail;

std::mutex engineMutex;
EngineData engineStore;
bool engineOk = false;
std::string engineRoot;


constexpr double kA = 0.17883277, kB = 0.28466892, kC = 0.55991073;

// ---------------------------------------------------------------- LMT input encodings
// np.interp(code, table, linspace(0, 1, n)) for a non-decreasing table.
double invertTable(const float *t, int n, double code) {
    code = std::min(std::max(code, double(t[0])), double(t[n - 1]));
    int hi = int(std::upper_bound(t, t + n, float(code)) - t);
    if (hi >= n) return 1.0;
    int lo = std::max(hi - 1, 0);
    double span = double(t[hi]) - t[lo];
    double f = span > 0 ? (code - t[lo]) / span : 0.0;
    return (lo + f * (hi - lo)) / double(n - 1);
}

// luxIndex > 280: trilinear lookup of the frame-independent inverse of the blended proproc encoding
// at the LMT nodes (tools/mastercolor/lux_blend_inverse.py). At a node this is the node's own solution.
Vec3 luxInverse(const EngineData &d, const Vec3 &code) {
    constexpr int n = kLutSize;
    double p[3];
    int i0[3];
    double f[3];
    for (int c = 0; c < 3; c++) {
        p[c] = std::min(std::max(code[c], 0.0), 1.0) * (n - 1);
        i0[c] = std::min(int(std::floor(p[c])), n - 2);
        f[c] = p[c] - i0[c];
    }
    Vec3 out = {0, 0, 0};
    for (int db = 0; db < 2; db++)
        for (int dg = 0; dg < 2; dg++)
            for (int dr = 0; dr < 2; dr++) {
                double w = (dr ? f[0] : 1 - f[0]) * (dg ? f[1] : 1 - f[1]) * (db ? f[2] : 1 - f[2]);
                size_t at = ((size_t(i0[2] + db) * n + (i0[1] + dg)) * n + (i0[0] + dr)) * 3;
                for (int c = 0; c < 3; c++) out[c] += w * d.luxInverse[at + c];
            }
    return out;
}

Vec3 decode(const EngineData &d, const Key &key, const float *ipe, const Vec3 &code) {
    Vec3 lin;
    switch (key.path) {
        case Path::Main:
        case Path::Preview:
            // Preview shares the stock LMTLut with capture, so its codes carry the capture encoding.
            // (com.oplus.ipe.gamma.info acts on ISP tone-mapped data: decoded as linear it spans
            // ~5 stops against ~11 for the builtin table, which rendered the preview flat.)
            if (key.path == Path::Main && key.luxBlend) return luxInverse(d, code);
            for (int c = 0; c < 3; c++) lin[c] = invertTable(d.builtin.data(), 65536, std::min(code[c], double(d.cap)));
            break;
        case Path::Thumbnail:
            for (int c = 0; c < 3; c++) lin[c] = invertTable(d.proproc.data(), 65536, code[c]);
            break;
    }
    return lin;
}

// ---------------------------------------------------------------- colour + output
// The preview table is rebaked while the camera runs: its threads give way to the camera's.
void yieldToCamera() {
#ifdef __ANDROID__
    setpriority(PRIO_PROCESS, gettid(), 10);
#endif
}

// toneCurve index of an HLG code h: round(h * 1023) with halves up. hlgOetf rises with its argument,
// so the index of u = hlgOetf^-1(h) is the number of steps at or below u: step[k - 1] is the smallest
// u whose index reaches k, found on hlgOetf itself so that the count equals the rounded code exactly.
// The steps are at least 0.39 % apart, so a guide table over the double's exponent and top 9 mantissa
// bits (cells 0.2 % wide) holds at most one step per cell: the count below the cell's lower edge, then
// a scan over the steps that the cell still contains.
struct ToneIndex {
    static constexpr int kMantissaBits = 9, kLowExponent = -24, kHighExponent = 4;  // u in [2^-24, 2^4)
    std::array<double, 1023> step{};
    std::vector<uint16_t> guide;

    static uint64_t bitsOf(double u) {
        uint64_t b;
        std::memcpy(&b, &u, sizeof b);
        return b;
    }
    static uint64_t cellOf(uint64_t bits) { return bits >> (52 - kMantissaBits); }
    static uint64_t firstCell() { return cellOf(bitsOf(std::ldexp(1.0, kLowExponent))); }

    ToneIndex() {
        auto code = [](double u) { return std::min(std::max(hlgOetf(u), 0.0), 1.0) * 1023; };
        for (int k = 1; k <= 1023; k++) {
            double want = k - 0.5, h = want / 1023;
            double u = h <= 0.5 ? h * h / 3.0 : (std::exp((h - kC) / kA) + kB) / 12.0;
            while (code(u) < want) u = std::nextafter(u, 1e9);
            for (double below; below = std::nextafter(u, 0.0), code(below) >= want;) u = below;
            step[k - 1] = u;
        }
        uint64_t first = firstCell(), last = cellOf(bitsOf(std::ldexp(1.0, kHighExponent)));
        guide.resize(size_t(last - first));
        for (uint64_t c = first; c < last; c++) {
            double edge;
            uint64_t b = c << (52 - kMantissaBits);
            std::memcpy(&edge, &b, sizeof edge);
            guide[size_t(c - first)] = uint16_t(std::lower_bound(step.begin(), step.end(), edge) - step.begin());
        }
    }

    int operator()(double u) const {
        uint64_t b = bitsOf(u);
        if (b < bitsOf(std::ldexp(1.0, kLowExponent))) return 0;
        uint64_t cell = cellOf(b) - firstCell();
        int index = guide[std::min<uint64_t>(cell, guide.size() - 1)];
        while (index < 1023 && step[size_t(index)] <= u) index++;
        return index;
    }
};

const ToneIndex &toneIndex() {
    static const ToneIndex index;
    return index;
}

// Display-linear P3 -> LMT output through the HDRTransform master model (toneCurve luma, HLG ratio).
// The scale s whose output luma meets the target is bisected on the grid j * 8 / 2^24 (j = 1 .. 2^24 - 1;
// 24 halvings of [0, 8)): the result is (J + 0.5) of a cell, J the number of grid points whose luma is still
// under the target. The luma only changes where a channel's toneCurve index steps, so J is found without
// halving all the way: `hint` (the previous node's J, nodes next to each other have close scales) brackets
// it, halving continues until the bracket holds at most eight index steps, and the steps are then placed
// on the grid and walked in order. Every comparison is the bisection's own, so the result is the same.
void invertOutput(const EngineData &d, const float t[3], float out[3], int &hint) {
    constexpr int64_t kCells = int64_t(1) << 24;
    constexpr double kDelta = 8.0 / double(kCells);
    double T[3], target = 0;
    for (int c = 0; c < 3; c++) {
        T[c] = std::min(std::max(double(t[c]), 0.0), 1.0);
        target += kW601[c] * srgbEncode(T[c]);
    }
    const ToneIndex &toneOf = toneIndex();
    auto indexAt = [&](int c, int64_t j) { return toneOf(double(j) * kDelta * T[c]); };
    // The luma at grid point j is under the target (the bisection takes j = 0 as under, 2^24 as not).
    auto below = [&](int64_t j) {
        if (j <= 0) return true;
        if (j >= kCells) return false;
        double y = 0;
        for (int c = 0; c < 3; c++) y += kW601[c] * d.toneCurve[indexAt(c, j)];
        return y < target;
    };
    int64_t jl = 0, jh = kCells;
    if (hint > 0) {
        int64_t w = std::max<int64_t>(hint >> 6, 64);
        jl = std::max<int64_t>(hint - w, 0);
        jh = std::min<int64_t>(hint + w, kCells);
        while (jh < kCells && below(jh)) {
            jl = jh;
            w *= 2;
            jh = std::min<int64_t>(jh + w, kCells);
        }
        while (jl > 0 && !below(jl)) {
            jh = jl;
            w *= 2;
            jl = jl > w ? jl - w : 0;
        }
    }
    // 2^24 is only taken as not under the target: test the last grid point so that !below(jh) holds by evaluation.
    if (jh == kCells) {
        if (below(kCells - 1)) jl = kCells - 1; else jh = kCells - 1;
    }
    // Here below(jl) and !below(jh).
    while (jh - jl > 1) {
        int low[3], high[3], steps = 0;
        for (int c = 0; c < 3; c++) {
            low[c] = indexAt(c, jl);
            high[c] = indexAt(c, jh);
            steps += high[c] - low[c];
        }
        if (steps > 8) {
            int64_t mid = (jl + jh) / 2;
            if (below(mid)) jl = mid; else jh = mid;
            continue;
        }
        // First grid point at which each step in (jl, jh] is reached.
        int64_t at[8];
        int n = 0;
        for (int c = 0; c < 3; c++) {
            for (int k = low[c]; k < high[c]; k++) {
                double reach = toneOf.step[size_t(k)];
                int64_t j = std::min<int64_t>(std::max<int64_t>(int64_t(std::ceil(reach / (T[c] * kDelta))), jl + 1), jh);
                while (j > jl + 1 && double(j - 1) * kDelta * T[c] >= reach) j--;
                while (j < jh && double(j) * kDelta * T[c] < reach) j++;
                at[n++] = j;
            }
        }
        std::sort(at, at + n);
        // The luma is constant from one step to the next: the first step at which it is no longer under
        // the target ends the run of grid points under it.
        int64_t last = jl;
        if (n > 0 && (jl > 0 || below(1))) {
            last = jh - 1;
            for (int m = 0; m < n; m++) {
                if (!below(at[m])) {
                    last = at[m] - 1;
                    break;
                }
            }
        }
        jl = last;
        jh = last + 1;
    }
    hint = int(std::max<int64_t>(jl, 1));
    double s = (double(jl) + 0.5) * kDelta;
    for (int c = 0; c < 3; c++) out[c] = float(std::min(std::max(hlgOetf(s * T[c]), 0.0), 1.0));
}


uint64_t fnv(const void *data, size_t n, uint64_t h = 1469598103934665603ull) {
    const uint8_t *p = static_cast<const uint8_t *>(data);
    for (size_t i = 0; i < n; i++) h = (h ^ p[i]) * 1099511628211ull;
    return h;
}

// ---------------------------------------------------------------- scene front stage (scene.bin)
struct SceneLens {
    std::string name;
    float focal = 0, t1 = 0, t2 = 0;
    Mat3 fm1{}, fm2{};
    int hue = 0, sat = 0;                 // one value division
    std::vector<float> hs1, hs2;          // HueSatMap 1 / 2, [hue][sat][3]
};
std::vector<SceneLens> sceneLenses;

bool loadScene(const std::string &path, std::string *error) {
    FILE *file = std::fopen(path.c_str(), "rb");
    if (!file) {
        if (error) *error = "missing " + path;
        return false;
    }
    std::vector<uint8_t> blob;
    uint8_t chunk[65536];
    size_t got;
    while ((got = std::fread(chunk, 1, sizeof(chunk), file)) > 0) blob.insert(blob.end(), chunk, chunk + got);
    std::fclose(file);
    size_t at = 8;
    uint32_t version = 0, count = 0;
    bool ok = blob.size() > 16 && std::memcmp(blob.data(), "MCSCENE1", 8) == 0 && readValue(blob, at, version) &&
              version == 1 && readValue(blob, at, count) && count >= 1 && count <= 16;
    std::vector<SceneLens> lenses;
    for (uint32_t i = 0; ok && i < count; i++) {
        SceneLens l;
        uint32_t length = 0, hue = 0, sat = 0, val = 0;
        ok = readValue(blob, at, length) && length > 0 && length <= 32 && at + length <= blob.size();
        if (ok) {
            l.name.assign(reinterpret_cast<const char *>(blob.data() + at), length);
            at += length;
        }
        ok = ok && readValue(blob, at, l.focal) && readValue(blob, at, l.t1) && readValue(blob, at, l.t2) &&
             readMat(blob, at, l.fm1) && readMat(blob, at, l.fm2) && readValue(blob, at, hue) && readValue(blob, at, sat) &&
             readValue(blob, at, val) && val == 1 && hue >= 2 && hue <= 360 && sat >= 2 && sat <= 64 && l.focal > 0 &&
             l.t1 > 0 && l.t2 > 0 && l.t1 != l.t2;
        l.hue = int(hue);
        l.sat = int(sat);
        ok = ok && readFloats(blob, at, size_t(hue) * sat * 3, l.hs1) && readFloats(blob, at, size_t(hue) * sat * 3, l.hs2);
        lenses.push_back(std::move(l));
    }
    if (!ok || at != blob.size()) {
        if (error) *error = "malformed scene data (" + std::to_string(blob.size()) + " bytes)";
        return false;
    }
    sceneLenses = std::move(lenses);
    return true;
}

// Post-CCM linear RGB -> scene-linear ProPhoto D50 for one frame: inverse CCM, the lens' ForwardMatrix and
// HueSatMap, both interpolated at the frame CCT (Adobe Standard profile; tools/mastercolor/scene_front.py).
struct FrontStage {
    Mat3 toProPhoto{};
    std::vector<float> table;
    int hue = 0, sat = 0;
};

// CIE D-illuminant (x, y) + orthogonal Duv in CIE 1960 UCS -> XYZ (Y = 1).
Vec3 illuminantXyz(double cct, double duv) {
    auto dXy = [](double T) -> std::pair<double, double> {
        double x = (T <= 7000.0)
            ? (-4.6070e9 / (T * T * T) + 2.9678e6 / (T * T) + 0.09911e3 / T + 0.244063)
            : (-2.0064e9 / (T * T * T) + 1.9018e6 / (T * T) + 0.24748e3 / T + 0.237040);
        double y = -3.000 * x * x + 2.870 * x - 0.275;
        return {x, y};
    };
    auto toUv = [](double x, double y) -> std::pair<double, double> {
        double d = -2.0 * x + 12.0 * y + 3.0;
        return {4.0 * x / d, 6.0 * y / d};
    };
    auto [x, y] = dXy(cct);
    auto [u, v] = toUv(x, y);
    if (std::abs(duv) > 1e-9) {
        auto [x2, y2] = dXy(cct + 1.0);
        auto [u2, v2] = toUv(x2, y2);
        double du = u2 - u, dv = v2 - v;
        double len = std::hypot(du, dv);
        // Normal pointing toward +v (green) as cct increases (-u direction -> (-dv, du) points +v).
        double nu = (len > 1e-12) ? (-dv / len) : 0.0;
        double nv = (len > 1e-12) ? (du / len) : 1.0;
        if (nv < 0) { nu = -nu; nv = -nv; }
        u += duv * nu;
        v += duv * nv;
    }
    double denom = 2.0 * u - 8.0 * v + 4.0;
    double xo = 3.0 * u / denom;
    double yo = 2.0 * v / denom;
    return {xo / yo, 1.0, (1.0 - xo - yo) / yo};
}

Mat3 bradfordWhiteAdapt(double wbTemp, double wbTint) {
    static constexpr Mat3 kBradford = {
         0.8951,  0.2664, -0.1614,
        -0.7502,  1.7135,  0.0367,
         0.0389, -0.0685,  1.0296,
    };
    constexpr double kBaseCct = 5500.0;
    Vec3 src = illuminantXyz(kBaseCct + wbTemp, wbTint * 0.0002);
    Vec3 dst = illuminantXyz(kBaseCct, 0.0);
    Vec3 lmsSrc = xform(kBradford, src);
    Vec3 lmsDst = xform(kBradford, dst);
    Mat3 diag = {
        lmsDst[0] / std::max(lmsSrc[0], 1e-9), 0, 0,
        0, lmsDst[1] / std::max(lmsSrc[1], 1e-9), 0,
        0, 0, lmsDst[2] / std::max(lmsSrc[2], 1e-9),
    };
    return mul(mul(inv(kBradford), diag), kBradford);
}

bool makeFrontStage(const Key &key, FrontStage &fs) {
    const std::string &lens = key.lens;
    const std::array<float, 9> &ccmF = key.ccm;
    int cct = key.cct;
    const SceneLens *l = nullptr;
    for (const SceneLens &candidate : sceneLenses)
        if (candidate.name == lens) l = &candidate;
    if (!l || cct < 1500 || cct > 20000) return false;
    Mat3 ccm;
    std::copy(ccmF.begin(), ccmF.end(), ccm.begin());
    for (double v : ccm) if (!std::isfinite(v)) return false;
    double det = ccm[0]*(ccm[4]*ccm[8]-ccm[5]*ccm[7])-ccm[1]*(ccm[3]*ccm[8]-ccm[5]*ccm[6])+ccm[2]*(ccm[3]*ccm[7]-ccm[4]*ccm[6]);
    if (std::abs(det) < 1e-9) return false;
    double w = std::clamp((1.0 / cct - 1.0 / l->t1) / (1.0 / l->t2 - 1.0 / l->t1), 0.0, 1.0);
    Mat3 fm;
    for (int i = 0; i < 9; i++) fm[i] = l->fm1[i] * (1 - w) + l->fm2[i] * w;
    double wbTemp = style::param(key, "wb_temp", 0.0f, -800.0f, 800.0f);
    double wbTint = style::param(key, "wb_tint", 0.0f, -50.0f, 50.0f);
    if (std::abs(wbTemp) > 0.5 || std::abs(wbTint) > 0.05) {
        fm = mul(bradfordWhiteAdapt(wbTemp, wbTint), fm);
    }
    fs.toProPhoto = mul(mul(inv(kProPhotoToXyz), fm), inv(ccm));
    fs.table.resize(l->hs1.size());
    for (size_t i = 0; i < fs.table.size(); i++) fs.table[i] = float(l->hs1[i] * (1 - w) + l->hs2[i] * w);
    fs.hue = l->hue;
    fs.sat = l->sat;
    return true;
}

Vec3 frontStage(const FrontStage &fs, const Vec3 &linear) {
    Vec3 pp = xform(fs.toProPhoto, linear);
    for (double &v : pp) v = std::max(v, 0.0);
    return hueSat(fs.hue, fs.sat, fs.table, pp);
}


// ---------------------------------------------------------------- look files
// looks/<name>.bin: "MCOLLOOK", version 1, kind, then the body of the style that owns the kind.
struct LoadedLook {
    const Style *style = nullptr;
    std::shared_ptr<const Look> look;
};

std::mutex lookMutex;
std::map<std::string, LoadedLook> lookCache;

bool loadLook(const std::string &name, LoadedLook &out) {
    std::lock_guard<std::mutex> lock(lookMutex);
    auto it = lookCache.find(name);
    if (it != lookCache.end()) {
        out = it->second;
        return true;
    }
    if (!validLookName(name)) return false;
    std::vector<uint8_t> blob;
    if (!readFile(engineRoot + "/looks/" + name + ".bin", blob)) return false;
    size_t at = 8;
    uint32_t version = 0, kind = 0;
    bool ok = blob.size() >= 16 && std::memcmp(blob.data(), "MCOLLOOK", 8) == 0 && readValue(blob, at, version) &&
              version == 1 && readValue(blob, at, kind);
    const Style *style = ok ? styleForKind(kind) : nullptr;
    std::shared_ptr<const Look> look = style ? style->parse(kind, blob, at) : nullptr;
    if (!look || at != blob.size()) return false;
    if (lookCache.size() >= 3) lookCache.clear();  // film tables take ~7 MB each in memory
    out = lookCache[name] = LoadedLook{style, look};
    return true;
}


}  // namespace

bool sceneLensFor(double equivalentFocal, std::string &lens) {
    const SceneLens *best = nullptr;
    double bestError = 1e9;
    for (const SceneLens &l : sceneLenses) {
        double error = std::fabs(std::log(equivalentFocal / l.focal));
        if (error < bestError) bestError = error, best = &l;
    }
    if (!best || !(bestError <= std::log(1.25))) return false;
    lens = best->name;
    return true;
}

std::string Key::name() const {
    uint64_t h = fnv(ccm.data(), sizeof(float) * 9);
    h = fnv(lens.data(), lens.size(), h);
    if (look != "hncs") h = fnv(look.data(), look.size(), h);
    for (const auto &[name, value] : params) {
        h = fnv(name.data(), name.size(), h);
        if (std::isfinite(value)) h = fnv(&value, sizeof(value), h);
    }
    if (blended()) {
        const float w[2] = {toneWeight, colourWeight};
        h = fnv(w, sizeof(w), h);
    }
    char buf[64];
    const char *tag = path == Path::Main ? (luxBlend ? "mb" : "m") : path == Path::Thumbnail ? "t" : "p";
    std::snprintf(buf, sizeof(buf), "%s%05d_%016llx", tag, cct, static_cast<unsigned long long>(h));
    return buf;
}


bool loadEngine(const std::string &path, std::string *error) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if (engineOk) return true;
    FILE *file = std::fopen(path.c_str(), "rb");
    if (!file) {
        if (error) *error = "missing " + path;
        return false;
    }
    std::vector<uint8_t> blob;
    uint8_t chunk[65536];
    size_t got;
    while ((got = std::fread(chunk, 1, sizeof(chunk), file)) > 0) blob.insert(blob.end(), chunk, chunk + got);
    std::fclose(file);
    EngineData d;
    size_t at = 8;
    uint32_t version = 0, count = 0;
    bool ok = blob.size() > 8 && std::memcmp(blob.data(), "HNCSENG1", 8) == 0 && readValue(blob, at, version) &&
              version == 4 && readValue(blob, at, d.k) && readValue(blob, at, d.greyScene) &&
              readValue(blob, at, d.gw) && readValue(blob, at, d.gh) && readValue(blob, at, d.cbStart) &&
              readValue(blob, at, d.crStart) && readValue(blob, at, d.divFactor) &&
              readValue(blob, at, d.matrixMax) && readValue(blob, at, d.tableMax) && readValue(blob, at, count);
    for (uint32_t i = 0; ok && i < count; i++) {
        Anchor a;
        ok = readValue(blob, at, a.temperature) && readFloats(blob, at, 9, a.values);
        d.matrices.push_back(std::move(a));
    }
    ok = ok && readValue(blob, at, count);
    for (uint32_t i = 0; ok && i < count; i++) {
        Anchor a;
        ok = readValue(blob, at, a.temperature) && readFloats(blob, at, size_t(d.gw) * d.gh * 2, a.values);
        d.tables.push_back(std::move(a));
    }
    ok = ok && readMat(blob, at, d.cm) && at + 65536 * 2 <= blob.size();
    if (ok) {
        d.film.resize(65536);
        for (int i = 0; i < 65536; i++) {
            uint16_t v;
            std::memcpy(&v, blob.data() + at + i * 2, 2);
            d.film[i] = v / 65535.0f;
        }
        at += 65536 * 2;
        ok = readFloats(blob, at, 1024, d.toneCurve) && readFloats(blob, at, 65536, d.builtin) &&
             readFloats(blob, at, 65536, d.proproc) &&
             readValue(blob, at, d.greyCode) && readValue(blob, at, d.cap) &&
             readFloats(blob, at, size_t(kLutSize) * kLutSize * kLutSize * 3, d.luxInverse) && at == blob.size() && d.k > 0 &&
             d.matrices.size() >= 2 && !d.tables.empty() && d.gw >= 2 && d.gh >= 2;
    }
    if (!ok) {
        if (error) *error = "malformed engine data (" + std::to_string(blob.size()) + " bytes)";
        return false;
    }
    size_t slash = path.rfind('/');
    std::string dir = slash == std::string::npos ? std::string(".") : path.substr(0, slash);
    if (!loadScene(dir + "/scene.bin", error)) return false;
    engineStore = std::move(d);
    engineOk = true;
    engineRoot = dir;
    return true;
}

bool engineReady() {
    std::lock_guard<std::mutex> lock(engineMutex);
    return engineOk;
}


const EngineData &engineData() { return engineStore; }
const std::string &engineDir() { return engineRoot; }

bool lookTables(const Key &key, std::vector<std::vector<float>> &tables, int threads) {
    if (!engineReady()) return false;
    const Style *style = styleForId(key.look);
    if (style && style->servesTables()) return style->tables(key, tables);
    tables.assign(1, {});
    return bake(key, tables[0], threads);
}

double lmtInputCap() { return engineStore.cap; }

bool builtinVignetteLut(float *outCode, int w, int h) {
    if (!engineReady()) return false;
    std::lock_guard<std::mutex> lock(engineMutex);
    if (!engineOk || engineStore.builtin.size() < 2 || !(engineStore.cap > 0.0f) || w < 2 || h < 2) return false;
    const double cap = engineStore.cap;
    const float *tbl = engineStore.builtin.data();
    const int n = int(engineStore.builtin.size());
    const double prevLin = invertTable(tbl, n, cap * (127.0 / 128.0));
    const double stepSlope = std::max(1.0 - prevLin, 1e-4) / (cap / 128.0);
    std::vector<double> colLin(static_cast<size_t>(w));
    for (int i = 0; i < w; i++) {
        const double code = double(i) / double(w - 1);
        colLin[i] = code <= cap ? invertTable(tbl, n, code) : (1.0 + (code - cap) * stepSlope);
    }
    constexpr double kMaxVigGain = 2.6;
    for (int j = 0; j < h; j++) {
        const double gain = (double(j) / double(h - 1)) * kMaxVigGain;
        for (int i = 0; i < w; i++) {
            double lin = colLin[i] * gain;
            if (gain > 1.001 && lin > 0.85) {
                const double t = lin - 0.85;
                lin = 0.85 + 0.15 * (t / (0.15 + t));
            }
            double encoded;
            if (lin <= 1.0) {
                const double pos = std::min(std::max(lin, 0.0), 1.0) * (n - 1);
                const int k0 = std::min(int(std::floor(pos)), n - 2);
                const double f = pos - k0;
                encoded = tbl[k0] * (1.0 - f) + tbl[k0 + 1] * f;
            } else {
                encoded = std::min(std::max(cap + (lin - 1.0) / stepSlope, 0.0), 1.0);
            }
            outCode[size_t(j) * w + i] = float(encoded);
        }
    }
    return true;
}

bool codeLatticeScene(const Key &key, int n, double axisMax, std::vector<double> &scene, std::array<double, 3> &grey) {
    if (!engineReady() || n < 2) return false;
    const EngineData &d = engineStore;
    FrontStage front;
    if (!makeFrontStage(key, front)) return false;
    const Vec3 greyScene = frontStage(front, decode(d, key, nullptr, {d.greyCode, d.greyCode, d.greyCode}));
    for (int c = 0; c < 3; c++) grey[c] = greyScene[c];
    scene.assign(size_t(n) * n * n * 3, 0.0);
    const bool coupled = key.path == Path::Main && key.luxBlend;
    std::vector<Vec3> axis;
    if (!coupled) {
        axis.resize(size_t(n));
        for (int i = 0; i < n; i++) {
            const double v = axisMax * i / (n - 1);
            axis[i] = decode(d, key, nullptr, {v, v, v});
        }
    }
    const int threads = std::max(1, std::min(8, int(std::thread::hardware_concurrency())));
    std::atomic<int> next{0};
    auto worker = [&]() {
        for (int b; (b = next.fetch_add(1)) < n;) {
            for (int g = 0; g < n; g++) {
                for (int r = 0; r < n; r++) {
                    const Vec3 lin = coupled
                        ? decode(d, key, nullptr, {axisMax * r / (n - 1), axisMax * g / (n - 1), axisMax * b / (n - 1)})
                        : Vec3{axis[r][0], axis[g][1], axis[b][2]};
                    const Vec3 s = frontStage(front, lin);
                    const size_t at = ((size_t(b) * n + g) * n + r) * 3;
                    for (int c = 0; c < 3; c++) scene[at + c] = s[c];
                }
            }
        }
    };
    std::vector<std::thread> pool;
    for (int t = 1; t < threads; t++) pool.emplace_back(worker);
    worker();
    for (auto &t : pool) t.join();
    return true;
}

bool linearLatticeScene(const Key &key, int n, double exponent, std::vector<double> &scene, std::array<double, 3> &grey) {
    if (!engineReady() || n < 2) return false;
    const EngineData &d = engineStore;
    FrontStage front;
    if (!makeFrontStage(key, front)) return false;
    const Vec3 greyScene = frontStage(front, decode(d, key, nullptr, {d.greyCode, d.greyCode, d.greyCode}));
    for (int c = 0; c < 3; c++) grey[c] = greyScene[c];
    std::vector<double> axis(n);
    for (int i = 0; i < n; i++) axis[i] = std::pow(i / double(n - 1), exponent);
    scene.assign(size_t(n) * n * n * 3, 0.0);
    const int threads = std::max(1, std::min(8, int(std::thread::hardware_concurrency())));
    std::atomic<int> next{0};
    auto worker = [&]() {
        for (int b; (b = next.fetch_add(1)) < n;) {
            for (int g = 0; g < n; g++) {
                for (int r = 0; r < n; r++) {
                    const Vec3 s = frontStage(front, {axis[r], axis[g], axis[b]});
                    const size_t at = ((size_t(b) * n + g) * n + r) * 3;
                    for (int c = 0; c < 3; c++) scene[at + c] = s[c];
                }
            }
        }
    };
    std::vector<std::thread> pool;
    for (int t = 1; t < threads; t++) pool.emplace_back(worker);
    worker();
    for (auto &t : pool) t.join();
    return true;
}

bool bake(const Key &key, std::vector<float> &lut, int threads, const std::vector<uint32_t> *only) {
    if (!engineReady()) return false;
    const EngineData &d = engineStore;
    const Style *style = styleForId(key.look);
    if (style && style->servesTables()) {
        std::vector<std::vector<float>> tables;
        if (!style->tables(key, tables) || tables.size() != 1) return false;
        lut = std::move(tables[0]);
        return true;
    }
    LoadedLook loaded;
    if (!style) {
        if (!loadLook(key.look, loaded)) return false;
        style = loaded.style;
    }
    // The scene front stage hands the style ProPhoto D50 scene light; the style puts the grey where its model wants it.
    FrontStage front;
    if (!makeFrontStage(key, front)) return false;
    Vec3 greyLin = decode(d, key, nullptr, {d.greyCode, d.greyCode, d.greyCode});
    Vec3 greyScene = frontStage(front, greyLin);
    std::unique_ptr<Renderer> renderer = style->prepare(loaded.look, key, greyScene);
    if (!renderer) return false;
    constexpr int n = kLutSize;
    // Decoded linear value of a lattice node: per channel unless the lux blend couples them.
    std::array<Vec3, n> axis;
    for (int i = 0; i < n; i++) {
        double code = i / double(n - 1);
        axis[i] = decode(d, key, nullptr, {code, code, code});
    }
    const bool coupled = key.path == Path::Main && key.luxBlend;
    auto lattice = [&](int r, int g, int b) -> Vec3 {
        if (coupled) return decode(d, key, nullptr, {r / double(n - 1), g / double(n - 1), b / double(n - 1)});
        return {axis[r][0], axis[g][1], axis[b][2]};
    };
    const bool directHdr = (key.path == Path::Main || key.path == Path::Thumbnail) && !key.blended();
    auto nodeOutput = [&](int r, int g, int b, int &hint, float out[3]) {
        Vec3 scene = frontStage(front, lattice(r, g, b));
        if (directHdr && renderer->renderHdr(scene, out)) return;
        float display[3];
        renderer->render(scene, display);
        invertOutput(d, display, out, hint);
    };
    if (only) {
        // The listed nodes only (index = (b * n + g) * n + r), as [node][channel].
        lut.assign(only->size() * 3, 0.0f);
        int hint = 0;
        for (size_t k = 0; k < only->size(); k++) {
            uint32_t index = (*only)[k];
            nodeOutput(int(index % n), int(index / n % n), int(index / (n * n)), hint, &lut[3 * k]);
        }
        return true;
    }
    lut.assign(size_t(3) * n * n * n, 0.0f);
    std::atomic<int> next{0};
    const bool low = key.path == Path::Preview;
    auto worker = [&]() {
        if (low) yieldToCamera();
        int hint = 0;
        for (int b; (b = next.fetch_add(1)) < n;) {
            for (int g = 0; g < n; g++) {
                for (int r = 0; r < n; r++) {
                    float out[3];
                    nodeOutput(r, g, b, hint, out);
                    size_t index = (size_t(b) * n + g) * n + r;
                    for (int c = 0; c < 3; c++) lut[size_t(c) * n * n * n + index] = out[c];
                }
            }
        }
    };
    threads = std::max(1, std::min(threads, 8));
    std::vector<std::thread> pool;
    for (int i = 0; i < threads; i++) pool.emplace_back(worker);
    for (auto &t : pool) t.join();
    return true;
}


namespace {

double hlgInverse(double h) {
    h = std::min(std::max(h, 0.0), 1.0);
    return h <= 0.5 ? h * h / 3.0 : (std::exp((h - kC) / kA) + kB) / 12.0;
}

// Non-decreasing curve through the neutral axis nodes: output as a function of the scene luma of the code.
struct NeutralCurve {
    std::vector<double> x, y;
    double at(double l) const {
        if (l <= x.front()) return y.front();
        if (l >= x.back()) return y.back();
        size_t hi = size_t(std::upper_bound(x.begin(), x.end(), l) - x.begin());
        size_t lo = hi - 1;
        double span = x[hi] - x[lo];
        double f = span > 0 ? (l - x[lo]) / span : 0.0;
        return y[lo] + f * (y[hi] - y[lo]);
    }
};

bool readTable(const std::string &path, std::vector<float> &out) {
    FILE *file = std::fopen(path.c_str(), "rb");
    if (!file) return false;
    constexpr size_t count = size_t(3) * kLutSize * kLutSize * kLutSize;
    out.resize(count);
    bool ok = std::fread(out.data(), sizeof(float), count, file) == count;
    std::fclose(file);
    return ok;
}

// Scene luma of every node's input code (through the same decode as the look bake). It depends on the
// path and the lux blend only, so every blend of a process shares one.
std::shared_ptr<const std::vector<double>> nodeLuma(const EngineData &d, const Key &key) {
    constexpr int n = kLutSize;
    static std::mutex mutex;
    static std::map<int, std::shared_ptr<const std::vector<double>>> cache;
    int id = int(key.path) * 2 + (key.path == Path::Main && key.luxBlend ? 1 : 0);
    std::lock_guard<std::mutex> lock(mutex);
    auto &entry = cache[id];
    if (!entry) {
        auto luma = std::make_shared<std::vector<double>>(size_t(n) * n * n);
        std::vector<float> ipe;
        for (int b = 0; b < n; b++)
            for (int g = 0; g < n; g++)
                for (int r = 0; r < n; r++) {
                    Vec3 lin = decode(d, key, ipe.data(), {r / double(n - 1), g / double(n - 1), b / double(n - 1)});
                    (*luma)[(size_t(b) * n + g) * n + r] = kW601[0] * lin[0] + kW601[1] * lin[1] + kW601[2] * lin[2];
                }
        entry = luma;
    }
    return entry;
}

// body(begin, end) over [0, count) in chunks, on `threads` threads.
template <typename F>
void parallelChunks(int threads, bool low, size_t count, F &&body) {
    constexpr size_t kChunk = 4096;
    std::atomic<size_t> next{0};
    auto worker = [&]() {
        if (low) yieldToCamera();
        for (size_t begin; (begin = next.fetch_add(kChunk)) < count;) body(begin, std::min(begin + kChunk, count));
    };
    std::vector<std::thread> pool;
    for (int i = 0; i < threads; i++) pool.emplace_back(worker);
    for (auto &t : pool) t.join();
}

}  // namespace

bool blendWithStock(const Key &key, const std::vector<std::vector<float>> &looks, const std::string &stockDir,
                    const TableSink &sink, int threads) {
    constexpr int n = kLutSize;
    constexpr size_t nodes = size_t(n) * n * n;
    constexpr double kFloor = 1e-5;
    if (!engineReady() || (looks.size() != 1 && looks.size() != size_t(kStockTables))) return false;
    for (const auto &look : looks)
        if (look.size() != 3 * nodes) return false;
    const double wt = std::min(std::max(double(key.toneWeight), 0.0), 1.0);
    const double wc = std::min(std::max(double(key.colourWeight), 0.0), 1.0);
    if (wt >= 1 && wc >= 1) {
        for (size_t i = 0; i < looks.size(); i++)
            if (!sink(int(i), looks[i])) return false;
        return true;
    }
    std::vector<float> stock;
    if (wt <= 0 && wc <= 0) {
        for (int i = 0; i < kStockTables; i++)
            if (!readTable(stockDir + "/LMTLut" + std::to_string(i), stock) || !sink(i, stock)) return false;
        return true;
    }
    const EngineData &d = engineStore;
    threads = std::max(1, std::min(threads, 8));
    const bool low = key.path == Path::Preview;
    const std::shared_ptr<const std::vector<double>> lumaOwner = nodeLuma(d, key);
    const std::vector<double> &luma = *lumaOwner;
    auto neutral = [&](const float *e) {
        NeutralCurve curve;
        double top = 0, lastX = -1;
        for (int i = 0; i < n; i++) {
            size_t index = (size_t(i) * n + i) * n + i;
            double x = std::max(luma[index], lastX);
            top = std::max(top, (double(e[index]) + e[nodes + index] + e[2 * nodes + index]) / 3.0);
            curve.x.push_back(x);
            curve.y.push_back(top);
            lastX = x;
        }
        return curve;
    };
    // The look's side of the blend is the same for every slot: its logarithms are taken once per look.
    std::vector<double> logToneLook(nodes), logColourLook(3 * nodes);
    auto prepareLook = [&](const std::vector<float> &look) {
        std::vector<float> eLook(3 * nodes), toneLookAt(nodes);
        parallelChunks(threads, low, 3 * nodes, [&](size_t begin, size_t end) {
            for (size_t i = begin; i < end; i++) eLook[i] = float(hlgInverse(look[i]));
        });
        const NeutralCurve toneLook = neutral(eLook.data());
        parallelChunks(threads, low, nodes, [&](size_t begin, size_t end) {
            for (size_t i = begin; i < end; i++) {
                toneLookAt[i] = float(std::max(toneLook.at(luma[i]), kFloor));
                double tl = toneLookAt[i];
                logToneLook[i] = std::log(tl);
                for (int c = 0; c < 3; c++) {
                    double cl = std::max(eLook[c * nodes + i] / tl, kFloor);
                    logColourLook[c * nodes + i] = std::log(cl);
                }
            }
        });
    };
    if (looks.size() == 1) prepareLook(looks[0]);

    std::vector<float> eStock(3 * nodes), table(3 * nodes);
    for (int t = 0; t < kStockTables; t++) {
        if (looks.size() > 1) prepareLook(looks[t]);
        if (!readTable(stockDir + "/LMTLut" + std::to_string(t), stock)) return false;
        parallelChunks(threads, low, 3 * nodes, [&](size_t begin, size_t end) {
            for (size_t i = begin; i < end; i++) eStock[i] = float(hlgInverse(stock[i]));
        });
        const NeutralCurve toneStock = neutral(eStock.data());
        parallelChunks(threads, low, nodes, [&](size_t begin, size_t end) {
            for (size_t i = begin; i < end; i++) {
                double ts = std::max(toneStock.at(luma[i]), kFloor);
                double tone = std::exp((1 - wt) * std::log(ts) + wt * logToneLook[i]);
                for (int c = 0; c < 3; c++) {
                    double cs = std::max(eStock[c * nodes + i] / ts, kFloor);
                    double e = tone * std::exp((1 - wc) * std::log(cs) + wc * logColourLook[c * nodes + i]);
                    table[c * nodes + i] = float(std::min(std::max(hlgOetf(std::min(e, 1.0)), 0.0), 1.0));
                }
            }
        });
        if (!sink(t, table)) return false;
    }
    return true;
}

bool blendWithStock(const Key &key, const std::vector<std::vector<float>> &looks, const std::string &stockDir,
                    std::vector<std::vector<float>> &tables, int threads) {
    tables.clear();
    return blendWithStock(key, looks, stockDir, [&](int, const std::vector<float> &table) {
        tables.push_back(table);
        return true;
    }, threads);
}

// Nodes of the preview comparison: mid-tones and highlights (every input code at least 6/63), every sixth step.
static const std::vector<uint32_t> &previewNodes() {
    static const std::vector<uint32_t> nodes = [] {
        std::vector<uint32_t> v;
        for (uint32_t b = 6; b < kLutSize; b += 6)
            for (uint32_t g = 6; g < kLutSize; g += 6)
                for (uint32_t r = 6; r < kLutSize; r += 6) v.push_back((b * kLutSize + g) * kLutSize + r);
        return v;
    }();
    return nodes;
}

double previewDistance(const Key &a, const Key &b) {
    std::vector<float> ta, tb;
    if (!bake(a, ta, 1, &previewNodes()) || !bake(b, tb, 1, &previewNodes())) return 1e9;
    std::vector<float> error(ta.size());
    for (size_t i = 0; i < ta.size(); i++) error[i] = std::fabs(ta[i] - tb[i]) * 1023.0f;
    size_t at = size_t(double(error.size()) * 0.99);
    std::nth_element(error.begin(), error.begin() + at, error.end());
    return error[at];
}


// MCOLKEY9: magic, path, CCT, CCM, lux flag, a reserved count (0), look name, tone and colour weights, lens name,
// then the style settings: count, and for each a name (length-prefixed) and a float. Written by MasterColor.java.
namespace {

struct Reader {
    const uint8_t *data;
    size_t size, at = 0;
    bool take(void *out, size_t n) {
        if (at + n > size) return false;
        std::memcpy(out, data + at, n);
        at += n;
        return true;
    }
    bool string(std::string &out, uint32_t limit) {
        uint32_t length = 0;
        if (!take(&length, 4) || length == 0 || length > limit || at + length > size) return false;
        out.assign(reinterpret_cast<const char *>(data + at), length);
        at += length;
        return true;
    }
};

}  // namespace

bool readKeyBytes(const uint8_t *data, size_t size, Key &key) {
    Reader in{data, size};
    char magic[8];
    int32_t p = 0, cct = 0, lux = 0;
    uint32_t count = 0, settings = 0;
    bool ok = in.take(magic, 8) && std::memcmp(magic, "MCOLKEY9", 8) == 0 && in.take(&p, 4) && in.take(&cct, 4) &&
              in.take(key.ccm.data(), 36) && in.take(&lux, 4) && in.take(&count, 4) && count == 0 &&
              in.string(key.look, 64) && in.take(&key.toneWeight, 4) && in.take(&key.colourWeight, 4) &&
              key.toneWeight >= 0 && key.toneWeight <= 1 && key.colourWeight >= 0 && key.colourWeight <= 1 &&
              in.string(key.lens, 32) && in.take(&settings, 4) && settings <= 32;
    key.params.clear();
    for (uint32_t i = 0; ok && i < settings; i++) {
        std::string name;
        float value = 0;
        ok = in.string(name, 32) && in.take(&value, 4) && !std::isinf(value);
        if (ok) key.params[name] = value;
    }
    if (!ok || in.at != size || p < 0 || p > 2) return false;
    key.path = static_cast<Path>(p);
    key.cct = cct;
    key.luxBlend = lux != 0;
    return true;
}

bool warmLook(const Key &key, bool background) {
    if (!engineReady()) return false;
    const Style *style = styleForId(key.look);
    if (!style) return false;
    style->warm(key, background);
    return true;
}

bool readKeyFile(const std::string &path, Key &key) {
    std::vector<uint8_t> blob;
    return readFile(path, blob) && readKeyBytes(blob.data(), blob.size(), key);
}

}  // namespace mastercolor

namespace {
std::mutex vigStateMutex;
bool gVigMasterMode = false;
bool gVigNativeOn = false;
}  // namespace

bool masterColorMasterMode() {
    std::lock_guard<std::mutex> lock(vigStateMutex);
    return gVigMasterMode;
}

void masterColorSetMasterMode(bool on) {
    std::lock_guard<std::mutex> lock(vigStateMutex);
    gVigMasterMode = on;
}

bool masterColorNativeVignette() {
    std::lock_guard<std::mutex> lock(vigStateMutex);
    return gVigNativeOn;
}

void masterColorSetNativeVignette(bool on) {
    std::lock_guard<std::mutex> lock(vigStateMutex);
    gVigNativeOn = on;
}

bool masterColorFilmPhysicalVignette(const std::string &, const mastercolor::Params &params) {
    auto it = params.find("vignette");
    return it != params.end() && it->second > 0.5f;
}

bool masterColorOpticalVignette() {
    std::string look;
    float tw = 1, cw = 1;
    mastercolor::Params params;
    masterColorLook(look, tw, cw, params);
    return masterColorFilmPhysicalVignette(look, params);
}

#ifdef __ANDROID__
extern "C" JNIEXPORT void JNICALL Java_local_jiege_hook_mastercolor_MasterPhysicalVignette_nativeSetState(JNIEnv *, jclass,
                                                                                                           jboolean masterMode,
                                                                                                           jboolean nativeVig) {
    std::lock_guard<std::mutex> lock(vigStateMutex);
    gVigMasterMode = masterMode != JNI_FALSE;
    gVigNativeOn = nativeVig != JNI_FALSE;
}

extern "C" JNIEXPORT jfloat JNICALL Java_local_jiege_hook_mastercolor_MasterPhysicalVignette_nativeFocalMm(JNIEnv *, jclass) {
    return softLightFocalMm();
}

extern "C" JNIEXPORT jfloatArray JNICALL Java_local_jiege_hook_mastercolor_MasterPhysicalVignette_nativeVignetteLut(JNIEnv *env, jclass,
                                                                                                                  jint w, jint h) {
    if (w < 2 || h < 2) return nullptr;
    const int count = int(w) * int(h);
    std::vector<float> out(static_cast<size_t>(count));
    if (!mastercolor::builtinVignetteLut(out.data(), w, h)) return nullptr;
    jfloatArray arr = env->NewFloatArray(count);
    if (arr) env->SetFloatArrayRegion(arr, 0, count, out.data());
    return arr;
}
#endif
