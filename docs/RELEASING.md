# Releases and signing

## Build variants

```sh
./gradlew :app:assembleDebug
./gradlew :app:assemblePreview
./gradlew :app:assembleRelease
```

Debug is for development. Preview enables R8 and uses the local debug key for optimized testing. Release intentionally builds unsigned. No signing credentials belong in this repository.

Debug keys differ between machines. A preview is not a production signing identity and may not upgrade another developer's preview.

## Production signing

Use Android Studio's **Generate Signed Bundle / APK**, or create a dedicated keystore with `keytool`. Store it outside the checkout, back it up privately and reuse the certificate for every public update. Do not put passwords in committed files or shell arguments.

For CLI signing, set `ANDROID_BUILD_TOOLS` to the SDK build-tools directory and `RAAG_KEYSTORE` to your private keystore. `apksigner` prompts for the password:

```sh
mkdir -p dist
"$ANDROID_BUILD_TOOLS/zipalign" -p -f 4 app/build/outputs/apk/release/app-release-unsigned.apk dist/raag-music-aligned.apk
"$ANDROID_BUILD_TOOLS/apksigner" sign --ks "$RAAG_KEYSTORE" --out dist/raag-music-release.apk dist/raag-music-aligned.apk
"$ANDROID_BUILD_TOOLS/apksigner" verify --verbose dist/raag-music-release.apk
```

Do not publish a debug key. Changing the signing certificate prevents normal upgrades; preview users may need to export their library before reinstalling. The application ID is `com.raagmusic.app`; use this exact ID for Firebase registration and future releases. It was finalized before public distribution. Older private test builds with another ID are separate installations without automatic data migration.

## Release checklist

For an official build with optional basic usage reporting, add `-PfirebaseAnalytics=true` to all build/inventory commands and provide the private local client configuration as described in [FIREBASE.md](FIREBASE.md). Default source builds exclude Firebase. Preserve and review Google's SDK terms/notices for Firebase-enabled binaries.

1. Increase version code/name and update CHANGELOG.md; revise privacy text if behavior changed.
2. Run the source audit, exporter tests, unit tests, lint and debug/release builds. Perform relevant device checks, including optimized playback and offline license reading.
3. If dependencies change, refresh the exact runtime inventory:

```sh
./gradlew -I scripts/runtime_inventory.init.gradle :app:writeRuntimeInventory
```

4. Review dependency licenses/notices and update THIRD_PARTY_NOTICES.md and LICENSES/. Run `python3 scripts/sync_legal.py` and re-run the audit.
5. Commit/tag the exact tested source; build/sign from that tag and export the matching source ZIP.
6. Make corresponding source available for copyleft dependencies too. Preserve exact NewPipeExtractor/Rhino revisions, build scripts and local patches. Provide dependency source archives alongside binaries or another compliant source-access mechanism. An app-only source ZIP does not cover every dependency obligation; do not rely on unversioned upstream branches.
7. Publish the tag through GitHub Releases with the signed APK, app/dependency source as needed, checksums, notes and known provider/device limitations.

CI checks builds; it does not publish, upload keys or install an updater. Set up automated signing/publishing only after the public repository and private production signing identity are established.
