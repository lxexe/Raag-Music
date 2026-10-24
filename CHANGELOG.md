# Changelog

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
