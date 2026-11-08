# Nova AI

A Jarvis-style voice assistant for Android. Say **"Hey Jarvis"** (or **"Hey Nova"** once trained) and give a command — it works 24/7, offline, and stays light on battery.

## What you can say

| Kind | Examples |
|---|---|
| Calls | "call mom", "call Aravind", "dial 9 8 7 6 5 4 3 2 1 0" |
| Messages | "message mom saying I'm on my way" (WhatsApp), "WhatsApp Rahul I'll be late", "text dad that I'm home" (SMS) |
| Switches | "turn on Wi-Fi", "Bluetooth off", "mobile data on", "airplane mode", "hotspot on", "torch off", "turn on do not disturb", "location off", "battery saver on", "dark mode", "auto rotate off", "turn on wifi and bluetooth" |
| Sound | "volume up", "volume 7", "mute", "ring volume down", "silent mode", "vibrate mode" |
| Screen | "brightness 50 percent", "dim the screen", "lock the phone", "take a screenshot" |
| Music & video | "pause", "stop" (while something plays), "play", "next song", "previous track". Works with any player: VLC, MX Player, the OnePlus music app, YouTube… |
| Your own songs | "play my songs" (shuffle), "play Naruto opening", "play blue bird by Ikimonogakari", "play songs by Anirudh". Finds music on the phone **and SD card** |
| Search | "search Black Clover", "give me search results on Black Clover the anime", "search it on Brave", "play lofi music on YouTube" |
| Meanings | "what's the meaning of vilest", "define ephemeral", "what does serendipity mean": answered **offline**, out loud |
| Time | "set an alarm for 6 30 am", "wake me up at 7", "set a timer for 10 minutes", "alarm in 20 minutes" |
| Apps | "open YouTube", "open WhatsApp", "open Wi-Fi settings" |
| Info | "what's the time", "what's the date", "how much battery" |
| Other | "go home", "go back", "show notifications", "what can you do", "never mind" |

## Locked phone and voice unlock

Nova keeps listening on the lock screen and with the screen off, unless you turn on *Settings → Pause when the screen is off*.

**Voice unlock** (off by default; turn it on in *Settings → Voice unlock*):

- Say your trigger phrase and then your PIN: **"Hey Jarvis, unlock 1 2 3 4"**. You choose the trigger phrase in Settings (default "unlock"), e.g. "Hey Jarvis, open sesame 1 2 3 4".
- Nova turns the screen on, brings up the PIN pad and taps your digits through its accessibility service.
- **Your PIN is never saved.** It's only held in memory while being typed and is shown as "••••" everywhere: on screen, in history and in the lock-screen notification.
- While the phone is locked and voice unlock is on, Nova only uses the **offline** recognizer, so the PIN never leaves the phone.
- After **2 wrong attempts** voice unlock pauses until you unlock by hand once. Android's own lockout after 5 wrong PINs still applies.
- ⚠️ Nova can't recognise *whose* voice it is. Anyone who hears your PIN, or knows it, can unlock by voice. Only use this where that's acceptable.
- Works with numeric PINs. Patterns and passwords with letters aren't supported.

Family words work for contacts: "mom" also finds *Amma*, *Mummy*, *Maa*; "dad" finds *Appa*, *Papa*, and so on.

## Setting it up on the phone

1. Install the app (see **Building** below) and open it.
2. Turn on **Always listening** and allow the microphone and notifications.
3. Tap the **🔧 Device check** icon. It reads your phone model and Android version and lists, for each item, whether it's done and a **Fix** button:
   - **Battery optimization off**: required, or Android stops Nova in the background.
   - **Accessibility service**: required for Wi-Fi, Bluetooth, mobile data, airplane mode, WhatsApp sending, locking, screenshots and opening apps. If the switch is greyed out (Android 13+ blocks this for apps installed from a file), open *App info → ⋮ → Allow restricted settings* first.
   - **OnePlus / OPPO / realme**: in *App info → Battery usage*, turn on **Allow background activity** and **Allow auto launch**, then lock Nova in Recents. Without this, OxygenOS kills Nova after a while.
   - Contacts and phone, SMS, Modify system settings, Do Not Disturb access, Display over other apps: optional, each unlocks the features listed next to it.
4. Optional but recommended: install the **offline English speech pack** (Google app → Settings → Voice → Offline speech recognition) and an **offline English (India) voice** for text-to-speech.

The bottom of Device check lists exactly how Nova does each feature on *your* phone (directly, through Quick Settings, or not available).

## How it works

```
mic (16 kHz) ─► wake-word model (openWakeWord, ONNX, offline)
                     │  "Hey Jarvis" detected → beep
                     ▼
              speech-to-text, picked automatically:
                • phone's recognizer (online, or offline if the pack is installed): best with names
                • Vosk Indian-English model bundled in the app: works with no internet at all
                     ▼
              CommandParser (offline) ─► ActionExecutor ─► spoken reply (offline TTS)
```

- **Everything except the optional phone recognizer runs on the phone.** No audio is uploaded by Nova itself.
- **Battery:** between commands only the small wake-word model runs, on one CPU core. When the room has been quiet for 2 seconds it stops running the models entirely and just watches the loudness, then catches up on the last 2 seconds the moment sound returns. The large speech recognizer only runs for a few seconds after the wake word. Listening pauses during calls, and (optional, in Settings) when the screen is off or the battery is below 15%.
- **Toggles:** since Android 10 apps can't switch Wi-Fi, mobile data, airplane mode etc. directly, so Nova opens Quick Settings and taps the tile using its accessibility service, reading the tile's real state so "Wi-Fi on" when it's already on does nothing. Flashlight, volume, ringer, brightness, rotation and Do Not Disturb are changed directly when the permission is granted.

## Living alongside your other apps

Tuned for the OnePlus Nord CE 3 Lite 5G (CPH2465 / CPH2467, Android 13–15). Works on any Android 10+ phone.

- **Microphone:** Nova lets go of the mic during phone, WhatsApp and VoIP calls, and the moment any other app starts recording (WhatsApp voice notes, camera video, Gboard voice typing, screen recorder). It resumes a few seconds after they stop.
- **Sound:** other apps only get quieter for the few seconds you're giving a command. Nova's own player only takes over the audio when you ask it to play something.
- **Movies saying "Jarvis":** while music or video plays out loud, Nova asks for a clearer wake word, so a film's soundtrack doesn't set it off. Talking to the phone still works.
- **Screen:** Nova never draws on top of other apps. It only touches the screen when you give it a command.
- **Brightness:** setting brightness by voice has to turn off adaptive brightness. Nova says so when that happens.
- **Banking / UPI apps** may show a warning while any accessibility app is enabled. This is expected; those apps can hide their screens from Nova, and Nova never reads them.

## Limits (Android rules, not bugs)

- While listening, Android always shows the green **mic dot** and a permanent **notification**.
- After a reboot, Android 14+ won't let an app start the microphone from the background. Nova tries (also from its accessibility service), and if Android refuses it shows a **"Tap to start listening"** notification. One tap and it's back.
- If another app is using the mic (camera video, voice note), Nova waits until it's free.
- A Quick Settings tile must be in your Quick Settings panel for Nova to switch it.
- The app is about 98 MB, mostly the offline speech model. On first use it copies that model (about 55 MB) and the dictionary (about 15 MB) to storage.

## Train "Hey Nova"

"Hey Jarvis" ships ready-made. For "Hey Nova", train a custom model once, for free:

1. Open the openWakeWord training notebook in Google Colab. The link is in the *Training New Models* section of <https://github.com/dscripka/openWakeWord#training-new-models>.
2. Set the target phrase to `hey nova` and run all cells (about 1 hour on the free GPU). It generates synthetic voices, so you don't need recordings.
3. Download the resulting `.onnx` model, rename it to **`hey_nova.onnx`**, and put it in `app/src/main/assets/wakeword/`.
4. Rebuild and install. Nova picks it up automatically; enable it in **Settings → Wake words**.

Any other `*.onnx` openWakeWord model dropped into that folder becomes an extra wake word too.

## Building

Requirements: Android Studio (any recent version; it bundles the Java 21 that the build uses).

```bash
# Windows (Git Bash) – or just press Run in Android Studio
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew assembleDebug        # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # parser, name matching and wake-word tests
```

The wake-word test runs the real models on "Hey Jarvis" clips and ordinary sentences in `app/src/test/resources/audio` (made with Windows text-to-speech).

Only the `arm64-v8a` CPU type is built (every OnePlus and almost every phone from the last six years); the Android emulator on a PC isn't supported.

## Project layout

```
core/command   CommandParser, TextNormalizer, FuzzyMatcher   (pure Kotlin, unit tested)
core/wake      WakeWordEngine (openWakeWord on ONNX Runtime), WakeWords (asset loader)
core/speech    Mic, AndroidRecognizer, VoskRecognizer, Speaker (TTS)
core/action    ActionExecutor (does the commands), contacts/apps lookup
core/device    DeviceProfile (phone analysis behind the Device check screen)
services       NovaListenerService (24/7 loop), NovaAccessibilityService, BootReceiver, Notifications
ui             Home, Device check and Settings screens
```

## Music, search and dictionary details

- **Your songs:** Nova reads Android's media library, which already covers internal storage and the SD card, and plays songs in its own small player. It only reads song details (title, artist, album), never documents. Needs the **Music and audio** permission (Device check). Pause/next/headset buttons control it like any music app, and it shows a music notification with ⏮ ⏯ ⏭ that also appears on the **lock screen**. Voice controls ("pause", "play", "next song", "previous song", "stop") work locked or unlocked, for Nova's player and for other apps. The music stops if you turn off *Always listening*.
- **Search:** opens Google results in your chosen browser (*Settings → Browser*: default, Chrome or Brave) or in YouTube. Nova still has no internet permission of its own; the browser does the browsing.
- **Dictionary:** WordNet 3.0 (about 155,000 words) is built into the app, so meanings work offline. "Vilest" is looked up as "vile", "went" as "go". Unknown words fall back to a web search. Rebuild the database with `python tools/build_dictionary.py <wordnet folder>`.

## Licences

- WordNet 3.0: © Princeton University, WordNet licence (free to use and redistribute with the notice in `app/src/main/assets/dictionary/LICENSE-wordnet.txt`).

- openWakeWord code: Apache 2.0. The pre-trained wake-word models (including *hey_jarvis*) are **CC BY-NC-SA 4.0**: fine for personal use, not for selling the app.
- Vosk and its `vosk-model-small-en-in-0.4` model: Apache 2.0.
- ONNX Runtime: MIT.
