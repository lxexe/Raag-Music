# Architecture

## Modules and compatibility

`app` is the native Android application, with application ID and namespace `com.raagmusic.app`. `benchmarks` provides optional Macrobenchmark/profile-generation tooling, included only with `-PperformanceTools=true`, under `com.raagmusic.app.benchmarks`.

## Data and providers

`LibraryStore` and `FeatureStore` use atomic DataStore updates for the library, preferences, queue, feedback and local listening aggregates. `LibraryBackup` validates portable JSON before merging; document grants are excluded. `LocalAudioImporter` uses Android's Storage Access Framework.

`YoutubeMusicRepository` coordinates search, artists, collections, discovery, recommendations and extraction. NewPipeExtractor provides native extraction. Parsers retain stable provider IDs and credits. Metadata caches and snapshots bound retained data. Personalization comes from local listening/likes and available provider radios, not an authenticated account feed.

`LyricsRepository` uses LRCLIB and lyrics.ovh, preserving cancellation and rate-limit handling. Persisted playback references use stable IDs rather than expiring signed URLs.

## Playback and UI

`PlaybackService`, a MediaLibraryService, owns ExoPlayer and the media session. `PlayerConnection` exposes commands/state to `MusicViewModel`; the Activity does not own audio. The service coordinates focus, video surfaces, queues, crossfade, timers, equalizer and recovery.

Resolver/session/lease helpers and `QueuePreloader` prepare bounded current/upcoming sources. Rejections refresh sessions/formats under bounded recovery. `ProviderFailureGate` pauses audio/video resolution across songs on verification/rate limits. Manual retries do not clear that gate; expiry or a different validated connection does. Temporary transport/stream failures retain the existing two-retry budget. Safe failure details cross the media session through track-scoped extras; the UI offers retry countdowns and external YouTube handling that excludes Raag's own link handler. This cannot remove YouTube's network restrictions.

`ListeningSession` measures actual heard time for once-per-play completions (80%) and early skips (<30 seconds); failure, seek position and unheard tracks do not create negative preference signals. Optional completion fields migrate existing history without resetting it. Quick Picks acquires related-song/artist candidates from positive local evidence and re-ranks meaningful feedback changes on Default, retaining a larger candidate pool, recording-alias exclusions, artist diversity and supported discovery slots. Feedback metadata stays in app-private storage.

`MediaCache` separates streaming cache and saved downloads. `MusicDownloadService` owns user-requested background transfers.

Android Auto callbacks expose browsable library/search items. Widgets use RemoteViews, immutable PendingIntents and short-lived controllers. No cloud backend or Cast receiver is configured.

`RaagApp` coordinates Discover, Search, Library and Downloads plus player/settings overlays. StateFlows are observed with lifecycle awareness. Library Insights are local. Compose helpers support light/dark/AMOLED. `LegalInformation` reads bundled legal/privacy text on IO and displays it offline.

## Tests and assets

Unit tests cover codecs, parsers, ranking, domain behavior and recovery. Instrumentation covers storage, media/UI and provider-dependent probes. Synthetic URLs and records in tests are intentional fixtures. Optimized-build/device checks remain important for R8, reflection and desugaring.

Only the final Raag Music artwork, native icons and licensed Plus Jakarta Sans font are bundled. No demo catalog, music files or cover photography ship with the app.

## Optional basic usage reporting

`UsageReporting` owns a separate local reporting preference. Mutually exclusive `src/firebase` and `src/noFirebase` backends are selected with `-PfirebaseAnalytics=true`; the default contains no SDK. The FirebaseInitProvider is removed. New Firebase installations enable reporting by default; a saved opt-out is preserved. The backend initializes Firebase only while reporting is enabled and uses automatic events without any playback/library hooks. Turning reporting off disables collection and resets local SDK analytics data. See FIREBASE.md and PRIVACY.md.

Privacy settings use separate App usage and Listening tabs. Local listening and history controls do not change remote reporting.

## Update checks

`updates/UpdateRepository` serializes daily/manual requests, keeps a private release-note/ETag cache, compares stable semantic versions, and respects network/rate-limit backoff. Two network-constrained, persisted JobScheduler tasks target the next local morning (08:00) and evening (19:00). Each re-enqueues its next occurrence; clock/timezone changes reset scheduling. Repository window tokens prevent duplicate automatic checks in the same window, and Activity foreground entry catches up if due. Settings opens the same update dialog immediately. A compact banner remains available, a new version prompts until dismissed, and the background task can send one local notification per version if permission/channel settings allow it. Release bodies are text, with maintainer HTML comments hidden; no remote images or scripts execute. Downloads open only official repository APK/release URLs in the browser. There is no silent installer or new telemetry dependency.

Search All uses bounded, explicit category previews from `groupedSearchResults`. Dedicated filters retain full append order and provider continuation, with repeated cursor/empty page guards. Library search stays local and separate from provider song results. Category/query changes cancel old page jobs and clear cursor state, preventing cross-category result leakage.
