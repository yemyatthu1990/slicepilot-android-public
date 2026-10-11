#!/usr/bin/env bash
# Downloads the third-party native sources expected by android/native-build/build.sh
# into android/native-build/src (used by CI; locally you can provide them yourself).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/../android/native-build/src"
mkdir -p "$SRC"
cd "$SRC"

get() { # url, expected-directory-name
  local url="$1" dir="$2" tmp
  if [ -d "$dir" ]; then
    echo ">>> $dir present"
    return
  fi
  echo ">>> fetching $dir"
  tmp="$(mktemp -d)"
  curl -sSL --retry 3 -o "$tmp/archive" "$url"
  case "$url" in
    *.zip)    unzip -q "$tmp/archive" -d "$tmp" ;;
    *.tar.gz) tar xzf "$tmp/archive" -C "$tmp" ;;
    *.tgz)    tar xzf "$tmp/archive" -C "$tmp" ;;
    *.tar.bz2) tar xjf "$tmp/archive" -C "$tmp" ;;
    *) echo "unknown archive type: $url" >&2; exit 1 ;;
  esac
  local root
  root="$(find "$tmp" -mindepth 1 -maxdepth 1 -type d | head -1)"
  [ -n "$root" ] || { echo "failed to extract $url" >&2; exit 1; }
  mv "$root" "$dir"
  rm -rf "$tmp"
}

get https://github.com/usnistgov/cereal/archive/refs/tags/v1.3.0.zip cereal-1.3.0
get https://gitlab.com/libeigen/eigen/-/archive/5.0.1/eigen-5.0.1.tar.gz eigen-5.0.1
get https://github.com/CGAL/cgal/archive/refs/tags/v5.6.3.zip CGAL-5.6.3
get https://github.com/libexpat/libexpat/releases/download/R_2_5_0/expat-2.5.0.tar.gz expat-2.5.0
get https://github.com/qhull/qhull/archive/refs/tags/v8.0.2.zip qhull-8.0.2
get https://github.com/stevengj/nlopt/archive/refs/tags/v2.5.0.tar.gz nlopt-2.5.0
get https://github.com/SoftFever/Orca-deps-libnoise/archive/refs/heads/master.zip Orca-deps-libnoise-1.0
get https://github.com/oneapi-src/oneTBB/archive/refs/tags/v2021.5.0.tar.gz oneTBB-2021.5.0
get https://github.com/openssl/openssl/archive/refs/tags/openssl-3.1.8.tar.gz openssl-openssl-3.1.8
get https://archives.boost.io/release/1.84.0/source/boost_1_84_0.tar.bz2 boost-1.84.0
get https://github.com/openvdb/openvdb/archive/a68fd58d0e2b85f01adeb8b13d7555183ab10aa5.tar.gz openvdb-a68fd58d0e2b85f01adeb8b13d7555183ab10aa5

echo ">>> all native sources ready in $SRC"
