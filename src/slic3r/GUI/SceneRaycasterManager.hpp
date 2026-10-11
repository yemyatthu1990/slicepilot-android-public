#ifndef slic3r_SceneRaycasterManager_hpp_
#define slic3r_SceneRaycasterManager_hpp_

#include "SceneRaycaster.hpp"
#include "libslic3r/Point.hpp"

#include <vector>

namespace Slic3r {

class GLVolume;

namespace GUI {

struct Camera;
class GPUColorPicker;

/**
 * @brief GPU picking manager for SceneRaycaster
 *
 * - Uses GPU Color Picking as a fallback while MeshRaycaster is not ready
 * - Switches back to CPU ray casting once MeshRaycaster finishes building
 * - Provides test toggles to force GPU/CPU solutions
 */
class SceneRaycasterManager
{
public:
    enum class PickingSolution
    {
        None = 0,
        CpuRaycaster,
        GpuColorPicking
    };

    /**
     * @brief Enhanced picking API that automatically chooses GPU or CPU flow
     *
     * @param raycaster      Original SceneRaycaster
     * @param volumes        List of GLVolume objects (for GPU picking)
     * @param mouse_pos      Mouse position in window coordinates
     * @param camera        Active camera
     * @param gpu_picker     GPU Color Picker instance
     * @param clipping_plane Optional clipping plane
     */
    static SceneRaycaster::HitResult hit_with_fallback(
        const SceneRaycaster& raycaster,
        const std::vector<Slic3r::GLVolume*>& volumes,
        const Vec2d& mouse_pos,
        const Camera& camera,
        GPUColorPicker* gpu_picker,
        const ClippingPlane* clipping_plane = nullptr
    );

    static PickingSolution get_last_picking_solution();

    /**
     * @brief Force a picking solution (test hook)
     *
     * - None: automatic mode
     * - CpuRaycaster: always use MeshRaycaster
     * - GpuColorPicking: always use GPU Color Picking
     */
    static void set_forced_picking_solution(PickingSolution solution);
    static PickingSolution get_forced_picking_solution();

private:
    static bool is_volume_raycaster_ready(const Slic3r::GLVolume* volume);

    static SceneRaycaster::HitResult gpu_pick(
        const std::vector<Slic3r::GLVolume*>& volumes,
        const Vec2d& mouse_pos,
        const Camera& camera,
        GPUColorPicker* gpu_picker
    );

    /**
     * @brief Build a HitResult from a volume index and depth value
     *
     * @param depth Depth value from the GPU FBO, used for back-projection
     */
    static SceneRaycaster::HitResult create_hit_result_from_volume_idx(
        const std::vector<Slic3r::GLVolume*>& volumes,
        int volume_idx,
        const Vec2d& mouse_pos,
        const Camera& camera,
        float depth
    );
};

} // namespace GUI
} // namespace Slic3r

#endif // slic3r_SceneRaycasterManager_hpp_

