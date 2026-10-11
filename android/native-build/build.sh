#!/usr/bin/env bash
# Cross-compiles native deps for ElegooSlicer Android (arm64-v8a)
set -euo pipefail

NDK="$HOME/Android/Sdk/ndk/27.0.12077973"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
API=28
ABI=arm64-v8a
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
SRC="$HERE/src"
PREFIX="$HERE/prefix/$ABI"
JOBS=$(nproc)

CC="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang"
CXX="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang++"
AR="$TOOLCHAIN/bin/llvm-ar"
RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
STRIP="$TOOLCHAIN/bin/llvm-strip"

mkdir -p "$PREFIX/lib" "$PREFIX/include"

common_cmake() {
  cmake -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-$API \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$PREFIX" \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -DCMAKE_POLICY_DEFAULT_CMP0057=NEW \
    -DCMAKE_PREFIX_PATH="$PREFIX" \
    -DCMAKE_FIND_ROOT_PATH="$PREFIX" \
    "$@"
}

build_header_only() {
  echo ">>> header-only: cereal, eigen, cgal"
  cp -r "$SRC/cereal-1.3.0/include/cereal" "$PREFIX/include/"
  cp -r "$SRC/eigen-5.0.1/Eigen" "$PREFIX/include/"
  cp -r "$SRC/CGAL-5.6.3/include/CGAL" "$PREFIX/include/"
}

build_expat() {
  echo ">>> expat"
  rm -rf "$SRC/expat-2.5.0/build" && mkdir -p "$SRC/expat-2.5.0/build"
  common_cmake -S "$SRC/expat-2.5.0" -B "$SRC/expat-2.5.0/build" \
    -DEXPAT_BUILD_TESTS=OFF -DEXPAT_BUILD_TOOLS=OFF -DEXPAT_BUILD_EXAMPLES=OFF \
    -DEXPAT_SHARED_LIBS=OFF
  cmake --build "$SRC/expat-2.5.0/build" -j$JOBS && cmake --install "$SRC/expat-2.5.0/build"
}

build_libpng() {
  echo ">>> libpng"
  rm -rf "$SRC/libpng-1.6.35/build" && mkdir -p "$SRC/libpng-1.6.35/build"
  common_cmake -S "$SRC/libpng-1.6.35" -B "$SRC/libpng-1.6.35/build" \
    -DPNG_SHARED=OFF -DPNG_TESTS=OFF -DPNG_TOOLS=OFF
  cmake --build "$SRC/libpng-1.6.35/build" -j$JOBS && cmake --install "$SRC/libpng-1.6.35/build"
}

build_jpeg() {
  echo ">>> jpeg (libjpeg-turbo 3.0.1)"
  cd "$SRC"
  [ -d libjpeg-turbo-3.0.1 ] || { curl -sSL -o jpeg.zip https://github.com/libjpeg-turbo/libjpeg-turbo/archive/refs/tags/3.0.1.zip && unzip -q jpeg.zip; }
  cd "$HERE"
  rm -rf "$SRC/libjpeg-turbo-3.0.1/build" && mkdir -p "$SRC/libjpeg-turbo-3.0.1/build"
  common_cmake -S "$SRC/libjpeg-turbo-3.0.1" -B "$SRC/libjpeg-turbo-3.0.1/build" \
    -DENABLE_SHARED=OFF -DENABLE_STATIC=ON -DWITH_JPEG8=ON -DWITH_TESTS=OFF
  cmake --build "$SRC/libjpeg-turbo-3.0.1/build" -j$JOBS && cmake --install "$SRC/libjpeg-turbo-3.0.1/build"
}

build_qhull() {
  echo ">>> qhull"
  rm -rf "$SRC/qhull-8.0.2/b-android" && mkdir -p "$SRC/qhull-8.0.2/b-android"
  common_cmake -S "$SRC/qhull-8.0.2" -B "$SRC/qhull-8.0.2/b-android" \
    -DBUILD_SHARED_LIBS=OFF -DBUILD_STATIC_LIBS=ON -DBUILD_TESTING=OFF
  cmake --build "$SRC/qhull-8.0.2/b-android" -j$JOBS && cmake --install "$SRC/qhull-8.0.2/b-android"
}

build_nlopt() {
  echo ">>> nlopt"
  rm -rf "$SRC/nlopt-2.5.0/build" && mkdir -p "$SRC/nlopt-2.5.0/build"
  common_cmake -S "$SRC/nlopt-2.5.0" -B "$SRC/nlopt-2.5.0/build" \
    -DNLOPT_CXX=OFF -DNLOPT_PYTHON=OFF -DNLOPT_OCTAVE=OFF -DNLOPT_GUILE=OFF -DNLOPT_TESTS=OFF \
    -DBUILD_SHARED_LIBS=OFF
  cmake --build "$SRC/nlopt-2.5.0/build" -j$JOBS && cmake --install "$SRC/nlopt-2.5.0/build"
}

build_libnoise() {
  echo ">>> libnoise"
  rm -rf "$SRC/Orca-deps-libnoise-1.0/build" && mkdir -p "$SRC/Orca-deps-libnoise-1.0/build"
  common_cmake -S "$SRC/Orca-deps-libnoise-1.0" -B "$SRC/Orca-deps-libnoise-1.0/build"
  cmake --build "$SRC/Orca-deps-libnoise-1.0/build" -j$JOBS && cmake --install "$SRC/Orca-deps-libnoise-1.0/build"
}

build_tbb() {
  echo ">>> tbb"
  rm -rf "$SRC/oneTBB-2021.5.0/build" && mkdir -p "$SRC/oneTBB-2021.5.0/build"
  common_cmake -S "$SRC/oneTBB-2021.5.0" -B "$SRC/oneTBB-2021.5.0/build" \
    -DTBB_TEST=OFF -DBUILD_SHARED_LIBS=OFF -DTBB_INSTALL=ON -DTBBMALLOC_BUILD=ON
  cmake --build "$SRC/oneTBB-2021.5.0/build" -j$JOBS && cmake --install "$SRC/oneTBB-2021.5.0/build"
}

build_openssl() {
  echo ">>> openssl"
  cd "$SRC/openssl-openssl-3.1.8"
  export ANDROID_NDK_HOME="$NDK"
  export ANDROID_NDK_ROOT="$NDK"
  export PATH="$TOOLCHAIN/bin:$PATH"
  rm -rf "$PREFIX/ssl"
  ./Configure android-arm64 -D__ANDROID_API__=$API no-shared no-tests no-engine \
    --prefix="$PREFIX" --openssldir="$PREFIX/ssl" 1>/dev/null
  make -j$JOBS install_sw 1>/dev/null
  cd "$HERE"
}

build_boost() {
  echo ">>> boost"
  cd "$SRC/boost-1.84.0"
  if [ ! -f ./b2 ]; then ./bootstrap.sh --with-toolset=clang >/dev/null; fi
  cat > "$HERE/boost-user-config.jam" <<EOF
using clang : arm64 : $CXX :
    <arch>arm
    <address-model>64
    ;
EOF
  ./b2 --user-config="$HERE/boost-user-config.jam" \
    toolset=clang-arm64 target-os=android address-model=64 \
    variant=release link=static runtime-link=static \
    cflags=-fPIC cxxflags=-fPIC \
    --with-system --with-filesystem --with-thread --with-log --with-locale \
    --with-regex --with-chrono --with-atomic --with-date_time --with-iostreams \
    --with-program_options --with-nowide \
    -j$JOBS -q install --prefix="$PREFIX"
  cd "$HERE"
}

build_freetype() {
  echo ">>> freetype"
  cd "$SRC"
  [ -d freetype-2.12.1 ] || { curl -sSL -o ft.tar.gz https://github.com/SoftFever/orca_deps/releases/download/freetype-2.12.1.tar.gz/freetype-2.12.1.tar.gz && tar xzf ft.tar.gz; }
  cd "$HERE"
  rm -rf "$SRC/freetype-2.12.1/build" && mkdir -p "$SRC/freetype-2.12.1/build"
  common_cmake -S "$SRC/freetype-2.12.1" -B "$SRC/freetype-2.12.1/build" \
    -DFT_DISABLE_BZIP2=ON -DFT_DISABLE_BROTLI=ON -DFT_DISABLE_HARFBUZZ=ON -DFT_DISABLE_PNG=ON \
    -DBUILD_SHARED_LIBS=OFF -DSKIP_PROGRAMS=ON
  cmake --build "$SRC/freetype-2.12.1/build" -j$JOBS && cmake --install "$SRC/freetype-2.12.1/build"
}

build_occt() {
  echo ">>> occt"
  cd "$SRC"
  [ -d occt-7.6.0 ] || { curl -sSL -o occt.zip https://github.com/Open-Cascade-SAS/OCCT/archive/refs/tags/V7_6_0.zip && unzip -q occt.zip && mv OCCT-7_6_0 occt-7.6.0; }
  cd "$HERE"
  cd "$SRC/occt-7.6.0"
  git apply --verbose --ignore-space-change --whitespace=fix "$REPO_ROOT/deps/OCCT/0001-OCCT-fix.patch" 2>/dev/null || echo "(patch already applied or skipped)"
  cd "$HERE"
  rm -rf "$SRC/occt-7.6.0/build" && mkdir -p "$SRC/occt-7.6.0/build"
  common_cmake -S "$SRC/occt-7.6.0" -B "$SRC/occt-7.6.0/build" \
    -DCMAKE_CXX_STANDARD=17 \
    -DBUILD_LIBRARY_TYPE=Static \
    -DUSE_TK=OFF -DUSE_TBB=OFF -DUSE_FFMPEG=OFF -DUSE_VTK=OFF \
    -DBUILD_DOC_Overview=OFF \
    -DBUILD_MODULE_ApplicationFramework=OFF \
    -DBUILD_MODULE_Draw=OFF \
    -DBUILD_MODULE_FoundationClasses=OFF \
    -DBUILD_MODULE_ModelingAlgorithms=OFF \
    -DBUILD_MODULE_ModelingData=OFF \
    -DBUILD_MODULE_Visualization=OFF \
    -DUSE_FREETYPE=ON -DFREETYPE_DIR="$PREFIX" \
    -DBUILD_TESTING=OFF
  cmake --build "$SRC/occt-7.6.0/build" -j$JOBS && cmake --install "$SRC/occt-7.6.0/build"
}

build_blosc() {
  echo ">>> blosc"
  cd "$SRC"
  [ -d c-blosc-1.17.0-tm ] || { curl -sSL -o blosc.zip https://github.com/tamasmeszaros/c-blosc/archive/refs/heads/v1.17.0_tm.zip && unzip -q blosc.zip && mv c-blosc-v1.17.0_tm c-blosc-1.17.0-tm; }
  cd "$HERE"
  rm -rf "$SRC/c-blosc-1.17.0-tm/build" && mkdir -p "$SRC/c-blosc-1.17.0-tm/build"
  common_cmake -S "$SRC/c-blosc-1.17.0-tm" -B "$SRC/c-blosc-1.17.0-tm/build" \
    -DBUILD_SHARED=OFF -DBUILD_STATIC=ON -DBUILD_TESTS=OFF -DBUILD_BENCHMARKS=OFF \
    -DDEPRECATE_HARD=OFF -DCMAKE_C_STANDARD=11 \
    -DCMAKE_C_FLAGS="-D_GNU_SOURCE"
  cmake --build "$SRC/c-blosc-1.17.0-tm/build" -j$JOBS && cmake --install "$SRC/c-blosc-1.17.0-tm/build"
}

build_openvdb() {
  echo ">>> openvdb"
  cd "$SRC/openvdb-a68fd58d0e2b85f01adeb8b13d7555183ab10aa5"
  git apply --verbose --ignore-space-change --whitespace=fix "$REPO_ROOT/deps/OpenVDB/0001-clang19.patch" 2>/dev/null || echo "(patch already applied or skipped)"
  cd "$HERE"
  rm -rf "$SRC/openvdb-a68fd58d0e2b85f01adeb8b13d7555183ab10aa5/build" && mkdir -p "$SRC/openvdb-a68fd58d0e2b85f01adeb8b13d7555183ab10aa5/build"
  common_cmake -S "$SRC/openvdb-a68fd58d0e2b85f01adeb8b13d7555183ab10aa5" \
    -B "$SRC/openvdb-a68fd58d0e2b85f01adeb8b13d7555183ab10aa5/build" \
    -DOPENVDB_BUILD_PYTHON_MODULE=OFF \
    -DUSE_BLOSC=ON \
    -DOPENVDB_CORE_SHARED=OFF \
    -DOPENVDB_CORE_STATIC=ON \
    -DOPENVDB_ENABLE_RPATH=OFF \
    -DTBB_STATIC=ON \
    -DOPENVDB_BUILD_VDB_PRINT=OFF \
    -DDISABLE_DEPENDENCY_VERSION_CHECKS=ON \
    -DBoost_DIR="$PREFIX/lib/cmake/Boost-1.84.0" \
    -DBoost_USE_STATIC_LIBS=ON -DBoost_USE_STATIC_RUNTIME=ON
  cmake --build "$SRC/openvdb-a68fd58d0e2b85f01adeb8b13d7555183ab10aa5/build" -j$JOBS && \
    cmake --install "$SRC/openvdb-a68fd58d0e2b85f01adeb8b13d7555183ab10aa5/build"
}

target="${1:-all}"
case "$target" in
  boost) build_boost ;;
  openssl) build_openssl ;;
  tbb) build_tbb ;;
  openvdb) build_openvdb ;;
  freetype) build_freetype ;;
  occt) build_occt ;;
  blosc) build_blosc ;;
  jpeg) build_jpeg ;;
  quick) build_header_only; build_expat; build_libpng; build_jpeg; build_qhull; build_nlopt; build_libnoise ;;
  all) build_header_only; build_expat; build_libpng; build_jpeg; build_qhull; build_nlopt; build_libnoise; build_tbb; build_openssl; build_boost; build_freetype; build_occt; build_openvdb ;;
  *) echo "usage: $0 {boost|openssl|tbb|openvdb|quick|all}"; exit 1 ;;
esac
echo ">>> $target OK"
