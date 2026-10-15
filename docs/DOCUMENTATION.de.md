# YouTube Companion – Feature-Kompendium

> [!WARNING]
> **Nutzung auf eigene Verantwortung.** Dieses Projekt wird ohne Gewähr bereitgestellt. Ich empfehle ausdrücklich nicht, während der Fahrt Videos im Auto abzuspielen. **Videos auf dem Auto-Display nur bei sicher geparktem Fahrzeug ansehen.** Die Bedienung darf nicht vom Verkehr ablenken; geltende Verkehrsregeln sind einzuhalten.
>
> **Ich übernehme keine Verantwortung für die Nutzung dieser App oder daraus entstehende Schäden, Unfälle oder sonstige Folgen.** Die integrierte Parkprüfung ersetzt weder die eigene Verantwortung noch die Pflicht, das Fahrzeug sicher zu führen.

**App:** YouTube Companion (`de.uni.youtubeaa`) · **Version:** `1.0.0` (1) · **minSdk:** 28 · **target/compileSdk:** 37

**Sprachen:** [English](DOCUMENTATION.md) · **Deutsch**

[Zur README](../README.de.md)

Dieses Handbuch beschreibt den implementierten Funktionsumfang von Smartphone-App, Android-Auto-Medienbrowser und Video-Projektion. Bezeichnungen und Standardwerte stammen aus den Einstellungsressourcen und dem Code. Die App gibt es auf Englisch (Standard) und Deutsch; dieses Handbuch nutzt die deutschen Bezeichnungen. Die Dokumentation ist ein Abgleich mit dem Quellstand, kein zusätzlicher Geräte- oder Wiedergabetest.

| Tabellenspalte | Bedeutung |
| --- | --- |
| Optionen / Werte | In der Oberfläche angebotene Auswahl |
| Standard | Wert einer frischen Installation; „—“ bezeichnet eine Aktion |
| Oberfläche | Handy, AA-Medienbrowser oder eigene AA-Projektion |
| Hinweise | Verfügbarkeit, gemeinsame Speicherung oder Einschränkungen |

---

## Inhalt

1. [Überblick](#1-überblick)
2. [Einstellungen und Speicherung](#2-einstellungen-und-speicherung)
3. [Feeds und Bibliothek](#3-feeds-und-bibliothek)
4. [Konto](#4-konto)
5. [Player-Standardwerte](#5-player-standardwerte)
6. [Audio, Darstellung und Daten](#6-audio-darstellung-und-daten)
7. [Bedienung während der Wiedergabe](#7-bedienung-während-der-wiedergabe)
8. [Handy und Auto zusammen](#8-handy-und-auto-zusammen)
9. [Qualität und Performance](#9-qualität-und-performance)
10. [Einrichtung und DHU](#10-einrichtung-und-dhu)
11. [Entwicklung und Build](#11-entwicklung-und-build)
12. [Datenhaltung und Diagnose](#12-datenhaltung-und-diagnose)
13. [Fehlerbehebung und Grenzen](#13-fehlerbehebung-und-grenzen)

---

## 1. Überblick

Eine APK enthält die Handy-Oberfläche und die Android-Auto-Integration. Die Projektion läuft vom Smartphone aus; sie ist keine eigenständig auf Android Automotive OS installierte Fahrzeug-App.

| Oberfläche | Funktionsumfang | Einstieg |
| --- | --- | --- |
| Smartphone | Feeds, Suche, Konto, Playlist, Einstellungen, nativer Video-/Audioplayer und Mini-Player | App-Symbol „YouTube Companion“ |
| AA-Medienbrowser | Vom Host dargestellte Medienlisten und Audiowiedergabe mit MediaSession-Steuerung | Medien-App in Android Auto |
| AA-Projektion | Eigener Browser, Listen/Karten, Video-/Audioplayer und Auto-Einstellungen | „YouTube Video“, sofern vom Host angeboten |

Gemeinsame Bausteine sind Feed-Zugriff, Stream-Auflösung, Kontositzung, lokale Playlist, Verlauf, Queue und Wiedergabesteuerung. Die Smartphone-Oberfläche verwendet Android Views, RecyclerView und Preference-Oberflächen; das Projekt nutzt kein Compose-UI.

## 2. Einstellungen und Speicherung

Auf dem Smartphone liegen die Einstellungen in den Gruppen **Konto**, **Player-Standardwerte**, **Audio-Standardwerte**, **Darstellung**, **Daten** und **Diagnose**. Die Projektion bietet eine kompakte Auswahl derselben gemeinsamen Einstellungen; Antippen einer Auswahlzeile schaltet dort zum nächsten Wert weiter.

Änderungen werden über `SettingsRepository` in den gemeinsamen App-Preferences gespeichert. Beide Oberflächen laufen in derselben Installation, daher ist dafür keine Netzwerk-Synchronisation nötig.

- Videoqualität und Kartenansicht sind für Handy und Auto getrennt gespeichert.
- „Handy bevorzugt im Audio-Modus starten“ wirkt ausschließlich auf das Handy.
- Der Standard-Wiedergabemodus ist gemeinsam; die Handy-Audio-Präferenz kann ihn auf dem Handy übersteuern.
- Geschwindigkeit, Audiosprache, Audioqualität, Autoplay, Verlauf und Cache sind gemeinsam.
- Lippensynchronität betrifft ausschließlich den nativen Headunit-Videoplayer.

## 3. Feeds und Bibliothek

| Bereich | Inhalt | Konto erforderlich? | Hinweise |
| --- | --- | --- | --- |
| Startseite / Home | Persönliche Empfehlungen oder öffentliche Videos | Für persönliche Empfehlungen | Fällt bei nicht verfügbaren persönlichen Inhalten auf öffentliche Inhalte zurück. |
| Abos | Abo-Videos, Kanalübersicht und vom Feed gelieferte Filter | Ja | Unterstützt weitere Seiten; Videos mit erkennbarem Datum werden nach Aktualität sortiert. |
| Shorts | Shorts aus den geladenen Abo-Feed-Seiten | Ja | Kein unabhängiger, unbegrenzter Shorts-Empfehlungsfeed. |
| Beliebt | Öffentliche populäre Inhalte | Nein | Bei leerer Liste verwendet der Datenzugriff eine „trending“-Suche. |
| Suche | Öffentliche Videosuche | Nein | Zugriff über den anonymen Browse-Pfad. |
| Kanal | Öffentliche Videos eines gewählten Kanals | Nein | Persönliche Abo-Kanalübersicht erfordert eine Anmeldung. |
| Verlauf | Lokal gespeicherte Wiedergaben | Nein | Kein Abruf des YouTube-Kontoverlaufs. |
| Playlist / Gespeichert | Eigene, lokal gespeicherte App-Playlist | Nein | Gemeinsamer Speicher für Handy, Medienbrowser und Projektion. |

Die eigene Playlist unterstützt Hinzufügen und Entfernen sowie das Abspielen der Liste. Auf dem Handy kann sie auch an die Auto-Projektion übergeben oder geleert werden. Das Speichern eines bereits vorhandenen Videos erzeugt keinen doppelten Eintrag.

Feed-Seiten und Metadaten werden zwischengespeichert. Verfügbare Filter, weitere Seiten, Datumsangaben und Vorschaubilder hängen von den YouTube-Antworten ab. Der native Android-Auto-Medienbrowser wird vom Host gestaltet und bildet nicht jede Funktion der eigenen Browse-Oberflächen identisch ab.

## 4. Konto

| Einstellung | Optionen / Werte | Standard | Hinweise |
| --- | --- | --- | --- |
| YouTube-Konto | Verknüpfen / Kontostatus / Abmelden | Nicht verknüpft | Anmeldung über den TV-Gerätecode auf dem Smartphone. |

Unter **Einstellungen → YouTube-Konto** den Anmeldevorgang öffnen, die angezeigte Aktivierungsseite aufrufen und den Code bestätigen. Nach erfolgreicher Verknüpfung verwenden Handy und Auto dieselbe Sitzung.

Der Auth-Client versucht die YouTube-TV-Clientdaten zur Laufzeit zu ermitteln. Als Build-Fallback können `YOUTUBE_TV_CLIENT_ID` und `YOUTUBE_TV_CLIENT_SECRET` als Gradle-Properties gesetzt werden. Beide Werte müssen vorhanden sein. Sie werden in `BuildConfig` eingebaut und sind damit keine geheimen serverseitigen Zugangsdaten. Für den normalen Gastbetrieb ist kein YouTube-Data-API-Schlüssel vorgesehen.

TV-OAuth-Tokens werden mit `EncryptedSharedPreferences` gespeichert. Persönliche Feeds kommen über den InnerTube-Zugriff; öffentliche Suche und Stream-Auflösung verwenden die anonyme Anbindung beziehungsweise NewPipeExtractor. Die Anmeldung hebt Einschränkungen einzelner Videos nicht automatisch auf.

## 5. Player-Standardwerte

| Einstellung auf dem Handy | Optionen / Werte | Standard | Hinweise |
| --- | --- | --- | --- |
| Standard-Tab | Startseite · Abos · Suche | Startseite | Die Auto-Einstellungen bieten für denselben Schlüssel Home · Abos · Beliebt · Verlauf · Gespeichert. Die Auswahllisten sind nicht identisch. |
| Autoplay | An / Aus | Aus | Startet das nächste Video der Queue automatisch. |
| Handy bevorzugt im Audio-Modus starten | An / Aus | Aus | Nur Handy; Wechsel zu Video bleibt im Player möglich. |
| Videoqualität auf dem Handy | Auto · 2160p · 1440p · 1080p · 720p · 480p · 360p · 240p · 144p | Auto | Verfügbarkeit hängt von Stream und Decoder ab. |
| Video-Auflösung im Auto | Auto · 1080p · 720p · 480p · 360p · 240p · 144p | Auto | Separate Headunit-Vorgabe; Auto berücksichtigt Netz und Last. |
| Standard-Geschwindigkeit | 0,25× · 0,5× · 0,75× · 1× · 1,25× · 1,5× · 1,75× · 2× | 1× | Gemeinsamer Standard. |
| Standard-Wiedergabemodus | Video · Nur Audio | Video | Gemeinsame Präferenz; auf dem Handy ggf. durch die zusätzliche Audio-Präferenz übersteuert. |
| Video-Backend (AA) | ExoPlayer · WebView | ExoPlayer | WebView ist eine ausdrücklich gewählte Kompatibilitätsoption. |
| AA Video-Oberfläche | SurfaceView · TextureView | SurfaceView | Für den nativen AA-Videoplayer; TextureView als alternative Darstellung bei Host-Problemen. |
| Parkprüfung vor Video | An / Aus | An | Nutzt den Parkbremssensor; unbekannter Status führt zum Sicherheitshinweis. |

### Parkprüfung

Bei aktiver Prüfung und gemeldetem Nicht-Parkzustand wird Video blockiert. Wenn der Host keinen verlässlichen Sensorstatus liefert, wird stattdessen ein bestätigungspflichtiger Hinweis angezeigt. Der Audio-Modus unterliegt dieser Video-Sperre nicht. Der Sensorstatus ist keine universelle Erkennung sämtlicher Fahrsituationen.

### Backend-Auswahl

ExoPlayer ist der native Standard. Ein gespeicherter älterer Backend-Wert `auto` wird zu `exoplayer` normalisiert. Ein Streamfehler löst keinen stillen Wechsel zur YouTube-WebView aus. Die nachfolgenden nativen Qualitäts-, Audio- und Synchronitätsoptionen gelten nicht automatisch für den eingebetteten Webplayer.

## 6. Audio, Darstellung und Daten

### Audio-Standardwerte

| Einstellung | Optionen / Werte | Standard | Hinweise |
| --- | --- | --- | --- |
| Standard-Audiosprache | Auto (Originalspur) · Gerätesprache · Originalspur · Deutsch · Englisch | Originalspur | Wunsch für die Stream-Auswahl; keine Garantie für eine zusätzliche Tonspur. |
| Standard-Audioqualität | Auto (höchste) · Hoch · Mittel · Niedrig | Auto | Relative Qualitätsstufen der verfügbaren Streams, keine festen Bitraten. |

### Darstellung

| Einstellung | Optionen / Werte | Standard | Hinweise |
| --- | --- | --- | --- |
| Companion-App als Karten | An / Aus | Aus | Aus zeigt eine kompakte Liste; An eine responsive Kartenansicht. |
| Android Auto als Karten | An / Aus | Aus | Eigene Projektion, je nach Platz ein bis vier Spalten. |
| Video-Bildauflösung | Auto (beste verfügbar) · Maximal · Hoch · Mittel · Niedrig | Auto | Betrifft Vorschaubilder, nicht die Videoauflösung. |

### Daten und Diagnose

| Einstellung | Optionen / Werte | Standard | Hinweise |
| --- | --- | --- | --- |
| Cache-Größe | 50 · 100 · 500 · 1000 MB | 100 MB | Budget des Vorschaubild-Diskcaches; kein Video-Downloadspeicher. |
| Verlauf speichern | An / Aus | An | Steuert das Speichern lokaler Wiedergaben; Ausschalten ist keine Löschaktion. |
| Head-Unit-Logs teilen | Aktion | — | Erstellt eine Diagnose-ZIP und öffnet den Android-Teilen-Dialog. |
| Head-Unit-Logs löschen | Aktion | — | Entfernt gespeicherte Diagnoseprotokolle. |

## 7. Bedienung während der Wiedergabe

| Funktion | Handy | Native AA-Projektion |
| --- | --- | --- |
| Wiedergabe / Pause, Position und Queue-Navigation | Player und Mini-Player | Player und MediaSession-Anbindung |
| Video / Audio wechseln | Ja, unter Übernahme der Position | Ja, Video abhängig von Parkprüfung |
| Geschwindigkeit | Player-Einstellungen | Player-Einstellungen |
| Videoqualität | Handy-Vorgabe | Auto-Vorgabe |
| Audiosprache / Audioqualität | Player-Einstellungen | Player-Einstellungen |
| Vollbild | Handy-Videoplayer | Eigene Projektionsoberfläche |
| Zur Playlist hinzufügen | Player und Browse-Aktionen | Gemeinsame Playlist über den Browser |
| Auf Auto-Display abspielen | Übergabeaktion | Empfängt die Wiedergabe |
| Lippensynchronität | — | Nur bei nativer Videowiedergabe |

### Hintergrund und Mini-Player

Der Handy-Player kann Video beim Verlassen beziehungsweise Ausschalten des Bildschirms an die Hintergrund-Audiowiedergabe übergeben. Der Mini-Player zeigt den laufenden Titel und erlaubt die Rückkehr zum Player. Medienbenachrichtigung, Sperrbildschirm und unterstützte Medien-Tasten greifen über die MediaSession auf die aktive Wiedergabe zu.

Ein Wechsel zwischen Audio und Video übernimmt die aktuelle Position. Muss dafür eine andere Streamquelle geladen werden, kann eine Ladepause entstehen. Die App koordiniert den aktiven Player über gemeinsame Wiedergabezustände, damit Steuerbefehle zur aktuellen Wiedergabe gelangen.

### Lippensynchronität im Auto

| Einstellung | Werte | Standard | Wirkung |
| --- | --- | --- | --- |
| Audio-/Video-Versatz | −1.000 bis +1.000 ms; Schritte von 25 ms | 0 ms | Positiv bedeutet Ton später relativ zum Bild, negativ früher. |
| Zurücksetzen | Aktion | — | Setzt den Versatz auf 0 ms. |

Der Wert wird gespeichert und nur im Headunit-Videomodus angewendet. Die Korrektur verändert die Bildausgabe relativ zur Audiouhr; Wiedergabeposition, Verlauf und Übergaben behalten dieselbe Medienzeit.

## 8. Handy und Auto zusammen

1. Auf dem Smartphone ein Video suchen oder die Playlist öffnen.
2. **Auf Auto-Display abspielen** beziehungsweise **Playlist auf Auto** wählen.
3. Die App versucht die Projektion zu öffnen und den Titel samt Wiedergabekontext zu übergeben.
4. Falls erforderlich, den Parkhinweis auf dem Auto-Display bestätigen.

Die Übergabe braucht eine verfügbare Android-Auto-Projektion. Eine vorgemerkte Übergabe ist noch keine Bestätigung, dass der Host die Oberfläche tatsächlich gestartet hat. Bei fehlender Verbindung oder nicht bestätigtem Parkhinweis meldet die Oberfläche, dass die Wiedergabe auf dem Auto-Display nicht möglich ist.

Dies ist die Übergabe an Android Auto auf demselben Smartphone, kein Chromecast- oder TV-Pairing-Protokoll. Konto und lokale Daten werden innerhalb der App geteilt; eine zweite Geräteinstallation erhält diese Daten nicht automatisch.

## 9. Qualität und Performance

Der Auto-Player berücksichtigt Projektionsbildrate, Wiedergabegeschwindigkeit, thermischen Status und Wiedergabebelastung. Die konkrete Stream-Auswahl erfolgt innerhalb dieses Budgets und der verfügbaren Formate.

| Auto-Qualitätssituation | Auflösungsbudget |
| --- | --- |
| Normalbetrieb ohne gemeldete starke Last | Bis 720p |
| Als getaktet gemeldetes Netz oder starke thermische Belastung | Bis 480p |
| Kritische thermische Belastung bzw. entsprechende Lastbewertung | Bis 360p |

Eine explizit gewählte Auflösung wird nicht wie die Auto-Vorgabe durch diese Abstufung ersetzt. Die Bildrate bleibt dennoch an Projektionsbudget und Geschwindigkeit gebunden. Mehrfache Wiedergabegeschwindigkeit erhöht die nötige Decoderleistung und kann die passende Quellbildrate begrenzen.

SurfaceView ist die Standardausgabe des nativen Auto-Players. TextureView ist eine gespeicherte Alternative für problematische Hosts. Hardware-Codec-Prüfung, begrenzte Bildgrößen und gecachte Vorschaubilder sollen unnötige Decoder-, Speicher- und Kopierlast vermeiden. Daraus folgt keine zugesicherte ruckelfreie Wiedergabe für jede Headunit.

## 10. Einrichtung und DHU

### Smartphone

1. APK wie im nächsten Abschnitt bauen und installieren.
2. App starten und zunächst öffentliche Inhalte laden.
3. Für Abos und persönliche Empfehlungen das YouTube-Konto verknüpfen.
4. Video- und Audiowiedergabe sowie die gewünschten Standardwerte einstellen.

### Android Auto und Desktop Head Unit

Für den vorgesehenen Sideload-Test braucht das Smartphone Android Auto und Google Play Services. Die DHU-Komponente kann über den Android SDK Manager installiert werden:

```bash
sdkmanager "extras;google;auto"
```

In Android Auto die Entwicklereinstellungen über mehrfaches Antippen der Versionsanzeige aktivieren und **Unbekannte Quellen** zulassen. Für den USB-/ADB-DHU-Test den Headunit-Server über das Entwicklermenü starten, das Smartphone per USB verbinden und USB-Debugging erlauben. Die Menüführung hängt von der installierten Android-Auto-Version ab.

```bash
# Bei mehreren angeschlossenen Geräten das Ziel wählen
export ANDROID_SERIAL=<geraeteseriennummer>

# Projekt-Helfer für Port-Forward und DHU-Start
./scripts/start-dhu.sh

# Optional mit anderer DHU-Konfiguration
./scripts/start-dhu.sh config/default_720p.ini
```

Der Helfer verwendet den Android-SDK-Pfad und richtet die Weiterleitung für Port 5277 ein. Für Sensorprüfungen stellt die DHU unter anderem `parking brake on` und `parking brake off` bereit. Ein DHU-Test deckt nicht automatisch Sensoren, Latenz und Videoausgabe einer echten Fahrzeug-Headunit ab.

Der Helfer setzt auch `LD_LIBRARY_PATH` für die benötigten Linux-Bibliotheken. Alternativ lässt sich die DHU manuell starten:

```bash
adb forward tcp:5277 tcp:5277
cd "$ANDROID_SDK_ROOT/extras/google/auto"
export LD_LIBRARY_PATH="$ANDROID_SDK_ROOT/emulator/lib64:/usr/lib64/rocm/llvm/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
./desktop-head-unit -c config/default_sensors.ini
```

## 11. Entwicklung und Build

### Module und zentrale Komponenten

| Pfad / Komponente | Aufgabe |
| --- | --- |
| `app/` | APK, Smartphone-Aktivitäten, Kontoanmeldung, Einstellungen und Handy-Player |
| `car/` | AA-Medienbrowser und Projektion sowie gemeinsame Daten- und Wiedergabelogik |
| `aauto/` | Gradle-Wrappermodul für `car/libs/aauto.aar` |
| `hybrid/BrowseFacade` | Gemeinsamer Einstieg für persönliche und öffentliche Feeds |
| `StreamResolver` / `PlaybackSources` | Stream-Auflösung und Medienquellen |
| `PlaybackEngine` / `VideoSessionBridge` | Aktive Wiedergabe, Ownership und Übergaben |
| `YouTubeMediaBrowserService` / `YouTubeMediaSession` | Medienbrowser, Hintergrund-Audio und externe Mediensteuerung |
| `CarVideoPolicy` / `CarPlaybackQos` | Video-Budget und Lastanpassung der Projektion |
| `SettingsRepository` | Gemeinsame Einstellungen |
| `scripts/` | DHU-Helfer und ergänzende Audit-Skripte |

### Build-Stand

| Baustein | Im Repository konfiguriert |
| --- | --- |
| Android Gradle Plugin | 9.4.0 |
| Gradle Wrapper | 9.8.0 |
| Java-Quell-/Zielkompatibilität | 17 |
| Android SDK | min 28; compile / target 37 |
| Media3 | 1.11.1 |
| NewPipeExtractor | Version in `car/build.gradle.kts`, JitPack |

JDK und Android SDK installieren, SDK Platform 37 und die vom Build benötigten Build-Tools bereitstellen. Den SDK-Pfad in `local.properties` als `sdk.dir=…` oder über die SDK-Umgebung setzen. Abhängigkeiten werden aus Google Maven, Maven Central und JitPack bezogen.

```bash
./gradlew :app:assembleDebug
./gradlew :car:testDebugUnitTest
./gradlew :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Die Unit-Tests unter `car/src/test/` prüfen unter anderem Feed-Parsing, Paging, Listen-Merging, Stream-Auswahl, Video-Budget, Playback-Ownership, Sitzungsupdates, Verlauf und Lippensynchronität. Sie ersetzen keine Prüfung von Login, Live-Streams, Bluetooth-Steuerung oder Projektion auf Geräten.

### Release und Signierung

`app/build.gradle.kts` liest eine lokale `keystore.properties` mit diesen Schlüsseln:

```properties
storeFile=keystore/release.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=youtubeaa
keyPassword=YOUR_KEY_PASSWORD
```

Das sind Platzhalter. Für eine installierbare Release-APK müssen Keystore, Alias und Passwörter zusammenpassen. Ohne konfigurierte Signierung erzeugt der direkte Gradle-Release-Build eine unsignierte APK. Release-Minifizierung erfolgt mit R8 im App-Modul.

| Befehl | Ausgabe / Verhalten |
| --- | --- |
| `./gradlew :app:assembleDebug` | `app/build/outputs/apk/debug/app-debug.apk`, mit Debug-Schlüssel signiert |
| `./gradlew :app:assembleRelease` | APK unter `app/build/outputs/apk/release/`; Dateiname abhängig von Signierung |
| `./build.sh debug` | Clean-Build und Kopie nach `dist/youtube-aa-companion-debug-1.0.0.apk` |
| `./build.sh release` | Verlangt `keystore.properties`; Kopie nach `dist/youtube-aa-companion-release-1.0.0.apk` |
| `./build.sh playstore` | Erzeugt ein signiertes AAB in `dist/`; keine Aussage über Store-Zulässigkeit |
| `./build.sh all` | Debug, Release und AAB nacheinander; Standard ohne Argument |
| `./build.sh gen-keystore` | Interaktive Schlüsselerzeugung; bricht ab, wenn der Ziel-Keystore bereits existiert |
| `./build.sh update-newpipe --check` | Prüft das neueste stabile GitHub-Release ohne Dateiänderungen |
| `./build.sh update-newpipe` | Prüft das JitPack-POM und aktualisiert die NewPipeExtractor-Version; Python 3 erforderlich, kein automatischer Build |

Die eingebundene OEM-Projektion ist laut [SDK-Hinweis](../car/libs/README.md) für Sideload-Nutzung vorgesehen. Der Name des AAB-Buildmodus ändert diese Einschränkung nicht.

## 12. Datenhaltung und Diagnose

| Daten | Ablage / Verhalten |
| --- | --- |
| YouTube-TV-Tokens | Verschlüsselte App-Preferences |
| Einstellungen | Gemeinsame lokale App-Preferences |
| Eigene Playlist | Lokale App-Preferences; keine YouTube-Playlist-Synchronisation |
| Verlauf und letzte Sitzung | Lokale Stores für Titel, Position und Wiederaufnahme-Kontext |
| Vorschaubilder | Speicher- und Diskcache; einstellbares Diskbudget |
| Feed-Listen | Gemeinsame Browse-Caches; kein vollständiges Offline-Archiv |
| Diagnose | Lokale Headunit-Ereignisse und Crash-Stacktraces; Export über Teilen-Dialog |

Android-App-Backups sind im Manifest deaktiviert. Eine eigene vollständige Backup-/Restore-Oberfläche ist nicht vorgesehen. Das Löschen der App-Daten entfernt auch lokale Einstellungen, Kontositzung, Playlist und Verlauf.

Unter **Einstellungen → Diagnose → Head-Unit-Logs teilen** wird eine ZIP bereitgestellt. Für einen reproduzierbaren Fehlerbericht sind außerdem Gerät, Android-/Android-Auto-Version, Verbindungstyp, Backend, Auflösung und konkrete Bedienfolge hilfreich. Die Löschaktion für Logs betrifft Diagnoseprotokolle, nicht Playlist oder Wiedergabeverlauf.

## 13. Fehlerbehebung und Grenzen

| Symptom | Prüfung |
| --- | --- |
| App fehlt im AA-Medienbereich | Installation und Android-Auto-Sideload-Einstellungen prüfen; danach Verbindung neu herstellen. |
| „YouTube Video“ fehlt, Audio ist verfügbar | Der Host unterstützt oder akzeptiert möglicherweise die inoffizielle OEM-Projektion nicht. Audio und Projektion haben unterschiedliche Einstiegspunkte. |
| Abos oder Shorts bleiben leer | Kontostatus prüfen und erneut verknüpfen; Shorts stammen aus dem Abo-Feed. |
| Startseite wirkt unpersönlich | Ohne nutzbaren persönlichen Feed verwendet die App öffentliche Inhalte. |
| TV-Anmeldung kann nicht gestartet werden | Netzwerk und dynamische Client-Ermittlung prüfen; ggf. die beiden Build-Fallback-Properties konfigurieren. |
| Stream lässt sich nicht öffnen | Andere öffentliche Videos testen und Diagnose sichern; Feed-Zugriff und Stream-Auflösung sind getrennte Vorgänge. Änderungen bei YouTube können den Extractor betreffen. |
| Auto-Video ist blockiert | Parkstatus und Hinweis auf der Headunit prüfen; für Audiowiedergabe den Audio-Modus verwenden. |
| Video ruckelt oder Smartphone wird stark belastet | Auto-Qualität, niedrigere Auflösung und 1× Geschwindigkeit prüfen; SurfaceView ist der Standard. |
| Ton und Bild sind versetzt | Im nativen Auto-Videoplayer den Lippensynchronitätswert anpassen und bei Quellenwechsel erneut beurteilen. |
| Schwarzes Bild bei laufendem nativen Player | Alternative AA-Video-Oberfläche TextureView prüfen; Diagnose für die konkrete Host-Kombination sichern. |
| WebView startet nach Playerfehler nicht | Erwartetes Verhalten: WebView muss ausdrücklich als AA-Backend gewählt werden. |
| Playlist oder Verlauf fehlen nach Neuinstallation | Die Daten sind lokal und werden nicht aus dem YouTube-Konto zurückgeladen. |
| Gewünschte Auflösung oder Audiosprache fehlt | Quelle, verfügbare Streams, Decoder und Auto-Video-Budget begrenzen die Auswahl. |

Nicht als Funktion zugesichert sind Offline-Videodownloads, Synchronisation lokaler Playlists mit YouTube, ein vollständiger YouTube-Kontoverlauf, Chromecast-/TV-Pairing und universelle Headunit-Kompatibilität. Historische Audit-Dateien dokumentieren frühere Prüfungen; aktuelle Aussagen über echte Geräte benötigen einen Test mit der jeweiligen APK und Gerätekombination.

### Quellen im Repository

- [Einstellungen der Smartphone-App](../app/src/main/res/xml/preferences.xml) und [Auswahlwerte](../app/src/main/res/values/arrays.xml)
- [SettingsRepository](../car/src/main/java/de/uni/youtubeaa/car/SettingsRepository.kt) und [Auto-Einstellungen](../car/src/main/java/de/uni/youtubeaa/car/SettingsCarFragment.kt)
- [BrowseFacade](../car/src/main/java/de/uni/youtubeaa/car/hybrid/BrowseFacade.kt), [TV-Anmeldung](../car/src/main/java/de/uni/youtubeaa/car/hybrid/YtTvAuthClient.kt) und [Playlist-Store](../car/src/main/java/de/uni/youtubeaa/car/UserPlaylistStore.kt)
- [Auto-Video-Budget](../car/src/main/java/de/uni/youtubeaa/car/CarVideoPolicy.kt), [Parkprüfung](../car/src/main/java/de/uni/youtubeaa/car/ParkGate.kt) und [Lippensynchronität](../car/src/main/java/de/uni/youtubeaa/car/CarLipSync.kt)
- [App-Build](../app/build.gradle.kts), [gemeinsames Modul](../car/build.gradle.kts) und [Build-Helfer](../build.sh)

## Lizenz und Weitergabe

Eigener Projektcode und eigene Dokumentation stehen unter GPL-3.0-or-later.
Siehe [Lizenztext](../LICENSE), [Lizenz- und Veröffentlichungshinweise](../LICENSING.md)
und [Drittanbieter-Komponenten](../THIRD_PARTY_NOTICES.md). Der Lizenzstatus des
mitgelieferten `aauto.aar` ist weiterhin ungeklärt und muss vor der Weitergabe
von Builds mit diesem SDK geklärt werden. Ein erfolgreicher Build bestätigt
keine lizenzrechtliche Freigabe.
