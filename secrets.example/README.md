# secrets/

Copy this directory to `secrets/` at the repo root and fill in the real files.
`secrets/` is gitignored and must never be committed.

```text
secrets/
  build.properties             Cloud endpoints, client ids, RSA public key,
                               Agora App ID (injected into BuildConfig at build time).
  google-services.json         Firebase project config for the SlicePilot app.
                               Gradle copies it to android/app/ at build time.
  upload-keystore.properties   Signing config for release builds (see template).
  slicepilot-upload.jks        Android upload keystore.
  slicepilot-upload-cert.pem   Certificate of the upload key.
  play-service-account.json    Google Cloud service account key with the
                               Google Play Developer API permission, used by
                               scripts/upload_play_internal.py.
```

Without `upload-keystore.properties`, release builds sign with the debug key.
Without `google-services.json`, the build fails with a "google-services.json
is missing" error from the Google Services Gradle plugin.
Without `build.properties` (or `-P<KEY>=` / env vars), the build fails with a
"Missing build secret" error naming the key.
