# Zweit-Gedächtnis – Ambient Context Assistant für Android

Ein **stummes Zweit-Gedächtnis**: Die App erfasst im Hintergrund, was auf dem Bildschirm zu sehen war
(sowie eigene Notizen und Sprachnotizen), indexiert alles **zu 100 % lokal** und beantwortet später Fragen
wie *„Was war das für eine PDF bezüglich des Budgets am Dienstag?“*.

- **Keine Server, kein Ollama, kein Whisper.** Alles läuft auf dem Gerät.
- **Daten bleiben auf dem Gerät.** Internet wird nur für den Download von Sprachmodellen genutzt, den du selbst startest. Erfasste Inhalte, Suchen und Antworten verlassen das Gerät nie, und die ML-Kit-Telemetrie ist deaktiviert.
- **Akku-schonend:** ereignisbasiert statt Polling, Debounce, Drosselung pro App, OCR nur als Fallback, Embeddings gebündelt per WorkManager, das LLM wird nur bei Bedarf geladen.

| Komponente | Technik |
|---|---|
| UI | Jetpack Compose, Material 3, eigenes Dark Theme (Tiefschwarz, Neon-Violett, Cyan) |
| Bildschirm-Kontext | `AccessibilityService` (Text-Baum) + `takeScreenshot()` und **ML Kit Text Recognition** (offline) |
| Datenbank | Room + **FTS4**-Volltextindex (unicode61) + Float32-Vektoren als BLOB |
| Embeddings | **MediaPipe TextEmbedder** (Universal Sentence Encoder, ~6 MB, wird beim Build automatisch geladen) |
| Antworten | **LiteRT-LM** mit In-App-Modellverwaltung (Qwen 2.5, Gemma 3, Gemma 3n, DeepSeek R1). Mehrere Modelle lassen sich installieren und wechseln. Erst GPU, dann CPU als Fallback |
| Suche | Hybrid: FTS-Präfixsuche + Kosinus-Ähnlichkeit, zusammengeführt per Reciprocal Rank Fusion, mit deutschem Zeit-Parser („gestern nachmittags“, „letzten Freitag“, „vor 2 Stunden“) |
| Hintergrundarbeit | WorkManager (Embeddings, Aufbewahrungsfrist), optional nur beim Laden |
| CI/CD | GitHub Actions: Tests, Debug- und Release-APK, Upload als Release-Asset |

## Projektstruktur

```
.
├── .github/workflows/android.yml        # CI/CD: Build, Tests, APK als Release-Asset
├── build.gradle.kts                     # Root-Build (Plugins)
├── settings.gradle.kts
├── gradle.properties
├── gradle/libs.versions.toml            # Zentrale Versionsverwaltung (Version Catalog)
├── gradlew / gradlew.bat / gradle/wrapper
└── app/
    ├── build.gradle.kts                 # Abhängigkeiten, Signatur, Modell-Download-Task
    ├── proguard-rules.pro
    ├── schemas/                         # Room-Schema-Export (wird beim Build erzeugt)
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── res/xml/accessibility_service_config.xml
        │   └── java/de/gedaechtnis/local/
        │       ├── GedaechtnisApp.kt            # Application + DI-Container
        │       ├── ai/
        │       │   ├── LocalAiEngine.kt         # MediaPipe: Embeddings + LLM (RAG)
        │       │   ├── QueryParser.kt           # Deutsche Zeitangaben → Zeitfenster
        │       │   └── VectorMath.kt
        │       ├── capture/
        │       │   ├── ScreenContextService.kt  # Schonender Hintergrunddienst
        │       │   ├── NodeTextCollector.kt     # Text aus dem Accessibility-Baum
        │       │   ├── OcrProcessor.kt          # ML Kit OCR
        │       │   └── VoiceNoteRecognizer.kt   # On-Device-Spracherkennung
        │       ├── data/
        │       │   ├── ContextRepository.kt     # Speichern + Hybrid-Suche
        │       │   ├── SettingsRepository.kt    # DataStore inkl. Sperrliste
        │       │   └── db/                      # Room: Entities, DAO, Database
        │       ├── work/EmbeddingWorker.kt
        │       └── ui/
        │           ├── MainActivity.kt          # Navigation (Dashboard / Suche / Einstellungen)
        │           ├── theme/Theme.kt
        │           ├── components/Components.kt
        │           ├── home/HomeScreen.kt  (+ ViewModel)
        │           ├── search/SearchScreen.kt (+ ViewModel)
        │           └── settings/SettingsScreen.kt (+ ViewModel)
        └── test/…/QueryParserTest.kt
```

## Lokal bauen

Voraussetzungen: Android Studio (Ladybug oder neuer) bzw. JDK 17 und Android SDK 35.

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest
```

Beim ersten Build lädt Gradle das Embedding-Modell (~6 MB) nach `app/src/main/assets/`.
Ohne Netz läuft der Build trotzdem durch. Die App nutzt dann nur die Volltextsuche.

## Inbetriebnahme auf dem Gerät

1. APK installieren (Android 12+, 64-Bit-ARM).
2. **Bedienungshilfe aktivieren:** Dashboard → „Bedienungshilfe aktivieren“ → *Zweit-Gedächtnis* einschalten.
   Bei per APK installierten Apps (Android 13+) zuerst unter *App-Info → ⋮ → Eingeschränkte Einstellungen zulassen* freigeben.
3. Optional ein **Sprachmodell installieren** (Einstellungen → Lokale Sprachmodelle → „Installieren“).
   Der Download läuft direkt in der App. Qwen 2.5 und DeepSeek brauchen kein Konto, Gemma braucht einen Hugging-Face-Token.
   Ohne Sprachmodell formuliert die App die Antworten regelbasiert in ganzen Sätzen.
4. Die Sperrliste prüfen. Banking-, Bezahl-, Passwort- und Authenticator-Apps sind vorausgewählt.

## Releases & CI/CD

Der Workflow `.github/workflows/android.yml` läuft bei jedem Push und PR (Tests und APK-Build als Workflow-Artefakt).
Für ein Release:

```bash
git tag v1.0.0 && git push origin v1.0.0
```

oder auf GitHub ein Release veröffentlichen, oder einen Commit mit `[release v1.2.3]` in der Nachricht pushen
(bzw. den Workflow manuell mit dem Parameter `release_tag` starten). Dann legt die CI Tag und Release selbst an. Die CI hängt dann `ZweitGedaechtnis-<version>-release.apk`,
`…-debug.apk` und `SHA256SUMS.txt` an das Release.

**Signatur (optional):** Lege diese Repository-Secrets an:
`SIGNING_KEYSTORE_BASE64` (`base64 -w0 release.jks`), `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.
Ohne Secrets wird die Release-APK mit dem Debug-Schlüssel signiert. Sie ist dann installierbar, aber nicht für den Play Store geeignet.

## Datenschutz-Design

- Erfasst werden nie: Apps auf der Sperrliste, Passwortfelder, der Sperrbildschirm, Tastaturen, Launcher und System-UI.
  Bei Fenstern mit `FLAG_SECURE` verweigert das System den Screenshot.
- Wird eine App gesperrt, löscht die App auch die bereits gespeicherten Daten dieser App.
- `allowBackup=false` und Data-Extraction-Rules verhindern Cloud-Backups und Geräte-Transfers.
- Alte Einträge werden nach der eingestellten Aufbewahrungsfrist automatisch gelöscht.
- Sprachnotizen speichern nur Text. Ab Android 12 wird die reine On-Device-Erkennung genutzt, sofern das Gerät sie anbietet.

## Bekannte Grenzen

- **Accessibility statt MediaProjection:** MediaProjection verlangt ab Android 14 bei jeder Sitzung eine neue Zustimmung
  und kodiert dauerhaft Videoframes. Das kostet Akku. Der AccessibilityService liest Text direkt und nutzt OCR nur als Fallback.
  Er wird vom System gebunden und braucht keinen eigenen Foreground-Service. Die Statusanzeige ist eine stille, dauerhafte Benachrichtigung.
- **Google Play:** Die Nutzung der Accessibility-API für diesen Zweck unterliegt strengen Play-Richtlinien.
  Die App ist primär für die Installation per APK (GitHub-Releases) gedacht.
- Die Modelle stammen aus Googles `litert-community` auf Hugging Face. Die Liste mit festen Commit-Hashes steht in
  `ModelManager.CATALOG`; Gemma-Modelle setzen die Annahme der Gemma-Lizenz und einen Token voraus.
