# Bewegungslogger

Zeichnet den ganzen Tag Bewegungsdaten auf und legt sie stundenweise als JSON-Lines in
einen Ordner, den die Nextcloud-App synchronisiert. **Die App wertet nichts aus** – ob
Gehen, Rad oder Auto, entscheidet später die lokale KI. Kein Google, keine Bibliotheken.

## Logik

| Quelle | Wann |
|---|---|
| Schrittzähler | immer |
| GPS (alle 5 s) + Beschleunigungssensor (25 Hz) | nur wenn **kein ortsfestes WLAN** verbunden **und** Bewegung erkannt |

- Bewegung = Significant-Motion-Sensor löst aus **oder** ≥ 15 Schritte im laufenden Intervall.
- GPS geht aus, sobald ein ortsfestes WLAN verbunden ist **oder** 1 min lang keine Bewegung
  (Bewegung dabei: GPS-Geschwindigkeit ≥ 1 m/s, ≥ 100 m Ortswechsel, oder ≥ 30 Schritte/Intervall).
- Ortsfeste WLANs werden über die BSSID (Router-Kennung) erkannt. Bei Mesh/Repeatern hat jeder
  Zugangspunkt eine eigene BSSID → in der Nähe jedes Geräts einmal „Aktuelles WLAN hinzufügen“.

Alle Schwellen stehen als Konstanten oben in `LoggerService.kt`. Sie sind gewählte Startwerte,
nicht kalibriert.

## Datenformat

Datei pro Stunde, z. B. `bewegung_2026-10-17_14+0200.jsonl` (Versatz zu UTC im Namen, damit die
doppelte Stunde bei der Zeitumstellung eindeutig ist). Eine abgeschlossene Datei wird nie mehr
verändert. Die laufende Stunde bleibt intern, bis sie vorbei ist.

Eine Zeile pro Intervall (Takt 1 min; schläft das Gerät, kann ein Intervall länger sein –
deshalb Start und Ende):

```json
{"v":1,"start":"2026-10-17T14:03:00+02:00","end":"2026-10-17T14:04:00+02:00",
 "start_ms":1792245780000,"end_ms":1792245840000,
 "steps":112,"stationary_wifi":false,"gps_on":true,"sig_motion":0,"batt":81,
 "gps":[{"t":1792245785000,"lat":54.0887,"lon":12.1405,"acc":6.0,"spd":1.42,"alt":21.3}],
 "acc":{"n":1500,"hz":25.0,"mean":10.12,"sd":2.31,"f_dom":1.86,"peak_ratio":0.41}}
```

| Feld | Bedeutung |
|---|---|
| `steps` | Schritte im Intervall; `null` = kein Schrittzähler/keine Berechtigung |
| `stationary_wifi` | ortsfestes WLAN verbunden |
| `gps_on` | GPS war im Intervall (zeitweise) an |
| `sig_motion` | Auslösungen des Significant-Motion-Sensors |
| `gps[]` | Rohpunkte: Zeit (ms), Lage, Genauigkeit (m), Geschwindigkeit (m/s, Doppler), Höhe |
| `acc` | nur bei GPS an: Betrag der Beschleunigung, Mittel/Std.-Abw. (m/s²), vorherrschende Frequenz 0,5–5 Hz und ihr Leistungsanteil |

Fehlende Zeilen = App lief nicht.

## Einrichtung am Handy

1. APK aus den GitHub-Releases installieren.
2. In der App die Schritte 1–4 durchgehen: Berechtigungen, Standort „Immer zulassen“,
   Zielordner (den Ordner, den die Nextcloud-App hochlädt), Akku-Optimierung aus.
   Beim Xperia zusätzlich STAMINA-Modus für die App ausnehmen, falls aktiv.
3. „Aufzeichnung starten“.
4. Zu Hause „Aktuelles WLAN hinzufügen“.

Nach Neustart oder App-Update startet die Aufzeichnung von selbst wieder, wenn sie an war.

## Bauen

GitHub Actions baut bei jedem Push auf `main`. Ein Release (`v0.2.0` usw.) mit der APK entsteht nur,
wenn sich die Versionsnummer in der Datei `VERSION` ändert (Major.Minor.Patch); sonst liegt die APK
nur als Artefakt am Workflow-Lauf.
Signiert wird mit `app/signing.jks` aus dem Repo, damit Updates sich über die alte Version
installieren lassen. Das ist nur in einem **privaten** Repo vertretbar.

Lokal: `./gradlew assembleRelease` (Android SDK nötig).
