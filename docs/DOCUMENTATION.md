# YouTube Companion – Feature compendium

> [!WARNING]
> **Use at your own risk.** This project is provided without warranty. I expressly do not recommend playing videos in a car while driving. **Only watch videos on the car display when the vehicle is safely parked.** Operating the app must not distract from traffic; follow applicable traffic rules.
>
> **I accept no responsibility for the use of this app or any resulting damage, accidents or other consequences.** The built-in parking check does not replace your own responsibility or your duty to operate the vehicle safely.

**App:** YouTube Companion (`de.uni.youtubeaa`) · **Version:** `1.0.0` (1) · **minSdk:** 28 · **target/compileSdk:** 37

**Languages:** **English** · [Deutsch](DOCUMENTATION.de.md)

[Back to README](../README.md)

This handbook describes the implemented phone app, Android Auto media browser and video projection. Labels and defaults are based on settings resources and code. English labels below translate the current German UI; they do not imply an English app localization. This is a source review, not an additional device or playback test.

| Table column | Meaning |
| --- | --- |
| Options / values | Choices available in the interface |
| Default | Value on a fresh installation; “—” indicates an action |
| Interface | Phone, AA media browser or custom AA projection |
| Notes | Availability, shared storage or limitations |

---

## Contents

1. [Overview](#1-overview)
2. [Settings and persistence](#2-settings-and-persistence)
3. [Feeds and library](#3-feeds-and-library)
4. [Account](#4-account)
5. [Playback defaults](#5-playback-defaults)
6. [Audio, appearance and data](#6-audio-appearance-and-data)
7. [Controls during playback](#7-controls-during-playback)
8. [Phone and car together](#8-phone-and-car-together)
9. [Quality and performance](#9-quality-and-performance)
10. [Setup and DHU](#10-setup-and-dhu)
11. [Development and build](#11-development-and-build)
12. [Data storage and diagnostics](#12-data-storage-and-diagnostics)
13. [Troubleshooting and limits](#13-troubleshooting-and-limits)

---

## 1. Overview

One APK contains the phone interface and Android Auto integration. Projection runs from the phone; this is not a standalone vehicle app installed on Android Automotive OS.

| Interface | Features | Entry point |
| --- | --- | --- |
| Phone | Feeds, search, account, playlist, settings, native video/audio player and mini-player | “YouTube Companion” app icon |
| AA media browser | Host-rendered media lists and audio playback with MediaSession controls | Media app in Android Auto |
| AA projection | Custom browser, lists/cards, video/audio player and car settings | “YouTube Video”, when offered by the host |

Shared components cover feeds, stream resolution, account session, local playlist, history, queue and playback controls. The phone interface uses Android Views, RecyclerView and preference screens; this project does not use a Compose UI.

## 2. Settings and persistence

Phone settings are grouped into **Account**, **Playback defaults**, **Audio defaults**, **Appearance**, **Data** and **Diagnostics**. Projection offers a compact selection of the same shared settings; tapping a choice row cycles to its next value.

Changes are persisted through `SettingsRepository` in shared app preferences. Both interfaces run within the same installation, so these settings do not require network synchronization.

- Video quality and card layout are stored separately for phone and car.
- “Prefer starting phone playback in audio mode” affects only the phone.
- The default playback mode is shared; the phone audio preference can override it on the phone.
- Speed, audio language, audio quality, autoplay, history and cache settings are shared.
- Lip sync applies exclusively to the native head-unit video player.

## 3. Feeds and library

| Section | Content | Account required? | Notes |
| --- | --- | --- | --- |
| Home | Personal recommendations or public videos | For personal recommendations | Falls back to public content when personal content is unavailable. |
| Subscriptions | Subscription videos, channel overview and feed-provided filters | Yes | Supports additional pages; videos with a recognized date are sorted by recency. |
| Shorts | Shorts from loaded subscription feed pages | Yes | Not an independent, unlimited Shorts recommendation feed. |
| Popular | Public popular content | No | Uses a “trending” search when the list is empty. |
| Search | Public video search | No | Uses the anonymous browsing path. |
| Channel | Public videos from a selected channel | No | The personal subscribed-channel overview requires sign-in. |
| History | Locally recorded playback | No | Does not retrieve YouTube account watch history. |
| Playlist / Saved | Your locally stored app playlist | No | Shared by phone, media browser and projection. |

The app playlist supports adding and removing videos and playing the list. On the phone, it can also be sent to car projection or cleared. Saving an existing video does not create a duplicate entry.

Feed pages and metadata are cached. Filters, additional pages, dates and thumbnails depend on YouTube responses. The native Android Auto media browser is rendered by the host and does not reproduce every feature of the custom browsing interfaces.

## 4. Account

| Setting | Options / values | Default | Notes |
| --- | --- | --- | --- |
| YouTube account | Link / account status / sign out | Not linked | TV device-code sign-in on the phone. |

Open **Settings → YouTube account** (“Einstellungen → YouTube-Konto”), start sign-in, visit the displayed activation page and confirm the code. Phone and car use the same session once linked.

The authentication client attempts to discover YouTube TV client credentials at runtime. `YOUTUBE_TV_CLIENT_ID` and `YOUTUBE_TV_CLIENT_SECRET` can be supplied as Gradle properties as a build-time fallback. Both values must be present. They are compiled into `BuildConfig`, so they are not secret server-side credentials. Normal guest use does not require a YouTube Data API key.

TV OAuth tokens are stored using `EncryptedSharedPreferences`. Personal feeds use InnerTube; public search and stream resolution use the anonymous integration and NewPipeExtractor respectively. Sign-in does not automatically remove restrictions on individual videos.

## 5. Playback defaults

| Phone setting | Options / values | Default | Notes |
| --- | --- | --- | --- |
| Default tab | Home · Subscriptions · Search | Home | Car settings cycle the same key through Home · Subscriptions · Popular · History · Saved. The choice lists differ. |
| Autoplay | On / Off | Off | Automatically starts the next video in the queue. |
| Prefer starting phone playback in audio mode | On / Off | Off | Phone only; switching to video remains available in the player. |
| Phone video quality | Auto · 2160p · 1440p · 1080p · 720p · 480p · 360p · 240p · 144p | Auto | Availability depends on streams and decoders. |
| Car video resolution | Auto · 1080p · 720p · 480p · 360p · 240p · 144p | Auto | Separate head-unit preference; Auto accounts for network and load. |
| Default speed | 0.25× · 0.5× · 0.75× · 1× · 1.25× · 1.5× · 1.75× · 2× | 1× | Shared default. |
| Default playback mode | Video · Audio only | Video | Shared preference; may be overridden by the additional phone audio preference. |
| Video backend (AA) | ExoPlayer · WebView | ExoPlayer | WebView is an explicitly selected compatibility option. |
| AA video surface | SurfaceView · TextureView | SurfaceView | Native AA video player; TextureView offers an alternative for host compatibility issues. |
| Parking check before video | On / Off | On | Uses the parking-brake sensor; unknown status produces a safety notice. |

### Parking check

With the check enabled and a reported non-parked state, video is blocked. When the host does not provide a reliable sensor status, a notice requiring confirmation appears instead. Audio mode is not subject to this video gate. The sensor status is not a universal detector of all driving situations.

### Backend selection

ExoPlayer is the native default. The older stored backend value `auto` is normalized to `exoplayer`. Stream errors do not silently switch to the YouTube WebView. The native quality, audio and synchronization controls described below do not automatically apply to the embedded web player.

## 6. Audio, appearance and data

### Audio defaults

| Setting | Options / values | Default | Notes |
| --- | --- | --- | --- |
| Default audio language | Auto (original track) · Device language · Original track · German · English | Original track | Stream selection preference, not a guarantee of an additional audio track. |
| Default audio quality | Auto (highest) · High · Medium · Low | Auto | Relative levels among available streams, not fixed bitrates. |

### Appearance

| Setting | Options / values | Default | Notes |
| --- | --- | --- | --- |
| Companion app as cards | On / Off | Off | Off shows a compact list; On shows responsive cards. |
| Android Auto as cards | On / Off | Off | Custom projection, with one to four columns depending on space. |
| Video thumbnail resolution | Auto (best available) · Maximum · High · Medium · Low | Auto | Controls thumbnails, not playback resolution. |

### Data and diagnostics

| Setting | Options / values | Default | Notes |
| --- | --- | --- | --- |
| Cache size | 50 · 100 · 500 · 1000 MB | 100 MB | Thumbnail disk-cache budget, not video download storage. |
| Save history | On / Off | On | Controls recording of local playback; turning it off is not a deletion action. |
| Share head-unit logs | Action | — | Creates a diagnostic ZIP and opens the Android share sheet. |
| Delete head-unit logs | Action | — | Removes stored diagnostic logs. |

## 7. Controls during playback

| Feature | Phone | Native AA projection |
| --- | --- | --- |
| Play / pause, position and queue navigation | Player and mini-player | Player and MediaSession integration |
| Switch video / audio | Yes, retaining position | Yes, with video subject to the parking check |
| Playback speed | Player settings | Player settings |
| Video quality | Phone preference | Car preference |
| Audio language / quality | Player settings | Player settings |
| Full screen | Phone video player | Custom projection interface |
| Add to playlist | Player and browsing actions | Shared playlist through browsing |
| Play on car display | Handoff action | Receives playback |
| Lip sync | — | Native video playback only |

### Background playback and mini-player

The phone player can hand video playback over to background audio when leaving the screen or turning the display off. The mini-player shows the current title and provides a way back to the player. The media notification, lock screen and supported media buttons control active playback through the MediaSession.

Switching between audio and video retains the current position. Loading another stream source may introduce a pause. Shared playback state coordinates the active player so controls reach the current playback owner.

### Lip sync in the car

| Setting | Values | Default | Effect |
| --- | --- | --- | --- |
| Audio/video offset | −1,000 to +1,000 ms in 25 ms steps | 0 ms | Positive means audio later relative to the picture; negative means earlier. |
| Reset | Action | — | Restores a 0 ms offset. |

The value is saved and applies only in head-unit video mode. The correction adjusts picture output relative to the audio clock; playback position, history and handoffs retain the same media timeline.

## 8. Phone and car together

1. Find a video or open the playlist on the phone.
2. Select **Play on car display** (“Auf Auto-Display abspielen”) or **Playlist on car** (“Playlist auf Auto”).
3. The app attempts to open projection and hand over the title with its playback context.
4. Confirm the parking notice on the car display if required.

Handoff requires an available Android Auto projection session. A queued request does not confirm that the host has actually opened the interface. If the connection is unavailable or the parking notice has not been confirmed, the interface reports that car-display playback is unavailable.

This is a handoff to Android Auto on the same phone, not a Chromecast or TV pairing protocol. Account and local data are shared inside the app; another device installation does not receive them automatically.

## 9. Quality and performance

The car player accounts for projection frame rate, playback speed, thermal status and playback load. Stream selection operates within this budget and the formats available from the source.

| Auto quality situation | Resolution budget |
| --- | --- |
| Normal operation without reported heavy load | Up to 720p |
| Network reported as metered or severe thermal load | Up to 480p |
| Critical thermal load or equivalent load assessment | Up to 360p |

An explicitly selected resolution is not replaced by these Auto steps. Frame rate still remains subject to projection capacity and playback speed. Faster playback increases decoder throughput requirements and may limit the suitable source frame rate.

SurfaceView is the native car player's default output. TextureView is a saved alternative for problematic hosts. Hardware codec checks, bounded image sizes and cached thumbnails help avoid unnecessary decoder, memory and copy load. These measures do not guarantee smooth playback on every head unit.

## 10. Setup and DHU

### Phone

1. Build and install the APK as described below.
2. Open the app and load public content first.
3. Link a YouTube account for subscriptions and personal recommendations.
4. Check video and audio playback and configure the desired defaults.

### Android Auto and Desktop Head Unit

The intended sideload test requires Android Auto and Google Play Services on the phone. Install the DHU component through the Android SDK Manager:

```bash
sdkmanager "extras;google;auto"
```

Enable Android Auto developer settings by repeatedly tapping the version display, then enable **Unknown sources**. For USB/ADB DHU testing, start the head-unit server from the developer menu, connect the phone over USB and allow USB debugging. Menu layout depends on the installed Android Auto version.

```bash
# Select a target when multiple devices are connected
export ANDROID_SERIAL=<device-serial>

# Project helper for port forwarding and DHU startup
./scripts/start-dhu.sh

# Optional alternative DHU configuration
./scripts/start-dhu.sh config/default_720p.ini
```

The helper uses the Android SDK path and sets up forwarding for port 5277. DHU sensor checks include `parking brake on` and `parking brake off`. A DHU test does not automatically validate sensors, latency or video output on a physical vehicle head unit.

The helper also sets `LD_LIBRARY_PATH` for the required Linux libraries. Alternatively, start the DHU manually:

```bash
adb forward tcp:5277 tcp:5277
cd "$ANDROID_SDK_ROOT/extras/google/auto"
export LD_LIBRARY_PATH="$ANDROID_SDK_ROOT/emulator/lib64:/usr/lib64/rocm/llvm/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
./desktop-head-unit -c config/default_sensors.ini
```

## 11. Development and build

### Modules and main components

| Path / component | Responsibility |
| --- | --- |
| `app/` | APK, phone activities, account sign-in, settings and phone player |
| `car/` | AA media browser and projection plus shared data and playback logic |
| `aauto/` | Gradle wrapper module for `car/libs/aauto.aar` |
| `hybrid/BrowseFacade` | Shared entry point for personal and public feeds |
| `StreamResolver` / `PlaybackSources` | Stream resolution and media sources |
| `PlaybackEngine` / `VideoSessionBridge` | Active playback, ownership and handoffs |
| `YouTubeMediaBrowserService` / `YouTubeMediaSession` | Media browser, background audio and external media controls |
| `CarVideoPolicy` / `CarPlaybackQos` | Projection video budget and load adaptation |
| `SettingsRepository` | Shared settings |
| `scripts/` | DHU helper and supplementary audit scripts |

### Build configuration

| Component | Configured in the repository |
| --- | --- |
| Android Gradle Plugin | 9.4.0 |
| Gradle Wrapper | 9.8.0 |
| Java source/target compatibility | 17 |
| Android SDK | min 28; compile / target 37 |
| Media3 | 1.11.1 |
| NewPipeExtractor | Version pinned in `car/build.gradle.kts`, JitPack |

Install a JDK and Android SDK, including SDK Platform 37 and the Build-Tools required by the build. Set the SDK path through `sdk.dir=…` in `local.properties` or the SDK environment. Dependencies are resolved from Google Maven, Maven Central and JitPack.

```bash
./gradlew :app:assembleDebug
./gradlew :car:testDebugUnitTest
./gradlew :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Tests under `car/src/test/` cover feed parsing, paging, list merging, stream selection, video budgets, playback ownership, session updates, history and lip sync. They do not replace device checks for sign-in, live streams, Bluetooth controls or projection.

### Release and signing

`app/build.gradle.kts` reads a local `keystore.properties` with these keys:

```properties
storeFile=keystore/release.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=youtubeaa
keyPassword=YOUR_KEY_PASSWORD
```

These are placeholders. An installable release APK requires a matching keystore, alias and passwords. Without configured signing, the direct Gradle release build produces an unsigned APK. R8 release minification runs in the app module.

| Command | Output / behavior |
| --- | --- |
| `./gradlew :app:assembleDebug` | `app/build/outputs/apk/debug/app-debug.apk`, signed with the debug key |
| `./gradlew :app:assembleRelease` | APK under `app/build/outputs/apk/release/`; filename depends on signing |
| `./build.sh debug` | Clean build and copy to `dist/youtube-aa-companion-debug-1.0.0.apk` |
| `./build.sh release` | Requires `keystore.properties`; copy to `dist/youtube-aa-companion-release-1.0.0.apk` |
| `./build.sh playstore` | Creates a signed AAB in `dist/`; does not imply Store eligibility |
| `./build.sh all` | Debug, release and AAB in sequence; default without an argument |
| `./build.sh gen-keystore` | Interactive key generation; aborts if the target keystore already exists |
| `./build.sh update-newpipe --check` | Checks the latest stable GitHub release without changing files |
| `./build.sh update-newpipe` | Checks the JitPack POM and updates the NewPipeExtractor pin; requires Python 3, no automatic build |

The bundled OEM projection is intended for sideload use according to the [SDK notes](../car/libs/README.md). The AAB build mode's name does not change this restriction.

## 12. Data storage and diagnostics

| Data | Storage / behavior |
| --- | --- |
| YouTube TV tokens | Encrypted app preferences |
| Settings | Shared local app preferences |
| App playlist | Local app preferences; no YouTube playlist synchronization |
| History and last session | Local stores for titles, position and resume context |
| Thumbnails | Memory and disk cache; adjustable disk budget |
| Feed lists | Shared browsing caches, not a complete offline archive |
| Diagnostics | Local head-unit events and crash stack traces; export through the share sheet |

Android app backups are disabled in the manifest. There is no dedicated full backup/restore interface. Clearing app data also removes local settings, account session, playlist and history.

**Settings → Diagnostics → Share head-unit logs** (“Einstellungen → Diagnose → Head-Unit-Logs teilen”) prepares a ZIP. A reproducible issue report should also include the device, Android/Android Auto versions, connection type, backend, resolution and exact steps. Deleting logs removes diagnostic records, not playlist or playback history.

## 13. Troubleshooting and limits

| Symptom | What to check |
| --- | --- |
| App missing from AA media apps | Check installation and Android Auto sideload settings, then reconnect. |
| “YouTube Video” missing but audio available | The host may not support or accept unofficial OEM projection. Audio and projection have separate entry points. |
| Subscriptions or Shorts stay empty | Check account status and relink; Shorts come from the subscription feed. |
| Home looks impersonal | The app uses public content when a personal feed is unavailable. |
| TV sign-in cannot start | Check network access and dynamic client discovery; configure both build fallback properties if needed. |
| A stream will not open | Test other public videos and capture diagnostics; feed access and stream resolution are separate operations. YouTube changes may affect the extractor. |
| Car video is blocked | Check parking status and the notice on the head unit; use audio mode for audio playback. |
| Video stutters or phone load is high | Check Auto quality, a lower resolution and 1× speed; SurfaceView is the default. |
| Audio and video are out of sync | Adjust lip sync in the native car video player and reassess when changing sources. |
| Black picture with native playback running | Try the alternative TextureView AA video surface and capture diagnostics for the specific host combination. |
| WebView does not start after a player error | Expected behavior: WebView must be selected explicitly as the AA backend. |
| Playlist or history missing after reinstall | Data is local and is not restored from the YouTube account. |
| Desired resolution or audio language unavailable | Source, available streams, decoders and the car video budget constrain selection. |

Features not promised include offline video downloads, synchronization of local playlists with YouTube, complete YouTube account history, Chromecast/TV pairing and universal head-unit compatibility. Historical audit files describe earlier checks; claims about current physical devices require testing the specific APK and device combination.

### Repository sources

- [Phone settings](../app/src/main/res/xml/preferences.xml) and [choice values](../app/src/main/res/values/arrays.xml)
- [SettingsRepository](../car/src/main/java/de/uni/youtubeaa/car/SettingsRepository.kt) and [car settings](../car/src/main/java/de/uni/youtubeaa/car/SettingsCarFragment.kt)
- [BrowseFacade](../car/src/main/java/de/uni/youtubeaa/car/hybrid/BrowseFacade.kt), [TV authentication](../car/src/main/java/de/uni/youtubeaa/car/hybrid/YtTvAuthClient.kt) and [playlist store](../car/src/main/java/de/uni/youtubeaa/car/UserPlaylistStore.kt)
- [Car video budget](../car/src/main/java/de/uni/youtubeaa/car/CarVideoPolicy.kt), [parking check](../car/src/main/java/de/uni/youtubeaa/car/ParkGate.kt) and [lip sync](../car/src/main/java/de/uni/youtubeaa/car/CarLipSync.kt)
- [App build](../app/build.gradle.kts), [shared module](../car/build.gradle.kts) and [build helper](../build.sh)

## License and redistribution

Original project code and documentation use GPL-3.0-or-later; see the
[license text](../LICENSE), [licensing and release requirements](../LICENSING.md),
and [third-party notices](../THIRD_PARTY_NOTICES.md). The license status of the
bundled `aauto.aar` remains unverified and must be resolved before distributing
builds containing it. A successful build is not a licensing clearance.
