# Optional basic usage reporting

Standard builds exclude Firebase entirely. The official build can include Google Analytics for Firebase to report first launches, sessions, engagement and app-version adoption, when reporting is enabled. This is separate from local listening Insights. Remote Config, push notifications and an updater are not included.

## Configure and build

1. Register Android package **`com.raagmusic.app`** in your Firebase project and enable/link Google Analytics under Project settings → Integrations.
2. Download `google-services.json` to `app/google-services.json`. This is a client configuration, not an Admin key. It is ignored by Git and excluded from public source exports. Never add service-account credentials to the Android app.
3. Build explicitly with reporting support:

```sh
./gradlew -PfirebaseAnalytics=true :app:assembleDebug
./gradlew -PfirebaseAnalytics=true :app:assemblePreview
./gradlew -PfirebaseAnalytics=true :app:assembleRelease
```

Release remains unsigned; use RELEASING.md for production signing. Without the property, the SDK and config-processing plugin are omitted even when the file exists. A fresh public-source checkout therefore needs no Firebase file. The build uses Google Services plugin 4.4.4 and Firebase BoM 34.4.0; versions are pinned, not dynamically resolved.

## Reporting controls and reports

Basic usage statistics are enabled by default on new Firebase installations. Existing saved opt-outs are preserved. Users can turn reporting off under **Settings → Basic usage statistics → App usage**. The separate **Listening** tab contains only local listening statistics and history controls. The App usage tab clearly explains both what is shared and what stays private. There is no first-launch consent dialog.

The auto-initialization provider remains removed so the saved preference is applied before manual Firebase initialization. Turning reporting off disables collection and resets local SDK analytics data; it does not remove already-uploaded data from Google.

Firebase uses installation/app-instance identifiers and device/app information. Do not describe the feature as completely anonymous. The app sends no custom events, user ID, name, song/search/library data or listening history. Ad consent, advertising-ID collection/permissions and automatic screen tracking are disabled. Automatic first-open reporting measures first use, not every download, and only reporting users appear.

View the linked Google Analytics property/Firebase Analytics dashboard for active/new users and app versions. Normal reports are delayed. DebugView can check a development build:

```sh
adb shell setprop debug.firebase.analytics.app com.raagmusic.app
```

Launch the Firebase-enabled build with reporting enabled on the test device, and inspect Analytics → DebugView. Debug-mode events are for validation, not production usage estimates. Disable afterward:

```sh
adb shell setprop debug.firebase.analytics.app .none.
```

Review Analytics retention and data-sharing settings and limit project access. Keep the published privacy notice consistent with your actual configuration. No public user database or Firestore rules are needed for this feature.

## Validation and dependency review

```sh
./gradlew -PfirebaseAnalytics=true :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:assembleDebugAndroidTest
./gradlew -PfirebaseAnalytics=true -I scripts/runtime_inventory.init.gradle :app:writeRuntimeInventory
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.raagmusic.app.UsageReportingTest
```

The five reporting-preference tests use isolated preferences and a fake backend. Run them against the default SDK-free build so application startup also has no real Firebase backend. They cover default-on behavior, preserved opt-outs, explicit choices, withdrawal and SDK-free behavior. Verify builds in both modes. Public CI uses the default mode without project configuration. Exact Firebase-enabled modules are recorded in LICENSES/runtime-dependencies-firebase.json. Review Google SDK terms and preserve notices before distributing official binaries; the standard app source license does not relicense Google binaries.

References: [Android setup](https://firebase.google.com/docs/android/setup), [collection controls](https://firebase.google.com/docs/analytics/android/configure-data-collection), [automatic events](https://support.google.com/analytics/answer/9234069), [Firebase privacy](https://firebase.google.com/support/privacy).
