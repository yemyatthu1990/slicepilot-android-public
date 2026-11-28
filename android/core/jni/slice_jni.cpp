// JNI bridge between the Kotlin app and the native slicing core.
#include <jni.h>
#include <string>
#include <memory>
#include <thread>
#include <atomic>
#include <mutex>
#include <typeinfo>
#include <set>
#include <algorithm>
#include <cctype>
#include <cmath>

#include <android/log.h>

#include "libslic3r/Model.hpp"
#include "libslic3r/Config.hpp"
#include "libslic3r/Print.hpp"
#include "libslic3r/PrintConfig.hpp"
#include "libslic3r/GCode/WipeTower.hpp"
#include "libslic3r/Utils.hpp"
#include "libslic3r/Preset.hpp"
#include "libslic3r/PresetBundle.hpp"
#include "libslic3r/AppConfig.hpp"
#include "libslic3r/Exception.hpp"
#include "libslic3r/Color.hpp"
#include "libslic3r/FlushVolCalc.hpp"
#include "libslic3r/Format/bbs_3mf.hpp"

#include "preview_parse.h"

#include <chrono>
#include <cfloat>
#include <cstdio>
#include <functional>
#include <vector>

#define LOG_TAG "SliceCore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace Slic3r;

namespace {

struct SliceState {
    std::mutex mutex;
    std::unique_ptr<Print> print;
    Print *print_raw = nullptr;
    std::thread worker;
    std::atomic<bool> running{false};
    std::atomic<bool> cancel_requested{false};
    std::string error;
    bool cancelled = false;
    JavaVM *jvm = nullptr;
    jobject callback = nullptr; // global ref to Kotlin callback
};

SliceState g_state;

struct ModelTransform {
    float rotX = 0.0f;
    float rotY = 0.0f;
    float rotZ = 0.0f;
    bool mirrorX = false;
    bool mirrorY = false;
    bool mirrorZ = false;
    float offsetX = 0.0f;
    float offsetY = 0.0f;
};

ModelTransform g_model_transform;

// One piece of a multi-piece file, posed by the app: the same eight values ModelTransform
// carries, plus whether the piece is printed at all. Empty while the app has no opinion
// about placement, in which case the model is placed the way it always was.
struct PartPose {
    float rotX = 0.0f;
    float rotY = 0.0f;
    float rotZ = 0.0f;
    bool mirrorX = false;
    bool mirrorY = false;
    bool mirrorZ = false;
    float offsetX = 0.0f;
    float offsetY = 0.0f;
    bool print = true;
};

// Floats per pose row, matching NativeLib.nativeSetPartPoses.
constexpr int kPartPoseRowSize = 9;
// Posed pieces, in the order the parser reported them. A pose only ever exists for a piece
// the parser named, so the list is either complete or absent - see nativeSetPartPoses.
constexpr size_t kMaxPartPoses = 512;
std::vector<PartPose> g_part_poses;

std::unique_ptr<AppConfig>     g_app_config;
std::unique_ptr<PresetBundle> g_preset_bundle;
// Serialise access to g_preset_bundle across UI threads (printer/process listing &
// selection) and slice launch.
std::mutex g_preset_mutex;

std::string jstring_to_string(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

void report_progress(int step, const std::string &text, int percent) {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (!g_state.jvm || !g_state.callback) return;
    JNIEnv *env = nullptr;
    if (g_state.jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_state.jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
    }
    jclass cls = env->GetObjectClass(g_state.callback);
    jmethodID mid = env->GetMethodID(cls, "onProgress", "(ILjava/lang/String;I)V");
    if (mid) {
        jstring jtext = env->NewStringUTF(text.c_str());
        env->CallVoidMethod(g_state.callback, mid, step, jtext, percent);
        env->DeleteLocalRef(jtext);
    }
    env->DeleteLocalRef(cls);
}

Vec3d bed_center_from_config(const DynamicPrintConfig &config) {
    Points pts = get_bed_shape(config, false);
    if (pts.empty()) return Vec3d(128.0, 128.0, 0.0);
    BoundingBox bb = get_extents(pts);
    Vec2d c = unscale(bb.center());
    return Vec3d(c.x(), c.y(), 0.0);
}

// Exact uncached world-space bounding box of one instance, reflecting both any
// authored 3MF instance/volume transforms and any rotations/mirrors just applied.
BoundingBoxf3 instance_world_bbox(const ModelObject *obj, const ModelInstance *inst) {
    BoundingBoxf3 bb;
    const Transform3d inst_mat = inst->get_matrix();
    for (const ModelVolume *vol : obj->volumes) {
        if (!vol->is_model_part()) continue;
        bb.merge(vol->mesh().transformed_bounding_box(inst_mat * vol->get_matrix()));
    }
    return bb;
}

// Place every object with the pose the app gave that piece: turn its own mesh around its
// own centre, then park its instances where the pose says. It is what apply_model_transform
// does to the whole model, done one object at a time - which is what lets a 3MF holding two
// models print only one of them, or slide them together to fit the plate.
void apply_part_poses(Model &model, const Vec3d &bedCenter) {
    const double deg2rad = PI / 180.0;
    std::vector<size_t> dropped;
    for (size_t i = 0; i < model.objects.size(); ++i) {
        ModelObject *obj = model.objects[i];
        const PartPose &pose = g_part_poses[i];
        if (!pose.print) {
            dropped.push_back(i);
            continue;
        }
        BoundingBoxf3 bb = obj->raw_mesh_bounding_box();
        if (!bb.defined) continue;
        const Vec3d pivot = bb.center();
        for (ModelVolume *vol : obj->volumes) {
            vol->translate(-pivot.x(), -pivot.y(), -pivot.z());
            if (pose.rotX != 0.0f) vol->rotate(pose.rotX * deg2rad, X);
            if (pose.rotY != 0.0f) vol->rotate(pose.rotY * deg2rad, Y);
            if (pose.rotZ != 0.0f) vol->rotate(pose.rotZ * deg2rad, Z);
            if (pose.mirrorX) vol->mirror(X);
            if (pose.mirrorY) vol->mirror(Y);
            if (pose.mirrorZ) vol->mirror(Z);
        }
        obj->invalidate_bounding_box();
        // Shift each instance from its actual world-space box so the piece's centre
        // lands at (bed centre + offset) and its lowest point sits on Z = 0, whether
        // the instance started at the origin (fast path) or an authored 3MF offset.
        for (ModelInstance *inst : obj->instances) {
            const BoundingBoxf3 posed = instance_world_bbox(obj, inst);
            if (!posed.defined) continue;
            const Vec3d center = posed.center();
            const Vec3d delta(bedCenter.x() + pose.offsetX - center.x(),
                              bedCenter.y() + pose.offsetY - center.y(),
                              -posed.min.z());
            inst->set_offset(inst->get_offset() + delta);
        }
        obj->invalidate_bounding_box();
    }
    // Pieces the user unticked are not sliced at all: leaving them in the model would print
    // the whole file, which is exactly the complaint about multi-model 3MFs. Deleted from
    // the back so the remaining indices stay valid.
    for (auto it = dropped.rbegin(); it != dropped.rend(); ++it) model.delete_object(*it);
    LOGI("Placed %zu pieces from app poses (%zu left out)", g_part_poses.size() - dropped.size(),
         dropped.size());
}

void apply_model_transform(Model &model, const Vec3d &bedCenter) {
    if (!g_part_poses.empty() && g_part_poses.size() == model.objects.size()) {
        apply_part_poses(model, bedCenter);
        return;
    }
    const ModelTransform t = g_model_transform;
    const double deg2rad = PI / 180.0;

    // Center each object's mesh on its local origin, then apply rotation/mirror.
    // (Meshes are frequently authored away from the origin.)
    for (ModelObject *obj : model.objects) {
        BoundingBoxf3 bb = obj->raw_mesh_bounding_box();
        if (!bb.defined) continue;
        Vec3d pivot = bb.center();
        for (ModelVolume *vol : obj->volumes) {
            vol->translate(-pivot.x(), -pivot.y(), -pivot.z());
            if (t.rotX != 0.0f) vol->rotate(t.rotX * deg2rad, X);
            if (t.rotY != 0.0f) vol->rotate(t.rotY * deg2rad, Y);
            if (t.rotZ != 0.0f) vol->rotate(t.rotZ * deg2rad, Z);
            if (t.mirrorX) vol->mirror(X);
            if (t.mirrorY) vol->mirror(Y);
            if (t.mirrorZ) vol->mirror(Z);
        }
        obj->invalidate_bounding_box();
    }

    // Measure the actual uncached world-space bounds (including authored 3MF instance
    // transforms) so full-import 3MFs do not get double-shifted off the plate or lifted
    // into mid-air, while rotated meshes still drop cleanly to Z = 0.
    BoundingBoxf3 global_bb;
    for (ModelObject *obj : model.objects) {
        for (ModelInstance *inst : obj->instances) {
            BoundingBoxf3 bb = instance_world_bbox(obj, inst);
            if (bb.defined) global_bb.merge(bb);
        }
    }
    if (global_bb.defined) {
        const Vec3d center = global_bb.center();
        const Vec3d delta(bedCenter.x() + t.offsetX - center.x(),
                         bedCenter.y() + t.offsetY - center.y(),
                         -global_bb.min.z());
        for (ModelObject *obj : model.objects) {
            for (ModelInstance *inst : obj->instances) {
                inst->set_offset(inst->get_offset() + delta);
            }
            obj->invalidate_bounding_box();
        }
        LOGI("Centered model on bed: dx=%.3f dy=%.3f dz=%.3f", delta.x(), delta.y(), delta.z());
    }
}

// Overlay the print settings embedded in a project 3MF onto the active config,
// so the file's support/quality settings are respected. Machine & filament preset
// keys are left to the selected Elegoo presets (so a Bambu 3MF never overwrites
// printer G-code, nozzle geometry, limits, or the user's mapped spool temperatures).
// In multi-colour mode, per-feature filament IDs from the project are preserved.
void merge_embedded_print_settings(DynamicPrintConfig &config, const DynamicPrintConfig &embedded, bool multi) {
    if (embedded.empty()) return;
    static const std::set<std::string> always_skip = {
        "support_filament",
        "support_interface_filament",
        "filament_type",
        "filament_colour",
        "filament_multi_colour",
        "filament_ids",
        "filament_settings_id",
        "filament_map",
        "wipe_tower_x",
        "wipe_tower_y",
        "wipe_tower_rotation_angle",
    };
    static const std::set<std::string> single_only_skip = {
        "outer_wall_filament_id",
        "inner_wall_filament_id",
        "sparse_infill_filament_id",
        "internal_solid_filament_id",
        "top_surface_filament_id",
        "bottom_surface_filament_id",
        "wipe_tower_filament",
    };
    int applied = 0;
    for (const std::string &key : Preset::print_options()) {
        if (always_skip.count(key) > 0) continue;
        if (!multi && single_only_skip.count(key) > 0) continue;
        if (const ConfigOption *opt = embedded.option(key)) {
            config.set_key_value(key, opt->clone());
            ++applied;
        }
    }
    LOGI("Applied %d print settings embedded in the model file (multi=%d)", applied, int(multi));
}

size_t embedded_filament_count(const DynamicPrintConfig &embedded) {
    size_t n = 1;
    if (const auto *fc = embedded.option<ConfigOptionStrings>("filament_colour"))
        n = std::max(n, fc->values.size());
    if (const auto *ft = embedded.option<ConfigOptionStrings>("filament_type"))
        n = std::max(n, ft->values.size());
    return n;
}

bool same_filament_colours(const DynamicPrintConfig &config, size_t n) {
    const auto *fc = config.option<ConfigOptionStrings>("filament_colour");
    if (!fc || fc->values.size() < 2) return true;
    auto norm = [](std::string s) {
        std::transform(s.begin(), s.end(), s.begin(),
                       [](unsigned char ch) { return static_cast<char>(std::toupper(ch)); });
        return s;
    };
    const std::string first = norm(fc->values.front());
    for (size_t i = 1; i < std::min(n, fc->values.size()); ++i)
        if (norm(fc->values[i]) != first) return false;
    return true;
}

void clamp_filament_selection(DynamicPrintConfig &config, int num_filaments, bool multi_colour) {
    if (num_filaments < 1) num_filaments = 1;

    auto clamp_option = [&config, num_filaments, multi_colour](const char *key, int lo, int hi) {
        ConfigOptionInt *opt = config.option<ConfigOptionInt>(key, false);
        if (!opt) return;
        int v = opt->value;
        if (v < lo || v > hi) {
            opt->value = (v < lo) ? lo : (multi_colour ? hi : 0);
            LOGI("Clamped %s from %d to %d (selected filament count=%d)", key, v, opt->value, num_filaments);
        }
    };

    clamp_option("support_filament", 0, num_filaments);
    clamp_option("support_interface_filament", 0, num_filaments);
    clamp_option("wipe_tower_filament", 0, num_filaments);

    clamp_option("outer_wall_filament_id", 0, num_filaments);
    clamp_option("inner_wall_filament_id", 0, num_filaments);
    clamp_option("sparse_infill_filament_id", 0, num_filaments);
    clamp_option("internal_solid_filament_id", 0, num_filaments);
    clamp_option("top_surface_filament_id", 0, num_filaments);
    clamp_option("bottom_surface_filament_id", 0, num_filaments);
}

// Ensures the prime/wipe tower is placed strictly inside the active printer's
// build plate and clear of both bed exclusion zones and the placed model(s).
// libslic3r's built-in defaults (wipe_tower_x=15, wipe_tower_y=220, width=60)
// extend past Y=256 on 256x256 beds like the Centauri Carbon 2, and foreign
// 3MF files can carry tower coordinates from larger/different beds.
void place_wipe_tower_on_bed(DynamicPrintConfig &config, const Model &model) {
    const auto *enabled = config.option<ConfigOptionBool>("enable_prime_tower", false);
    if (!enabled || !enabled->value) return;

    double bed_min_x = 0.0, bed_min_y = 0.0, bed_max_x = 256.0, bed_max_y = 256.0;
    const Points bed_pts = get_bed_shape(config, false);
    if (!bed_pts.empty()) {
        const BoundingBox bed_bb = get_extents(bed_pts);
        bed_min_x = unscale<double>(bed_bb.min.x());
        bed_min_y = unscale<double>(bed_bb.min.y());
        bed_max_x = unscale<double>(bed_bb.max.x());
        bed_max_y = unscale<double>(bed_bb.max.y());
    }
    const double bed_w = bed_max_x - bed_min_x;
    const double bed_h = bed_max_y - bed_min_y;
    if (bed_w < 60.0 || bed_h < 60.0) return;

    // Active printer exclusion area (e.g. front-left filament cutter / purge corner).
    struct Rect2 {
        double min_x, min_y, max_x, max_y;
        double overlap_area(const Rect2 &o) const {
            const double ox = std::max(0.0, std::min(max_x, o.max_x) - std::max(min_x, o.min_x));
            const double oy = std::max(0.0, std::min(max_y, o.max_y) - std::max(min_y, o.min_y));
            return ox * oy;
        }
    };
    std::vector<Rect2> exclude_rects;
    if (const auto *excl = config.option<ConfigOptionPoints>("bed_exclude_area", false)) {
        if (excl->values.size() >= 3) {
            double ex0 = DBL_MAX, ey0 = DBL_MAX, ex1 = -DBL_MAX, ey1 = -DBL_MAX;
            for (const Vec2d &pt : excl->values) {
                ex0 = std::min(ex0, pt.x());
                ey0 = std::min(ey0, pt.y());
                ex1 = std::max(ex1, pt.x());
                ey1 = std::max(ey1, pt.y());
            }
            if (ex1 > ex0 && ey1 > ey0) {
                exclude_rects.push_back(Rect2{ex0 - 4.0, ey0 - 4.0, ex1 + 4.0, ey1 + 4.0});
            }
        }
    }

    // Placed model footprints expanded by 6 mm clearance.
    std::vector<Rect2> model_rects;
    for (const ModelObject *obj : model.objects) {
        for (const ModelInstance *inst : obj->instances) {
            const BoundingBoxf3 bb = instance_world_bbox(obj, inst);
            if (bb.defined) {
                model_rects.push_back(Rect2{
                    bb.min.x() - 6.0, bb.min.y() - 6.0,
                    bb.max.x() + 6.0, bb.max.y() + 6.0,
                });
            }
        }
    }

    const auto *w_opt = config.option<ConfigOptionFloat>("prime_tower_width", false);
    const auto *vol_opt = config.option<ConfigOptionFloat>("prime_volume", false);
    const auto *brim_opt = config.option<ConfigOptionFloat>("prime_tower_brim_width", false);
    double width = (w_opt && w_opt->value > 2.0) ? w_opt->value : 35.0;
    const double max_width = std::max(25.0, bed_w * 0.35);
    if (width > max_width) {
        width = max_width;
        config.set_key_value("prime_tower_width", new ConfigOptionFloat(width));
    }
    const double volume = (vol_opt && vol_opt->value > 1.0) ? vol_opt->value : 45.0;
    const double brim = (brim_opt && brim_opt->value >= 0.0) ? std::max(3.0, brim_opt->value) : 5.0;
    // Conservative depth bound covering prime paths, extra walls, and stabilization cone base.
    const double est_depth = std::clamp(std::max(45.0, (volume / std::max(width, 20.0)) * 6.0), 38.0, 65.0);
    const double pad = brim + 8.0;

    const double min_x = bed_min_x + pad;
    const double max_x = std::max(min_x, bed_max_x - width - pad);
    const double min_y = bed_min_y + pad;
    const double max_y = std::max(min_y, bed_max_y - est_depth - pad);
    const double mid_x = std::clamp((bed_min_x + bed_max_x - width) * 0.5, min_x, max_x);
    const double mid_y = std::clamp((bed_min_y + bed_max_y - est_depth) * 0.5, min_y, max_y);

    auto tower_rect = [&](double x, double y) -> Rect2 {
        return Rect2{x - brim, y - brim, x + width + brim, y + est_depth + brim};
    };
    auto score = [&](double x, double y) -> std::pair<double, double> {
        const Rect2 r = tower_rect(x, y);
        double excl_ov = 0.0;
        for (const Rect2 &e : exclude_rects) excl_ov += r.overlap_area(e);
        double model_ov = 0.0;
        for (const Rect2 &m : model_rects) model_ov += r.overlap_area(m);
        return {excl_ov, model_ov};
    };

    // Keep current position only if rotation is 0, strictly in-bounds, and collision-free.
    const auto *tx_opt = config.option<ConfigOptionFloats>("wipe_tower_x", false);
    const auto *ty_opt = config.option<ConfigOptionFloats>("wipe_tower_y", false);
    const auto *rot_opt = config.option<ConfigOptionFloat>("wipe_tower_rotation_angle", false);
    if (tx_opt && !tx_opt->values.empty() && ty_opt && !ty_opt->values.empty() &&
        (!rot_opt || std::abs(rot_opt->value) < 0.01)) {
        const double cx = tx_opt->values.front();
        const double cy = ty_opt->values.front();
        if (cx >= min_x && cx <= max_x && cy >= min_y && cy <= max_y) {
            const auto [e_ov, m_ov] = score(cx, cy);
            if (e_ov == 0.0 && m_ov == 0.0) {
                LOGI("Keeping prime tower at (%.1f, %.1f) inside bed", cx, cy);
                return;
            }
        }
    }

    // Evaluate perimeter candidates inside the build plate (rear-right preferred).
    const std::vector<std::pair<double, double>> candidates = {
        {max_x, max_y},
        {min_x, max_y},
        {max_x, min_y},
        {min_x, min_y},
        {mid_x, max_y},
        {max_x, mid_y},
        {min_x, mid_y},
        {mid_x, min_y},
    };
    double best_x = max_x;
    double best_y = max_y;
    std::pair<double, double> best_score = {DBL_MAX, DBL_MAX};
    for (const auto &[cx, cy] : candidates) {
        const auto sc = score(cx, cy);
        if (sc < best_score) {
            best_score = sc;
            best_x = cx;
            best_y = cy;
            if (sc.first == 0.0 && sc.second == 0.0) break;
        }
    }

    config.set_key_value("wipe_tower_x", new ConfigOptionFloats{best_x});
    config.set_key_value("wipe_tower_y", new ConfigOptionFloats{best_y});
    config.set_key_value("wipe_tower_rotation_angle", new ConfigOptionFloat(0.0));
    LOGI("Placed prime tower at (%.1f, %.1f) size=%.0fx%.0fmm inside bed [%.0f..%.0f]x[%.0f..%.0f] (excl_ov=%.1f model_ov=%.1f)",
         best_x, best_y, width, est_depth, bed_min_x, bed_max_x, bed_min_y, bed_max_y,
         best_score.first, best_score.second);
}

// Verifies the *actual* generated wipe-tower geometry (including ribs, rib_offset,
// brim, stabilization cone, and every extrusion move) against the active bed bounds
// right after Print::process() and before G-code export. If any part of the generated
// tower still extends past the build plate or enters an exclusion zone, snaps
// wipe_tower_x/y using the exact local extents and re-runs only psSkirtBrim (~1 ms).
void ensure_generated_wipe_tower_on_bed(Print *print, DynamicPrintConfig &config, const Model &model) {
    if (!print || !print->has_wipe_tower()) return;
    const auto &wt_data = print->wipe_tower_data();
    if (wt_data.tool_changes.empty()) return;

    double bed_min_x = 0.0, bed_min_y = 0.0, bed_max_x = 256.0, bed_max_y = 256.0;
    const Points bed_pts = get_bed_shape(config, false);
    if (!bed_pts.empty()) {
        const BoundingBox bed_bb = get_extents(bed_pts);
        bed_min_x = unscale<double>(bed_bb.min.x());
        bed_min_y = unscale<double>(bed_bb.min.y());
        bed_max_x = unscale<double>(bed_bb.max.x());
        bed_max_y = unscale<double>(bed_bb.max.y());
    }
    const double bed_w = bed_max_x - bed_min_x;
    const double bed_h = bed_max_y - bed_min_y;
    if (bed_w < 60.0 || bed_h < 60.0) return;

    const int plate_idx = print->get_plate_index();
    const Vec3d origin = print->get_plate_origin();
    const double cur_x = print->config().wipe_tower_x.get_at(plate_idx);
    const double cur_y = print->config().wipe_tower_y.get_at(plate_idx);
    const bool is_type2 = print->wipe_tower_type() == WipeTowerType::Type2;
    const Vec2f rib_off = is_type2 ? Vec2f::Zero() : print->get_rib_offset();
    const double rot_rad = print->config().wipe_tower_rotation_angle.value * (M_PI / 180.0);
    const double ca = std::cos(rot_rad);
    const double sa = std::sin(rot_rad);

    double wt_min_x = DBL_MAX, wt_min_y = DBL_MAX;
    double wt_max_x = -DBL_MAX, wt_max_y = -DBL_MAX;
    auto expand = [&](double x, double y) {
        if (!std::isfinite(x) || !std::isfinite(y)) return;
        wt_min_x = std::min(wt_min_x, x);
        wt_min_y = std::min(wt_min_y, y);
        wt_max_x = std::max(wt_max_x, x);
        wt_max_y = std::max(wt_max_y, y);
    };

    // 1. Exact first-layer corners (includes brim, rib_offset, and stabilization cone).
    for (const Point &pt : print->first_layer_wipe_tower_corners(true)) {
        expand(unscale<double>(pt.x()), unscale<double>(pt.y()));
    }

    // 2. Every actual prime-tower extrusion move as transformed by WipeTowerIntegration.
    for (const auto &layer_tcrs : wt_data.tool_changes) {
        for (const auto &tcr : layer_tcrs) {
            if (tcr.priming) continue;
            for (const auto &ext : tcr.extrusions) {
                const double rx = ext.pos.x() * ca - ext.pos.y() * sa;
                const double ry = ext.pos.x() * sa + ext.pos.y() * ca;
                const double wx = rx + cur_x + rib_off.x() + origin.x();
                const double wy = ry + cur_y + rib_off.y() + origin.y();
                const double hw = std::max(0.25, static_cast<double>(ext.width) * 0.5);
                expand(wx - hw, wy - hw);
                expand(wx + hw, wy + hw);
            }
        }
    }

    if (wt_min_x > wt_max_x || wt_min_y > wt_max_y) return;

    constexpr double kEdgeMargin = 6.0;
    const double safe_min_x = bed_min_x + kEdgeMargin;
    const double safe_max_x = bed_max_x - kEdgeMargin;
    const double safe_min_y = bed_min_y + kEdgeMargin;
    const double safe_max_y = bed_max_y - kEdgeMargin;

    struct Rect2 {
        double min_x, min_y, max_x, max_y;
        double overlap_area(const Rect2 &o) const {
            const double ox = std::max(0.0, std::min(max_x, o.max_x) - std::max(min_x, o.min_x));
            const double oy = std::max(0.0, std::min(max_y, o.max_y) - std::max(min_y, o.min_y));
            return ox * oy;
        }
    };
    std::vector<Rect2> exclude_rects;
    if (const auto *excl = config.option<ConfigOptionPoints>("bed_exclude_area", false)) {
        if (excl->values.size() >= 3) {
            double ex0 = DBL_MAX, ey0 = DBL_MAX, ex1 = -DBL_MAX, ey1 = -DBL_MAX;
            for (const Vec2d &pt : excl->values) {
                ex0 = std::min(ex0, pt.x());
                ey0 = std::min(ey0, pt.y());
                ex1 = std::max(ex1, pt.x());
                ey1 = std::max(ey1, pt.y());
            }
            if (ex1 > ex0 && ey1 > ey0) {
                exclude_rects.push_back(Rect2{ex0 - 4.0, ey0 - 4.0, ex1 + 4.0, ey1 + 4.0});
            }
        }
    }
    std::vector<Rect2> model_rects;
    for (const ModelObject *obj : model.objects) {
        for (const ModelInstance *inst : obj->instances) {
            const BoundingBoxf3 bb = instance_world_bbox(obj, inst);
            if (bb.defined) {
                model_rects.push_back(Rect2{
                    bb.min.x() - 4.0, bb.min.y() - 4.0,
                    bb.max.x() + 4.0, bb.max.y() + 4.0,
                });
            }
        }
    }

    const Rect2 cur_rect{wt_min_x, wt_min_y, wt_max_x, wt_max_y};
    double cur_excl_ov = 0.0;
    for (const Rect2 &e : exclude_rects) cur_excl_ov += cur_rect.overlap_area(e);
    const bool in_bounds =
        wt_min_x >= safe_min_x && wt_max_x <= safe_max_x &&
        wt_min_y >= safe_min_y && wt_max_y <= safe_max_y;
    if (in_bounds && cur_excl_ov == 0.0) {
        LOGI("Verified prime tower bbox [%.1f..%.1f]x[%.1f..%.1f] strictly inside bed [%.0f..%.0f]x[%.0f..%.0f]",
             wt_min_x, wt_max_x, wt_min_y, wt_max_y, bed_min_x, bed_max_x, bed_min_y, bed_max_y);
        return;
    }

    // Exact local extents relative to current (cur_x, cur_y).
    const double local_min_x = wt_min_x - cur_x;
    const double local_max_x = wt_max_x - cur_x;
    const double local_min_y = wt_min_y - cur_y;
    const double local_max_y = wt_max_y - cur_y;
    const double exact_w = local_max_x - local_min_x;
    const double exact_h = local_max_y - local_min_y;

    double min_x = safe_min_x - local_min_x;
    double max_x = safe_max_x - local_max_x;
    if (min_x > max_x) {
        const double center_x = (bed_min_x + bed_max_x) * 0.5 - (local_min_x + local_max_x) * 0.5;
        min_x = max_x = center_x;
    }
    double min_y = safe_min_y - local_min_y;
    double max_y = safe_max_y - local_max_y;
    if (min_y > max_y) {
        const double center_y = (bed_min_y + bed_max_y) * 0.5 - (local_min_y + local_max_y) * 0.5;
        min_y = max_y = center_y;
    }
    const double mid_x = (min_x + max_x) * 0.5;
    const double mid_y = (min_y + max_y) * 0.5;

    auto score = [&](double cx, double cy) -> std::pair<double, double> {
        const Rect2 r{cx + local_min_x, cy + local_min_y, cx + local_max_x, cy + local_max_y};
        double excl_ov = 0.0;
        for (const Rect2 &e : exclude_rects) excl_ov += r.overlap_area(e);
        double model_ov = 0.0;
        for (const Rect2 &m : model_rects) model_ov += r.overlap_area(m);
        return {excl_ov, model_ov};
    };

    const std::vector<std::pair<double, double>> candidates = {
        {std::clamp(cur_x, min_x, max_x), std::clamp(cur_y, min_y, max_y)},
        {max_x, max_y},
        {min_x, max_y},
        {max_x, min_y},
        {min_x, min_y},
        {mid_x, max_y},
        {max_x, mid_y},
        {min_x, mid_y},
        {mid_x, min_y},
    };
    double best_x = candidates.front().first;
    double best_y = candidates.front().second;
    std::pair<double, double> best_score = {DBL_MAX, DBL_MAX};
    for (const auto &[cx, cy] : candidates) {
        const auto sc = score(cx, cy);
        if (sc < best_score) {
            best_score = sc;
            best_x = cx;
            best_y = cy;
            if (sc.first == 0.0 && sc.second == 0.0) break;
        }
    }

    LOGI("Snapping prime tower from (%.1f, %.1f) [%.1f..%.1f]x[%.1f..%.1f] to (%.1f, %.1f) [%.1f..%.1f]x[%.1f..%.1f] (exact %.1fx%.1fmm, excl_ov=%.1f model_ov=%.1f)",
         cur_x, cur_y, wt_min_x, wt_max_x, wt_min_y, wt_max_y,
         best_x, best_y,
         best_x + local_min_x, best_x + local_max_x,
         best_y + local_min_y, best_y + local_max_y,
         exact_w, exact_h, best_score.first, best_score.second);

    config.set_key_value("wipe_tower_x", new ConfigOptionFloats{best_x});
    config.set_key_value("wipe_tower_y", new ConfigOptionFloats{best_y});
    config.set_key_value("wipe_tower_rotation_angle", new ConfigOptionFloat(0.0));
    print->apply(model, config);
    print->process();
}

// Per-filament minimum flush volume for one nozzle (port of Slic3r::GUI::get_min_flush_volumes).
std::vector<int> min_flush_volumes_for(const DynamicPrintConfig &config, size_t nozzle_id) {
    const ConfigOptionFloatsNullable *nozzle_volume = config.option<ConfigOptionFloatsNullable>("nozzle_volume");
    int nozzle_volume_val = 0;
    if (nozzle_volume && !nozzle_volume->values.empty())
        nozzle_volume_val = static_cast<int>(nozzle_volume->get_at(std::min(nozzle_id, nozzle_volume->values.size() - 1)));

    const ConfigOptionInt *enable = config.option<ConfigOptionInt>("enable_long_retraction_when_cut");
    const int machine_level = enable ? enable->value : 0;
    const ConfigOptionBools *machine_flags = config.option<ConfigOptionBools>("long_retractions_when_cut");
    const bool machine_activated = machine_flags && nozzle_id < machine_flags->values.size() && machine_flags->values[nozzle_id];

    const ConfigOptionFloats *diameters = config.option<ConfigOptionFloats>("filament_diameter");
    if (!diameters || diameters->values.empty()) return {};
    const size_t n = diameters->values.size();

    std::vector<double> filament_retract(n, 18.0);
    if (const ConfigOptionFloats *o = config.option<ConfigOptionFloats>("filament_retraction_distances_when_cut"))
        if (!o->values.empty()) filament_retract = o->values;
    filament_retract.resize(n, 18.0);

    std::vector<double> printer_retract;
    if (const ConfigOptionFloats *o = config.option<ConfigOptionFloats>("retraction_distances_when_cut"))
        printer_retract = o->values;
    const double machine_retract = printer_retract.empty() ? 18.0
        : printer_retract[std::min(nozzle_id, printer_retract.size() - 1)];

    std::vector<unsigned char> filament_long(n, 0);
    if (const ConfigOptionBools *o = config.option<ConfigOptionBools>("filament_long_retractions_when_cut"))
        for (size_t i = 0; i < n && i < o->values.size(); ++i) filament_long[i] = o->values[i] ? 1 : 0;

    std::vector<int> out;
    out.reserve(n);
    for (size_t i = 0; i < n; ++i) {
        int retract = (machine_level && machine_activated) ? static_cast<int>(machine_retract) : 0;
        const unsigned char filament_activated = filament_long[i];
        if (filament_activated == 0)
            retract = 0;
        else if (filament_activated == 1 && machine_level == LongRectrationLevel::EnableFilament)
            retract = std::isnan(filament_retract[i]) ? static_cast<int>(machine_retract) : static_cast<int>(filament_retract[i]);
        out.emplace_back(nozzle_volume_val - static_cast<int>(PI * 1.75 * 1.75 / 4.0 * retract));
    }
    return out;
}

// Compute one nozzle's n_active x n_active flush block with FlushVolCalculator
// (per-printer flush_volumes.json override first, HSV colour transition formula second).
bool resolve_flush_block(const DynamicPrintConfig &config, size_t nozzle_id, size_t n_active,
                         std::vector<double> &block, std::string &reason) {
    const ConfigOptionStrings *colours = config.option<ConfigOptionStrings>("filament_colour");
    if (!colours || colours->values.size() < n_active) {
        reason = "filament_colour has " + std::to_string(colours ? colours->values.size() : 0) + " entries, " +
                 std::to_string(n_active) + " slots need colours";
        return false;
    }
    std::vector<ColorRGBA> rgb;
    for (size_t i = 0; i < n_active; ++i) {
        ColorRGBA c;
        if (!decode_color(colours->values[i], c)) {
            reason = "undecodable filament_colour \"" + colours->values[i] + "\"";
            return false;
        }
        rgb.push_back(c);
    }

    const std::vector<int> mins = min_flush_volumes_for(config, nozzle_id);
    if (mins.size() < n_active) {
        reason = "min_flush_volumes resolved " + std::to_string(mins.size()) + " of " + std::to_string(n_active) + " slots";
        return false;
    }

    const ConfigOptionBools *is_support = config.option<ConfigOptionBools>("filament_is_support");
    const ConfigOptionString *printer_id = config.option<ConfigOptionString>("printer_settings_id");
    const std::string printer_settings_id = printer_id ? printer_id->value : std::string();
    const ConfigOptionIntsNullable *datasets = config.option<ConfigOptionIntsNullable>("nozzle_flush_dataset");
    int dataset = 0;
    if (datasets && nozzle_id < datasets->values.size()) dataset = datasets->values[nozzle_id];

    const auto byte = [](float v) { return static_cast<unsigned char>(std::lround(v * 255.0f)); };
    block.assign(n_active * n_active, 0.0);
    for (size_t from = 0; from < n_active; ++from) {
        const bool from_is_support = is_support && from < is_support->values.size() && is_support->values[from];
        const unsigned char from_rgba[4] = { byte(rgb[from].r()), byte(rgb[from].g()), byte(rgb[from].b()), byte(rgb[from].a()) };
        for (size_t to = 0; to < n_active; ++to) {
            double volume = 0.0;
            if (from != to) {
                const bool to_is_support = is_support && to < is_support->values.size() && is_support->values[to];
                if (to_is_support) {
                    volume = g_flush_volume_to_support;
                } else {
                    const unsigned char to_rgba[4] = { byte(rgb[to].r()), byte(rgb[to].g()), byte(rgb[to].b()), byte(rgb[to].a()) };
                    FlushVolCalculator calculator(from_is_support ? g_min_flush_volume_from_support : mins[from],
                                                  g_max_flush_volume, dataset);
                    volume = calculator.calc_flush_vol(from_rgba[0], from_rgba[1], from_rgba[2], from_rgba[3],
                                                       to_rgba[0], to_rgba[1], to_rgba[2], to_rgba[3], printer_settings_id);
                    if (from_is_support) volume = std::max<int>(g_min_flush_volume_from_support, static_cast<int>(volume));
                }
            }
            block[from * n_active + to] = volume;
        }
    }
    return true;
}

void run_slice(std::string model_path, DynamicPrintConfig config, std::string overrides_ini,
               std::string out_path, size_t bundle_filaments, bool multi_colour) {
    try {
        report_progress(0, "Loading model", 0);
        try {
            auto fsize = boost::filesystem::file_size(boost::filesystem::path(model_path));
            LOGI("Slice input: %s size=%llu bytes (filaments=%zu multi=%d)",
                 model_path.c_str(), (unsigned long long) fsize, bundle_filaments, int(multi_colour));
        } catch (const std::exception &e) {
            LOGE("Slice input stat failed for %s: %s", model_path.c_str(), e.what());
        }
        const auto t_model0 = std::chrono::steady_clock::now();
        DynamicPrintConfig embedded;
        ConfigSubstitutionContext embedded_substitutions(ForwardCompatibilitySubstitutionRule::EnableSilent);
        Model model;
        {
            // Fast path for single-material 3MF: stream full-resolution geometry with
            // preview_parse. Multi-colour / multi-filament 3MFs MUST use full
            // Model::read_from_file so per-part extruders and triangle painting
            // (mmu_segmentation_facets) survive into the sliced G-code.
            bool fast = false;
            FILE *hdr = std::fopen(model_path.c_str(), "rb");
            bool is_zip = false;
            if (hdr) {
                unsigned char m[2] = {0};
                is_zip = std::fread(m, 1, 2, hdr) == 2 && m[0] == 'P' && m[1] == 'K';
                std::fclose(hdr);
            }
            const bool fast_path_allowed = is_zip && !multi_colour && bundle_filaments <= 1;
            if (is_zip && !fast_path_allowed) {
                LOGI("Skipping fast 3mf path for multi-material slice (filaments=%zu, multi=%d)",
                     bundle_filaments, int(multi_colour));
            }
            if (fast_path_allowed) {
                preview::Preview full;
                std::string perr;
                if (preview::parse_preview(model_path, 20000000, full, perr, {}, false)
                    && !full.indices.empty() && !full.has_grid_transforms) {
                    auto add_part_object = [&](const preview::PartGeometry &part) {
                        indexed_triangle_set its;
                        its.vertices.reserve(part.vertex_count);
                        for (int v = 0; v < part.vertex_count; ++v) {
                            const float *p = &full.positions[(size_t)(part.vertex_start + v) * 3];
                            its.vertices.emplace_back(p[0], p[1], p[2]);
                        }
                        its.indices.reserve(part.index_count / 3);
                        for (int i = 0; i + 2 < part.index_count; i += 3) {
                            const int *base = &full.indices[part.index_start];
                            its.indices.emplace_back(base[i] - part.vertex_start,
                                                     base[i + 1] - part.vertex_start,
                                                     base[i + 2] - part.vertex_start);
                        }
                        const std::string name =
                            part.name.empty() ? "part_" + std::to_string(model.objects.size())
                                              : part.name;
                        model.add_object(name.c_str(), model_path.c_str(), TriangleMesh(std::move(its)));
                        model.objects.back()->add_instance();
                    };
                    if (!full.parts.empty()) {
                        for (const preview::PartGeometry &part : full.parts) add_part_object(part);
                        LOGI("Fast 3mf path: %zu per-part objects", full.parts.size());
                    } else {
                        indexed_triangle_set its;
                        its.vertices.reserve(full.positions.size() / 3);
                        for (size_t i = 0; i + 2 < full.positions.size(); i += 3)
                            its.vertices.emplace_back(full.positions[i], full.positions[i + 1],
                                                      full.positions[i + 2]);
                        its.indices.reserve(full.indices.size() / 3);
                        for (size_t i = 0; i + 2 < full.indices.size(); i += 3)
                            its.indices.emplace_back(full.indices[i], full.indices[i + 1],
                                                     full.indices[i + 2]);
                        model.add_object("model", model_path.c_str(), TriangleMesh(std::move(its)));
                        model.objects.back()->add_instance();
                    }
                    try {
                        Model cfg_only = Model::read_from_file(model_path, &embedded, &embedded_substitutions,
                            LoadStrategy::LoadConfig | LoadStrategy::Silence);
                    } catch (const std::exception &e) {
                        if (embedded.empty())
                            LOGE("fast 3mf config-only read failed (%s); continuing without embedded config", e.what());
                    }
                    fast = !model.objects.empty();
                }
                if (!fast)
                    LOGI("fast 3mf slice path not used: %s",
                        full.has_grid_transforms ? "part_platform grids" : perr.c_str());
            }
            if (!fast) {
                auto on_import = [](int stage, int current, int total, bool &cancel) {
                    if (g_state.cancel_requested.load(std::memory_order_relaxed)) {
                        cancel = true;
                        return;
                    }
                    int base = 2, span = 0;
                    switch (stage) {
                        case IMPORT_STAGE_OPEN:            base = 2;  span = 1; break;
                        case IMPORT_STAGE_READ_FILES:      base = 3;  span = 2; break;
                        case IMPORT_STAGE_EXTRACT:         base = 5;  span = 4; break;
                        case IMPORT_STAGE_LOADING_OBJECTS: base = 9;  span = 4; break;
                        case IMPORT_STAGE_LOADING_PLATES:  base = 13; span = 1; break;
                        default: return;
                    }
                    const int pct = total > 0 ? base + span * std::min(current, total) / total : base;
                    report_progress(0, "Loading model", pct);
                };
                model = Model::read_from_file(model_path, &embedded, &embedded_substitutions,
                    LoadStrategy::AddDefaultInstances | LoadStrategy::LoadModel | LoadStrategy::LoadConfig,
                    nullptr, nullptr, nullptr, nullptr, on_import);
            }
        }
        LOGI("Model loaded: %zu objects, embedded config keys=%zu in %lld ms",
            model.objects.size(), embedded.keys().size(),
            (long long) std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - t_model0).count());
        merge_embedded_print_settings(config, embedded, multi_colour);
        if (!overrides_ini.empty()) {
            try {
                config.load_from_ini_string(overrides_ini, ForwardCompatibilitySubstitutionRule::EnableSilent);
                // Explicit user adhesion overrides must also win over per-object overrides
                // embedded in a 3MF (e.g. a part with per-object brim_type=outer_only or
                // painted brim ears): PrintObject::object_config_from_model_object overlays
                // ModelObject::config on top of the global config.
                DynamicPrintConfig parsed_overrides;
                parsed_overrides.load_from_ini_string(overrides_ini, ForwardCompatibilitySubstitutionRule::EnableSilent);
                static const char *kObjectAdhesionKeys[] = {"brim_type", "brim_width", "raft_layers"};
                for (const char *key : kObjectAdhesionKeys) {
                    if (!parsed_overrides.has(key)) continue;
                    for (ModelObject *obj : model.objects) {
                        if (obj->config.erase(key)) {
                            LOGI("Cleared per-object 3MF override %s so user adhesion override takes effect", key);
                        }
                    }
                }
            } catch (const std::exception &e) {
                LOGE("Failed to apply overrides: %s", e.what());
            }
        }
        // Enforce multi-filament / multi-extruder vector invariants after embedded 3MF
        // settings and INI overrides: ToolOrdering and WipeTower derive filament_nums from
        // filament_colour.values.size() and index flush_volumes_matrix as
        // filament_nums * filament_nums per nozzle.
        const size_t heads = config.option<ConfigOptionFloats>("nozzle_diameter")
            ? std::max<size_t>(1, config.option<ConfigOptionFloats>("nozzle_diameter")->values.size()) : 1;
        const size_t n_active = std::max<size_t>(std::max<size_t>(1, bundle_filaments), heads);
        if (n_active > 1 || heads > 1) {
            auto normalize_colours = [&config, n_active](const char *key) {
                auto *opt = config.option<ConfigOptionStrings>(key, false);
                std::vector<std::string> vals = opt ? opt->values : std::vector<std::string>{};
                if (vals.size() != n_active) {
                    const std::string fallback = vals.empty() ? "#26A69A" : vals.back();
                    vals.resize(n_active, fallback);
                    config.set_key_value(key, new ConfigOptionStrings(std::move(vals)));
                    LOGI("Normalized %s to %zu entries", key, n_active);
                }
            };
            normalize_colours("filament_colour");
            normalize_colours("filament_multi_colour");

            auto *fmap = config.option<ConfigOptionInts>("filament_map", false);
            std::vector<int> fmaps = fmap ? fmap->values : std::vector<int>{};
            const bool need_fmap = fmaps.size() != n_active || (heads > 1 && n_active > 1);
            if (need_fmap) {
                fmaps.resize(n_active, 1);
                if (heads > 1) {
                    for (size_t i = 0; i < n_active; ++i)
                        fmaps[i] = static_cast<int>(std::min<size_t>(i + 1, heads));
                }
                config.set_key_value("filament_map", new ConfigOptionInts(std::move(fmaps)));
                LOGI("Normalized filament_map to %zu entries (%zu nozzles)", n_active, heads);
            }

            auto *mult = config.option<ConfigOptionFloats>("flush_multiplier", false);
            std::vector<double> mults = mult ? mult->values : std::vector<double>{};
            if (mults.size() != heads) {
                mults.resize(heads, 1.0);
            }
            const bool uniform_colour = same_filament_colours(config, n_active);
            for (double &m : mults) {
                if (!(m >= 1.0) && !uniform_colour) m = 1.0;
            }
            config.set_key_value("flush_multiplier", new ConfigOptionFloats(std::move(mults)));

            const size_t need = n_active * n_active * heads;
            auto *ftypes = config.option<ConfigOptionStrings>("filament_type", false);
            auto norm_type = [&](size_t idx) -> std::string {
                if (!ftypes || ftypes->values.empty()) return "";
                std::string t = idx < ftypes->values.size() ? ftypes->values[idx] : ftypes->values.back();
                for (char &ch : t) ch = static_cast<char>(std::toupper(static_cast<unsigned char>(ch)));
                return t;
            };
            auto min_flush_for_pair = [&](size_t r, size_t c) -> double {
                if (r == c) return 0.0;
                const std::string tr = norm_type(r);
                const std::string tc = norm_type(c);
                // Dissimilar materials sharing one nozzle (e.g. PLA <-> PETG or breakaway support)
                // need heavy flushing to prevent cross-contamination and brittle layer delamination.
                if (heads == 1 && !tr.empty() && !tc.empty() && tr != tc) return 500.0;
                return (heads == 1) ? 200.0 : 140.0;
            };

            // Compute colour-accurate flush volumes via FlushVolCalculator (per-printer JSON
            // overrides + HSV transition formula), then apply the dissimilar-material floor.
            // Fall back to safe non-zero defaults only if a slot has no decodable colour.
            std::vector<double> resolved(need, 0.0);
            bool complete = true;
            for (size_t head = 0; head < heads; ++head) {
                std::vector<double> block;
                std::string reason;
                if (!resolve_flush_block(config, head, n_active, block, reason)) {
                    complete = false;
                    LOGE("Flush volumes unresolved for nozzle %zu: %s", head, reason.c_str());
                    break;
                }
                for (size_t i = 0; i < block.size(); ++i)
                    resolved[head * block.size() + i] = block[i];
            }
            for (size_t h = 0; h < heads; ++h) {
                for (size_t r = 0; r < n_active; ++r) {
                    for (size_t c = 0; c < n_active; ++c) {
                        const size_t idx = (h * n_active + r) * n_active + c;
                        if (r == c) {
                            resolved[idx] = 0.0;
                        } else if (!complete) {
                            resolved[idx] = min_flush_for_pair(r, c);
                        } else {
                            // Preserve dissimilar-material safety floor on single-nozzle printers.
                            const std::string tr = norm_type(r);
                            const std::string tc = norm_type(c);
                            if (heads == 1 && !tr.empty() && !tc.empty() && tr != tc) {
                                resolved[idx] = std::max(resolved[idx], 500.0);
                            }
                        }
                    }
                }
            }
            config.set_key_value("flush_volumes_matrix", new ConfigOptionFloats(resolved));
            LOGI("flush_volumes_matrix %s for %zu slots x %zu nozzles: %s",
                 complete ? "computed" : "fallback", n_active, heads,
                 ConfigOptionFloats(resolved).serialize().c_str());
        }
        clamp_filament_selection(config, static_cast<int>(n_active), multi_colour);
        Vec3d bedCenter = bed_center_from_config(config);
        LOGI("Bed center: x=%.3f y=%.3f", bedCenter.x(), bedCenter.y());
        apply_model_transform(model, bedCenter);
        place_wipe_tower_on_bed(config, model);

        auto print = std::make_unique<Print>();
        print->set_status_callback([](const PrintBase::SlicingStatus &status) {
            static std::string last_text;
            if (status.text != last_text) {
                last_text = status.text;
                LOGI("Slice stage: %s (%d%%)", status.text.c_str(), status.percent);
            }
            report_progress(0, status.text, status.percent);
        });
        // Abort promptly on cancel: libslic3r calls this callback at its internal
        // checkpoints (every slicing step, support layer, seam pass, model prep
        // section), so a cancel lands within seconds even during "apply".
        print->set_cancel_callback([] {
            if (g_state.cancel_requested.load(std::memory_order_relaxed))
                throw CanceledException();
        });
        {
            std::lock_guard<std::mutex> lock(g_state.mutex);
            g_state.print = std::move(print);
            g_state.print_raw = g_state.print.get();
        }

        const auto t_apply0 = std::chrono::steady_clock::now();
        g_state.print_raw->apply(model, config);
        g_state.print_raw->process();
        ensure_generated_wipe_tower_on_bed(g_state.print_raw, config, model);
        const auto t_process0 = std::chrono::steady_clock::now();
        g_state.print_raw->export_gcode(out_path, nullptr);
        const auto t_end = std::chrono::steady_clock::now();
        LOGI("Slice timing: apply=%lld ms, process=%lld ms, export=%lld ms",
            (long long) std::chrono::duration_cast<std::chrono::milliseconds>(t_process0 - t_apply0).count(),
            (long long) std::chrono::duration_cast<std::chrono::milliseconds>(t_end - t_process0).count(),
            (long long) std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t_end).count());
        LOGI("G-code exported to %s", out_path.c_str());

        std::lock_guard<std::mutex> lock(g_state.mutex);
        g_state.error.clear();
    } catch (const CanceledException &) {
        LOGI("Slice cancelled");
        std::lock_guard<std::mutex> lock(g_state.mutex);
        g_state.cancelled = true;
    } catch (const Slic3r::SlicingErrors &e) {
        // GCode.cpp re-throws every per-object Slic3r::SlicingError as one
        // Slic3r::SlicingErrors, whose what() is the literal "Errors"
        // (libslic3r/Exception.hpp). Catching it here is the only way to keep the
        // real reason - otherwise the UI shows "Error: Errors" and the actionable
        // text ("One object has an empty first layer and can't be printed. Please
        // Cut the bottom or enable supports.") is thrown away.
        constexpr size_t kMaxMessages = 3;
        std::string reason;
        size_t shown = 0;
        for (const auto &err : e.errors_) {
            if (shown > 0) reason += "; ";
            reason += err.what();
            if (++shown >= kMaxMessages) break;
        }
        if (e.errors_.size() > kMaxMessages)
            reason += "; +" + std::to_string(e.errors_.size() - kMaxMessages) + " more";
        LOGE("Slice failed (Slic3r::SlicingErrors): %s", reason.c_str());
        std::lock_guard<std::mutex> lock(g_state.mutex);
        g_state.error = reason;
    } catch (const std::exception &e) {
        LOGE("Slice failed (%s): %s", typeid(e).name(), e.what());
        std::lock_guard<std::mutex> lock(g_state.mutex);
        g_state.error = e.what();
    }
    g_state.running = false;
}

// Launch a slice job with a fully-built config. Returns false if a slice is already running.
bool launch_slice(const std::string &model_path, DynamicPrintConfig config, const std::string &overrides_ini,
                 const std::string &out_path, size_t bundle_filaments, bool multi_colour,
                 JNIEnv *env, jobject callback) {
    std::thread old;
    {
        std::lock_guard<std::mutex> lock(g_state.mutex);
        if (g_state.running) {
            g_state.error = "A slice is already running";
            LOGE("Slice already running");
            return false;
        }
        // Take ownership of the previous (finished) worker so we can join it outside the lock.
        old = std::move(g_state.worker);
        if (g_state.callback) {
            env->DeleteGlobalRef(g_state.callback);
        }
        g_state.callback = env->NewGlobalRef(callback);
        g_state.error.clear();
        g_state.cancelled = false;
        g_state.cancel_requested = false;
        g_state.running = true;
    }
    // Join the old worker outside the lock: it has already passed its last mutex use
    // (running was observed false), so this cannot deadlock.
    if (old.joinable()) old.join();
    {
        std::lock_guard<std::mutex> lock(g_state.mutex);
        g_state.worker = std::thread(run_slice, model_path, std::move(config), overrides_ini,
                                     out_path, bundle_filaments, multi_colour);
    }
    return true;
}

void set_error(const std::string &msg) {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    g_state.error = msg;
}

std::string json_escape(const std::string &in) {
    std::string out;
    out.reserve(in.size() + 8);
    for (char c : in) {
        switch (c) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n";  break;
            case '\r': out += "\\r";  break;
            case '\t': out += "\\t";  break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out += c;
                }
        }
    }
    return out;
}

// Serialize a preset collection's visible (non built-in-default) preset names to a JSON array.
std::string presets_to_json(PresetCollection &collection, bool only_compatible_visible = false) {
    std::string out = "[";
    bool first = true;
    for (const Preset &p : collection) {
        if (only_compatible_visible && !(p.is_compatible && p.is_visible)) continue;
        if (!first) out += ",";
        out += "\"";
        out += json_escape(p.name);
        out += "\"";
        first = false;
    }
    out += "]";
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
    g_state.jvm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL
Java_world_yemyat_slicepilot_NativeLib_nativeInit(JNIEnv *env, jclass, jstring dataDir, jstring resDir) {
    std::string data = jstring_to_string(env, dataDir);
    std::string res = jstring_to_string(env, resDir);
    boost::filesystem::create_directories(boost::filesystem::path(data));
    boost::filesystem::create_directories(boost::filesystem::path(res));
    Slic3r::set_data_dir(data);
    Slic3r::set_resources_dir(res);
    // Model::get_backup_path() roots at temporary_dir(); on Android it is unset,
    // which makes 3mf project-config extraction write to "/elegooslicer_model/..." and fail.
    Slic3r::set_temporary_dir(data + "/tmp");
    boost::filesystem::create_directories(boost::filesystem::path(data + "/tmp"));
    LOGI("nativeInit data=%s res=%s", data.c_str(), res.c_str());

    std::lock_guard<std::mutex> lock(g_preset_mutex);
    try {
        g_app_config = std::make_unique<AppConfig>();
        g_preset_bundle = std::make_unique<PresetBundle>();
        g_preset_bundle->setup_directories();
        g_preset_bundle->load_presets(*g_app_config, ForwardCompatibilitySubstitutionRule::EnableSilent);
        // The bundled profiles are all intended to be usable; the fresh AppConfig has no enabled
        // printer variants, which would otherwise mark every vendor preset is_visible=false.
        for (Preset &p : g_preset_bundle->printers)  p.is_visible = true;
        for (Preset &p : g_preset_bundle->prints)    p.is_visible = true;
        for (Preset &p : g_preset_bundle->filaments) p.is_visible = true;
        size_t n_printers = 0, n_filaments = 0, n_prints = 0;
        for (const Preset &p : g_preset_bundle->printers)  { (void)p; ++n_printers; }
        for (const Preset &p : g_preset_bundle->filaments) { (void)p; ++n_filaments; }
        for (const Preset &p : g_preset_bundle->prints)    { (void)p; ++n_prints; }
        LOGI("PresetBundle loaded: printers=%zu filaments=%zu processes=%zu", n_printers, n_filaments, n_prints);
    } catch (const std::exception &e) {
        LOGE("PresetBundle load failed: %s", e.what());
        g_preset_bundle.reset();
    }
}

JNIEXPORT jboolean JNICALL
Java_world_yemyat_slicepilot_NativeLib_nativeSlice(JNIEnv *env, jclass, jstring modelPath,
                                           jstring iniConfig, jstring outPath, jobject callback) {
    std::string model = jstring_to_string(env, modelPath);
    std::string ini = jstring_to_string(env, iniConfig);
    std::string out = jstring_to_string(env, outPath);

    DynamicPrintConfig config = DynamicPrintAndCLIConfig::full_print_config();
    return launch_slice(model, std::move(config), ini, out, 1, false, env, callback) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_world_yemyat_slicepilot_NativeLib_nativeGetPrinters(JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lock(g_preset_mutex);
    if (!g_preset_bundle) return env->NewStringUTF("[]");
    return env->NewStringUTF(presets_to_json(g_preset_bundle->printers).c_str());
}

JNIEXPORT jstring JNICALL
Java_world_yemyat_slicepilot_NativeLib_nativeGetFilaments(JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lock(g_preset_mutex);
    if (!g_preset_bundle) return env->NewStringUTF("[]");
    return env->NewStringUTF(presets_to_json(g_preset_bundle->filaments).c_str());
}

JNIEXPORT jstring JNICALL
Java_world_yemyat_slicepilot_NativeLib_nativeGetProcesses(JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lock(g_preset_mutex);
    if (!g_preset_bundle) return env->NewStringUTF("[]");
    return env->NewStringUTF(presets_to_json(g_preset_bundle->prints).c_str());
}

JNIEXPORT jboolean JNICALL
Java_world_yemyat_slicepilot_NativeLib_nativeSelectPrinter(JNIEnv *env, jclass, jstring printerName) {
    std::lock_guard<std::mutex> lock(g_preset_mutex);
    if (!g_preset_bundle) return JNI_FALSE;
    std::string name = jstring_to_string(env, printerName);
    try {
        bool ok = g_preset_bundle->printers.select_preset_by_name(name, true);
        if (ok) g_preset_bundle->update_compatible(PresetSelectCompatibleType::Never);
        return ok ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception &e) {
        LOGE("select printer failed: %s", e.what());
        return JNI_FALSE;
    }
}

JNIEXPORT jboolean JNICALL
Java_world_yemyat_slicepilot_NativeLib_nativeSelectProcess(JNIEnv *env, jclass, jstring processName) {
    std::lock_guard<std::mutex> lock(g_preset_mutex);
    if (!g_preset_bundle) return JNI_FALSE;
    std::string name = jstring_to_string(env, processName);
    try {
        bool ok = g_preset_bundle->prints.select_preset_by_name(name, true);
        if (ok) g_preset_bundle->update_compatible(PresetSelectCompatibleType::Never);
        return ok ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception &e) {
        LOGE("select process failed: %s", e.what());
        return JNI_FALSE;
    }
}

JNIEXPORT jstring JNICALL
Java_world_yemyat_slicepilot_NativeLib_nativeGetCompatibleProcesses(JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lock(g_preset_mutex);
    if (!g_preset_bundle) return env->NewStringUTF("[]");
    return env->NewStringUTF(presets_to_json(g_preset_bundle->prints, true).c_str());
}

JNIEXPORT jstring JNICALL
Java_world_yemyat_slicepilot_NativeLib_nativeGetCompatibleFilaments(JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lock(g_preset_mutex);
    if (!g_preset_bundle) return env->NewStringUTF("[]");
    return env->NewStringUTF(presets_to_json(g_preset_bundle->filaments, true).c_str());
}

// Report the filament list baked into a 3MF plate config (Bambu Studio / OrcaSlicer / PrusaSlicer):
// {"multi":bool,"filaments":[{"type":..,"color":"#RRGGBB","preset":..},..]}.
// STL / plain 3MF report a single filament.
JNIEXPORT jstring JNICALL
Java_world_yemyat_slicepilot_NativeLib_nativeInspectModel(JNIEnv *env, jclass, jstring modelPath) {
    std::string model = jstring_to_string(env, modelPath);
    std::string out = "{\"multi\":false,\"filaments\":[]}";
    try {
        DynamicPrintConfig cfg;
        ConfigSubstitutionContext subs(ForwardCompatibilitySubstitutionRule::EnableSilent);
        try {
            Model::read_from_file(model, &cfg, &subs, LoadStrategy::LoadConfig | LoadStrategy::Silence);
        } catch (const std::exception &e) {
            // A config-only read of a 2023 Bambu 3MF v1 (external meshes) fills cfg
            // and then throws "file is empty" because no geometry was loaded; keep cfg.
            if (cfg.empty()) throw;
            LOGI("inspect %s: config-only read ended with '%s' (config kept)", model.c_str(), e.what());
        }
        const size_t n = embedded_filament_count(cfg);
        const auto *colors = cfg.option<ConfigOptionStrings>("filament_colour");
        const auto *types = cfg.option<ConfigOptionStrings>("filament_type");
        const auto *presets = cfg.option<ConfigOptionStrings>("filament_settings_id");
        std::string arr;
        for (size_t i = 0; i < n; ++i) {
            std::string color = (colors && i < colors->values.size()) ? colors->values[i] : "";
            std::string type = (types && i < types->values.size()) ? types->values[i] : "";
            std::string preset = (presets && i < presets->values.size()) ? presets->values[i] : "";
            if (!color.empty() && color[0] != '#') color = "#" + color;
            if (!arr.empty()) arr += ",";
            arr += "{\"type\":\"" + json_escape(type) + "\",\"color\":\"" + json_escape(color) +
                   "\",\"preset\":\"" + json_escape(preset) + "\"}";
        }
        out = "{\"multi\":" + std::string(n > 1 ? "true" : "false") + ",\"filaments\":[" + arr + "]}";
        LOGI("inspect %s: config keys=%zu filaments=%zu", model.c_str(), cfg.keys().size(), n);
    } catch (const std::exception &e) {
        LOGE("inspect model failed: %s", e.what());
        out = "{\"error\":\"" + json_escape(e.what()) + "\"}";
    }
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jboolean JNICALL
Java_world_yemyat_slicepilot_NativeLib_nativeSliceWithPreset(JNIEnv *env, jclass, jstring modelPath,
                                                     jstring printerName, jstring filamentName,
                                                     jstring processName, jstring outPath, jobject callback) {
    std::string model = jstring_to_string(env, modelPath);
    std::string printer = jstring_to_string(env, printerName);
    std::string filament = jstring_to_string(env, filamentName);
    std::string process = jstring_to_string(env, processName);
    std::string out = jstring_to_string(env, outPath);

    DynamicPrintConfig config;
    {
        std::lock_guard<std::mutex> lock(g_preset_mutex);
        if (!g_preset_bundle) {
            set_error("PresetBundle not loaded");
            return JNI_FALSE;
        }
        try {
            bool ok_p = g_preset_bundle->printers.select_preset_by_name(printer, true);
            bool ok_f = g_preset_bundle->filaments.select_preset_by_name(filament, true);
            bool ok_r = g_preset_bundle->prints.select_preset_by_name(process, true);
            if (!ok_p || !ok_f || !ok_r) {
                set_error("Preset not found (printer=" + std::to_string(ok_p) +
                          " filament=" + std::to_string(ok_f) +
                          " process=" + std::to_string(ok_r) + ")");
                return JNI_FALSE;
            }
            g_preset_bundle->filament_presets = { g_preset_bundle->filaments.get_selected_preset_name() };
            config = g_preset_bundle->full_config();
        } catch (const std::exception &e) {
            set_error(std::string("Preset selection failed: ") + e.what());
            return JNI_FALSE;
        }
    }

    return launch_slice(model, std::move(config), "", out, 1, false, env, callback) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_world_yemyat_slicepilot_NativeLib_nativeSliceWithOverrides(JNIEnv *env, jclass, jstring modelPath,
                                                        jstring printerName, jobjectArray filamentNames,
                                                        jstring processName, jstring overridesIni,
                                                        jboolean multiColour,
                                                        jstring outPath, jobject callback) {
    std::string model = jstring_to_string(env, modelPath);
    std::string printer = jstring_to_string(env, printerName);
    std::string process = jstring_to_string(env, processName);
    std::string overrides = jstring_to_string(env, overridesIni);
    std::string out = jstring_to_string(env, outPath);

    std::vector<std::string> requested_filaments;
    if (filamentNames) {
        const jsize len = env->GetArrayLength(filamentNames);
        requested_filaments.reserve(static_cast<size_t>(std::max<jsize>(0, len)));
        for (jsize i = 0; i < len; ++i) {
            jstring js = static_cast<jstring>(env->GetObjectArrayElement(filamentNames, i));
            std::string s = jstring_to_string(env, js);
            if (js) env->DeleteLocalRef(js);
            if (!s.empty()) requested_filaments.push_back(std::move(s));
        }
    }
    if (requested_filaments.empty()) {
        set_error("No filament preset provided");
        return JNI_FALSE;
    }

    DynamicPrintConfig config;
    size_t active_presets = 1;
    {
        std::lock_guard<std::mutex> lock(g_preset_mutex);
        if (!g_preset_bundle) {
            set_error("PresetBundle not loaded");
            return JNI_FALSE;
        }
        try {
            bool ok_p = g_preset_bundle->printers.select_preset_by_name(printer, true);
            bool ok_f = g_preset_bundle->filaments.select_preset_by_name(requested_filaments[0], true);
            bool ok_r = g_preset_bundle->prints.select_preset_by_name(process, true);
            if (!ok_p || !ok_f || !ok_r) {
                set_error("Preset not found (printer=" + std::to_string(ok_p) +
                          " filament=" + std::to_string(ok_f) +
                          " process=" + std::to_string(ok_r) + ")");
                return JNI_FALSE;
            }
            const std::string primary_filament = g_preset_bundle->filaments.get_selected_preset_name();
            std::vector<std::string> resolved_filaments;
            resolved_filaments.reserve(requested_filaments.size());
            resolved_filaments.push_back(primary_filament);
            for (size_t i = 1; i < requested_filaments.size(); ++i) {
                const std::string &req = requested_filaments[i];
                if (req == primary_filament) {
                    resolved_filaments.push_back(primary_filament);
                } else if (g_preset_bundle->filaments.select_preset_by_name(req, true)) {
                    resolved_filaments.push_back(g_preset_bundle->filaments.get_selected_preset_name());
                } else {
                    LOGE("Filament preset #%zu not found: %s (falling back to %s)",
                         i + 1, req.c_str(), primary_filament.c_str());
                    resolved_filaments.push_back(primary_filament);
                }
            }
            // Restore filament #1 as the active selection for any single-filament lookups.
            g_preset_bundle->filaments.select_preset_by_name(primary_filament, true);

            // Multi-extruder machine profiles require at least one filament entry per nozzle.
            const auto *nozzles = g_preset_bundle->printers.get_edited_preset().config
                .option<ConfigOptionFloats>("nozzle_diameter", false);
            const size_t heads = nozzles ? std::max<size_t>(1, nozzles->values.size()) : 1;
            while (resolved_filaments.size() < heads) {
                resolved_filaments.push_back(primary_filament);
            }
            active_presets = resolved_filaments.size();
            g_preset_bundle->filament_presets = std::move(resolved_filaments);
            LOGI("Slice presets: printer=%s process=%s filaments=%zu multi=%d",
                 printer.c_str(), process.c_str(), active_presets, multiColour == JNI_TRUE ? 1 : 0);
            config = g_preset_bundle->full_config();
        } catch (const std::exception &e) {
            set_error(std::string("Slice config failed: ") + e.what());
            return JNI_FALSE;
        }
    }

    return launch_slice(model, std::move(config), overrides, out, active_presets,
                        multiColour == JNI_TRUE, env, callback) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_world_yemyat_slicepilot_NativeLib_nativeSetModelTransform(JNIEnv *, jclass,
                                                       jfloat rotX, jfloat rotY, jfloat rotZ,
                                                       jboolean mirrorX, jboolean mirrorY, jboolean mirrorZ,
                                                       jfloat offsetX, jfloat offsetY) {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    g_model_transform = ModelTransform{
        rotX, rotY, rotZ,
        mirrorX == JNI_TRUE, mirrorY == JNI_TRUE, mirrorZ == JNI_TRUE,
        offsetX, offsetY,
    };
    LOGI("Model transform set: rot=(%.1f, %.1f, %.1f) mirror=(%d, %d, %d) offset=(%.2f, %.2f)",
         rotX, rotY, rotZ,
         mirrorX == JNI_TRUE ? 1 : 0, mirrorY == JNI_TRUE ? 1 : 0, mirrorZ == JNI_TRUE ? 1 : 0,
         offsetX, offsetY);
}

// Per-piece placement for a file the parser split into pieces: [rowCount * 9] floats, one
// row per piece in the order the parser reported them -
//   [rotX, rotY, rotZ, mirrorX, mirrorY, mirrorZ, offsetX, offsetY, print]
// (degrees, 0/1 flags, mm relative to the bed centre, 0/1 for "print this piece").
// A row count that does not match the model, or a null array, clears the poses and the whole
// file is placed by nativeSetModelTransform as before.
JNIEXPORT void JNICALL
Java_world_yemyat_slicepilot_NativeLib_nativeSetPartPoses(JNIEnv *env, jclass, jfloatArray poses,
                                                           jint row_count) {
    std::vector<PartPose> parsed;
    const jsize values = poses == nullptr ? 0 : env->GetArrayLength(poses);
    const jsize rows = row_count > 0 ? row_count : 0;
    if (poses != nullptr && values == rows * kPartPoseRowSize && rows <= kMaxPartPoses) {
        const jfloat *v = env->GetFloatArrayElements(poses, nullptr);
        if (v != nullptr) {
            for (jsize r = 0; r < rows; ++r) {
                const jfloat *row = v + r * kPartPoseRowSize;
                PartPose p;
                p.rotX = row[0];
                p.rotY = row[1];
                p.rotZ = row[2];
                p.mirrorX = row[3] != 0.f;
                p.mirrorY = row[4] != 0.f;
                p.mirrorZ = row[5] != 0.f;
                p.offsetX = row[6];
                p.offsetY = row[7];
                p.print = row[8] != 0.f;
                parsed.push_back(p);
            }
            env->ReleaseFloatArrayElements(poses, const_cast<jfloat *>(v), JNI_ABORT);
        }
    }
    {
        std::lock_guard<std::mutex> lock(g_state.mutex);
        g_part_poses.swap(parsed);
    }
    LOGI("Part poses set: %zu pieces", g_part_poses.size());
}

JNIEXPORT void JNICALL
Java_world_yemyat_slicepilot_NativeLib_nativeCancel(JNIEnv *, jclass) {
    g_state.cancel_requested.store(true, std::memory_order_relaxed);
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (g_state.print_raw) g_state.print_raw->cancel();
}

JNIEXPORT jboolean JNICALL
Java_world_yemyat_slicepilot_NativeLib_nativeIsRunning(JNIEnv *, jclass) {
    return g_state.running ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_world_yemyat_slicepilot_NativeLib_nativeGetError(JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    return env->NewStringUTF(g_state.error.c_str());
}

JNIEXPORT jstring JNICALL
Java_world_yemyat_slicepilot_NativeLib_nativeGetVersion(JNIEnv *env, jclass) {
    return env->NewStringUTF(SLIC3R_APP_NAME " " SLIC3R_VERSION);
}

// Parses STL/3MF with the fast streaming preview parser (reads at most ~target
// triangles) and returns
//   {positions, normals, indices, bbox[6], partRows, partNames}
// partRows holds ten floats per piece - vertex start, vertex count, index start, index
// count, then the authored min/max xyz in mm - and partNames the matching labels, in the
// order the pieces appear in the file; see NativeLib.parsePreviewMesh and model/MeshPart.
// Older callers read the first four entries only, so the last two are additive: on failure
// the whole result is null.
JNIEXPORT jobjectArray JNICALL
Java_world_yemyat_slicepilot_NativeLib_nativeParsePreview(
    JNIEnv *env, jclass, jstring jpath, jint target_tris, jobject callback)
{
    const std::string path = jstring_to_string(env, jpath);
    try {
        // Optional progress callback: NativeLib.PreviewCallback.onProgress(int).
        jmethodID progress_mid = nullptr;
        if (callback) {
            jclass cb_cls = env->FindClass("world/yemyat/slicepilot/NativeLib$PreviewCallback");
            if (cb_cls) progress_mid = env->GetMethodID(cb_cls, "onProgress", "(I)V");
            env->DeleteLocalRef(cb_cls);
        }
        std::function<void(int)> progress;
        if (progress_mid)
            progress = [env, callback, progress_mid](int pct) {
                env->CallVoidMethod(callback, progress_mid, (jint) pct);
            };

        const auto t0 = std::chrono::steady_clock::now();
        preview::Preview pv;
        std::string err;
        if (!preview::parse_preview(path, (int) target_tris, pv, err, progress)) {
            LOGE("nativeParsePreview failed: %s", err.c_str());
            return nullptr;
        }
        const size_t vc = pv.positions.size() / 3;
        const size_t tc = pv.indices.size() / 3;
        if (vc == 0 || tc == 0) return nullptr;

        // Bounding box of the preview mesh.
        float mn[3] = {FLT_MAX, FLT_MAX, FLT_MAX};
        float mx[3] = {-FLT_MAX, -FLT_MAX, -FLT_MAX};
        for (size_t v = 0; v < vc; ++v)
            for (int k = 0; k < 3; ++k) {
                const float f = pv.positions[v * 3 + k];
                if (f < mn[k]) mn[k] = f;
                if (f > mx[k]) mx[k] = f;
            }

        jfloatArray pos = env->NewFloatArray((jsize) pv.positions.size());
        env->SetFloatArrayRegion(pos, 0, (jsize) pv.positions.size(), pv.positions.data());
        jfloatArray nrm = env->NewFloatArray((jsize) pv.normals.size());
        env->SetFloatArrayRegion(nrm, 0, (jsize) pv.normals.size(), pv.normals.data());
        jintArray idx = env->NewIntArray((jsize) pv.indices.size());
        env->SetIntArrayRegion(idx, 0, (jsize) pv.indices.size(), pv.indices.data());
        jfloatArray bbox = env->NewFloatArray(6);
        const float bb[6] = {mn[0], mn[1], mn[2], mx[0], mx[1], mx[2]};
        env->SetFloatArrayRegion(bbox, 0, 6, bb);

        jobjectArray out = env->NewObjectArray(7, env->FindClass("java/lang/Object"), nullptr);
        env->SetObjectArrayElement(out, 0, pos);
        env->SetObjectArrayElement(out, 1, nrm);
        env->SetObjectArrayElement(out, 2, idx);
        env->SetObjectArrayElement(out, 3, bbox);
        env->DeleteLocalRef(pos);
        env->DeleteLocalRef(nrm);
        env->DeleteLocalRef(idx);
        env->DeleteLocalRef(bbox);

        // The pieces themselves: ten floats per piece (see the header comment) plus their
        // names, so a piece can be drawn, posed or left out without touching the shared
        // vertex buffers.
        const size_t pc = pv.parts.size();
        std::vector<float> rows;
        rows.reserve(pc * 10);
        std::vector<jstring> names;
        names.reserve(pc);
        const char empty[] = "";
        for (const preview::PartGeometry &p : pv.parts) {
            rows.insert(rows.end(),
                        {(float) p.vertex_start,
                         (float) p.vertex_count,
                         (float) p.index_start,
                         (float) p.index_count,
                         p.bbox[0], p.bbox[1], p.bbox[2], p.bbox[3], p.bbox[4], p.bbox[5]});
            names.push_back(env->NewStringUTF(p.name.empty() ? empty : p.name.c_str()));
        }
        jfloatArray part_rows = env->NewFloatArray((jsize) rows.size());
        env->SetFloatArrayRegion(part_rows, 0, (jsize) rows.size(), rows.data());
        jclass str_cls = env->FindClass("java/lang/String");
        jobjectArray part_names = env->NewObjectArray((jsize) names.size(), str_cls, nullptr);
        for (size_t i = 0; i < names.size(); ++i) {
            env->SetObjectArrayElement(part_names, (jsize) i, names[i]);
            env->DeleteLocalRef(names[i]);
        }
        env->DeleteLocalRef(str_cls);
        env->SetObjectArrayElement(out, 4, part_rows);
        env->SetObjectArrayElement(out, 5, part_names);
        env->DeleteLocalRef(part_rows);
        env->DeleteLocalRef(part_names);

        // Per-triangle 1-based extruder ids for multi-colour 3MF preview colouring.
        if (pv.tri_ext.size() == tc) {
            jintArray ext = env->NewIntArray((jsize) pv.tri_ext.size());
            env->SetIntArrayRegion(ext, 0, (jsize) pv.tri_ext.size(), pv.tri_ext.data());
            env->SetObjectArrayElement(out, 6, ext);
            env->DeleteLocalRef(ext);
        }

        const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - t0).count();
        LOGI("nativeParsePreview: %zu tris, %zu verts, %zu parts in %lld ms", tc, vc, pc,
             (long long) ms);
        return out;
    } catch (const std::exception &e) {
        LOGE("nativeParsePreview crashed: %s", e.what());
        return nullptr;
    }
}

} // extern "C"
