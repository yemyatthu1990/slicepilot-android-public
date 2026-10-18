// Fast streaming preview parser for STL (binary/ASCII) and 3MF.
//
// Reads at most ~target triangles from the file: for 3MF, a first pass counts
// triangles per object (following build items and components across the
// external .model files a 3MF may reference), a second pass keeps a strided
// sample of the geometry, then everything is grid-clustered down to the target
// budget. Big assemblies / hi-poly meshes preview in seconds; slicing is
// unaffected (it re-reads the original file at full detail).
#include "preview_parse.h"

#include <expat.h>
#include <miniz.h>

#include <algorithm>
#include <array>
#include <cfloat>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <deque>
#include <functional>
#include <map>
#include <set>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

namespace preview {

namespace {

// ---------------------------------------------------------------- utilities

const char *bare_name(const XML_Char *name) {
    const char *c = std::strrchr(name, ':');
    return c ? c + 1 : name;
}

const XML_Char *attr(const XML_Char **attribs, const char *name) {
    for (int i = 0; attribs[i]; i += 2)
        if (std::strcmp(attribs[i], name) == 0) return attribs[i + 1];
    return nullptr;
}

float fattr(const XML_Char **a, const char *n, float dflt = 0.f) {
    const XML_Char *v = attr(a, n);
    return v ? (float) std::atof(v) : dflt;
}

// Parse a 3MF "transform" (12 floats, row-major 3x4), scaling the translation by unit.
bool parse_matrix(const char *s, float out[12], float unit) {
    float v[12];
    int n = 0;
    const char *p = s;
    while (*p && n < 12) {
        char *end;
        v[n++] = (float) std::strtod(p, &end);
        if (end == p) break;
        p = end;
    }
    if (n < 12) return false;
    for (int i = 0; i < 9; ++i) out[i] = v[i];
    out[9] = v[9] * unit;
    out[10] = v[10] * unit;
    out[11] = v[11] * unit;
    return true;
}

void apply_matrix(const float m[12], const float v[3], float out[3]) {
    out[0] = m[0] * v[0] + m[3] * v[1] + m[6] * v[2] + m[9];
    out[1] = m[1] * v[0] + m[4] * v[1] + m[7] * v[2] + m[10];
    out[2] = m[2] * v[0] + m[5] * v[1] + m[8] * v[2] + m[11];
}

void mul_matrix(const float a[12], const float b[12], float out[12]) {
    for (int col = 0; col < 3; ++col)
        for (int row = 0; row < 3; ++row)
            out[col * 3 + row] = a[row] * b[col * 3] + a[3 + row] * b[col * 3 + 1] + a[6 + row] * b[col * 3 + 2];
    out[9] = a[0] * b[9] + a[3] * b[10] + a[6] * b[11] + a[9];
    out[10] = a[1] * b[9] + a[4] * b[10] + a[7] * b[11] + a[10];
    out[11] = a[2] * b[9] + a[5] * b[10] + a[8] * b[11] + a[11];
}

std::string norm_path(const std::string &p) {
    std::string s;
    s.reserve(p.size());
    for (char c : p) s.push_back(c == '\\' ? '/' : c);
    while (!s.empty() && s[0] == '/') s.erase(s.begin());
    std::vector<std::string> parts;
    size_t i = 0;
    while (i < s.size()) {
        size_t j = s.find('/', i);
        if (j == std::string::npos) j = s.size();
        std::string seg = s.substr(i, j - i);
        if (seg.empty() || seg == ".") {}
        else if (seg == "..") { if (!parts.empty()) parts.pop_back(); }
        else parts.push_back(seg);
        i = j + 1;
    }
    std::string out;
    for (size_t k = 0; k < parts.size(); ++k) {
        if (k) out.push_back('/');
        out += parts[k];
    }
    return out;
}

std::string resolve_path(const std::string &base_file, const std::string &rel) {
    std::string s = rel;
    for (char &c : s) if (c == '\\') c = '/';
    if (!s.empty() && s[0] == '/') return norm_path(s);
    const size_t slash = base_file.rfind('/');
    const std::string dir = slash == std::string::npos ? std::string() : base_file.substr(0, slash);
    return norm_path(dir.empty() ? s : dir + "/" + s);
}

// ---------------------------------------------------------------- clustering

// Monotonic 0..100 progress reporter.
struct Prog {
    const std::function<void(int)> *cb = nullptr;
    mutable int last = -1;
    void operator()(int p) const {
        if (!cb) return;
        p = std::max(0, std::min(100, p));
        if (p > last) { last = p; (*cb)(p); }
    }
};

// Snap vertices to a grid and drop collapsed triangles. Vertices are placed at
// the centroid of everything that fell into their cell, which preserves surface
// shape far better than snapping to cell centers. The cell size is derived from
// the mesh *surface area* (not the bounding-box volume, which wastes resolution
// on the empty space of multi-plate layouts), and refined until the decimated
// mesh lands near the triangle target.
void grid_cluster(std::vector<float> &pos, std::vector<int> &idx, int target) {
    const size_t tris = idx.size() / 3;
    if ((int) tris <= target || tris == 0) return;
    float mn[3] = {FLT_MAX, FLT_MAX, FLT_MAX}, mx[3] = {-FLT_MAX, -FLT_MAX, -FLT_MAX};
    for (size_t i = 0; i + 2 < pos.size(); i += 3)
        for (int k = 0; k < 3; ++k) {
            mn[k] = std::min(mn[k], pos[i + k]);
            mx[k] = std::max(mx[k], pos[i + k]);
        }
    float d[3];
    for (int k = 0; k < 3; ++k) d[k] = std::max(mx[k] - mn[k], 1e-3f);
    const float maxdim = std::max(d[0], std::max(d[1], d[2]));

    // Total triangle surface area, used to size the grid to the surface itself.
    double area = 0.0;
    for (size_t t = 0; t + 2 < idx.size(); t += 3) {
        const float *a = &pos[(size_t) idx[t] * 3];
        const float *b = &pos[(size_t) idx[t + 1] * 3];
        const float *c = &pos[(size_t) idx[t + 2] * 3];
        const float e1[3] = {b[0] - a[0], b[1] - a[1], b[2] - a[2]};
        const float e2[3] = {c[0] - a[0], c[1] - a[1], c[2] - a[2]};
        const float nx = e1[1] * e2[2] - e1[2] * e2[1];
        const float ny = e1[2] * e2[0] - e1[0] * e2[2];
        const float nz = e1[0] * e2[1] - e1[1] * e2[0];
        area += 0.5f * std::sqrt(nx * nx + ny * ny + nz * nz);
    }
    // Each grid cell on the surface yields ~1.5 triangles on average; start from
    // that estimate and iterate up to twice.
    float s = std::sqrt(area / (1.5 * (double) target));
    s = std::max(s, maxdim / 4095.f);
    s = std::max(s, 1e-6f);

    struct Cell { double x = 0, y = 0, z = 0; long n = 0; int vert = -1; };
    std::vector<int> nidx;
    std::vector<float> npos;
    for (int iter = 0; iter < 3; ++iter) {
        std::unordered_map<int64_t, Cell> cell;
        cell.reserve(std::min((size_t) target * 4, tris));
        nidx.clear();
        nidx.reserve((size_t) target * 3);
        int v[3];
        for (size_t t = 0; t + 2 < idx.size(); t += 3) {
            bool ok = true;
            for (int k = 0; k < 3 && ok; ++k) {
                const float *p = &pos[(size_t) idx[t + k] * 3];
                if (std::isnan(p[0])) { ok = false; break; }
                const int ix = std::min((int) std::floor((p[0] - mn[0]) / s), 4095);
                const int iy = std::min((int) std::floor((p[1] - mn[1]) / s), 4095);
                const int iz = std::min((int) std::floor((p[2] - mn[2]) / s), 4095);
                const int64_t key = ((int64_t) ix << 24) | ((int64_t) iy << 12) | iz;
                Cell &c = cell[key];
                c.x += p[0]; c.y += p[1]; c.z += p[2]; ++c.n;
                if (c.vert < 0) c.vert = (int) cell.size() - 1; // stable id (first insertion index)
                v[k] = c.vert;
            }
            if (!ok) continue;
            if (v[0] != v[1] && v[1] != v[2] && v[0] != v[2]) {
                nidx.push_back(v[0]); nidx.push_back(v[1]); nidx.push_back(v[2]);
            }
        }
        const size_t out = nidx.size() / 3;
        if (out <= (size_t) target * 22 / 10 || iter == 2) {
            if (nidx.empty()) return;
            npos.assign(cell.size() * 3, 0.f);
            for (const auto &kv : cell) {
                const Cell &c = kv.second;
                if (c.vert < 0 || c.n <= 0) continue;
                npos[(size_t) c.vert * 3] = (float) (c.x / c.n);
                npos[(size_t) c.vert * 3 + 1] = (float) (c.y / c.n);
                npos[(size_t) c.vert * 3 + 2] = (float) (c.z / c.n);
            }
            pos.swap(npos);
            idx.swap(nidx);
            return;
        }
        // Too many triangles: enlarge the grid and repeat.
        s *= std::sqrt((double) out / (1.5 * target));
    }
}

// Drop vertices no surviving triangle references.
void compact_vertices(std::vector<float> &pos, std::vector<int> &idx) {
    const size_t nv = pos.size() / 3;
    std::vector<int> remap(nv, -1);
    std::vector<float> np;
    np.reserve(idx.size() * 3);
    for (int &v : idx) {
        int r = remap[v];
        if (r < 0) {
            r = (int) (np.size() / 3);
            np.push_back(pos[(size_t) v * 3]);
            np.push_back(pos[(size_t) v * 3 + 1]);
            np.push_back(pos[(size_t) v * 3 + 2]);
            remap[v] = r;
        }
        v = r;
    }
    pos.swap(np);
}

void smooth_normals(const std::vector<float> &pos, const std::vector<int> &idx, std::vector<float> &nrm) {
    nrm.assign(pos.size(), 0.f);
    for (size_t t = 0; t + 2 < idx.size(); t += 3) {
        const float *a = &pos[(size_t) idx[t] * 3];
        const float *b = &pos[(size_t) idx[t + 1] * 3];
        const float *c = &pos[(size_t) idx[t + 2] * 3];
        const float e1[3] = {b[0] - a[0], b[1] - a[1], b[2] - a[2]};
        const float e2[3] = {c[0] - a[0], c[1] - a[1], c[2] - a[2]};
        const float n[3] = {e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0]};
        for (int k = 0; k < 3; ++k) {
            const size_t o = (size_t) idx[t + k] * 3;
            nrm[o] += n[0]; nrm[o + 1] += n[1]; nrm[o + 2] += n[2];
        }
    }
    for (size_t i = 0; i + 2 < nrm.size(); i += 3) {
        const float x = nrm[i], y = nrm[i + 1], z = nrm[i + 2];
        const float len = std::sqrt(x * x + y * y + z * z);
        if (len > 1e-9f) { nrm[i] = x / len; nrm[i + 1] = y / len; nrm[i + 2] = z / len; }
        else nrm[i + 2] = 1.f;
    }
}

// ---------------------------------------------------------------- STL

bool parse_stl(const std::string &path, int target, Preview &out, std::string &err,
               const std::function<void(int)> &progress) {
    Prog prog{&progress};
    prog(3);
    FILE *f = std::fopen(path.c_str(), "rb");
    if (!f) { err = "cannot open file"; return false; }
    std::vector<float> raw;
    std::vector<int> idx;
    raw.reserve((size_t) target * 9);
    idx.reserve((size_t) target * 3);

    unsigned char head[5];
    const size_t hn = std::fread(head, 1, 5, f);
    // Binary STL: size matches the 80-byte header + 4-byte count + 50 B/facet,
    // even when the exporter wrote a "solid" header (ASCII starts with "solid"
    // and does not match the size formula).
    bool binary = hn != 5 || std::memcmp(head, "solid", 5) != 0;
    if (!binary) {
        std::fseek(f, 0, SEEK_END);
        const long sz = std::ftell(f);
        uint32_t n = 0;
        std::fseek(f, 80, SEEK_SET);
        if (std::fread(&n, 1, 4, f) == 4 && n > 0 && (long) n * 50 + 84 == sz) binary = true;
    }
    std::fseek(f, 0, SEEK_SET);

    if (binary) {
        std::fseek(f, 0, SEEK_SET);
        unsigned char hdr[84];
        if (std::fread(hdr, 1, 84, f) != 84) { std::fclose(f); err = "short header"; return false; }
        uint32_t n = 0;
        std::memcpy(&n, hdr + 80, 4);
        const size_t stride = std::max<size_t>(1, (size_t) n / 5000000);
        unsigned char rec[50];
        size_t kept = 0;
        for (uint32_t i = 0; i < n; ++i) {
            if (std::fread(rec, 1, 50, f) != 50) break;
            if ((i / 128) % stride) continue;
            if ((i & 0xFFFF) == 0) prog(5 + (int)(80.0 * i / n));
            const float *frec = (const float *) rec;
            for (int k = 0; k < 3; ++k) {
                raw.push_back(frec[3 + k * 3]);
                raw.push_back(frec[4 + k * 3]);
                raw.push_back(frec[5 + k * 3]);
                idx.push_back((int) kept++);
            }
            if (kept >= 5000000 * 3) break;
        }
    } else {
        // ASCII STL: count facets first, then stream with a stride.
        char line[512];
        size_t vertex_lines = 0;
        while (std::fgets(line, sizeof line, f)) {
            if (std::strstr(line, "vertex")) ++vertex_lines;
            if (((vertex_lines >> 16) & 15) == 0 && vertex_lines) prog(2 + (int)(28.0 * vertex_lines / (1 + vertex_lines + 1e-9)));
        }
        const size_t facets = vertex_lines / 3;
        const size_t stride = std::max<size_t>(1, facets / 1000000);
        std::rewind(f);
        size_t facet_idx = 0;
        float cur[9];
        int cur_n = 0;
        while (std::fgets(line, sizeof line, f)) {
            if ((facet_idx & 0xFFFF) == 0 && facet_idx) prog(30 + (int)(55.0 * facet_idx / facets));
            if (std::sscanf(line, " vertex %f %f %f", cur + cur_n, cur + cur_n + 1, cur + cur_n + 2) == 3) {
                cur_n += 3;
                if (cur_n == 9) {
                    if ((facet_idx / 128) % stride == 0) {
                        const size_t base = raw.size() / 3;
                        for (int k = 0; k < 9; ++k) raw.push_back(cur[k]);
                        idx.push_back((int) base);
                        idx.push_back((int) (base + 1));
                        idx.push_back((int) (base + 2));
                        if (raw.size() / 3 >= 1000000 * 3) break;
                    }
                    ++facet_idx;
                    cur_n = 0;
                }
            }
        }
    }
    std::fclose(f);
    prog(85);
    if (idx.empty()) { err = "no triangles"; return false; }
    grid_cluster(raw, idx, target);
    prog(90);
    compact_vertices(raw, idx);
    prog(94);
    smooth_normals(raw, idx, out.normals);
    out.positions.swap(raw);
    out.indices.swap(idx);
    prog(100);
    return true;
}

// ---------------------------------------------------------------- 3MF

struct MObject {
    std::vector<float> verts;   // pass 2: xyz triples, unit-scaled (needed objects)
    std::vector<int> tris;      // pass 2: sampled vertex-index triples
    size_t tri_count = 0;       // pass 1: full triangle count
    std::vector<int> comp_obj;  // pass 1: component children
    std::vector<std::string> comp_path;
    std::vector<std::array<float, 12>> comp_tr;
};

struct MItem {
    std::string path;           // empty = same file
    int objectid = 0;
    bool has_tr = false;
    std::array<float, 12> tr{};
};

struct MFFile {
    std::string path;
    std::unordered_map<int, MObject> objects;
    std::vector<MItem> items;   // build items (root file only)
};

struct Ctx {
    MFFile *file = nullptr;
    float unit = 1.f;
    bool pass2 = false;
    size_t stride = 1;
    const std::set<int> *needed = nullptr;
    int cur_id = -1;
    bool cur_needed = true;
    bool in_object = false;
    bool in_build = false;
    size_t tri_seen = 0;
};

void XMLCALL mf_start(void *ud, const XML_Char *name, const XML_Char **attribs) {
    Ctx &c = *(Ctx *) ud;
    MFFile &f = *c.file;
    const char *el = bare_name(name);
    if (!std::strcmp(el, "model")) {
        const XML_Char *unit = attr(attribs, "unit");
        if (unit) {
            if (!std::strcmp(unit, "micron")) c.unit = 0.001f;
            else if (!std::strcmp(unit, "centimeter")) c.unit = 10.f;
            else if (!std::strcmp(unit, "inch")) c.unit = 25.4f;
            else if (!std::strcmp(unit, "meter")) c.unit = 1000.f;
            else if (!std::strcmp(unit, "foot")) c.unit = 304.8f;
        }
    } else if (!std::strcmp(el, "build")) {
        c.in_build = true;
    } else if (!std::strcmp(el, "object")) {
        const XML_Char *id = attr(attribs, "id");
        c.cur_id = id ? std::atoi(id) : -1;
        c.in_object = c.cur_id >= 0;
        c.tri_seen = 0;
        c.cur_needed = !c.pass2 || (c.needed && c.needed->count(c.cur_id) > 0);
        if (c.pass2 && c.cur_id >= 0) {
            MObject &o = f.objects[c.cur_id];
            o.verts.clear();
            o.tris.clear();
        }
    } else if (!std::strcmp(el, "vertex")) {
        if (!c.in_object || !c.cur_needed) return;
        MObject &o = f.objects[c.cur_id];
        o.verts.push_back(fattr(attribs, "x", 0.f) * c.unit);
        o.verts.push_back(fattr(attribs, "y", 0.f) * c.unit);
        o.verts.push_back(fattr(attribs, "z", 0.f) * c.unit);
    } else if (!std::strcmp(el, "triangle")) {
        if (!c.in_object) return;
        MObject &o = f.objects[c.cur_id];
        if (!c.pass2) {
            ++o.tri_count;
        } else {
            if (c.cur_needed) {
                // Keep contiguous runs of triangles (exporters emit spatially
                // coherent strips), so sampled geometry forms patches, not confetti.
                if ((c.tri_seen / 128) % c.stride == 0) {
                    const XML_Char *a = attr(attribs, "v1");
                    const XML_Char *b = attr(attribs, "v2");
                    const XML_Char *cc = attr(attribs, "v3");
                    if (a && b && cc) {
                        o.tris.push_back(std::atoi(a));
                        o.tris.push_back(std::atoi(b));
                        o.tris.push_back(std::atoi(cc));
                    }
                }
                ++c.tri_seen;
            }
        }
    } else if (!std::strcmp(el, "component")) {
        const XML_Char *oid = attr(attribs, "objectid");
        if (!oid) oid = attr(attribs, "object");
        if (!oid || !c.in_object || c.pass2) return;
        // Bambu Studio writes the path as the namespaced "p:path" production attribute.
        const XML_Char *p = attr(attribs, "path");
        if (!p) p = attr(attribs, "p:path");
        const XML_Char *tr = attr(attribs, "transform");
        MObject &o = f.objects[c.cur_id];
        o.comp_obj.push_back(std::atoi(oid));
        o.comp_path.push_back(p ? p : "");
        std::array<float, 12> m{};
        if (tr) parse_matrix(tr, m.data(), c.unit);
        else for (int i = 0; i < 9; ++i) m[i] = (i % 4 == 0) ? 1.f : 0.f;
        o.comp_tr.push_back(m);
    } else if (!std::strcmp(el, "item")) {
        if (!c.in_build || c.pass2) return;
        const XML_Char *oid = attr(attribs, "objectid");
        if (!oid) oid = attr(attribs, "object");
        if (!oid) return;
        const XML_Char *p = attr(attribs, "path");
        if (!p) p = attr(attribs, "p:path");
        const XML_Char *tr = attr(attribs, "transform");
        MItem it;
        it.path = p ? p : "";
        it.objectid = std::atoi(oid);
        it.has_tr = tr && parse_matrix(tr, it.tr.data(), c.unit);
        f.items.push_back(it);
    }
}

struct ZipFeed {
    XML_Parser parser;
    bool ok = true;
    size_t bytes = 0;
    size_t last_report = 0;
    const Prog *prog = nullptr;
    int lo = 0, hi = 0;
    size_t base_bytes = 0, total_bytes = 1;
};

size_t XMLCALL zip_read(void *user, mz_uint64, const void *buf, size_t len) {
    ZipFeed &feed = *(ZipFeed *) user;
    if (feed.ok)
        feed.ok = XML_Parse(feed.parser, (const char *) buf, (int) len, 0) != 0;
    if (feed.ok) {
        feed.bytes += len;
        if (feed.prog && feed.bytes - feed.last_report >= (1u << 22)) {
            feed.last_report = feed.bytes;
            const double frac = (double)(feed.base_bytes + feed.bytes) / (double) std::max<size_t>(1, feed.total_bytes);
            (*feed.prog)(feed.lo + (int)((feed.hi - feed.lo) * std::min(frac, 1.0)));
        }
    }
    return feed.ok ? len : 0;
}

bool run_pass(mz_zip_archive &zip, int file_idx, MFFile &file, bool pass2, size_t stride,
              const std::set<int> *needed, const Prog *prog = nullptr,
              int lo = 0, int hi = 0, size_t base_bytes = 0, size_t total_bytes = 1) {
    Ctx c;
    c.file = &file;
    c.pass2 = pass2;
    c.stride = std::max<size_t>(1, stride);
    c.needed = needed;
    XML_Parser parser = XML_ParserCreate(nullptr);
    XML_SetUserData(parser, &c);
    XML_SetElementHandler(parser, mf_start, nullptr);
    ZipFeed feed{parser, true, 0, 0, prog, lo, hi, base_bytes, total_bytes};
    mz_zip_reader_extract_to_callback(&zip, file_idx, zip_read, &feed, 0);
    XML_Parse(parser, nullptr, 0, 1);
    XML_ParserFree(parser);
    return feed.ok;
}

bool parse_3mf(const std::string &path, int target, Preview &out, std::string &err,
               const std::function<void(int)> &progress) {
    Prog prog{&progress};
    prog(1);
    mz_zip_archive zip;
    std::memset(&zip, 0, sizeof zip);
    if (!mz_zip_reader_init_file(&zip, path.c_str(), 0)) { err = "cannot open zip"; return false; }

    auto locate = [&](const std::string &p) -> int {
        int idx = mz_zip_reader_locate_file(&zip, p.c_str(), nullptr, 0);
        if (idx < 0) idx = mz_zip_reader_locate_file(&zip, ("/" + p).c_str(), nullptr, 0);
        return idx;
    };

    // Root model part via _rels, falling back to the standard path.
    std::string root = "3D/3dmodel.model";
    {
        const int rels_idx = locate("_rels/.rels");
        if (rels_idx >= 0) {
            mz_zip_archive_file_stat stt;
            if (mz_zip_reader_file_stat(&zip, (mz_uint) rels_idx, &stt) && stt.m_uncomp_size < 64 * 1024) {
                std::vector<char> buf(stt.m_uncomp_size);
                if (mz_zip_reader_extract_to_mem(&zip, (mz_uint) rels_idx, buf.data(), buf.size(), 0) == buf.size()) {
                    const std::string rels(buf.data(), buf.size());
                    const size_t p = rels.find("3dmodel");
                    if (p != std::string::npos) {
                        const size_t q = rels.rfind("Target=\"", p);
                        if (q != std::string::npos) {
                            const size_t from = q + 8;
                            const size_t to = rels.find('"', from);
                            root = norm_path(rels.substr(from, to - from));
                        }
                    }
                }
            }
        }
    }
    if (locate(root) < 0) { mz_zip_reader_end(&zip); err = "no model part"; return false; }

    // Pass 1 over the referenced file graph: counts, build items, components.
    std::map<std::string, MFFile> files;
    std::deque<std::string> queue{root};
    std::set<std::string> parsed;
    size_t done1 = 0, total1 = 0;
    while (!queue.empty()) {
        const std::string fp = queue.front();
        queue.pop_front();
        if (!parsed.insert(fp).second) continue;
        const int idx = locate(fp);
        if (idx < 0) continue;
        MFFile &f = files[fp];
        f.path = fp;
        mz_zip_archive_file_stat fst;
        const size_t fsz = mz_zip_reader_file_stat(&zip, (mz_uint) idx, &fst) ? fst.m_uncomp_size : 0;
        total1 += fsz;
        bool ok = true;
        if (!run_pass(zip, idx, f, false, 1, nullptr, &prog, 2, 35, done1, total1)) ok = false;
        done1 += fsz;
        if (!ok) { mz_zip_reader_end(&zip); err = "xml pass1 failed in " + fp; return false; }
        for (const MItem &it : f.items)
            if (!it.path.empty()) queue.push_back(resolve_path(fp, it.path));
        for (const auto &kv : f.objects)
            for (size_t c = 0; c < kv.second.comp_path.size(); ++c)
                if (!kv.second.comp_path[c].empty())
                    queue.push_back(resolve_path(fp, kv.second.comp_path[c]));
    }
    if (files.empty()) { mz_zip_reader_end(&zip); err = "no model files"; return false; }
    if (!files.count(root)) { mz_zip_reader_end(&zip); err = "root model unparseable"; return false; }

    // Objects actually referenced from the build (through component chains).
    typedef std::pair<std::string, int> Ref;
    std::set<Ref> needed;
    {
        std::deque<Ref> q;
        MFFile &rf = files[root];
        if (rf.items.empty()) {
            for (const auto &kv : rf.objects) q.push_back(Ref(root, kv.first));
        } else {
            for (const MItem &it : rf.items)
                q.push_back(Ref(it.path.empty() ? root : resolve_path(root, it.path), it.objectid));
        }
        while (!q.empty()) {
            Ref r = q.front();
            q.pop_front();
            if (r.first == root && !files.count(r.first)) continue;
            auto fit = files.find(r.first);
            if (fit == files.end()) continue;
            auto oit = fit->second.objects.find(r.second);
            if (oit == fit->second.objects.end()) continue;
            if (!needed.insert(r).second) continue;
            MObject &o = oit->second;
            for (size_t c = 0; c < o.comp_obj.size(); ++c) {
                const std::string cp = o.comp_path[c].empty() ? r.first : resolve_path(r.first, o.comp_path[c]);
                q.push_back(Ref(cp, o.comp_obj[c]));
            }
        }
    }
    if (needed.empty()) { mz_zip_reader_end(&zip); err = "no build objects"; return false; }

    size_t total = 0;
    for (const Ref &r : needed)
        total += files[r.first].objects[r.second].tri_count;
    // The clusterer needs the *full* surface: sampling triangles out before
    // clustering leaves gaps that render as transparency. Only pathological
    // files (>5M triangles) get thinned, and even then by a small factor.
    const size_t stride = std::max<size_t>(1, total / 5000000);

    // Pass 2: sampled geometry of the needed objects, per file.
    prog(36);
    std::map<std::string, std::set<int>> by_file;
    for (const Ref &r : needed) by_file[r.first].insert(r.second);
    size_t done2 = 0, total2 = 0;
    {
        mz_zip_archive_file_stat fst;
        for (const auto &kv : by_file) {
            const int i = locate(kv.first);
            if (i >= 0 && mz_zip_reader_file_stat(&zip, (mz_uint) i, &fst))
                total2 += fst.m_uncomp_size;
        }
    }
    for (auto &kv : by_file) {
        auto fit = files.find(kv.first);
        if (fit == files.end()) continue;
        const int idx = locate(kv.first);
        if (idx < 0) continue;
        mz_zip_archive_file_stat fst;
        const size_t fsz = mz_zip_reader_file_stat(&zip, (mz_uint) idx, &fst) ? fst.m_uncomp_size : 0;
        if (!run_pass(zip, idx, fit->second, true, stride, &kv.second, &prog, 36, 80, done2, total2)) {
            mz_zip_reader_end(&zip);
            err = "xml pass2 failed in " + kv.first;
            return false;
        }
        done2 += fsz;
    }
    mz_zip_reader_end(&zip);
    prog(82);

    // Instantiate the sampled geometry with build item transforms. Each build
    // item gets a share of the triangle budget proportional to its sampled
    // size, so multi-plate assemblies render whole instead of truncating the
    // later items.
    std::vector<float> raw;
    std::vector<int> idx;
    raw.reserve((size_t) target * 9);
    idx.reserve((size_t) target * 3);
    // Memory guards for pathological files; normal files stay below these.
    const size_t max_verts = 12000000;
    const size_t max_tris = 5000000;

    MFFile &rf = files[root];
    std::vector<MItem> items = rf.items;
    if (items.empty())
        for (const auto &kv : rf.objects) items.push_back(MItem{std::string(), kv.first, false, {}});

    std::function<void(const std::string &, int, const float *, int, size_t &)> place =
        [&](const std::string &fp, int oid, const float *tr, int depth, size_t &budget) {
            if (depth > 32 || budget == 0) return;
            if (raw.size() / 3 >= max_verts) return;
            auto fit = files.find(fp);
            if (fit == files.end()) return;
            auto oit = fit->second.objects.find(oid);
            if (oit == fit->second.objects.end()) return;
            MObject &o = oit->second;
            const size_t base = raw.size() / 3;
            const size_t nv = o.verts.size() / 3;
            for (size_t v = 0; v < nv && raw.size() / 3 < max_verts; ++v) {
                const float *p = &o.verts[v * 3];
                if (tr) {
                    float q[3];
                    apply_matrix(tr, p, q);
                    raw.push_back(q[0]); raw.push_back(q[1]); raw.push_back(q[2]);
                } else {
                    raw.push_back(p[0]); raw.push_back(p[1]); raw.push_back(p[2]);
                }
            }
            const size_t appended = raw.size() / 3 - base;
            for (size_t t = 0; t + 2 < o.tris.size(); t += 3) {
                if (budget == 0) break;
                const int i0 = o.tris[t], i1 = o.tris[t + 1], i2 = o.tris[t + 2];
                if (i0 < (int) appended && i1 < (int) appended && i2 < (int) appended) {
                    idx.push_back((int) (base + i0));
                    idx.push_back((int) (base + i1));
                    idx.push_back((int) (base + i2));
                    --budget;
                }
            }
            for (size_t c = 0; c < o.comp_obj.size() && budget > 0; ++c) {
                const std::string cp = o.comp_path[c].empty() ? fp : resolve_path(fp, o.comp_path[c]);
                std::array<float, 12> m;
                if (tr) mul_matrix(tr, o.comp_tr[c].data(), m.data());
                else m = o.comp_tr[c];
                place(cp, o.comp_obj[c], m.data(), depth + 1, budget);
            }
        };

    size_t budget = max_tris;
    for (const MItem &it : items) {
        if (budget == 0) break;
        const std::string fp = it.path.empty() ? root : resolve_path(root, it.path);
        place(fp, it.objectid, it.has_tr ? it.tr.data() : nullptr, 0, budget);
    }
    if (idx.empty()) { err = "no triangles"; return false; }
    grid_cluster(raw, idx, target);
    prog(90);
    compact_vertices(raw, idx);
    prog(94);
    smooth_normals(raw, idx, out.normals);
    out.positions.swap(raw);
    out.indices.swap(idx);
    prog(100);
    return true;
}

} // namespace

bool parse_preview(const std::string &path, int target, Preview &out, std::string &err,
                   const std::function<void(int)> &progress) {
    FILE *f = std::fopen(path.c_str(), "rb");
    if (!f) { err = "cannot open file"; return false; }
    unsigned char magic[4] = {0};
    const size_t n = std::fread(magic, 1, 4, f);
    std::fclose(f);
    const bool is_zip = n == 4 && magic[0] == 'P' && magic[1] == 'K';
    return is_zip ? parse_3mf(path, target, out, err, progress)
                  : parse_stl(path, target, out, err, progress);
}

} // namespace preview
