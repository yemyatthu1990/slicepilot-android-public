# SlicePilot Android

Standalone Android app for SlicePilot, including the Android UI, JNI bridge, `libslic3r`, and vendored native sources required by the Android native build.

This repository intentionally does **not** include prebuilt native dependency binaries or downloaded third-party native source trees. Those are expected to be built locally or provided separately.

## Repository Layout

```text
android/              Android Gradle project, app module, cloud-auth module, JNI bridge, native build scripts
src/libslic3r/        Slicing library sources used by the Android native build
src/slic3r/           Shared slic3r headers/sources required by libslic3r
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

## Cloud Auth Library

The `:cloud-auth` module owns Elegoo cloud sign-in/session state:

```text
android/cloud-auth/
```

It provides:

- `CloudAuthClient`
- `CloudAuthResult`
- `CloudAuthUser`
- `CloudLoginWebView`

The app module depends on it with:

```kotlin
implementation(project(":cloud-auth"))
```

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

## Secrets (not in the repo)

Sensitive files are kept out of git in a local `secrets/` directory at the repo root:

```text
secrets/
  google-services.json         Firebase config (copied to android/app/ at build time)
  upload-keystore.properties   store/key passwords for the release signing config
  slicepilot-upload.jks        upload signing keystore
  slicepilot-upload-cert.pem   upload certificate
  play-service-account.json    Google Play service account key (used by scripts/upload_play_internal.py)
```

To set up a fresh clone, create `secrets/` and provide these files. See `secrets.example/` for the expected layout and a template for `upload-keystore.properties`. Without the keystore, release builds fall back to the debug signing config; without `google-services.json`, the Google Services plugin fails the build (see Firebase section below).

## Firebase

`android/app/google-services.json` is **not** committed. Place the Firebase config file at `secrets/google-services.json` and the Gradle build copies it into `android/app/` automatically (task `copyFirebaseConfig`).

Firebase Analytics and Crashlytics are enabled. Caught errors are sent to both Analytics and Crashlytics.
