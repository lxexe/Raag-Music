# Raag Music privacy

This document describes version 1.24.7. Update it and the bundled copy when behavior changes.

## On-device data

Raag Music stores favorites, playlists, track metadata, queue position, settings, optional profile name, searches, listening history and statistics in app-private storage. Listening Insights are local aggregates, not remote telemetry. Listening analytics and recently played history can be disabled separately in Settings; history can be cleared there.

Artwork, lyrics, discovery metadata and stream data may be cached. Offline downloads stay on the device until removed or the app is uninstalled. Local audio import uses Android's document picker and URI grants. Android automatic app backup is disabled.

## External requests

Searches, public video/channel/playlist IDs, track metadata and playback requests go to YouTube/YouTube Music and related delivery servers. Lyric requests send title/artist and, where needed, album/duration to LRCLIB or lyrics.ovh. Artwork loads from the host returned by the provider.

These services receive normal network information such as an IP address and request headers and apply their own policies. Raag Music does not control their logging. Never post signed playback URLs in public reports.

Country selection can use network/SIM/device country or a manual setting; GPS/location permission is not requested. Voice search launches an installed speech-recognition activity, whose provider may process audio under its own terms.

## App update checks

The app requests the latest published stable release from GitHub about once a day when online, with a catch-up check when the app opens. Settings → Check for updates requests a manual check. Android controls background timing; no exact alarm or new runtime permission prompt is used. A persisted system job survives restarts using the boot-completed permission.

Requests go to `api.github.com/repos/lxexe/Raag-Music/releases/latest` without a GitHub account, token or installation identifier. GitHub receives normal network information such as your IP address and a generic update-checker user agent. Music, profile, library and listening information are not included. This request is independent of the Firebase reporting switch. The last check time, cached release notes, cache identifier and dismissed version stay in app-private storage. Notes are displayed as text and do not load remote images or run HTML/scripts.

An available update is shown inside the app; no push notification service is involved. Download opens the official GitHub APK link (or release page if no APK is attached) in your browser. Your browser handles the download, and Android requires your confirmation to install. The app does not silently download or install updates.

## Accounts, analytics and sharing

The standard open-source build contains no Firebase SDK. Official builds can optionally include Google Analytics for Firebase. In those builds basic usage reporting is enabled by default on new installations. An existing saved opt-out is preserved across updates. Settings → Basic usage statistics → App usage → Share basic usage statistics turns reporting off or on independently of local Listening analytics. Firebase initialization follows this saved setting; the SDK auto-initialization provider remains removed.

When enabled, Firebase's automatic events measure first launches, sessions, engagement, app updates and app-version adoption. The SDK also processes an app-instance/installation identifier and standard app/device information, including OS/model, language and approximate geographic information derived by the service. Counts describe reporting app instances, not verified individual people or every APK download; reinstalls and multiple devices can affect counts. This is pseudonymous reporting, not identifier-free anonymous collection.

Raag Music sends no custom analytics events, user ID, profile name, songs, artist names, searches, playlists, library contents, listening records, stream URLs or audio. Advertising-ID permissions/collection, ad storage, ad personalization and automatic screen tracking are disabled. The Firebase build includes Google's Analytics measurement SDK under Google's terms. See https://firebase.google.com/support/privacy and https://policies.google.com/privacy.

Turning reporting off disables further collection and calls the SDK's local analytics reset. This does not delete data already processed by Google. Clearing app storage/uninstalling removes the local preference and app data; an existing server record can remain under the Analytics project's retention settings. The maintainer controls that project's access, retention and data-sharing settings.

No account is required; Raag Music does not import a signed-in YouTube feed. Cloud sync, crash-reporting uploads and push notifications are not implemented. GitHub update checks and user-initiated downloads are described above; silent installation is not implemented.

Backup exports user-selected JSON with portable favorites, playlists and saved track metadata. It excludes local document grants, audio files and listening history. Share cards/links leave through the Android sharing flow selected by the user.

## Permissions

- Internet/network state: discovery, artwork, lyrics, playback and downloads.
- Foreground media playback, wake lock and notifications: background listening and controls.
- Foreground data sync: user-requested downloads.
- Modify audio settings: equalizer/audio effects.

The manifest does not request contacts, GPS location, broad media-storage access or direct microphone-recording permission.

## Deletion and questions

Use the app's clear/removal controls, clear app storage in Android Settings, or uninstall. Manually exported backups and shared files remain where you saved/sent them. Ask app-specific privacy questions in the public issue tracker without personal data. Security concerns should follow SECURITY.md.
