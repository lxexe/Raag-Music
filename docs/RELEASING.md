# Releases and signing

## Automatic signed GitHub releases

The **Signed release** workflow builds and tests the tagged source, checks the exact runtime inventory, aligns/signs/verifies the APK and creates a **draft GitHub Release** containing the signed APK, audited app source ZIP, both dependency inventories and SHA-256 checksums. No signing commands are needed for future releases. The final Publish release action remains manual so you can test the APK and finish the dependency source attachments described below. This automation does not add an in-app updater.

### One-time private setup

After committing/pushing the automation, run from your GitHub-connected checkout:

```sh
cd ~/raag-music
bash scripts/setup_release_secrets.sh
```

The helper uses GitHub CLI (`brew install gh`, then `gh auth login` if needed). It prompts for a keystore path, alias and password. Reuse an existing production key if you have one; otherwise it creates a new private PKCS12 keystore outside the checkout. Back up that file and password privately and reuse them for all updates. Enter the path to your local `google-services.json` when asked for Firebase statistics. The helper uploads directly to repository Actions Secrets without printing values. Do not paste passwords or keys into issues, commits or chat.

The secrets can alternatively be entered at **Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Value |
| --- | --- |
| `RAAG_KEYSTORE_BASE64` | Base64 of the production signing keystore |
| `RAAG_KEYSTORE_PASSWORD` | Keystore password |
| `RAAG_KEY_ALIAS` | Signing key alias, usually `raag` |
| `RAAG_KEY_PASSWORD` | Key password (defaults to keystore password if absent) |
| `GOOGLE_SERVICES_JSON_BASE64` | Base64 of the local Firebase client configuration |

A base64 keystore must fit GitHub's 48 KB secret limit. The helper does not create tokens or upload credentials into source. Repository policy must allow GitHub Actions to create releases (`contents: write`). Use the helper's optional first argument to target a different `owner/repository`.

### Each release

1. Set `versionName`, increase `versionCode` in `app/build.gradle.kts` for updates, update CHANGELOG.md and commit/push the tested changes to `main`.
2. Push a matching tag. For the current app version:

```sh
git tag v1.24.4
git push origin v1.24.4
```

3. Open **Actions → Signed release** to follow progress. Once it finishes, open **Releases**, download/test the draft APK, attach corresponding dependency sources or provide the required source-access mechanism, edit the notes, and click **Publish release**.

Tag-triggered releases include Firebase by default. For SDK-free tag releases, set the repository Actions variable `RELEASE_FIREBASE` to `false` before pushing the tag. You can also use **Actions → Signed release → Run workflow**, enter an existing tag and turn off **Include basic usage statistics** (avoid running both triggers for the same release). The manual workflow requires the workflow file to be on the default branch; the tag's source must also contain these scripts. Tags must exactly match `vMAJOR.MINOR.PATCH` and Android `versionName`, and point to a commit on `main`. A second run refuses to overwrite an existing release; signed build artifacts remain downloadable from the Actions run if draft creation fails. Delete an unpublished draft deliberately before rerunning if needed; never overwrite a published release's APK under the same version.

CI does not run device playback tests. Download the signed APK and check playback/background playback, downloads, settings and the offline notices before publishing. Do not publish the unsigned APK or a debug/preview key. Release output does not include the keystore, passwords, Firebase configuration file, or R8 mapping files.

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

The Android checks workflow verifies source changes. The separate Signed release workflow prepares signed draft releases after the private signing secrets are configured; it never runs on pull requests. Publishing and device testing remain maintainer actions.
