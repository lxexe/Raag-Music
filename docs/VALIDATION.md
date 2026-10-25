# Open-source preparation checks

Latest validated version: **1.24.10 (41)**, application ID **`com.raagmusic.app`**, on 25 October 2026.

## Playback reliability and Quick Picks — 1.24.10 (41)

- Added safe, track-scoped player failure cards with retry countdowns and an external YouTube action that cannot route back into Raag. Failure classification survives MediaController IPC through session extras without exposing provider bodies, cookies or signed stream URLs.
- Verification pauses audio/video resolution across songs for 60 seconds. Resolver rate limits use the provider's numeric Retry-After (30–300 seconds, 60-second fallback); media-server HTTP 429 also registers a shared 60-second cooldown. Manual retries cannot clear the cooldown; expiry or a different validated connection permits a fresh session. Initial capability callbacks do not count as a connection change. Wrapped provider restrictions retain their type and do not receive repository/automatic playback retries. Temporary network/403 stream recovery retains its two-attempt budget and preserves queue/position.
- Added local completion counts, migrated as zero for old records. Completion requires 80% actually heard; seeking cannot manufacture it. Early skips require under 30 seconds heard and ignore unheard/failed tracks. History clearing and analytics preference changes reset the active listening ledger.
- Quick Picks uses qualified local listening, completions, likes, early skips, followed artists and Not interested. Positive seeds drive related-song/artist retrieval; arbitrary recent clicks no longer choose artists. A retained pool re-ranks meaningful signals locally, with recording-alias exclusions, artist diversity, room for supported discovery and feedback-aware cache keys. Not interested remembers metadata so alternate uploads remain excluded. Shelf scrolling is preserved during reranking.
- Final SDK-free and Firebase `testDebugUnitTest`, `lintDebug`, `assembleDebug` and optimized `assembleRelease` pass. All 280 unit tests pass in each variant; lint reports 0 errors and 36 existing warnings. The SDK-free instrumentation APK compiles. All 14 Python release/exporter tests, public source audit, release metadata for v1.24.10 and whitespace checks pass. No dependencies changed.
- Six focused device tests pass on the disposable SDK-free emulator: failure actions in light/AMOLED, cooldown taps and working retry, immediate local Quick Picks updates after likes/Not interested without refetching, plus the four existing search/update/settings scheduling regressions. Visually reviewed the failure card in light and AMOLED. Initial device runs encountered an emulator System UI ANR and host-sleep expiry of a short synthetic cooldown; the final test uses a long fixture deadline and passes. No live carrier playback, public release or production APK installation was performed.
- Updated the privacy disclosure and bundled copy, architecture, changelog, release examples and audited source ZIP. Synced the changes to the GitHub-connected checkout without changing its Git history. Committing/tagging/publishing remain user actions.
- Real-carrier limitation: the emulator cannot validate the user's mobile carrier or remove YouTube IP/account verification. Before publishing, test the signed release on mobile data: several songs, next/previous, background playback, airplane-mode interruption/recovery, switching Wi-Fi/mobile networks, repeated verification retries, and queue/position preservation. Confirm Open in YouTube opens an external app. Check that listening/skips/completions change Quick Picks and disabling Listening analytics stops new aggregates. Do not call mobile playback verified until those phone checks pass.

## Search grouping and update presentation — 1.24.9 (40)

- Replaced the mixed All search feed with bounded, labelled previews: Songs (4), Artists/Albums/Playlists (3 each), Videos (2) and Your library (3), each linking to its full category. Dedicated categories retain full results and append available provider continuation pages; Library searches local saved tracks without provider requests.
- Category paging now continues across a bounded number of duplicate-only pages while stopping repeated cursor cycles. Query/category changes cancel old work and reset pagination. Suggestions have their own labelled group.
- Reworked the update dialog with status/version card, readable notes, prominent Update app and immediate Maybe later dismissal. Removed schedule/connectivity explanations and timestamps from the normal update UI. Added a compact in-app update message and optional local update notifications that open the release notes. Settings includes notification controls and extra bottom content clearance.
- Replaced the daily periodic job with persisted morning/evening tasks targeting 08:00 and 19:00 local time. Each reschedules its next occurrence; clock/timezone changes rebuild scheduling. The repository deduplicates automatic checks by local window and preserves manual checking/rate-limit handling. Android can defer jobs for network/battery conditions; exact execution times are not promised. The old daily job is cancelled during migration.
- Updated the privacy disclosure and bundled copy, changelog, architecture and release guidance. No new dependency or remote analytics event was added. Notification permission is requested only when users select Update notifications; in-app notices remain available when notifications are off.
- Final SDK-free and Firebase debug/release builds, instrumentation APK compilation and lint pass. All 264 unit tests pass in each variant, with 0 lint errors and 36 warnings in each. All 14 Python release/exporter tests pass. Four device tests pass on a disposable SDK-free emulator: light/AMOLED update dialog controls and hidden schedule copy, grouped category continuation without songs leaking into Artists, final Settings action clearance, and migration from the legacy daily job to two persisted jobs.
- Visually reviewed the update dialogs, grouped All/Artists search, and Settings bottom clearance using synthetic fixtures. A separate background-job smoke test produced a local update notification once, persisted the notified version, and opened the in-app notes when tapped. No fake public release or real APK installation was triggered; the temporary emulator was closed after testing.
- No dependencies changed. The audited source ZIP and GitHub-connected checkout include the feature; committing/tagging/publishing remain user actions.

## Playback verification/session recovery — 1.24.8 (39)

- Traced the reported verification message to NewPipe's bot-verification response mapping. This can reflect a YouTube connection restriction; no confirmed upstream fix for this symptom was identified. The extractor dependency remains unchanged.
- Fixed stale in-memory visitor handling: both reused memory and disk sessions receive at most one refresh on LOGIN_REQUIRED within the existing request deadline. Refresh requests explicitly remove the old visitor value before obtaining a new one. Explicit bot-verification responses discard the rejected session and stop without full-extractor fanout. CAPTCHA exceptions also stop immediately. The repository does not apply its normal network retry to typed verification failures; age/account restrictions retain their separate handling.
- Added regressions for successful in-memory recovery, persistent verification across audio/video, no fallback on CAPTCHA, distinction from age/account requirements, and no repository retry on verification. Existing cancellation, stream rejection and session expiry tests remain in the full suite.
- Full SDK-free and Firebase-enabled debug/release builds, instrumentation APK compilation, all 253 unit tests in each variant, and lint passed (0 errors). All 14 Python release/exporter tests, YAML syntax, source audit and whitespace checks pass. No dependencies changed; APK device playback on the reporting connection was not tested.
- This is an app-side session/retry correction, not a bypass of YouTube verification. Playback on the reporting user's mobile connection was not reproduced or proven fixed; an IP/network restriction may remain. No account login, cookie collection, proxy, or verification bypass was added.

## Daily GitHub update checks — 1.24.7 (38)

- Added a network-constrained, persisted Android JobScheduler task with a 24-hour interval and six-hour flex window. Android selects the execution time; Activity startup catches up if the daily check is due. RECEIVE_BOOT_COMPLETED preserves the job after reboot. No new dependency or runtime permission prompt was added.
- Added Settings → Check for updates and an in-app release-note dialog. Only newer stable published semantic versions prompt; drafts/prereleases, equal versions and downgrades do not. Later persists dismissal of that version; manual checking remains available. Official APK downloads open in the browser, with normal user-controlled installation.
- Added serialized checks, ETag caching, offline notes, bounded responses/notes, short manual retry cooldown, automatic error backoff and server rate-limit handling. Bad responses retain the last valid cache. Notes display as text without remote images or scripts. Update failures and unavailable storage do not interrupt playback.
- Release workflow notes now come from the exact CHANGELOG section; hidden maintainer comments are excluded from the in-app display. Updated privacy, architecture and release guidance, including the bundled privacy copy. Existing 1.24.6 installations need a one-time manual install of 1.24.7 before they can check future versions.
- Full SDK-free and Firebase debug/release builds, instrumentation APK compilation and lint passed during development. Update unit coverage includes version ordering, cached/manual checks, 304s, rate limiting, cancellation, concurrency, clock changes, bad caches, missing releases and storage failures. Final validation: all 248 unit tests pass in each variant (including 13 updater regressions), 0 lint errors and 42 warnings in each variant. All 14 Python release/exporter tests pass. Runtime dependencies remain unchanged.
- A fresh disposable emulator confirmed the Settings action, immediate check dialog, persisted daily job, synthetic newer-release prompt with cached notes and Download button, Later persistence across cold restart, and forced execution of the scheduled service. Network failures were exercised; live GitHub delivery in the emulator was not established. No fake release was published, APK download/installation was not triggered, and the temporary emulator was closed.
- Both runtime inventories reproduce unchanged. Source audit, Python release/exporter tests, YAML syntax and whitespace checks pass. The public source ZIP excludes the private Firebase configuration and signing credentials. No GitHub commit/tag/release was pushed as part of this feature.

## Repository download button — 24 October 2026

- Added a local SVG Download latest APK button near the top of README, linking to the latest published release's `Raag-Music-latest.apk` asset. Added an adjacent release-notes link.
- Updated the signed release workflow to attach the stable APK alias alongside the versioned filename, with both included in checksums. APK content/signature is unchanged; the alias is a byte-for-byte copy after signing.
- Source audit, all 12 Python tests, workflow YAML parsing and whitespace checks passed. Updated the README's Gradle version to match the current wrapper.

## Release version correction — 1.24.6 (37)

- Hosted run 40100527795 stopped before build/signing: tag v1.24.5 pointed to Android versionName 1.24.4. Prepared fresh version 1.24.6 with incremented versionCode 37, avoiding replacement of existing tags.
- Release metadata validation now passes for v1.24.6. Python tests, source audit, YAML parsing and whitespace checks pass; source ZIP refreshed. This changes version metadata and documentation only; full Android validation above remains historical for the prior version. Hosted signed release execution is still required after committing and tagging the correction.

## Automatic signed draft releases — 24 October 2026

- Added Signed release workflow for version tags and manual runs against an existing tag. It checks tag/version equality and ancestry on main, audits/exports source, runs unit tests/lint/release assembly, checks the selected dependency inventory, signs/verifies the APK with Actions Secrets and creates a draft GitHub Release with source, inventories and checksums. Pull requests do not trigger this workflow.
- Added one-time local secret setup helper and environment-based signing helper. The setup creates a key only if the selected path does not exist; users must preserve/reuse their production signing identity. Signing keys are decoded only into a temporary directory and removed on exit; Firebase client configuration is removed at job completion. No production key was generated or uploaded during implementation.
- Twelve Python tests pass, including matching/mismatched versions, invalid tags and ambiguous version rejection. Shell syntax, workflow YAML parsing, public source audit and whitespace checks pass.
- Exercised the actual signing helper against the existing release APK with a temporary test key: signature/certificate and alignment verification passed; wrong password failed without producing an APK. Test key removed afterward. No application source changed, so full Android builds were not repeated.
- Automation and the refreshed ZIP are prepared locally. A hosted signed release still requires one-time repository secrets and a pushed tag. Draft publishing remains manual for device testing and corresponding dependency source review; existing published assets cannot be overwritten by this workflow. It does not add an app updater.

## Final dependency PR validation — 24 October 2026

- Synced the merged public workflow actions, Gradle wrapper 9.8.2 and UI Automator 2.4.0 into the development snapshot. Applied Google Services plugin 4.5.0 and test-only JSON 20260814 together, matching the final two dependency PRs.
- Reviewed JSON 20260814's exact published POM and upstream license: Public Domain. Updated the development-tool notice and synchronized the offline copy.
- Both Firebase-enabled and SDK-free builds pass debug/release APK assembly, instrumentation APK compilation, all 235 unit tests without failures/skips, and lint with 0 errors. Firebase lint has 40 warnings. Validation uses JDK 17 and the merged Gradle 9.8.2 wrapper.
- Firebase Google Services resource processing succeeded with the local client configuration; configuration contents remain private and excluded from the source ZIP. Live Analytics delivery and device playback were not retested.
- Both runtime inventories reproduce unchanged. Source audit, all eight exporter tests, workflow YAML parsing and whitespace checks pass. The GitHub checkout and source ZIP include the reviewed updates; no remote merge or push was performed.

## Compose BOM compatibility review — 24 October 2026

- Inspected hosted run [40093755354](https://github.com/lxexe/Raag-Music/actions/runs/40093755354), testing Compose BOM 2026.09.00. AAR metadata validation reports that its Compose 1.12.1 modules require compile SDK 37 and AGP 9.1+, while the project uses 35 and 9.0.1.
- Retained the tested Compose BOM 2024.10.01 and configured this dependency for manual upgrade review by excluding it from automatic Dependabot proposals. This defers the framework/toolchain migration; it does not make the incompatible PR build successfully. Existing PRs must be closed or migrated separately.
- No app dependencies or workflow checks changed for this correction. Source audit, eight exporter tests, YAML parsing, whitespace checks and source ZIP verification passed. The preceding full Android build remains applicable.

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
