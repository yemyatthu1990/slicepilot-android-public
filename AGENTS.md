# SlicePilot — agent notes

## JNI ↔ R8 rule (learned the hard way, v1.6.2 crash)

**Every Java type/method that native code resolves by name via JNI must be kept from R8
in `android/app/proguard-rules.pro`.** Release builds are minified; debug builds are not,
so JNI name lookups only break in release — they are invisible on a debug-tested phone.

The v1.6.2 release crashed on real devices with:

```
java.lang.NoSuchMethodError: no non-static method
  "Lworld/yemyat/slicepilot/NativeLib$PreviewCallback;.onProgress(I)V"
```

because `NativeLib$SliceCallback` had a `-keep` rule but `NativeLib$PreviewCallback` did not.

Rules:

- When adding a JNI callback (any `FindClass` / `GetMethodID` in `android/core/jni/*.cpp`),
  add a matching `-keep` / `-keep interface` rule in the same commit.
- `-keep class X { *; }` does **not** cover nested/inner types — each `X$Y` needs its own rule.
- After changing JNI, verify with a **release** build, not only `installDebug`:
  `./gradlew :app:bundleRelease` (R8 output in `app/build/outputs/mapping/release/mapping.txt`;
  check the callback method survived name-mangling there).

## Secrets

All secrets live in gitignored `secrets/` (template: `secrets.example/`). Never hardcode
tokens/keys/endpoint credentials in Kotlin/C++ — public site API URLs are fine. CI rebuilds
them from GitHub Actions secrets (see `.github/workflows/android.yml`).

## Release checklist

1. Bump `versionCode` + `versionName` in `android/app/build.gradle.kts`.
2. `cd android && ./gradlew :app:bundleRelease :app:extractReleaseNativeSymbolTables :app:mergeReleaseNativeDebugMetadata`
3. Upload with `scripts/upload_play_internal.py` (needs `google-api-python-client`; a venv
   works) including `--mapping` and `--native-symbols` so Crashlytics stays symbolicated.
4. Commit + tag `vX.Y.Z` + push (the tag triggers CI artifacts).
5. Store listing edits go through a Play API edit; leave the edit uncommitted to keep it a
   draft (see `store_listing/metadata.md`).

## Debugging on-device

- `adb logcat -s SliceCore` — native slice stages, timings, progress.
- `adb logcat -s ModelBrowser` — cloud browse/download.
- Crashes: `adb shell dumpsys dropbox --print` (full Java stacks) and `F/myat.slicepilot`
  lines in logcat for native aborts.
- Firebase CLI (`firebase`) + gcloud ADC are configured for `slicepilot-bbfb5`.
