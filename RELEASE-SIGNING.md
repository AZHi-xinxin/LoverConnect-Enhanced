# LoverConnect release signing

Release builds must use the existing private LoverConnect release key so an APK can cover-install the current RC5 package without clearing app data. The key and its passwords must never be committed.

The build reads these four values from temporary environment variables or private Gradle properties:

- `LC_RELEASE_STORE_FILE`
- `LC_RELEASE_STORE_PASSWORD`
- `LC_RELEASE_KEY_ALIAS`
- `LC_RELEASE_KEY_PASSWORD`

If any value is missing, `assembleRelease` deliberately stops instead of producing a misleading unsigned artifact. Debug builds remain available for compilation and isolated-device testing, but their certificate cannot cover-install RC5.

After signing, compare the new APK certificate SHA-256 with the RC5 certificate before installation. Do not print passwords in logs or write them into this source tree.
