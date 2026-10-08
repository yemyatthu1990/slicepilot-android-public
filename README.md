# SlicePilot Android

Standalone Android app for SlicePilot, including the Android UI, JNI bridge, `libslic3r`, and vendored native sources required by the Android native build.

This repository intentionally does **not** include prebuilt native dependency binaries or downloaded third-party native source trees. Those are expected to be built locally or provided separately.

## Repository Layout

```text
android/              Android Gradle project, JNI bridge, native build scripts
src/libslic3r/        Slicing library sources used by the Android native build
deps_src/             Vendored native dependency sources used by the Android build
```

## Requirements

- Android SDK
- Android NDK `27.0.12077973` or compatible
- CMake `3.18+`
- Ninja
- JDK 17

The native build script currently defaults to:

```bash
$HOME/Android/Sdk/ndk/27.0.12077973
```

Edit `android/native-build/build.sh` if your NDK path is different.

## Native Dependencies

This repo includes only the native build scripts:

```bash
android/native-build/build.sh
android/native-build/boost-user-config.jam
```

Before running the script, place the required third-party native sources under:

```text
android/native-build/src/
```

Expected source directories include Boost, CGAL, Eigen, OpenSSL, TBB, OCCT, OpenVDB, libpng, libjpeg-turbo, expat, qhull, nlopt, libnoise, blosc, cereal, and FreeType.

Build the native dependencies:

```bash
android/native-build/build.sh
```

This produces:

```text
android/native-build/prefix/arm64-v8a/
```

## Build the Android App

```bash
cd android
./gradlew :app:assembleDebug
```

Install on a connected device:

```bash
./gradlew :app:installDebug
```

## Firebase

The repository includes `android/app/google-services.json` for the SlicePilot Firebase project.

Firebase Analytics and Crashlytics are enabled. Caught errors are sent to both Analytics and Crashlytics.
