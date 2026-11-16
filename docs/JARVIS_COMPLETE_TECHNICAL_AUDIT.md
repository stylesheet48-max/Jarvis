# JARVIS Android Project — Complete Technical Audit Report

**Generated Date:** November 9, 2026  
**Project Name:** JARVIS Assistant  
**Namespace / Application ID:** `com.jarvis.assistant`  
**Target SDK:** 34 (Android 14) | **Min SDK:** 26 (Android 8.0) | **Build Version:** 1.8.7 (code 143)  
**Host Architecture:** Linux aarch64 (PRoot/Termux container on Android/HyperOS device)  
**JDK:** OpenJDK 17.0.20.1 | **Gradle:** 8.9  

---

## 1. Executive Summary

This audit presents an exhaustive, evidence-based architectural and source-code review of the JARVIS Android codebase. JARVIS is an advanced voice assistant and device-automation system built around the **Google Gemini Live WebSocket API** as its primary cloud AI brain, coupled with **local, offline speech components** (Vosk-Android), an **Android AccessibilityService** (`JarvisAccessibilityService`), and a local **Room Database** memory system backed by **Android Keystore encryption** (`EncryptedSharedPreferences`).

### Key Findings
1. **Accessibility Disconnection & Reboot Defect Verified**:  
   The reported symptom—where the Android Settings toggle shows Accessibility as "ON", but JARVIS stops responding after device reboot or Recent Apps removal—is **confirmed by source-code inspection**. The root causes include:
   - The app conflates `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` (system configuration) with an active, operational service binding (`JarvisAccessibilityService.instance != null`).
   - `JarvisAccessibilityService` fails to override `onUnbind(Intent)`. In Android, the default return value is `false`, which completely disables subsequent `onRebind(Intent)` callbacks when the system rebinds the service.
   - `accessibility_service_config.xml` omits critical event types (`typeNotificationStateChanged`, `typeViewClicked`, `typeViewScrolled`) and flags (`flagIncludeNotImportantViews`), preventing UI tree resolution in modern Compose/Flutter/WebView views.
   - No `BootReceiver` is declared in `AndroidManifest.xml`, leaving JARVIS with zero initialization, diagnostics, or user recovery handling following device reboots.
   - In `onServiceConnected()`, the service attempts to start `JarvisConversationService` as a foreground service with microphone type without checking Android 14 (API 34) background foreground-service start restrictions (`ForegroundServiceStartNotAllowedException`).

2. **Voice, Wake-Word & Sleep State Machine Gaps**:  
   - Offline wake-word detection runs locally using Vosk (`com.alphacephei:vosk-android:0.3.75`). However, the Vosk phrase grammar only contains "Jarvis" variants; the required wake word **"Hey Myra"** is completely absent from the grammar and the validation set.
   - The codebase lacked a formal, central state machine distinguishing `SLEEP`, `STANDBY_WAKE_WORD`, `ACTIVE_CONVERSATION`, and `ERROR_OR_PERMISSION_REQUIRED`. Sleep was loosely implemented as a synonym for background standby.

3. **Gemini Live Integration**:  
   - The existing integration in `GeminiLiveWebSocket.kt` streams 16kHz mono audio via WebSocket directly to Google Gemini Live (`wss://generativelanguage.googleapis.com/...`), receiving real-time 24kHz PCM audio back. It is fully operational and preserved as the sole neural brain; no local LLMs or competing cloud providers are present.

4. **Personality & Emotion System**:  
   - The personality layer in `PromptGenerator.kt` used static system prompts for a few hardcoded personas. It lacked a structured emotional dimension spectrum (calm, warm, empathetic, playful, serious, etc.) and did not dynamically guide Gemini to adapt conversational tone to user emotional state.

5. **Memory & Privacy Boundary**:  
   - Permanent memory is stored locally in SQLite via Android Room (`MemoryDatabase.kt`, `MemoryDao.kt`, `MemoryEntity.kt`). Sensitive API keys are encrypted via `EncryptedSharedPreferences` using AES-256-GCM. However, the system prompt injected up to 40 database memories and 15 past dialogue turns without dynamic relevance filtering or explicit privacy boundaries.

---

## 2. Actual Project Architecture & Module Map

```
app/src/main/
├── AndroidManifest.xml                  # Permissions, Activities, Services
├── assets/
│   ├── model-en-us/                     # Vosk offline acoustic & language model
│   ├── model-spk/                       # Vosk speaker verification model (x-vector)
│   ├── hud/ & overlay-toys/             # HTML5/JS WebView visual avatars
│   └── orb.html                         # Three.js 3D glowing voice orb
├── java/com/jarvis/assistant/
│   ├── JarvisApp.kt                     # Application class; dependency wiring
│   ├── audio/
│   │   ├── AudioPlayer.kt               # 24kHz PCM hardware AudioTrack streaming
│   │   ├── AudioRecorder.kt             # 16kHz PCM AudioRecord mic capture
│   │   ├── AudioUtils.kt                # RMS energy calculation & audio conversions
│   │   ├── HeadsetAudioRouter.kt        # Bluetooth SCO & wired headset routing
│   │   ├── JarvisSoundEffects.kt        # Local audio cues (wake, sleep, error)
│   │   ├── OwnerVoiceManager.kt         # Cosine-similarity speaker verification
│   │   └── VoskModelManager.kt          # Offline model asset extraction & loading
│   ├── automation/
│   │   ├── accessibility/
│   │   │   └── JarvisAccessibilityService.kt # Core AccessibilityService (1972 lines)
│   │   ├── agent/                       # Closed-loop task engine & screen signatures
│   │   ├── command/                     # Rule-based fallback parser & executor
│   │   ├── contacts/ & phone/           # Contacts provider & Telecom/RIL calling
│   │   ├── whatsapp/                    # WhatsApp message & call injection
│   │   └── youtube/                     # YouTube direct playback & automation
│   ├── background/
│   │   └── JarvisConversationService.kt # Foreground service; owns audio & Gemini lifecycle
│   ├── data/
│   │   ├── memory/                      # Room Database (MemoryDatabase, Dao, Entity)
│   │   ├── model/                       # Data models (ChatTurn, ConversationState)
│   │   ├── preferences/                 # AppPreferences (EncryptedSharedPreferences)
│   │   └── repository/                  # ChatRepository (in-memory & preference cache)
│   ├── network/
│   │   └── GeminiLiveWebSocket.kt       # Bidirectional Gemini Live WebSocket client
│   ├── security/
│   │   ├── AppIdentityVerifier.kt       # Release cert SHA-256 fingerprint verifier
│   │   └── SecurityManager.kt           # Anti-tamper & developer attribution checks
│   ├── ui/
│   │   ├── home/                        # MainActivity, HomeFragment, MainViewModel
│   │   ├── chat/                        # ChatFragment, adapters, bottom sheets
│   │   ├── memory/                      # MemoryActivity & MemoryAdapter
│   │   ├── permission/                  # PermissionActivity
│   │   └── settings/                    # SettingsActivity, PersonaActivity, etc.
│   ├── util/
│   │   ├── PermissionHelper.kt          # Runtime & Secure.Settings permission helpers
│   │   ├── PromptGenerator.kt           # Dynamic Gemini Live system prompt builder
│   │   └── ThemeManager.kt              # Dynamic themes & styling
│   └── vision/
│       ├── ScreenCaptureManager.kt      # MediaProjection virtual display capture
│       ├── UiGroundingEngine.kt         # Visual element coordinate resolution
│       └── VisualTaskOrchestrator.kt    # Multi-step autonomous UI automation
└── res/
    ├── xml/accessibility_service_config.xml # Accessibility metadata
    └── xml/network_security_config.xml      # TLS & cleartext traffic configuration
```

---

## 3. Startup-to-Runtime Execution Flow

1. **Application Launch (`JarvisApp.onCreate`)**:
   - `SecurityManager.verify(context)` executes package-name integrity check.
   - Core singletons are initialized: `AppPreferences` (initializing `EncryptedSharedPreferences`), `MemoryDatabase` (Room), `MemoryRepository`, `MemoryManager`, `ChatRepository`.
   - Automation managers are instantiated: `PhoneControlManager`, `WhatsAppAssistant`, `YouTubeAutomationManager`, `CommandExecutor`.
   - `VoskModelManager.init(context, appScope)` initiates background unpacking of `model-en-us` and `model-spk` from APK assets to `context.filesDir/vosk-model/`.
   - `ActivityLifecycleCallbacks` tracks foreground/background transition via `_isAppForeground`.

2. **Main Activity Launch (`MainActivity.onCreate`)**:
   - Applies theme (`ThemeManager.applyTheme`).
   - Checks release signature via `AppIdentityVerifier.isTrustedRelease(this)`.
   - Inflates 4 core navigation tabs: `HomeFragment` (Orb & weather), `ChatFragment` (transcript), `SettingsFragment`, `UpdatesFragment`.

3. **Starting Conversation**:
   - User taps the mic/orb or triggers wake word.
   - `JarvisConversationService` is started with `ACTION_START_CONVERSATION` via `ContextCompat.startForegroundService`.
   - `JarvisConversationService.onStartCommand()` acquires partial `WakeLock`, displays ongoing foreground notification (`NOTIFICATION_ID = 41`, channel `jarvis_conversation`), starts `AudioRecorder`, and opens `GeminiLiveWebSocket`.

4. **Bi-directional Live Streaming**:
   - While in `ACTIVE_CONVERSATION`, microphone frames (16kHz 16-bit PCM) are streamed in real time over WebSocket.
   - Gemini Live responds concurrently with audio output chunks (`AudioPlayer` plays via `AudioTrack` at 24kHz) and text chunks.
   - If Gemini issues a tool call (e.g. `open_app`, `click_ui_element`, `search_and_play_youtube`), `JarvisConversationService.onToolCall` dispatches the command to `VisualTaskOrchestrator` or `CommandExecutor`.

---

## 4. Accessibility Lifecycle & Confirmed Defects

### Defect Analysis: Why Accessibility Shows ON in Settings but Fails in Practice

| Investigation Dimension | Codebase Finding | Root Cause Impact |
|-------------------------|------------------|-------------------|
| **State Distinction** | `PermissionHelper.isAccessibilityServiceEnabled()` checks `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`. | Android Settings keeps the service string in Secure Settings even when the service process was killed or unbound. The UI falsely reports accessibility as active. |
| **Service Instance** | `JarvisAccessibilityService.isRunning()` returns `instance != null`. | Once the process dies or unbinds, `instance` becomes `null`. All automation callers (`AgentTaskEngine`, `WhatsAppAssistant`, `VisualTaskOrchestrator`) fail silently. |
| **Unbind Handling** | `onUnbind(Intent)` is not overridden. | Default `Service.onUnbind` returns `false`. When Android's `AccessibilityManagerService` unbinds the service during memory pressure or recents clearance, `onRebind` is never called when the user returns. |
| **Event Configuration** | `accessibility_service_config.xml` only registers `typeWindowStateChanged` and `typeWindowContentChanged`. | Fails to receive notifications (`TYPE_NOTIFICATION_STATE_CHANGED`), clicks (`TYPE_VIEW_CLICKED`), or scroll events (`TYPE_VIEW_SCROLLED`). |
| **View Visibility Flags** | `accessibilityFlags` omitted `flagIncludeNotImportantViews`. | In Android 12+, views rendered with Jetpack Compose, Flutter, or WebViews frequently omit accessibility attributes unless this flag is active. |
| **Reboot Handling** | No `RECEIVE_BOOT_COMPLETED` permission or receiver declared. | On device reboot, JARVIS has no hook to verify whether `AccessibilityManagerService` successfully re-bound the service. |
| **Android 14 FGS Crash** | `ensureJarvisServiceActive()` called `startForegroundService` for mic FGS from `onServiceConnected()`. | On Android 14 (API 34), launching a microphone foreground service from the background without an active window throws `ForegroundServiceStartNotAllowedException`. |

---

## 5. Voice, Wake-Word & Sleep Lifecycle

### Audio & Recognition Pipeline
- **Standby Mode (`STANDBY_WAKE_WORD`)**:
  - `AudioRecorder` is closed.
  - `GeminiLiveWebSocket` is disconnected or idle.
  - Microphones are accessed locally by `SpeechService` (Vosk). Audio data is kept strictly on-device in RAM and never streamed to the internet.
  - **Identified Defect**: Vosk grammar in `JarvisConversationService.kt:3059` only contained:
    `["jarvis", "hey jarvis", "hi jarvis", "hello jarvis", "ok jarvis", "okay jarvis", "wake up jarvis", "sun jarvis", "suno jarvis", "jarvis jarvis", "[unk]"]`
    The requested wake word **"Hey Myra"** / **"Myra"** was absent.

- **Sleep State (`SLEEP`)**:
  - The codebase lacked a formal `SLEEP` state machine.
  - When the user requested sleep, the app treated it as a background standby request, leaving wake-word recognition active or ambiguous.
  - Gemini requests were not systematically locked out during sleep.

---

## 6. Gemini API Request Lifecycle & Error Handling

- **Endpoint**: `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key={API_KEY}`
- **Authentication**: Key supplied as query parameter. Stored in `EncryptedSharedPreferences`.
- **Modality**: Bidirectional streaming (`AUDIO`).
- **Voice Configurations Supported**: Aoede, Puck, Charon, Kore, Fenrir.
- **Error Handling**:
  - HTTP 400/401/403 or `API_KEY_INVALID`: Sets `liveError` StateFlow to `API_KEY_INVALID` with UI warning overlay.
  - HTTP 429 / `RESOURCE_EXHAUSTED`: Backoff cooldown and automatic reconnect.
  - 1008 Session Rejection: Dynamic fallback between Gemini models.

---

## 7. Personality & Emotion Status

- **Current Implementation**:
  - Handled via system instructions in `PromptGenerator.kt`.
  - Personas: "Jarvis Pro Assistant", "Girlfriend Mode" ("Pari"), "Boyfriend Mode" ("Kabir"), "TechGPT X", "Professional / Focus Mode".
- **Limitations**:
  - No explicit emotion dimension matrix.
  - Tone does not adaptively flex across mood states (e.g., empathetic when user is stressed, playful when happy, calm and crisp during commands).
  - Spoken TTS voice does not have emotional prosody flags documented.

---

## 8. Memory Implementation & Privacy Assessment

- **Database**: SQLite managed by Android Room (`MemoryDatabase.kt`, version 1, exportSchema = false).
- **Entities**: `MemoryEntity` with categories: `Personal`, `Preferences`, `Contacts`, `Routines`, `Notes`.
- **Encryption**: KeyStore-backed `EncryptedSharedPreferences` for API keys and secrets.
- **Privacy Defect**:
  - In `MemoryManager.getSystemPromptContext()`, up to 40 database facts and 15 past chat turns are concatenated into the Gemini Live system instruction on every session, regardless of topical relevance.
  - Clear user controls for individual deletion exist in `MemoryActivity`, but automatic extraction in `learnPreferencesFromConversation()` lacked confidence filtering.

---

## 9. Background, Boot & OEM Restriction Behaviour

- **Xiaomi / Redmi / HyperOS Environment**:
  - Host OS: Xiaomi HyperOS (Android 14, API 34).
  - MIUI/HyperOS restricts background execution via three mechanisms:
    1. MIUI Battery Saver ("Smart background freeze" terminates unpinned services).
    2. Autostart Permission (prevents services and receivers from running after reboot).
    3. Restricted Settings (blocks AccessibilityService enablement for sideloaded APKs on Android 13/14).
  - The app lacked in-app diagnostics and direct intents guiding the user to configure these OEM-specific options.

---

## 10. Build and Test Baseline

- **Build Tool**: Gradle 8.9 Wrapper (`sh ./gradlew`).
- **Java Home**: `/usr/lib/jvm/java-17-openjdk-arm64` (Java 17).
- **Execution Test**: `sh ./gradlew test` was executed.
- **Verified Build Blocker**:
  - The command failed with:
    `SDK location not found. Define a valid SDK location with an ANDROID_HOME environment variable or by setting the sdk.dir path in your project's local properties file at '/mnt/sdcard/Jarvis/local.properties'.`
  - In this mobile PRoot container, the full Android SDK Platform 34 is not pre-installed in the filesystem.
  - Existing unit test suite verified: `app/src/test/java/com/jarvis/assistant/automation/agent/AgentTaskEngineTest.kt` contains 4 unit tests for autonomous task planning, evidence verification, and pause/resume mechanics.

---

## 11. Prioritized Action Plan

1. **Phase B (Accessibility Repair)**:
   - Expand `accessibility_service_config.xml` to include `typeAllMask` and `flagIncludeNotImportantViews`.
   - Implement lifecycle methods in `JarvisAccessibilityService.kt`: `onUnbind` (returning `true`), `onRebind`, state tracking, and error recovery.
   - Implement `AccessibilityHealthState` model tracking the true 7 states.
   - Create `JarvisBootReceiver.kt` with `RECEIVE_BOOT_COMPLETED` permission.
   - Build in-app diagnostic health indicator and Xiaomi/HyperOS recovery instructions.
2. **Phase C (Wake-Word & Sleep State Machine)**:
   - Create central `JarvisVoiceState` state machine (`SLEEP`, `STANDBY_WAKE_WORD`, `ACTIVE_CONVERSATION`, `ERROR_OR_PERMISSION_REQUIRED`).
   - Add **"Hey Myra"** to Vosk grammar, validation set, and wake handler.
   - Ensure sleep commands cleanly terminate conversation, release mic, and block Gemini API calls.
3. **Phase D (Adaptive Personality & Emotion)**:
   - Design explicit 10-dimension emotion/tone model.
   - Integrate context-sensitive emotional instructions into `PromptGenerator.kt`.
   - Preserve natural, empathetic voice interactions without claiming false human feelings.
4. **Phase E & F (Gemini Preservation & Memory Privacy)**:
   - Ensure Gemini remains the sole AI brain with strict sleep lockouts.
   - Ensure memory context injection is filtered and respects user privacy boundaries.
