#ifndef slic3r_GPUColorPicker_hpp_
#define slic3r_GPUColorPicker_hpp_

#include "libslic3r/Point.hpp"
#include "libslic3r/Color.hpp"
#include "GLModel.hpp"

#include <memory>
#include <unordered_map>
#include <vector>

namespace Slic3r {

class GLShaderProgram;
class GLVolume;

namespace GUI {

struct Camera;

class GPUColorPicker
{
public:
    GPUColorPicker();
    ~GPUColorPicker();

    GPUColorPicker(const GPUColorPicker&) = delete;
    GPUColorPicker& operator=(const GPUColorPicker&) = delete;

    bool init(int width, int height);
    void shutdown();
    void resize(int width, int height);

    void render_for_picking(
        const std::vector<Slic3r::GLVolume*>& volumes,
        const Camera& camera
    );

    int pick_object_id(int mouse_x, int mouse_y);

    bool pick_detailed(
        int mouse_x,
        int mouse_y,
        int& out_volume_idx,
        Vec3f& out_world_pos,
        float* out_depth = nullptr
    );

    bool is_initialized() const { return m_fbo != 0; }

    std::pair<int, int> get_viewport_size() const { return {m_width, m_height}; }

    static ColorRGBA id_to_color(int id);
    static int color_to_id(const ColorRGBA& color);
    static int color_to_id(unsigned char r, unsigned char g, unsigned char b);

private:
    unsigned int m_fbo{0};
    unsigned int m_pick_texture{0};
    unsigned int m_depth_rbo{0};

    int m_width{0};
    int m_height{0};

    bool create_framebuffer();
    void destroy_framebuffer();
    bool check_framebuffer_status() const;
};

} // namespace GUI
} // namespace Slic3r

#endif // slic3r_GPUColorPicker_hpp_

