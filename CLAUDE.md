# Working on Moyna (notes for an AI assistant)

Moyna is a Bengali/English voice assistant and wall hub for an old Android phone. Read `README.md` first; it explains what the app does and how it is set up. If a `NOTES-PRIVATE.md` exists on this computer, read its **"START HERE"** section before doing anything: it holds this family's own details (the phone and how to connect to it, where the tools are, what was tried and why, open to-dos).

## Starting a session

1. Read `NOTES-PRIVATE.md` → "START HERE" (if present), then `git status` and `git log --oneline -5`.
2. To reach the phone: `adb devices`; if it isn't listed, use the wireless address in the private notes (`adb connect <ip>:5555`).
3. Ask what the owner wants to work on. Don't redo anything listed under "Things already learned".

## Rules

- **This repository is public.** Never commit keys, addresses, coordinates, bus stop codes, bus line names of a real household, PINs, logs, recordings of people, prayer calendar photos, or an azan recording.
- Everything personal lives in git-ignored places: `local.properties` (API keys), `personal.properties` (contact, PIN, bus stops, location) and `private/` (prayer CSVs, azan, logs, screenshots, notes).
- Before every commit, check the staged changes for the items above. Ask the owner before pushing.
- The owner is not a programmer. Explain each step in one or two plain sentences, stop for testing on the real phone, and ask before anything destructive or hard to undo.
- Write chat messages in **English only**: the owner can't read Bengali. Describe Bengali phrases in English or romanized ("Moyna thamo"). Bengali belongs in the app, prompts and code.
- **Secrets:** never print, echo or commit them, and never ask for a password in chat. Don't open `private/kasa-login.properties` at all (scripts may read it, and print only results). Avoid opening `local.properties` too: after the owner edits a file the assistant has opened, some tools show its new contents. New secrets go in a file the owner creates and fills in himself.
- Commits use the repository's local git identity (GitHub noreply email); don't change it.
- Screenshots for the README must not show the family's real bus lines. Build with stand-in settings: `gradlew assembleDebug -PpersonalFile=private/personal.demo.properties`, take the pictures, then rebuild normally and reinstall.

## Where things are

| File | What it holds |
|---|---|
| `app/src/main/java/app/moyna/MoynaConfig.kt` | Every easy-to-change setting, the tiles, and the system prompt |
| `MoynaEngine.kt` | The state machine: wake word, conversation, tools, tiles, azan |
| `GeminiLiveClient.kt`, `LookUp.kt` | The Gemini Live connection and the Google Search look-up |
| `MainActivity.kt` | The hub screen, night look, admin screen, test commands |
| `HubData.kt`, `PrayerCalc.kt` | Bus, weather and prayer data |
| `Kasa.kt`, `Lights.kt` | TP-Link Kasa smart plugs (KLAP v2, local Wi-Fi) and the light tiles; login in `private/kasa-login.properties`, never read it |
| `WakeWord.kt`, `Audio.kt` | On-phone wake word (sherpa-onnx), microphone and speaker |
| `Kiosk.kt`, `Events.kt` | Kiosk mode and the events log |
| `tools/` | Scripts to pull the events log and summarise a week |
| `app/src/main/assets/kws/` | Wake word spellings (`wake.txt`), stop words (`commands.txt`), the sherpa-onnx model |
| `app/src/main/assets/clips/` | Pre-recorded Bengali messages and sounds (offline, busy, problem, sleep, searching, waiting); see its README.txt |
| `personal.properties.example` | Every personal setting, documented (copy to `personal.properties`) |

## Build and install

Needs JDK 17 and the Android SDK (`JAVA_HOME`, `ANDROID_HOME`). The target phone is 32-bit (armeabi-v7a) on Android 11.

```bash
./gradlew assembleDebug
adb install -r -t <path to app-debug.apk>   # -t is required: the app is marked testOnly for kiosk mode
```

On Windows the APK is written under `%LOCALAPPDATA%/moyna-build` (see the root `build.gradle.kts`). Test commands are listed in the README under "Built-in test modes".

## Things already learned (do not redo)

- The wake word only hears within a few feet on the test phone; many tunings were tried. The tap-to-talk tile is the answer for distance.
- The phone cannot hear a voice over its own speaker, so stopping is the red button.
- Google Search on the free tier only works through the older Live model (`LookUp.kt`).
- sherpa-onnx int8 models hang on 32-bit ARM; use the full-precision ones.
- The night look goes by the clock, not the light sensor.
- The Kasa EP10 plugs (firmware 1.1.2) use KLAP and need the Kasa account login; plain http must be allowed (`usesCleartextTraffic`). `python-kasa` on the PC finds them (`Discover.discover`) and is the reference for `Kasa.kt`.
- Wake-word tests need a real person's voice. Computer voices and sounds played through PC speakers are distorted and give meaningless results. Use the beep test.
- The wake word uses the zh-en *phoneme* model; English-spelling models hear "Moyna" as "why not / my na". Lone Bengali stop words are heard as Chinese pinyin (`t ā m ò`), so they are written that way in `commands.txt`.
- Gemini transcribes Moyna's own echo even with the phone's echo canceller, so the mic is never streamed to Gemini while she talks.
- Gemini free tier (checked Nov 2026): the Live models have no daily request limit (per-minute only); Google Search grounding is free only for Gemini 2.x models (0 for Gemini 3+); `gemini-3.8-live` had no free quota. Re-check AI Studio → Rate limits before relying on this.
- A "TV mode" (stricter wake word when noisy) and loud-voice barge-in were tried and removed: they made Moyna deaf or cut her off.
