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
};

// `progress` (optional) receives monotonically increasing percentages 0..100.
bool parse_preview(const std::string &path, int target, Preview &out, std::string &err,
                   const std::function<void(int)> &progress = {});

} // namespace preview
