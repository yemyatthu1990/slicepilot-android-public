// JNI bridge between the Kotlin app and the native slicing core.
#include <jni.h>
#include <string>
#include <memory>
#include <thread>
#include <atomic>
#include <mutex>
#include <typeinfo>
#include <set>

#include <android/log.h>

#include "libslic3r/Model.hpp"
#include "libslic3r/Config.hpp"
#include "libslic3r/Print.hpp"
#include "libslic3r/PrintConfig.hpp"
#include "libslic3r/Utils.hpp"
#include "libslic3r/Preset.hpp"
#include "libslic3r/PresetBundle.hpp"
#include "libslic3r/AppConfig.hpp"

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

std::unique_ptr<AppConfig>     g_app_config;
std::unique_ptr<PresetBundle> g_preset_bundle;

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

void apply_model_transform(Model &model, const Vec3d &bedCenter) {
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

    // Shift the whole model (instances included) so its XY center sits at the
    // requested offset (bed center by default) and its lowest point is on the bed.
    // Moving instances is required for 3MF files, whose placement lives in the
    // object instance transform rather than the mesh.
    BoundingBoxf3 global_bb;
    for (ModelObject *obj : model.objects) {
        for (size_t i = 0; i < obj->instances.size(); ++i) {
            BoundingBoxf3 bb = obj->instance_bounding_box(i);
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
// so the file's support/quality settings are respected. Machine & filament keys
// are intentionally left to the selected presets.
void merge_embedded_print_settings(DynamicPrintConfig &config, const DynamicPrintConfig &embedded) {
    if (embedded.empty()) return;
    static const std::set<std::string> skip_keys = {
        "outer_wall_filament_id",
        "inner_wall_filament_id",
        "sparse_infill_filament_id",
        "internal_solid_filament_id",
        "top_surface_filament_id",
        "bottom_surface_filament_id",
        "support_filament",
        "support_interface_filament",
        "wipe_tower_filament",
        "filament_type",
        "filament_colour",
        "filament_ids",
        "filament_settings_id",
        "filament_map",
    };
    int applied = 0;
    for (const std::string &key : Preset::print_options()) {
        if (skip_keys.count(key) > 0) continue;
        if (const ConfigOption *opt = embedded.option(key)) {
            config.set_key_value(key, opt->clone());
            ++applied;
        }
    }
    LOGI("Applied %d print settings embedded in the model file", applied);
}

void clamp_filament_selection(DynamicPrintConfig &config) {
    int num_filaments = g_preset_bundle ? static_cast<int>(g_preset_bundle->filament_presets.size()) : 1;
    if (num_filaments < 1) num_filaments = 1;

    auto clamp_option = [&config, num_filaments](const char *key, int lo, int hi) {
        ConfigOptionInt *opt = config.option<ConfigOptionInt>(key, false);
        if (!opt) return;
        int v = opt->value;
        if (v < lo || v > hi) {
            opt->value = (v < lo) ? lo : 0;
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

void run_slice(std::string model_path, DynamicPrintConfig config, std::string overrides_ini, std::string out_path) {
    try {
        report_progress(0, "Loading model", 0);
        try {
            auto fsize = boost::filesystem::file_size(boost::filesystem::path(model_path));
            LOGI("Slice input: %s size=%llu bytes", model_path.c_str(), (unsigned long long) fsize);
        } catch (const std::exception &e) {
            LOGE("Slice input stat failed for %s: %s", model_path.c_str(), e.what());
        }
        DynamicPrintConfig embedded;
        ConfigSubstitutionContext embedded_substitutions(ForwardCompatibilitySubstitutionRule::EnableSilent);
        Model model = Model::read_from_file(model_path, &embedded, &embedded_substitutions,
            LoadStrategy::AddDefaultInstances | LoadStrategy::LoadModel | LoadStrategy::LoadConfig);
        LOGI("Model loaded: %zu objects, embedded config keys=%zu", model.objects.size(), embedded.keys().size());
        merge_embedded_print_settings(config, embedded);
        if (!overrides_ini.empty()) {
            try {
                config.load_from_ini_string(overrides_ini, ForwardCompatibilitySubstitutionRule::EnableSilent);
            } catch (const std::exception &e) {
                LOGE("Failed to apply overrides: %s", e.what());
            }
        }
        clamp_filament_selection(config);
        Vec3d bedCenter = bed_center_from_config(config);
        LOGI("Bed center: x=%.3f y=%.3f", bedCenter.x(), bedCenter.y());
        apply_model_transform(model, bedCenter);

        auto print = std::make_unique<Print>();
        print->set_status_callback([](const PrintBase::SlicingStatus &status) {
            report_progress(0, status.text, status.percent);
        });
        {
            std::lock_guard<std::mutex> lock(g_state.mutex);
            g_state.print = std::move(print);
            g_state.print_raw = g_state.print.get();
        }

        g_state.print_raw->apply(model, std::move(config));
        g_state.print_raw->process();
        g_state.print_raw->export_gcode(out_path, nullptr);
        LOGI("G-code exported to %s", out_path.c_str());

        std::lock_guard<std::mutex> lock(g_state.mutex);
        g_state.error.clear();
    } catch (const CanceledException &) {
        LOGI("Slice cancelled");
        std::lock_guard<std::mutex> lock(g_state.mutex);
        g_state.cancelled = true;
    } catch (const std::exception &e) {
        LOGE("Slice failed (%s): %s", typeid(e).name(), e.what());
        std::lock_guard<std::mutex> lock(g_state.mutex);
        g_state.error = e.what();
    }
    g_state.running = false;
}

// Launch a slice job with a fully-built config. Returns false if a slice is already running.
bool launch_slice(const std::string &model_path, DynamicPrintConfig config, const std::string &overrides_ini,
                 const std::string &out_path, JNIEnv *env, jobject callback) {
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
        g_state.running = true;
    }
    // Join the old worker outside the lock: it has already passed its last mutex use
    // (running was observed false), so this cannot deadlock.
    if (old.joinable()) old.join();
    {
        std::lock_guard<std::mutex> lock(g_state.mutex);
        g_state.worker = std::thread(run_slice, model_path, std::move(config), overrides_ini, out_path);
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
    LOGI("nativeInit data=%s res=%s", data.c_str(), res.c_str());

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
    return launch_slice(model, std::move(config), ini, out, env, callback) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_world_yemyat_slicepilot_NativeLib_nativeGetPrinters(JNIEnv *env, jclass) {
    if (!g_preset_bundle) return env->NewStringUTF("[]");
    return env->NewStringUTF(presets_to_json(g_preset_bundle->printers).c_str());
}

JNIEXPORT jstring JNICALL
Java_world_yemyat_slicepilot_NativeLib_nativeGetFilaments(JNIEnv *env, jclass) {
    if (!g_preset_bundle) return env->NewStringUTF("[]");
    return env->NewStringUTF(presets_to_json(g_preset_bundle->filaments).c_str());
}

JNIEXPORT jstring JNICALL
Java_world_yemyat_slicepilot_NativeLib_nativeGetProcesses(JNIEnv *env, jclass) {
    if (!g_preset_bundle) return env->NewStringUTF("[]");
    return env->NewStringUTF(presets_to_json(g_preset_bundle->prints).c_str());
}

JNIEXPORT jboolean JNICALL
Java_world_yemyat_slicepilot_NativeLib_nativeSelectPrinter(JNIEnv *env, jclass, jstring printerName) {
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
    if (!g_preset_bundle) return env->NewStringUTF("[]");
    return env->NewStringUTF(presets_to_json(g_preset_bundle->prints, true).c_str());
}

JNIEXPORT jstring JNICALL
Java_world_yemyat_slicepilot_NativeLib_nativeGetCompatibleFilaments(JNIEnv *env, jclass) {
    if (!g_preset_bundle) return env->NewStringUTF("[]");
    return env->NewStringUTF(presets_to_json(g_preset_bundle->filaments, true).c_str());
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

    if (!g_preset_bundle) {
        set_error("PresetBundle not loaded");
        return JNI_FALSE;
    }

    DynamicPrintConfig config;
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

    return launch_slice(model, std::move(config), "", out, env, callback) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_world_yemyat_slicepilot_NativeLib_nativeSliceWithOverrides(JNIEnv *env, jclass, jstring modelPath,
                                                        jstring printerName, jstring filamentName,
                                                        jstring processName, jstring overridesIni,
                                                        jstring outPath, jobject callback) {
    std::string model = jstring_to_string(env, modelPath);
    std::string printer = jstring_to_string(env, printerName);
    std::string filament = jstring_to_string(env, filamentName);
    std::string process = jstring_to_string(env, processName);
    std::string overrides = jstring_to_string(env, overridesIni);
    std::string out = jstring_to_string(env, outPath);

    if (!g_preset_bundle) {
        set_error("PresetBundle not loaded");
        return JNI_FALSE;
    }

    DynamicPrintConfig config;
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
        set_error(std::string("Slice config failed: ") + e.what());
        return JNI_FALSE;
    }

    return launch_slice(model, std::move(config), overrides, out, env, callback) ? JNI_TRUE : JNI_FALSE;
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

JNIEXPORT void JNICALL
Java_world_yemyat_slicepilot_NativeLib_nativeCancel(JNIEnv *, jclass) {
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

} // extern "C"
