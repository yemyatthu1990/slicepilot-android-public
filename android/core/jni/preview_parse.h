// Fast streaming preview parser: reads at most ~target triangles from an
// STL (binary/ASCII) or 3MF file and reduces them for the 3D preview.
#pragma once

#include <functional>
#include <string>
#include <vector>

namespace preview {

struct Preview {
    std::vector<float> positions;  // xyz triples
    std::vector<float> normals;    // xyz triples, per vertex
    std::vector<int> indices;      // vertex-index triples
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
