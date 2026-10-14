# APKs auf GitHub bauen lassen

Die Datei `.github/workflows/apk.yml` muss zusammen mit dem Projekt nach GitHub
gepusht werden. Für den manuellen Start muss sie auf dem Standardbranch liegen.

## Sofort einen Debug-Build starten

Unter **Actions → Build APK → Run workflow** die Variante **debug** wählen.
Nach erfolgreichem Build steht unten im Workflow-Lauf unter **Artifacts**
`youtube-aa-companion-debug` zum Download bereit. Das ZIP enthält die APK.
Hierfür sind keine Signing-Secrets erforderlich. Debug-APKs aus unterschiedlichen
Runnern können unterschiedliche Signaturen haben und sind daher nicht zuverlässig
als Update übereinander installierbar.

## Signierte Release-APKs

Unter **Settings → Secrets and variables → Actions → New repository secret**
einmalig diese vier Secrets anlegen:

| Secret | Inhalt |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Bestehende JKS-Datei als Base64, ohne Zeilenumbrüche |
| `ANDROID_KEYSTORE_PASSWORD` | Passwort des Keystores |
| `ANDROID_KEY_ALIAS` | Alias des Signierschlüssels |
| `ANDROID_KEY_PASSWORD` | Passwort des Signierschlüssels |

Unter Linux den vorhandenen Keystore in eine temporäre Base64-Datei umwandeln:

```bash
umask 077
base64 -w 0 keystore/release.jks > /tmp/youtube-aa-keystore.base64
```

Den Dateiinhalt als `ANDROID_KEYSTORE_BASE64` einfügen und die temporäre Datei
anschließend löschen. Keystore und Passwörter niemals committen. Den bestehenden
Schlüssel verwenden, damit Updates auf bereits installierte Release-APKs passen.

Danach entweder manuell **Run workflow → release** starten oder auf GitHub
unter **Releases → Draft a new release** einen Release mit einem Tag wie `v1.0.0`
veröffentlichen. Der Workflow baut die signierte APK, prüft ihre Signatur und hängt
sie automatisch an den veröffentlichten Release an. Ein Tag-Push allein startet
diesen Workflow nicht. Der getaggte Commit muss den Workflow bereits enthalten.

Vor einer neuen Version `versionCode` und `versionName` in
`app/build.gradle.kts` erhöhen. Der Tag ändert die App-Version nicht automatisch.
Bei fehlenden Secrets bricht der Release-Build mit einer Fehlermeldung ab.

Die verwendeten Actions sind in der offiziellen Dokumentation beschrieben:
[setup-java](https://github.com/actions/setup-java) und
[upload-artifact](https://github.com/actions/upload-artifact).
