# Android core: libslic3r + vendored deps + JNI bridge -> libslice_core.so
cmake_minimum_required(VERSION 3.18)
project(slice_core CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

get_filename_component(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../.." ABSOLUTE)
include_directories("${REPO_ROOT}/src" "${CMAKE_BINARY_DIR}/gen")
set(LIBSLIC3R_DIR "${REPO_ROOT}/src/libslic3r")
set(DEPS_SRC "${REPO_ROOT}/deps_src")
include_directories("${DEPS_SRC}")
set(NATIVE_PREFIX "${CMAKE_CURRENT_SOURCE_DIR}/../native-build/prefix/${ANDROID_ABI}"
    CACHE PATH "Prebuilt native dependency prefix")

# ---------------------------------------------------------------------------
# INTERFACE shims for names used by deps_src targets
# ---------------------------------------------------------------------------
add_library(Eigen3::Eigen INTERFACE IMPORTED)
set_target_properties(Eigen3::Eigen PROPERTIES INTERFACE_INCLUDE_DIRECTORIES "${NATIVE_PREFIX}/include")
add_library(NLopt::nlopt INTERFACE IMPORTED)
set_target_properties(NLopt::nlopt PROPERTIES
    INTERFACE_INCLUDE_DIRECTORIES "${NATIVE_PREFIX}/include"
    INTERFACE_LINK_LIBRARIES "${NATIVE_PREFIX}/lib/libnlopt.a")
add_library(TBB::tbb INTERFACE IMPORTED)
set_target_properties(TBB::tbb PROPERTIES
    INTERFACE_INCLUDE_DIRECTORIES "${NATIVE_PREFIX}/include"
    INTERFACE_LINK_LIBRARIES "${NATIVE_PREFIX}/lib/libtbb.a")
add_library(TBB::tbbmalloc INTERFACE IMPORTED)
set_target_properties(TBB::tbbmalloc PROPERTIES
    INTERFACE_INCLUDE_DIRECTORIES "${NATIVE_PREFIX}/include"
    INTERFACE_LINK_LIBRARIES "${NATIVE_PREFIX}/lib/libtbbmalloc.a")
add_library(Boost::boost INTERFACE IMPORTED)
set_target_properties(Boost::boost PROPERTIES INTERFACE_INCLUDE_DIRECTORIES "${NATIVE_PREFIX}/include")
add_library(semver_includes INTERFACE IMPORTED)
set_target_properties(semver_includes PROPERTIES INTERFACE_INCLUDE_DIRECTORIES "${DEPS_SRC}")
add_library(boost_headeronly INTERFACE IMPORTED)
set_target_properties(boost_headeronly PROPERTIES INTERFACE_INCLUDE_DIRECTORIES "${NATIVE_PREFIX}/include")
add_library(libslic3r INTERFACE IMPORTED)
set_target_properties(libslic3r PROPERTIES
    INTERFACE_INCLUDE_DIRECTORIES "${REPO_ROOT}/src;${CMAKE_BINARY_DIR}/gen;${NATIVE_PREFIX}/include")

# ---------------------------------------------------------------------------
# Vendored deps from deps_src
# ---------------------------------------------------------------------------
add_subdirectory("${DEPS_SRC}/admesh" ${CMAKE_BINARY_DIR}/deps/admesh)
add_subdirectory("${DEPS_SRC}/clipper" ${CMAKE_BINARY_DIR}/deps/clipper)
add_subdirectory("${DEPS_SRC}/clipper2" ${CMAKE_BINARY_DIR}/deps/clipper2)
add_subdirectory("${DEPS_SRC}/glu-libtess" ${CMAKE_BINARY_DIR}/deps/glu-libtess)
add_subdirectory("${DEPS_SRC}/libigl" ${CMAKE_BINARY_DIR}/deps/libigl)
add_subdirectory("${DEPS_SRC}/libnest2d" ${CMAKE_BINARY_DIR}/deps/libnest2d)
add_subdirectory("${DEPS_SRC}/mcut" ${CMAKE_BINARY_DIR}/deps/mcut)
add_subdirectory("${DEPS_SRC}/miniz" ${CMAKE_BINARY_DIR}/deps/miniz)
add_subdirectory("${DEPS_SRC}/qoi" ${CMAKE_BINARY_DIR}/deps/qoi)
add_subdirectory("${DEPS_SRC}/semver" ${CMAKE_BINARY_DIR}/deps/semver)
add_subdirectory("${DEPS_SRC}/earcut" ${CMAKE_BINARY_DIR}/deps/earcut)
add_subdirectory("${DEPS_SRC}/nlohmann" ${CMAKE_BINARY_DIR}/deps/nlohmann)
add_subdirectory("${DEPS_SRC}/ankerl" ${CMAKE_BINARY_DIR}/deps/ankerl)
add_subdirectory("${DEPS_SRC}/fast_float" ${CMAKE_BINARY_DIR}/deps/fast_float)
add_subdirectory("${DEPS_SRC}/stb_dxt" ${CMAKE_BINARY_DIR}/deps/stb_dxt)
add_subdirectory("${DEPS_SRC}/nanosvg" ${CMAKE_BINARY_DIR}/deps/nanosvg)

# ---------------------------------------------------------------------------
# libslic3r version header
# ---------------------------------------------------------------------------
set(SLIC3R_APP_NAME "ElegooSlicer")
set(SLIC3R_APP_KEY "ElegooSlicer")
set(SLIC3R_VERSION "1.5.3-android")
set(ELEGOOSLICER_VERSION "1.5.3-android")
set(SLIC3R_BUILD_ID "1.5.3-android")
set(BBL_INTERNAL_TESTING 0)
set(ORCA_CHECK_GCODE_PLACEHOLDERS 0)
set(ORCA_COMPATIBLE_VERSION "2.4.2")
set(ELEGOO_INTERNAL_TESTING 0)
set(BAMBU_COMPATIBLE_VERSION "02.06.00.51")
set(ELEGOO_ENABLE_DEBUG_OUTPUT 0)
set(ELEGOO_CHINA_IOT_URL "")
set(ELEGOO_GLOBAL_IOT_URL "")
set(ELEGOO_CHINA_UPDATE_URL "")
set(ELEGOO_GLOBAL_UPDATE_URL "")
set(ELEGOO_CHINA_LOGIN_URL "")
set(ELEGOO_GLOBAL_LOGIN_URL "")
set(ELEGOO_CHINA_ONLINE_MODEL_URL "")
set(ELEGOO_GLOBAL_ONLINE_MODEL_URL "")
set(ELEGOO_CHINA_PLUGIN_UPDATE_URL "")
set(ELEGOO_GLOBAL_PLUGIN_UPDATE_URL "")
set(ELEGOO_CHINA_PROFILE_UPDATE_URL "")
set(ELEGOO_GLOBAL_PROFILE_UPDATE_URL "")
set(ELEGOO_CHINA_TELEMETRY_URL "")
set(ELEGOO_GLOBAL_TELEMETRY_URL "")
set(SENTRY_DSN_WIN "")
set(SENTRY_DSN_MAC "")
set(SENTRY_DSN_LINUX "")
configure_file("${LIBSLIC3R_DIR}/libslic3r_version.h.in" "${CMAKE_BINARY_DIR}/gen/libslic3r_version.h" @ONLY)

# ---------------------------------------------------------------------------
# libslic3r sources
# ---------------------------------------------------------------------------
file(GLOB_RECURSE SLIC3R_SOURCES "${LIBSLIC3R_DIR}/*.cpp")
list(REMOVE_ITEM SLIC3R_SOURCES
    "${LIBSLIC3R_DIR}/Format/DRC.cpp"          # draco: not built for Android
    "${LIBSLIC3R_DIR}/GCodeSender.cpp"         # serial sender: not used on Android
    "${LIBSLIC3R_DIR}/ObjColorUtils.cpp"      # obj color utils: OpenCV-based, GUI only
    "${LIBSLIC3R_DIR}/TryCatchSignalSEH.cpp"  # Windows SEH variant
)

# CGAL-dependent static library (same sources as upstream libslic3r_cgal)
set(CGAL_SOURCES
    "${LIBSLIC3R_DIR}/CutSurface.cpp"
    "${LIBSLIC3R_DIR}/IntersectionPoints.cpp"
    "${LIBSLIC3R_DIR}/MeshBoolean.cpp"
    "${LIBSLIC3R_DIR}/TryCatchSignal.cpp"
    "${LIBSLIC3R_DIR}/Triangulation.cpp"
)
foreach (s ${CGAL_SOURCES})
    list(REMOVE_ITEM SLIC3R_SOURCES ${s})
endforeach ()

file(GLOB BOOST_LIBS "${NATIVE_PREFIX}/lib/libboost_*.a")

add_library(libslic3r_cgal STATIC ${CGAL_SOURCES})
target_compile_definitions(libslic3r_cgal PRIVATE CGAL_DISABLE_GMP)
target_include_directories(libslic3r_cgal PUBLIC
    "${LIBSLIC3R_DIR}" "${CMAKE_BINARY_DIR}/gen" "${NATIVE_PREFIX}/include")
target_link_directories(libslic3r_cgal PUBLIC "${NATIVE_PREFIX}/lib")
target_link_libraries(libslic3r_cgal PUBLIC admesh libigl ${BOOST_LIBS})

add_library(slice_core SHARED
    ${SLIC3R_SOURCES}
    "${CMAKE_CURRENT_SOURCE_DIR}/jni/slice_jni.cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/stubs/android_stubs.cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/stubs/nanosvg_impl.cpp"
)

find_library(ANDROID_LOG log)
find_library(ANDROID_Z z)

target_compile_definitions(slice_core PRIVATE
    USE_TBB
    TBB_USE_CAPTURED_EXCEPTION=0
    CGAL_DISABLE_GMP
    BOOST_ALL_NO_LIB
    SLIC3R_NO_WIN_SDL=1
)

target_include_directories(slice_core PRIVATE
    "${LIBSLIC3R_DIR}"
    "${CMAKE_BINARY_DIR}/gen"
    "${NATIVE_PREFIX}/include"
    "${NATIVE_PREFIX}/include/opencascade"
    "${CMAKE_CURRENT_SOURCE_DIR}/jni"
    "${CMAKE_CURRENT_SOURCE_DIR}/stubs"
)
target_link_directories(slice_core PRIVATE "${NATIVE_PREFIX}/lib")

# Boost static libs (built by native-build/build.sh)
file(GLOB BOOST_LIBS "${NATIVE_PREFIX}/lib/libboost_*.a")

target_link_libraries(slice_core PRIVATE
    libslic3r_cgal
    admesh libigl libnest2d miniz clipper Clipper2 glu-libtess mcut qoi semver semver_includes nanosvg
    "${NATIVE_PREFIX}/lib/libexpat.a"
    "${NATIVE_PREFIX}/lib/libpng.a"
    "${NATIVE_PREFIX}/lib/libjpeg.a"
    "${NATIVE_PREFIX}/lib/libqhullstatic_r.a"
    "${NATIVE_PREFIX}/lib/libqhullcpp.a"
    "${NATIVE_PREFIX}/lib/libblosc.a"
    "${NATIVE_PREFIX}/lib/libnlopt.a"
    "${NATIVE_PREFIX}/lib/liblibnoise_static.a"
    "${NATIVE_PREFIX}/lib/libtbb.a"
    "${NATIVE_PREFIX}/lib/libtbbmalloc.a"
    "${NATIVE_PREFIX}/lib/libcrypto.a"
    "${NATIVE_PREFIX}/lib/libfreetype.a"
    ${BOOST_LIBS}
    ${ANDROID_LOG} ${ANDROID_Z}
    c++_static
)

if (EXISTS "${NATIVE_PREFIX}/lib/libopenvdb.a")
    target_link_libraries(slice_core PRIVATE "${NATIVE_PREFIX}/lib/libopenvdb.a")
endif ()
if (EXISTS "${NATIVE_PREFIX}/lib/libTKernel.a")
    file(GLOB OCCT_LIBS "${NATIVE_PREFIX}/lib/libTK*.a")
    target_link_libraries(slice_core PRIVATE ${OCCT_LIBS})
endif ()

target_link_options(slice_core PRIVATE "-Wl,--build-id=sha1")
