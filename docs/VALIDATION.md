# Open-source preparation checks

Latest validated version: **1.24.4 (36)**, application ID **`com.raagmusic.app`**, on 24 October 2026.

## Coroutines dependency inventory correction — 24 October 2026

- Inspected hosted run [40092974760](https://github.com/lxexe/Raag-Music/actions/runs/40092974760), which tested Coroutines 1.11.0. Its build/test/lint step passed; the inventory check failed because the PR changed library versions without updating the recorded graph.
- Applied Coroutines 1.11.0, which resolves Kotlin standard library 2.2.20. The Kotlin Compose compiler plugin remains 2.2.10. Regenerated both runtime inventories with resolved dependencies and updated source links/version notices, including the app's offline copy. The inventory check remains enabled.
- Full standard build validation passed: debug/release APKs, instrumentation APK compilation, all 235 unit tests without failures/skips, and lint with 0 errors and 43 warnings. Firebase inventory generation passed online; Firebase APK builds and live playback were not repeated.
- Inventory generation now rejects unresolved dependencies rather than silently exporting an incomplete graph. Source audit and all eight exporter tests pass. The source ZIP and GitHub-connected checkout include the correction.

## OkHttp Dependabot exclusion correction — 24 October 2026

- Inspected hosted run [40093038318](https://github.com/lxexe/Raag-Music/actions/runs/40093038318), which tested the OkHttp 5.4.0 dependency PR. AAR metadata validation failed because that version requires compile SDK 36 and the app uses 35.
- The previous exclusion of versions 5.5.0 and above allowed Dependabot to propose 5.4.0. Broadened the exclusion to all versions 5.0.0 and above, retaining updates within the tested 4.x series. Remove the exclusion only after validating the SDK and playback migration. The application dependency remains 4.12.0; CI is unchanged.
- Source audit, eight exporter tests, workflow/Dependabot YAML parsing and whitespace checks pass. App builds were not repeated for this configuration/documentation-only correction; the preceding full build remains applicable. Existing PRs and failed run records are not rewritten by local configuration changes.

## Dependency update CI review — 24 October 2026

- Inspected hosted runs 40081516882 (OkHttp 5.5.0), 40081720767 (Kotlin Compose plugin 2.4.21), and 40081539294 (Coil 2.7.0). OkHttp failed AAR metadata validation because it requires compile SDK 37; the project uses 35. Kotlin and Coil passed build/test/lint and failed the dependency inventory check.
- Added an explicit inventory mismatch error, a review artifact, and branch-specific regeneration/license review guidance in CONTRIBUTING.md. The inventory guard and pinned dependencies remain unchanged. These workflow changes do not resolve or merge the existing dependency PRs.
- Workflow YAML parsing, public source audit, all eight exporter tests, and whitespace checks pass locally. No application code changed; Android builds were not repeated for these workflow/documentation changes. Hosted execution of the revised workflow remains to be verified after pushing.

## Dependency compatibility correction — 24 October 2026

- Updated Coil to 2.7.0 and its transitive Okio dependency to 3.9.0. Regenerated both standard and optional Firebase inventories from Gradle resolution, updated third-party version/source notices, and synchronized the offline notices.
- Retained Kotlin Compose plugin 2.2.10. Local validation of 2.4.21 exposed D8 Kotlin metadata parsing errors despite the earlier hosted build completing; the newer Kotlin version needs a coordinated toolchain update. Dependabot excludes Kotlin Compose plugin versions 2.4 and above until that migration.
- Retained OkHttp 4.12.0. Initially excluded versions 5.5.0 and above because they require compile SDK 37; the follow-up correction below broadens this exclusion. The app remains on compile SDK 35. Both exclusions are documented for removal with the toolchain migration; CI checks remain enabled.
- Full default CI-equivalent validation passed: debug APK, optimized release APK, instrumentation APK compilation, all 235 unit tests (no failures/skips), and lint (0 errors). The optional Firebase inventory was regenerated successfully; Firebase APK builds were not repeated for this dependency-only change.
- The compatibility exclusions affect future Dependabot processing after pushing the configuration. Existing failed run records remain historical; do not merge the incompatible update branches.

## Default reporting and separate settings — 1.24.4

CI setup correction: the first hosted run failed before compilation because SDK setup requested the obsolete `tools` package. The workflow now specifies the supported SDK packages explicitly, restricts push-triggered checks to `main` and permits manual dispatch. Workflow YAML/source checks pass locally; a fresh hosted run is required to confirm the full GitHub environment.

- New Firebase installations enable basic usage reporting by default. Saved opt-outs remain disabled across updates. The SDK-free build cannot enable remote reporting.
- App usage and Listening are separate privacy tabs, reachable from dedicated Settings entries. Names, songs, artists, searches, playlists, library contents and listening history are explicitly excluded from analytics; Firebase device/app metadata and its installation identifier are disclosed accurately.
- Firebase and clean SDK-free debug/release builds, instrumentation APK compilation, all 235 unit tests and lint pass. Lint has 0 errors, with 45 warnings for Firebase and 32 for the clean SDK-free build.
- All five reporting-preference instrumentation tests pass against the SDK-free app using isolated preferences and a fake backend. Tests cover default-on behavior, saved opt-outs, explicit choices, withdrawal and inability to enable reporting without an SDK backend.
- Public source audit and all eight exporter tests pass. Client configuration remains excluded. Both tested builds use the same application sources/assets as the final source export.
- Advertising and automatic screen tracking remain disabled. The removed auto-initialization provider lets application startup honor a saved opt-out before initializing Firebase.
- In a fresh temporary emulator, verified the Firebase App usage switch starts on, the real SDK disables collection when switched off, and the Listening switch changes local analytics independently. Checked the two-tab UI in light mode. This smoke check used Firebase debug mode; the test device was left opted out.
- The first-launch consent dialog was removed. The 1.24.3 validation below describes the previous opt-in behavior and is retained as historical verification.

## Optional basic usage reporting — 1.24.3

- Validated the locally supplied client configuration against `com.raagmusic.app` without exposing its values. It remains ignored and excluded from source exports.
- Firebase-enabled debug/release builds, instrumentation APK compilation, 235 unit tests and lint passed. Firebase lint reports 0 errors and 45 warnings.
- Extracted the public ZIP without any Firebase configuration and built the SDK-free debug/release variants and instrumentation APK. All 235 unit tests pass; default lint reports 0 errors and 32 warnings.
- The default dependency graph contains no Firebase or Google Play services SDKs and matches the original dependency inventory. A separate Firebase inventory records 164 release modules, including platform metadata.
- Four consent instrumentation tests pass against the debug app using a fake backend: no collection before a choice, persistent decline, opt-in/withdrawal across restart, and inability to enable reporting without an SDK backend. These tests send no analytics data.
- Verified the Firebase release manifest removes FirebaseInitProvider and advertising-ID/AdServices permissions, and disables collection, automatic screen reporting and advertising-ID collection by default.
- Verified the first-launch choice, saved decline and independent Settings switch on the emulator. After declining, the measurement preference file had not been created. Sequenced the reporting choice after the existing optional-name onboarding prompt.
- All 13 bundled legal/privacy documents match their source in the optimized APK. A locally debug-signed Firebase preview is provided for testing; use a private production identity for public releases.
- Console reports and live data arrival must be checked in the linked Analytics project after explicit opt-in; the console account/settings were not accessed during implementation. See FIREBASE.md for DebugView and report instructions.

## Final Android identity — 1.24.2

- Application ID and Kotlin namespace are `com.raagmusic.app`; benchmark namespace is `com.raagmusic.app.benchmarks`.
- Rebranded application, Compose, widget and resource names. Updated baseline-profile descriptors, tests and source-audit paths.
- Debug/release builds, instrumentation APK compilation, all 235 unit tests and lint passed. Lint has 0 errors and 35 warnings in the development checkout.
- Optional benchmark Kotlin compilation passed with `-PperformanceTools=true`.
- Checked the built APK's package, version, launcher, Application, media services, widget components and FileProvider authority for the final identity.
- Installed a locally debug-signed optimized APK and successfully cold-launched `com.raagmusic.app.MainActivity` on the emulator.
- This is a new Android identity finalized before public distribution. Old private test installations are separate; automatic data migration is not included.
- Register **`com.raagmusic.app`** in Firebase. Optional Firebase reporting was added subsequently in 1.24.3; see FIREBASE.md.

## Original preparation record

The following records the earlier **1.24.1 (33)** source-cleanup validation, before the Android identity change.

## Source and cleanup

- Removed unused demonstration photos, abandoned branding concepts, historical screenshots/logs, APK archives and unused cloud/cast scaffolding.
- Kept final Raag Music artwork, licensed fonts, application source, meaningful tests and optional benchmark tooling.
- Removed a personal contact from the lyric request header. No credential patterns were found in the audited public snapshot.
- Eight exporter tests pass, covering excluded history/builds, private keys, credential redaction, symlinks, manifest traversal and intentional synthetic stream signatures.
- The public source export contains no Git history, SDK configuration, keys, APKs, generated caches or historical development logs. Git history is neither rewritten nor audited.

## Build verification

The source ZIP was extracted into a separate folder and built with JDK 17 and an installed Android SDK, using cached public dependencies. It required no private repository, API key or original local.properties file.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:assembleDebugAndroidTest --offline
```

All tasks passed. **235 unit tests passed**, with no failures or skips. Lint reported **0 errors and 30 warnings** in the clean export (35 in the original checkout), including dependency/version suggestions and manifest/layout recommendations. Instrumentation APK compilation passed; the full instrumented suite and live-provider playback probes were not run during this cleanup.

The dependency inventory was reproduced from the extracted source with no changes. It records 127 release modules (including platform metadata) and two desugaring modules. Every one of the 13 bundled legal/privacy documents and the font license matches its reviewed source in the optimized APK. APK alignment/signature verification and installation of a locally debug-signed optimized build passed on the development emulator.

The optimized app's About screen showed version 1.24.1. The bundled privacy and GPL documents opened successfully, and document/list/Settings back navigation worked. Installation used an in-place update and retained the existing application ID.

GitHub workflow/template YAML parsed successfully. A fresh repository initialized from the extracted source passed the initial staged-file whitespace check. Actual GitHub Actions execution starts after the first push; no remote or public release was created.

## Publishing

Use the [first-push guide](OPEN_SOURCE_GUIDE.md) and the clean ZIP. Deleted development files still exist in the old private history; pushing that history would publish them again. Follow [release/signing guidance](RELEASING.md) for production signing, device checks and corresponding dependency source availability.

The snapshot audit checks common patterns and allowlisted files. It is not a complete security, rights or licensing assessment.
