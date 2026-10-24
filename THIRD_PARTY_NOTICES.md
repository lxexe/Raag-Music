# Third-party notices

Raag Music's original source is GPL-3.0-or-later. Dependencies, fonts and build tools retain their own licenses and copyright notices. This document covers the release dependency families; exact resolved modules, including platform metadata, are in [LICENSES/runtime-dependencies.json](LICENSES/runtime-dependencies.json). A module inventory is not a dependency source archive.

## Runtime components

| Component | Version / revision | License | Upstream source |
| --- | --- | --- | --- |
| AndroidX, Compose, Material 3, Activity, Lifecycle, DataStore, Media3, ProfileInstaller and their AndroidX dependencies | Exact modules in inventory | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| Kotlin standard library and kotlinx.coroutines | 2.2.10 / 1.9.0 | Apache-2.0 | https://github.com/JetBrains/kotlin and https://github.com/Kotlin/kotlinx.coroutines |
| Coil | 2.7.0 | Apache-2.0 | https://github.com/coil-kt/coil/tree/2.7.0 |
| OkHttp / Okio | 4.12.0 / 3.9.0 | Apache-2.0 | https://github.com/square/okhttp/tree/parent-4.12.0 and https://github.com/square/okio/tree/parent-3.9.0 |
| NewPipeExtractor | v0.26.3 | GPL-3.0-or-later | https://github.com/TeamNewPipe/NewPipeExtractor/tree/v0.26.3 |
| nanojson | e9d656ddb49a412a5a0a5d5ef20ca7ef09549996 | Apache-2.0 | https://github.com/TeamNewPipe/nanojson/tree/e9d656ddb49a412a5a0a5d5ef20ca7ef09549996 |
| jsoup | 1.22.2 | MIT | https://github.com/jhy/jsoup/tree/jsoup-1.22.2 |
| Rhino / Rhino engine | 1.8.1 | MPL-2.0 | https://github.com/mozilla/rhino/tree/Rhino1_8_1_Release |
| Protocol Buffers Java Lite | 4.35.0 | BSD-3-Clause | https://github.com/protocolbuffers/protobuf/tree/v35.0 |
| Guava / failureaccess / empty listenablefuture compatibility artifact | 33.0.0-android / 1.0.2 / 9999.0-empty-to-avoid-conflict-with-guava | Apache-2.0 | https://github.com/google/guava |
| JSR-305 annotations | 3.0.2 | Apache-2.0 in artifact metadata; concurrent annotation source notices also specify CC-BY-2.5 | https://repo.maven.apache.org/maven2/com/google/code/findbugs/jsr305/3.0.2/ |
| JetBrains annotations | 23.0.0 | Apache-2.0 | https://github.com/JetBrains/java-annotations |
| Accompanist drawable painter | 0.32.0 | Apache-2.0 | https://github.com/google/accompanist/tree/v0.32.0 |
| Core library desugaring / desugar_jdk_libs_nio | 2.1.5 | GPL-2.0-with-classpath-exception and Apache-2.0; upstream files retain their individual notices | https://github.com/google/desugar_jdk_libs/tree/73170c345e6a762fc6a1f0301bb15218850023ef |

Copyright holders include The Android Open Source Project, JetBrains s.r.o. and Kotlin contributors, Coil contributors, Square, Inc., Team NewPipe and contributors, the nanojson Authors, Jonathan Hedley, Mozilla contributors, Google LLC and contributors, and Brian Goetz. Consult the included license texts and the exact upstream sources for file-specific notices.

The desugaring tool transforms selected OpenJDK APIs into the Android application. Preserve the upstream OpenJDK license, Classpath Exception and file notices when distributing builds; copies are in LICENSES/. The Classpath Exception applies as stated by upstream.

## Optional Firebase build

Only builds requested with `-PfirebaseAnalytics=true` include Firebase Analytics (BoM 34.4.0), Firebase support libraries and Google Play services measurement dependencies. The default open-source build excludes them. Exact resolved coordinates are recorded separately in LICENSES/runtime-dependencies-firebase.json. Google's Analytics/Play services binaries retain their own terms and are not relicensed under the project's GPL. Review [Firebase terms](https://firebase.google.com/terms/), [Google APIs terms](https://developers.google.com/terms/) and the exact artifact notices before distributing such binaries. Firebase's open-source support components retain their Apache-2.0 notices. The Google Services Gradle plugin is development tooling. Client project configuration is excluded from the public source export.

## Bundled font and artwork

**Plus Jakarta Sans**: Copyright 2020 The Plus Jakarta Sans Project Authors, https://github.com/tokotype/PlusJakartaSans. Licensed under SIL Open Font License 1.1; the complete copyright and license are bundled in `app/src/main/assets/fonts/PlusJakartaSans-OFL.txt`. The five bundled font files are not relicensed under the app's GPL.

The final Raag Music note icon and editable branding assets are project assets described in NOTICE and docs/BRANDING.md. Unused concept images and demonstration photos are not included. Remote album artwork, lyrics, recordings and provider trademarks remain their owners' property and are not part of the source license.

## Development tools and test dependencies

The Gradle wrapper and Android build tools use their upstream licenses; the wrapper is Apache-2.0. JUnit 4.13.2 is EPL-1.0, Hamcrest 1.3 uses BSD-3-Clause, and the JVM-test-only `org.json:json:20240303` uses the JSON License. AndroidX test libraries are Apache-2.0. These are fetched as development dependencies and are not bundled by this repository's source exporter or the production app. Their source and license records are available through their Maven coordinates and upstream projects. Keep them test-scoped.

## Included texts and distribution

LICENSE contains GPL version 3. LICENSES/ includes Apache-2.0, MPL-2.0, jsoup's MIT notice, Protocol Buffers' BSD notice, nanojson's source notice, JSR-305's concurrent annotation notice and CC-BY-2.5, plus desugaring license/exception notices. The app's **Settings → About Raag Music** makes the runtime notices and licenses available offline.

When changing dependencies, regenerate the inventory using `scripts/runtime_inventory.init.gradle`, review exact upstream licenses and notices, update this document, then run `python3 scripts/sync_legal.py`. Review license/source obligations before distributing binaries; see docs/RELEASING.md. This source snapshot does not include third-party source archives or promise that a link alone satisfies every distribution obligation.
