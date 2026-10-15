<div align="center">

<img src="app/src/main/res/drawable-xxxhdpi/ic_brand_play.png" alt="YouTube Companion" width="112">

# YouTube Companion

**YouTube auf dem Smartphone und in Android Auto — mit persönlichen Feeds, Hintergrund-Audio, eigener Playlist und Video-Projektion für den geparkten Zustand.**

[![Android 9+](https://img.shields.io/badge/Android-API%2028%2B-3DDC84?logo=android&logoColor=white)](#voraussetzungen)
[![Android Auto](https://img.shields.io/badge/Android%20Auto-Companion-4285F4)](#features)
[![Media3](https://img.shields.io/badge/Player-Media3%20%2F%20ExoPlayer-E53935)](#features)
[![License: GPL v3 or later](https://img.shields.io/badge/License-GPL--3.0--or--later-blue.svg)](LICENSE)
[![Version](https://img.shields.io/badge/Version-1.0.1-E5A00D)](#build)

[![GitHub Sponsors](https://img.shields.io/badge/GitHub%20Sponsors-Sponsor-EA4AAA?logo=githubsponsors&logoColor=white)](https://github.com/sponsors/seinist)
[![Ko-fi](https://img.shields.io/badge/Ko--fi-Support-FF5E5B?logo=kofi&logoColor=white)](https://ko-fi.com/seinist)

[Features](#features) · [Unterstützen](#unterstützen) · [Einrichtung](#einrichtung) · [Build](#build) · [Grenzen](#bekannte-grenzen) · [Dokumentation](docs/DOCUMENTATION.de.md)

**Paket** `de.uni.youtubeaa` · **Version im Repository** `1.0.1` · **Android** ab API 28

</div>

> [!WARNING]
> **Nutzung auf eigene Verantwortung.** Dieses Projekt wird ohne Gewähr bereitgestellt. Ich empfehle ausdrücklich nicht, während der Fahrt Videos im Auto abzuspielen. **Videos auf dem Auto-Display nur bei sicher geparktem Fahrzeug ansehen.** Die Bedienung darf nicht vom Verkehr ablenken; geltende Verkehrsregeln sind einzuhalten.
>
> **Ich übernehme keine Verantwortung für die Nutzung dieser App oder daraus entstehende Schäden, Unfälle oder sonstige Folgen.** Die integrierte Parkprüfung ersetzt weder die eigene Verantwortung noch die Pflicht, das Fahrzeug sicher zu führen.

**Sprachen:** [English](README.md) · **Deutsch**

## Die Idee

Videos am Handy finden, unterwegs als Audio hören und im geparkten Fahrzeug auf dem Auto-Display öffnen: YouTube Companion verbindet diese Oberflächen in einer App. Konto, gespeicherte Videos und Wiedergabestatus werden von der Smartphone-App und der Android-Auto-Integration gemeinsam verwendet.

Das Projekt kombiniert den Android-Auto-Medienbrowser für Audio mit einer eigenen, inoffiziellen Projektionsoberfläche für Video. Es ist ein Sideload-/Uni-Projekt und kein offizieller YouTube-Client.

## Unterstützen

Wenn dir YouTube Companion gefällt, kannst du die Weiterentwicklung mit einem Kaffee oder einem GitHub-Sponsoring unterstützen. Vielen Dank! ☕

[![GitHub Sponsors](https://img.shields.io/badge/GitHub%20Sponsors-Sponsor-EA4AAA?logo=githubsponsors&logoColor=white)](https://github.com/sponsors/seinist)
[![Ko-fi](https://img.shields.io/badge/Ko--fi-Support-FF5E5B?logo=kofi&logoColor=white)](https://ko-fi.com/seinist)

## Features

<table>
<tr>
<td width="50%" valign="top">

### 📚 Feeds & Bibliothek

- **Startseite** mit persönlichen Empfehlungen nach Anmeldung und öffentlichen Inhalten als Rückfall
- **Abos** mit Kanalübersicht, Feed-Filtern und Nachladen weiterer Seiten
- **Shorts** aus dem Abo-Feed in einem eigenen Bereich
- **Suche, beliebte Videos und Kanalvideos** auch ohne Anmeldung
- **Lokaler Verlauf** mit Wiedergabefortschritt
- **Eigene App-Playlist** zum Speichern und gemeinsamen Abspielen

</td>
<td width="50%" valign="top">

### ▶️ Wiedergabe

- **Media3 / ExoPlayer** für native Video- und Audiowiedergabe
- **Video ↔ Audio** im Player wechseln und die Position übernehmen
- **Hintergrund-Audio**, Mini-Player und Medienbenachrichtigung
- Queue, Vorheriges/Nächstes und optionales **Autoplay**
- Geschwindigkeit von **0,25× bis 2×**
- Auswahl von Videoqualität, Audiosprache und Audioqualität
- App auf **Englisch und Deutsch** (folgt der Gerätesprache; App-Sprache pro App ab Android 13)

</td>
</tr>
<tr>
<td valign="top">

### 🚘 Android Auto

- **Medienbrowser** für Audio und Steuerung über die MediaSession
- Eigene **„YouTube Video“**-Projektionsoberfläche mit Browser, Player und Einstellungen
- **„Auf Auto-Display abspielen“** vom Handy, auch für die App-Playlist
- Separate Videoqualität für die Headunit; **Auto** berücksichtigt Netz und Auslastung
- **Lippensynchronität** von −1.000 bis +1.000 ms
- Parkprüfung vor Video; Audio-Modus ohne Video-Parksperre

</td>
<td valign="top">

### 📱 Oberfläche & Alltag

- **Listen oder Karten**, getrennt für Handy und Auto einstellbar
- Responsive Kartenansicht auf dem Auto-Display mit bis zu vier Spalten
- **YouTube-Konto per TV-Code** verknüpfen
- Getrennte Handy-Audio-Präferenz und gemeinsame Player-Standardwerte
- Einstellbare Vorschaubildqualität und **Bildcache von 50 bis 1.000 MB**
- Headunit-Diagnoseprotokolle als **ZIP teilen** oder lokal löschen

</td>
</tr>
</table>

## Voraussetzungen

| Bereich | Voraussetzung |
| --- | --- |
| Smartphone | Android 9 / API 28 oder neuer; Internet für Feeds und Streams |
| Android Auto | Kompatibles Smartphone mit Android Auto und Google Play Services; Headunit oder Desktop Head Unit (DHU) |
| Persönliche Feeds | Verknüpftes YouTube-Konto; öffentliche Suche und Inhalte funktionieren auch als Gast |
| Entwicklung | JDK 17 oder kompatible neuere Build-JDK, Android SDK Platform 37 und SDK Build-Tools; Gradle Wrapper liegt bei |
| Video-Projektion | Unterstützung der inoffiziellen OEM-Projektion durch die konkrete Android-Auto-/Headunit-Kombination |

> [!NOTE]
> Video auf der Headunit ist für den geparkten Zustand vorgesehen. Die standardmäßig aktive Parkprüfung nutzt den verfügbaren Parkbremssensor; fehlt dessen Status, erscheint ein bestätigungspflichtiger Hinweis. Die inoffizielle Projektion ist nicht auf jeder Headunit verfügbar.

## Einrichtung

1. Debug-APK bauen und auf dem Smartphone installieren.
2. **YouTube Companion** öffnen. Suche und öffentliche Inhalte sind ohne Anmeldung nutzbar.
3. Für persönliche Inhalte **Einstellungen → YouTube-Konto** öffnen und den angezeigten TV-Code über die angebotene Aktivierungsseite bestätigen.
4. Smartphone mit Android Auto verbinden. Für Sideload-Tests in dessen Entwicklereinstellungen unbekannte Quellen zulassen.
5. Den Medien-Eintrag für Audio oder, sofern verfügbar, **YouTube Video** für die Projektion öffnen. Den Parkhinweis auf dem Auto-Display beachten.

Einrichtung, DHU und Fehlerbehebung stehen im [Handbuch](docs/DOCUMENTATION.de.md#10-einrichtung-und-dhu).

## Build

```bash
# Debug-APK
./gradlew :app:assembleDebug

# Auf dem angeschlossenen Android-Gerät installieren
adb install -r app/build/outputs/apk/debug/app-debug.apk

# JVM-Tests des gemeinsamen Moduls
./gradlew :car:testDebugUnitTest

# Release-APK; für Installation ist eine Signierung erforderlich
./gradlew :app:assembleRelease
```

SDK-Pfad über `local.properties` (`sdk.dir=…`) oder die SDK-Umgebung konfigurieren. Der Build verwendet **AGP 9.4.0**, **Gradle 9.8.0** und **compileSdk / targetSdk 37**.

Alternativ erstellt `./build.sh debug` eine versionierte APK in `dist/`. `./build.sh release` verlangt `keystore.properties` und prüft die APK-Signatur, wenn `apksigner` gefunden wird. Diese Helfer führen einen Clean-Build aus. Signierung und Ausgabepfade beschreibt die [Build-Dokumentation](docs/DOCUMENTATION.de.md#11-entwicklung-und-build).


### NewPipeExtractor aktualisieren

```bash
./build.sh update-newpipe --check  # Nur nach einer neuen stabilen Version suchen
./build.sh update-newpipe          # Version in car/build.gradle.kts aktualisieren
./gradlew :car:testDebugUnitTest :app:assembleDebug
```

Benötigt Python 3 und Internetzugriff. Der Befehl ermittelt das neueste stabile
GitHub-Release und prüft vor der Änderung dessen JitPack-POM. Er übernimmt keine
Vorabversionen und führt keine automatischen Downgrades oder Builds aus.
Danach Suche und Wiedergabe am Gerät sowie Änderungen der Abhängigkeitslizenzen
prüfen; ein neues Release kann Anpassungen am Code erfordern.

## Bekannte Grenzen

- **Inoffizielle YouTube-Anbindung:** Feed-Zugriff und Stream-Auflösung können durch Änderungen bei YouTube ausfallen. Eine Anmeldung garantiert nicht, dass jeder Inhalt abspielbar ist.
- **Projektion ist experimentell:** Das eingebundene OEM-SDK ist für Sideload-Tests vorgesehen. Ein erzeugtes AAB bedeutet keine Play-Store-Freigabe für diese Integration.
- **Playlist und Verlauf sind lokal:** Sie werden innerhalb dieser Installation geteilt und nicht als YouTube-Kontoplaylist oder YouTube-Verlauf synchronisiert.
- **Qualität hängt von Quelle und Gerät ab:** Eine gewählte Auflösung oder Sprache ist nur nutzbar, wenn passende Streams und Decoder vorhanden sind.
- **WebView wird ausdrücklich gewählt:** Es gibt keinen automatischen Wechsel vom nativen AA-Player zur WebView bei Fehlern.
- **Kein Offline-Download:** Der einstellbare Cache speichert Vorschaubilder, keine vollständigen Videos.

## Dokumentation

- [Feature-Kompendium und Handbuch](docs/DOCUMENTATION.de.md) — Oberflächen, alle Einstellungen, Player, Datenhaltung und Fehlerbehebung
- [Android-Auto-SDK](car/libs/README.md) — Herkunft und Einbindung des mitgelieferten AAR
- [Playback-Audit](PLAYBACK-AUDIT.md) und [Performance-Audit](AA-PERFORMANCE-AUDIT-2026-10-07.md) — historische Untersuchungen; kein Nachweis für die Funktion jeder aktuellen Gerätekombination

## Lizenz

Eigener Projektcode und eigene Dokumentation stehen unter der **GNU GPL Version 3
oder neuer (`GPL-3.0-or-later`)**. Den vollständigen Lizenztext enthält
[LICENSE](LICENSE); Geltungsbereich, Gewährleistungsausschluss und Anforderungen
an die Weitergabe stehen in [LICENSING.md](LICENSING.md) (Englisch).

Drittanbieter-Komponenten behalten ihre eigenen Lizenzen; siehe
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). NewPipeExtractor steht unter
GPL-3.0-or-later. **Der Lizenzstatus des mitgelieferten SDK `aauto.aar` ist ungeklärt:**
Vor der Weitergabe von Builds mit diesem SDK müssen dessen Weitergaberechte und
GPL-Kompatibilität geklärt werden. Siehe [SDK-Herkunft](car/libs/README.md).
