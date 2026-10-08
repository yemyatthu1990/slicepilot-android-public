#include "libslic3r/libslic3r.h"

#include "SceneRaycasterManager.hpp"

#include "3DScene.hpp"
#include "GPUColorPicker.hpp"
#include "Camera.hpp"

#include <igl/unproject.h>

#include <atomic>
#include <boost/log/trivial.hpp>
namespace Slic3r {
namespace GUI {

namespace {

std::atomic<SceneRaycasterManager::PickingSolution> g_last_picking_solution(
    SceneRaycasterManager::PickingSolution::None);

std::atomic<SceneRaycasterManager::PickingSolution> g_forced_picking_solution(
    SceneRaycasterManager::PickingSolution::None);

bool log_forced_gpu_once()
{
    static std::atomic<bool> logged(false);
    bool expected = false;
    if (logged.compare_exchange_strong(expected, true)) {
        BOOST_LOG_TRIVIAL(warning) << "[Test Mode] Forcing GPU Color Picking (ignoring MeshRaycaster)";
        return true;
    }
    return false;
}

bool log_forced_cpu_once()
{
    static std::atomic<bool> logged(false);
    bool expected = false;
    if (logged.compare_exchange_strong(expected, true)) {
        BOOST_LOG_TRIVIAL(warning) << "[Test Mode] Forcing CPU MeshRaycaster (GPU fallback disabled)";
        return true;
    }
    return false;
}

} // namespace

SceneRaycaster::HitResult SceneRaycasterManager::hit_with_fallback(
    const SceneRaycaster& raycaster,
    const std::vector<Slic3r::GLVolume*>& volumes,
    const Vec2d& mouse_pos,
    const Camera& camera,
    GPUColorPicker* gpu_picker,
    const ClippingPlane* clipping_plane)
{
    const PickingSolution forced_solution = get_forced_picking_solution();

    if (forced_solution == PickingSolution::GpuColorPicking) {
        log_forced_gpu_once();
        if (gpu_picker != nullptr && gpu_picker->is_initialized()) {
            SceneRaycaster::HitResult gpu_result = gpu_pick(volumes, mouse_pos, camera, gpu_picker);
            g_last_picking_solution.store(PickingSolution::GpuColorPicking, std::memory_order_relaxed);
            return gpu_result;
        }

        BOOST_LOG_TRIVIAL(error) << "Test-mode GPU color picking was forced, but the GPU picking system is unavailable";
        g_last_picking_solution.store(PickingSolution::GpuColorPicking, std::memory_order_relaxed);
        return {};
    }

    SceneRaycaster::HitResult cpu_result = raycaster.hit(mouse_pos, camera, clipping_plane);

    if (cpu_result.is_valid()) {
        g_last_picking_solution.store(PickingSolution::CpuRaycaster, std::memory_order_relaxed);
        return cpu_result;
    }

    if (forced_solution == PickingSolution::CpuRaycaster) {
        log_forced_cpu_once();
        g_last_picking_solution.store(PickingSolution::CpuRaycaster, std::memory_order_relaxed);
        return cpu_result;
    }

    bool has_unready_raycasters = false;
    for (const Slic3r::GLVolume* volume : volumes) {
        if (volume == nullptr || volume->is_wipe_tower)
            continue;

        if (!is_volume_raycaster_ready(volume)) {
            has_unready_raycasters = true;
            break;
        }
    }

    if (!has_unready_raycasters) {
        g_last_picking_solution.store(PickingSolution::CpuRaycaster, std::memory_order_relaxed);
        return cpu_result;
    }

    if (gpu_picker != nullptr && gpu_picker->is_initialized()) {
        SceneRaycaster::HitResult gpu_result = gpu_pick(volumes, mouse_pos, camera, gpu_picker);
        g_last_picking_solution.store(PickingSolution::GpuColorPicking, std::memory_order_relaxed);
        return gpu_result;
    }

    BOOST_LOG_TRIVIAL(warning) << "SceneRaycasterManager: Unable to use GPU fallback (GPUColorPicker not initialized)";
    g_last_picking_solution.store(PickingSolution::CpuRaycaster, std::memory_order_relaxed);
    return cpu_result;
}

SceneRaycasterManager::PickingSolution SceneRaycasterManager::get_last_picking_solution()
{
    return g_last_picking_solution.load(std::memory_order_relaxed);
}

void SceneRaycasterManager::set_forced_picking_solution(PickingSolution solution)
{
    g_forced_picking_solution.store(solution, std::memory_order_relaxed);
    if (solution == PickingSolution::None) {
        BOOST_LOG_TRIVIAL(info) << "[Test Mode] Picking solution switched to auto mode";
    }
}

SceneRaycasterManager::PickingSolution SceneRaycasterManager::get_forced_picking_solution()
{
    return g_forced_picking_solution.load(std::memory_order_relaxed);
}

bool SceneRaycasterManager::is_volume_raycaster_ready(const Slic3r::GLVolume* volume)
{
    if (volume == nullptr)
        return false;

    return volume->mesh_raycaster != nullptr;
}

SceneRaycaster::HitResult SceneRaycasterManager::gpu_pick(
    const std::vector<Slic3r::GLVolume*>& volumes,
    const Vec2d& mouse_pos,
    const Camera& camera,
    GPUColorPicker* gpu_picker)
{
    if (gpu_picker == nullptr || !gpu_picker->is_initialized())
        return {};

    gpu_picker->render_for_picking(volumes, camera);

    int volume_idx = -1;
    Vec3f world_pos_placeholder = Vec3f::Zero();
    float depth = 0.0f;
    const bool success = gpu_picker->pick_detailed(
        static_cast<int>(mouse_pos.x()),
        static_cast<int>(mouse_pos.y()),
        volume_idx,
        world_pos_placeholder,
        &depth);

    if (!success || volume_idx < 0 || volume_idx >= static_cast<int>(volumes.size()))
        return {};

    return create_hit_result_from_volume_idx(volumes, volume_idx, mouse_pos, camera, depth);
}

SceneRaycaster::HitResult SceneRaycasterManager::create_hit_result_from_volume_idx(
    const std::vector<Slic3r::GLVolume*>& volumes,
    int volume_idx,
    const Vec2d& mouse_pos,
    const Camera& camera,
    float depth)
{
    if (volume_idx < 0 || volume_idx >= static_cast<int>(volumes.size()))
        return {};

    const Slic3r::GLVolume* volume = volumes[volume_idx];
    if (volume == nullptr)
        return {};

    SceneRaycaster::HitResult result;
    result.type = SceneRaycaster::EType::Volume;
    result.raycaster_id = volume_idx;

    const Vec4i32 viewport(camera.get_viewport().data());
    const int gl_y = viewport[3] - static_cast<int>(mouse_pos.y());

    Vec3d world_pos = Vec3d::Zero();
    igl::unproject(
        Vec3d(mouse_pos.x(), gl_y, depth),
        camera.get_view_matrix().matrix(),
        camera.get_projection_matrix().matrix(),
        viewport,
        world_pos);

    result.position = world_pos.cast<float>();

    Vec3d camera_to_point = world_pos - camera.get_position();
    if (camera_to_point.squaredNorm() > EPSILON) {
        result.normal = -camera_to_point.normalized().cast<float>();
    } else {
        result.normal = Vec3f::UnitZ();
    }

    return result;
}

} // namespace GUI
} // namespace Slic3r


