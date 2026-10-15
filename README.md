<div align="center">

<img src="app/src/main/res/drawable-xxxhdpi/ic_brand_play.png" alt="YouTube Companion" width="112">

# YouTube Companion

**YouTube on your phone and in Android Auto — personal feeds, background audio, your own playlist and video projection while parked.**

[![Android 9+](https://img.shields.io/badge/Android-API%2028%2B-3DDC84?logo=android&logoColor=white)](#requirements)
[![Android Auto](https://img.shields.io/badge/Android%20Auto-Companion-4285F4)](#features)
[![Media3](https://img.shields.io/badge/Player-Media3%20%2F%20ExoPlayer-E53935)](#features)
[![License: GPL v3 or later](https://img.shields.io/badge/License-GPL--3.0--or--later-blue.svg)](LICENSE)
[![Version](https://img.shields.io/badge/Version-1.0.1-E5A00D)](#build)

[![GitHub Sponsors](https://img.shields.io/badge/GitHub%20Sponsors-Sponsor-EA4AAA?logo=githubsponsors&logoColor=white)](https://github.com/sponsors/seinist)
[![Ko-fi](https://img.shields.io/badge/Ko--fi-Support-FF5E5B?logo=kofi&logoColor=white)](https://ko-fi.com/seinist)

[Features](#features) · [Support](#support) · [Setup](#setup) · [Build](#build) · [Limits](#known-limits) · [Documentation](docs/DOCUMENTATION.md) · [Deutsch](README.de.md)

**Package** `de.uni.youtubeaa` · **Version in repository** `1.0.1` · **Android** API 28+

</div>

> [!WARNING]
> **Use at your own risk.** This project is provided without warranty. I expressly do not recommend playing videos in a car while driving. **Only watch videos on the car display when the vehicle is safely parked.** Operating the app must not distract from traffic; follow applicable traffic rules.
>
> **I accept no responsibility for the use of this app or any resulting damage, accidents or other consequences.** The built-in parking check does not replace your own responsibility or your duty to operate the vehicle safely.

**Languages:** **English** · [Deutsch](README.de.md)

## The idea

Find videos on your phone, listen to them as audio on the go and open them on the car display when parked: YouTube Companion connects these interfaces in one app. The phone app and Android Auto integration share the account, saved videos and playback state.

The project combines Android Auto's media browser for audio with a custom, unofficial projection interface for video. It is a sideload/university project, not an official YouTube client.

## Support

If you enjoy YouTube Companion, a coffee or a GitHub sponsorship helps support its continued development. Thank you! ☕

[![GitHub Sponsors](https://img.shields.io/badge/GitHub%20Sponsors-Sponsor-EA4AAA?logo=githubsponsors&logoColor=white)](https://github.com/sponsors/seinist)
[![Ko-fi](https://img.shields.io/badge/Ko--fi-Support-FF5E5B?logo=kofi&logoColor=white)](https://ko-fi.com/seinist)

## Features

<table>
<tr>
<td width="50%" valign="top">

### 📚 Feeds & library

- **Home** with personal recommendations after sign-in and public content as a fallback
- **Subscriptions** with a channel overview, feed filters and additional pages
- **Shorts** from the subscriptions feed in a separate section
- **Search, popular videos and channel videos** without signing in
- **Local history** with playback progress
- **Your own app playlist** to save videos and play them together

</td>
<td width="50%" valign="top">

### ▶️ Playback

- **Media3 / ExoPlayer** for native video and audio playback
- Switch **video ↔ audio** in the player while retaining the position
- **Background audio**, mini-player and media notification
- Queue, previous/next and optional **autoplay**
- Playback speed from **0.25× to 2×**
- Video quality, audio language and audio quality selection
- App in **English and German** (follows the device language; per-app language on Android 13+)

</td>
</tr>
<tr>
<td valign="top">

### 🚘 Android Auto

- **Media browser** for audio and MediaSession controls
- Custom **“YouTube Video”** projection interface with browsing, playback and settings
- **Play on car display** from the phone, including the app playlist
- Separate head-unit video quality; **Auto** accounts for network and load
- **Lip sync** adjustment from −1,000 to +1,000 ms
- Parking check before video; audio mode without the video parking gate

</td>
<td valign="top">

### 📱 Interface & everyday use

- **Lists or cards**, configured separately for phone and car
- Responsive cards on the car display with up to four columns
- Link your **YouTube account using a TV code**
- Separate phone audio preference and shared playback defaults
- Adjustable thumbnail quality and **50–1,000 MB image cache**
- Share head-unit diagnostics as a **ZIP** or delete logs locally

</td>
</tr>
</table>

## Requirements

| Area | Requirement |
| --- | --- |
| Phone | Android 9 / API 28 or newer; internet access for feeds and streams |
| Android Auto | Compatible phone with Android Auto and Google Play Services; head unit or Desktop Head Unit (DHU) |
| Personal feeds | Linked YouTube account; public search and content also work as a guest |
| Development | JDK 17 or a compatible newer build JDK, Android SDK Platform 37 and SDK Build-Tools; Gradle Wrapper included |
| Video projection | Support for unofficial OEM projection in the particular Android Auto/head-unit combination |

> [!NOTE]
> Head-unit video is intended for use while parked. The parking check is enabled by default and uses the available parking-brake sensor; an unknown status produces a notice that requires confirmation. Unofficial projection is not available on every head unit.

## Setup

1. Build the debug APK and install it on the phone.
2. Open **YouTube Companion**. Search and public content work without sign-in.
3. For personal content, open **Settings → YouTube account** (“Einstellungen → YouTube-Konto”) and confirm the displayed TV code on the offered activation page.
4. Connect the phone to Android Auto. For sideload tests, enable unknown sources in its developer settings.
5. Open the media entry for audio or, where available, **YouTube Video** for projection. Follow the parking notice on the car display.

See the [handbook](docs/DOCUMENTATION.md#10-setup-and-dhu) for setup, DHU and troubleshooting.

## Build

```bash
# Debug APK
./gradlew :app:assembleDebug

# Install on the connected Android device
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Shared module JVM tests
./gradlew :car:testDebugUnitTest

# Release APK; signing is required for installation
./gradlew :app:assembleRelease
```

Configure the SDK path in `local.properties` (`sdk.dir=…`) or through the SDK environment. The build uses **AGP 9.4.0**, **Gradle 9.8.0** and **compileSdk / targetSdk 37**.

Alternatively, `./build.sh debug` creates a versioned APK in `dist/`. `./build.sh release` requires `keystore.properties` and verifies the APK signature when `apksigner` is available. These helpers perform a clean build. See [build documentation](docs/DOCUMENTATION.md#11-development-and-build) for signing and output paths.

### Update NewPipeExtractor

```bash
./build.sh update-newpipe --check  # Check for a newer stable release only
./build.sh update-newpipe          # Update the pin in car/build.gradle.kts
./gradlew :car:testDebugUnitTest :app:assembleDebug
```

Requires Python 3 and internet access. The command queries the latest stable
GitHub release and checks its JitPack POM before changing the dependency.
It does not select prereleases, downgrade automatically or run a build.
Afterwards, test search and playback on a device and review dependency license
changes; new releases may require code changes.

## Known limits

- **Unofficial YouTube integration:** YouTube changes can break feeds or stream resolution. Signing in does not guarantee playback of every item.
- **Experimental projection:** The bundled OEM SDK is intended for sideload tests. Generating an AAB does not imply Play Store approval for this integration.
- **Local playlist and history:** Shared within this installation, without synchronization to a YouTube account playlist or watch history.
- **Quality depends on source and device:** A selected resolution or language requires suitable streams and decoders.
- **WebView is an explicit choice:** Native AA player errors do not automatically switch playback to WebView.
- **No offline video downloads:** The configurable cache stores thumbnails, not complete videos.

## Documentation

- [Feature compendium and handbook](docs/DOCUMENTATION.md) — interfaces, all settings, playback, data storage and troubleshooting
- [German handbook](docs/DOCUMENTATION.de.md)
- [Android Auto SDK](car/libs/README.md) — origin and integration of the bundled AAR
- [Playback audit](PLAYBACK-AUDIT.md) and [performance audit](AA-PERFORMANCE-AUDIT-2026-10-07.md) — historical investigations, not proof for every current device combination

## License

Original project code and documentation are licensed under **GNU GPL v3 or later
(`GPL-3.0-or-later`)**. See [LICENSE](LICENSE) for the full text and
[LICENSING.md](LICENSING.md) for scope, warranty disclaimer and redistribution requirements.

Third-party components retain their own licenses; see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). NewPipeExtractor is GPL-3.0-or-later.
**The bundled `aauto.aar` SDK has an unverified license status:** its redistribution
rights and GPL compatibility must be clarified before distributing builds that
include it. See [SDK provenance](car/libs/README.md).
