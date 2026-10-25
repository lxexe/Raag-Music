# Changelog

## 1.24.10 — playback recovery and personal Quick Picks

- Show clear playback failure cards with retry controls and an Open in YouTube action.
- Pause stream requests after connection verification or rate limits, with a retry countdown and recovery after switching networks.
- Keep bounded retries for temporary failures while preserving the queue and playback position.
- Improve Quick Picks using local likes, actual listening, completed plays, early skips and Not interested feedback.
- Leave room for relevant new music, avoid blocked upload aliases, and refresh recommendations when meaningful feedback changes.
- Keep completion and skip signals on the device; failed playback does not count as a disliked song.

## 1.24.9 — clearer search and update alerts

- Group All search results into small previews for songs, artists, albums, playlists, videos and your library.
- Open full category results with See all and keep loading available pages as you scroll.
- Refresh the update dialog, add a compact in-app update message and send update alerts when notifications are allowed.
- Improve background update detection and notification controls.
- Add bottom breathing room to Settings so its final actions remain fully visible.

## 1.24.8 — playback session recovery

- Refresh a rejected in-memory playback session once, matching recovery after an app restart.
- Clear rejected verification sessions and avoid additional extraction requests after an explicit bot-verification response.
- Explain connection verification errors more clearly. YouTube can still restrict a network independently of app session recovery.

## 1.24.7 — GitHub update checks

- Check for new published versions about once a day, with a catch-up check when the app opens.
- Add Check for updates in Settings and show release notes inside the app.
- Open the official signed APK download in your browser; installation stays under your control.
- Cache update details for offline viewing and avoid repeated prompts after choosing Later.

## 1.24.6 — automated signed releases

- Prepare matching Android version metadata for the signed release workflow (version code 37).
- Include reviewed dependency updates, separate analytics settings, and automatic signed draft release preparation.
- Skip the existing v1.24.5 tag, which pointed to version 1.24.4 and failed validation before building.

## 1.24.4 — separate analytics settings

- Enable basic usage reporting by default on new Firebase installations while preserving existing opt-outs.
- Separate App usage reporting and on-device Listening analytics into dedicated privacy tabs.
- Explain shared app/device metadata and explicitly exclude names, songs, searches, playlists and listening history from analytics.
- Keep SDK-free builds free of remote reporting.


## 1.24.3 — optional usage reporting

- Added optional Firebase Analytics builds and an explicit first-launch reporting choice.
- Added an independent basic-usage setting; local listening Insights remain separate.
- Disabled automatic screen/ad-ID collection and advertising consent. No custom music/library events are sent.
- Standard open-source builds exclude Firebase and need no configuration.
- Updated privacy, dependency notices and Firebase setup guidance.

## 1.24.2 — final Android identity

- Finalized application ID and source namespace as `com.raagmusic.app` before public distribution.
- Rebranded application/UI/widget classes, resources, internal labels and baseline-profile descriptors.
- Updated tests, benchmarks, source-audit paths and Firebase/release guidance for the final identity.
- This installs separately from older private test builds; their data is not automatically migrated.

## 1.24.1 — open-source preparation

- Final Raag Music name and selected coral-and-cream note icon.
- Removed unused demo photos, abandoned branding, local development archives and unused cloud/cast scaffolding.
- Added GPL-3.0-or-later licensing, dependency/font notices and an offline About & Licenses reader.
- Added contributor, privacy/security and GitHub publishing/release guidance.
- Added source audit/export tooling, GitHub templates, stronger ignore rules and Gradle checksum validation.
- Preserved application ID and saved-library/preferences compatibility.

The public snapshot includes discovery, native background playback, lyrics, supported downloads, playlists, local import, Library Insights, themes, widgets and Android Auto browsing. Provider availability remains network-dependent.
