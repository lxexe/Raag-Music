# Architecture

## Modules and compatibility

`app` is the native Android application, with application ID and namespace `com.raagmusic.app`. `benchmarks` provides optional Macrobenchmark/profile-generation tooling, included only with `-PperformanceTools=true`, under `com.raagmusic.app.benchmarks`.

## Data and providers

`LibraryStore` and `FeatureStore` use atomic DataStore updates for the library, preferences, queue, feedback and local listening aggregates. `LibraryBackup` validates portable JSON before merging; document grants are excluded. `LocalAudioImporter` uses Android's Storage Access Framework.

`YoutubeMusicRepository` coordinates search, artists, collections, discovery, recommendations and extraction. NewPipeExtractor provides native extraction. Parsers retain stable provider IDs and credits. Metadata caches and snapshots bound retained data. Personalization comes from local listening/likes and available provider radios, not an authenticated account feed.

`LyricsRepository` uses LRCLIB and lyrics.ovh, preserving cancellation and rate-limit handling. Persisted playback references use stable IDs rather than expiring signed URLs.

## Playback and UI

`PlaybackService`, a MediaLibraryService, owns ExoPlayer and the media session. `PlayerConnection` exposes commands/state to `MusicViewModel`; the Activity does not own audio. The service coordinates focus, video surfaces, queues, crossfade, timers, equalizer and recovery.

Resolver/session/lease helpers and `QueuePreloader` prepare bounded current/upcoming sources. Rejections refresh sessions/formats under bounded recovery. `MediaCache` separates streaming cache and saved downloads. `MusicDownloadService` owns user-requested background transfers.

Android Auto callbacks expose browsable library/search items. Widgets use RemoteViews, immutable PendingIntents and short-lived controllers. No cloud backend or Cast receiver is configured.

`RaagApp` coordinates Discover, Search, Library and Downloads plus player/settings overlays. StateFlows are observed with lifecycle awareness. Library Insights are local. Compose helpers support light/dark/AMOLED. `LegalInformation` reads bundled legal/privacy text on IO and displays it offline.

## Tests and assets

Unit tests cover codecs, parsers, ranking, domain behavior and recovery. Instrumentation covers storage, media/UI and provider-dependent probes. Synthetic URLs and records in tests are intentional fixtures. Optimized-build/device checks remain important for R8, reflection and desugaring.

Only the final Raag Music artwork, native icons and licensed Plus Jakarta Sans font are bundled. No demo catalog, music files or cover photography ship with the app.

## Optional basic usage reporting

`UsageReporting` owns a separate local reporting preference. Mutually exclusive `src/firebase` and `src/noFirebase` backends are selected with `-PfirebaseAnalytics=true`; the default contains no SDK. The FirebaseInitProvider is removed. New Firebase installations enable reporting by default; a saved opt-out is preserved. The backend initializes Firebase only while reporting is enabled and uses automatic events without any playback/library hooks. Turning reporting off disables collection and resets local SDK analytics data. See FIREBASE.md and PRIVACY.md.

Privacy settings use separate App usage and Listening tabs. Local listening and history controls do not change remote reporting.
