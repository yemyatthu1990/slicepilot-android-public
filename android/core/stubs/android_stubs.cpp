// Android stubs for features excluded from the Android core build.
#include <string>
#include <algorithm>
#include <cmath>
#include "libslic3r/Format/DRC.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/TriangleMesh.hpp"

// RGB2HSV normally lives in slic3r/Utils/ColorSpaceConvert.cpp which pulls in wxWidgets.
void RGB2HSV(float r, float g, float b, float* h, float* s, float* v)
{
    float Cmax = std::max(std::max(r, g), b);
    float Cmin = std::min(std::min(r, g), b);
    float delta = Cmax - Cmin;

    if (std::abs(delta) < 0.001f) {
        *h = 0.f;
    } else if (Cmax == r) {
        *h = 60.f * std::fmod((g - b) / delta, 6.f);
    } else if (Cmax == g) {
        *h = 60.f * ((b - r) / delta + 2);
    } else {
        *h = 60.f * ((r - g) / delta + 4);
    }

    if (std::abs(Cmax) < 0.001f) {
        *s = 0.f;
    } else {
        *s = delta / Cmax;
    }

    *v = Cmax;
}

namespace Slic3r {

// Draco (.drc) support is not compiled into the Android core.
bool load_drc(const char * /*path*/, TriangleMesh * /*mesh*/) { return false; }
bool load_drc(const char * /*path*/, Model * /*model*/, const char * /*object_name_in*/) { return false; }
bool store_drc(const char * /*path*/, TriangleMesh * /*mesh*/, int /*bits*/, int /*speed*/) { return false; }
bool store_drc(const char * /*path*/, ModelObject * /*model_object*/, int /*bits*/, int /*speed*/) { return false; }
bool store_drc(const char * /*path*/, Model * /*model*/, int /*bits*/, int /*speed*/) { return false; }

} // namespace Slic3r
