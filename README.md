# SlicePilot — On-Device Android 3D Slicer & Elegoo Centauri Carbon 2 Print Controller

[<img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" alt="Get it on Google Play" width="240">](https://play.google.com/store/apps/details?id=world.yemyat.slicepilot)

- **Google Play (Free):** <https://play.google.com/store/apps/details?id=world.yemyat.slicepilot>
- **Website & Guides:** <https://slicepilot.eu> ([Centauri Carbon 2 & AMS](https://slicepilot.eu/elegoo-centauri-carbon-2/) · [Android 3D Slicer](https://slicepilot.eu/android-3d-slicer/) · [MakerWorld Customizer](https://slicepilot.eu/makerworld-customizer-android/) · [Comparison](https://slicepilot.eu/compare/))

**SlicePilot** puts a full desktop-class 3D slicer (native C++ `libslic3r`), an in-app **MakerWorld parametric customizer**, and **multi-colour AMS / Canvas** cloud & local LAN printing for the **Elegoo Centauri Carbon 2** straight on your Android phone — no PC required.

## Highlights (v1.10.2)

- **Native On-Device Slicing (`libslic3r` via JNI):** Slice STL and multi-part / multi-colour 3MF projects locally on Android (`arm64-v8a`, 16 KB page aligned) with supports, multi-material support interfaces, brim/skirt/raft, seams, and background slicing.
- **In-App MakerWorld Parametric Customizer:** Personalize OpenSCAD parametric models (name keychains, signs, tags) with an inline interactive 3D preview, 48+ curated fonts, a 20-swatch + RGB filament colour picker, and one-tap multi-colour 3MF export.
- **Multi-Colour 3MF & Centauri Carbon 2 Canvas / AMS Support:** Colour 3D model preview, automatic spool matching against loaded AMS / Canvas filaments, compact waste-chute prime tower (`M6211`) verified strictly inside the 256×256 mm build plate, and layer-by-layer G-code preview.
- **4 Model Communities in One App:** Unified search and browsing across **MakerWorld**, **Printables**, **Thingiverse**, and **Nexprint**, plus local STL/3MF files from Android Files.
- **Cloud & Local LAN Printer Control:** Connect to your Elegoo Centauri Carbon 2 over Elegoo Cloud (password or Sign in with Google) or local Wi-Fi (LAN) to upload sliced G-code, monitor print progress, watch the live printer camera, and control temperatures, fans, and case light.

This repository contains the Android app UI, `:cloud-auth` module, JNI bridge, `libslic3r` sources, landing page (`landing/`), and native build scripts. It intentionally does **not** include prebuilt native dependency binaries or downloaded third-party native source trees.

## Repository Layout

```text
android/              Android Gradle project, app module, cloud-auth module, JNI bridge, native build scripts
src/libslic3r/        Slicing library sources used by the Android native build
src/slic3r/           Shared slic3r headers/sources required by libslic3r
deps_src/             Vendored native dependency sources used by the Android build
knowledge/            Project knowledge graph: current state, history, and status digest (see below)
```

## Project Knowledge Graph

`knowledge/` keeps track of where the project actually stands, so the answer is not trapped in
chat logs or in someone's memory of the last release:

```bash
open knowledge/STATUS.md                 # generated status: shipped, in flight, blocked, next
python3 knowledge/kb.py query --kind feature
python3 knowledge/kb.py show feat:multi-filament
python3 knowledge/kb.py session add --agent pi --summary "..." --touched feat:... --evidence commit:...
python3 knowledge/kb.py render           # regenerate STATUS.md from the graph
```

`graph.json` holds the nodes (project, releases, modules, features, branches, externals,
devices, artifacts, secrets, rules, issues) with their status, evidence and next actions;
`sessions.jsonl` is the append-only log of what each agent session changed. `knowledge/README.md`
documents the schema, and agents are expected to read the graph before working and update it in
the same commit as their change. `STATUS.md` is generated — edit the graph instead.

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
- `CloudLoginData`
- `CloudSocialLogin`
- `CloudLoginWebView`

Signing in is either the account's email and password, or Google (and Facebook) through Elegoo's
federated auth centre: the app opens Elegoo's provider redirect in a browser tab and exchanges the
token that comes back on `world.yemyat.slicepilot://social/google` for the same session a password
login returns. No Google Cloud setup and no extra secret are needed — the Google OAuth client is
Elegoo's and their server runs the exchange (see `CloudSocialLogin`). Google is configured for the
international region only, so the option is hidden when the account is on `CLOUD_GATEWAY_CHINA`.

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
  build.properties             Cloud endpoints, client ids, RSA public key,
                               Agora App ID (injected into BuildConfig at build time)
  google-services.json         Firebase config (copied to android/app/ at build time)
  upload-keystore.properties   store/key passwords for the release signing config
  slicepilot-upload.jks        upload signing keystore
  slicepilot-upload-cert.pem   upload certificate
  play-service-account.json    Google Cloud service account key (used by scripts/upload_play_internal.py)
```

To set up a fresh clone, create `secrets/` and provide these files. See `secrets.example/` for the expected layout and templates. Without the keystore, release builds fall back to the debug signing config; without `google-services.json` or `build.properties`, the build fails with a clear error.

### Build configuration (`secrets/build.properties`)

The app and `:cloud-auth` modules read cloud endpoints, OAuth client ids, the
Elegoo RSA public key, and the Agora App ID from `secrets/build.properties`
(local builds) or environment variables / Gradle `-P` properties, and inject
them as `BuildConfig` fields. Nothing is hardcoded in the sources. Keys:

```text
CLOUD_GATEWAY_GLOBAL, CLOUD_GATEWAY_CHINA          Elegoo cloud API gateways
CLOUD_LOGIN_URL_GLOBAL, CLOUD_LOGIN_URL_CHINA      web login pages
CLOUD_COOKIE_DOMAINS                               comma-separated cookie domains
CLOUD_CLIENT_ID, CLOUD_OAUTH_CLIENT_ID             OAuth client identifiers
CLOUD_RSA_PUBLIC_KEY                               password-encryption public key (PEM, \n-escaped)
AGORA_APP_ID                                       Agora App ID for RTM
```

### CI (GitHub Actions)

`.github/workflows/android.yml` runs on `v*` tags (and manually). It writes
the repository secrets into `secrets/`, builds the native dependencies
(cached), then `assembleRelease` + `bundleRelease`, and publishes a GitHub
Release with `app-release.apk` (direct install) and `app-release.aab` (the
format submitted to Google Play). Repository secrets required:
`BUILD_PROPERTIES`, `GOOGLE_SERVICES_JSON`, `KEYSTORE_PROPERTIES`,
`UPLOAD_KEYSTORE_BASE64`.

## Firebase

`android/app/google-services.json` is **not** committed. Place the Firebase config file at `secrets/google-services.json` and the Gradle build copies it into `android/app/` automatically (task `copyFirebaseConfig`).

Firebase Analytics and Crashlytics are enabled. Caught errors are sent to both Analytics and Crashlytics.
