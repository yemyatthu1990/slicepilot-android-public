// Fast streaming preview parser: reads at most ~target triangles from an
// STL (binary/ASCII) or 3MF file and reduces them for the 3D preview.
#pragma once

#include <array>
#include <functional>
#include <string>
#include <vector>

namespace preview {

// One printable part: a range of the shared vertex/index buffers plus the authored bbox.
// Every 3MF build item becomes one part (an STL is a single part), so the app can print a
// subset of the file and move pieces individually instead of only the whole model.
struct PartGeometry {
    int vertex_start = 0;  // first vertex of the part in Preview::positions / 3
    int vertex_count = 0;
    int index_start = 0;   // first index of the part in Preview::indices
    int index_count = 0;   // indices, i.e. triangles * 3
    std::array<float, 6> bbox{{0.f, 0.f, 0.f, 0.f, 0.f, 0.f}};  // authored min/max xyz, mm
    std::string name;                                 // object name from the file, may be empty
};

struct Preview {
    std::vector<float> positions;  // xyz triples
    std::vector<float> normals;    // xyz triples, per vertex
    std::vector<int> indices;      // vertex-index triples
    // Parts in file order; the concatenation of their ranges is exactly the three arrays
    // above, so a part can be drawn or sliced on its own without copying geometry.
    std::vector<PartGeometry> parts;
    // True when build items use 21-float (part_platform) transforms, common in
    // newer Bambu files: the streaming parser cannot map the 9-slice plate grid,
    // so full-fidelity consumers (the slicer) must fall back to the full import.
    bool has_grid_transforms = false;
};

// `progress` (optional) receives monotonically increasing percentages 0..100.
// `keep_support` merges in-file support meshes (Prusa mesh-support) into the
// result; the slicer passes false so support is regenerated from settings.
bool parse_preview(const std::string &path, int target, Preview &out, std::string &err,
                   const std::function<void(int)> &progress = {}, bool keep_support = true);

} // namespace preview
