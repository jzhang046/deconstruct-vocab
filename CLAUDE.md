# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# Maintenance Rules
- This CLAUDE.md is a living document. After any major architectural change, refactor, or new convention, update the relevant sections immediately.
- When I say “update CLAUDE.md”, revise only the changed parts and keep the file concise.
- **Self-update on every commit**: after each `git commit`, review whether the commit changed architecture, conventions, or build/network/platform wiring; if so, update the relevant CLAUDE.md sections in the same change. A `PostToolUse` hook in `.claude/settings.json` injects this reminder after commits run inside Claude Code.

## Project Overview

**DeconstructChinese** — Kotlin Multiplatform Compose app for Chinese character translation and learning. Targets Android, iOS, Web (JS/WASM). Translates text via an OpenAI-compatible LLM provider (Qwen, with Gemini as automatic fallback), stores vocabulary locally with frequency tracking.

### Technology Stack

- **KMP**: Kotlin 2.3, Compose Multiplatform 1.10
- **Network**: Ktor Client 3.0 (OkHttp on Android, Darwin on iOS)
- **State**: ViewModel + StateFlow, Multiplatform Settings for persistence
- **Translation**: `TranslationService` interface over OpenAI-compatible chat/completions; `createTranslationService()` tries Qwen first, Gemini as fallback (see Network below). Doubao/OpenRouter adapters also present but unused.
- **Build**: Gradle 8.11 with version catalog (libs.versions.toml)
- **Audio**: Platform-specific TTS (Android `TextToSpeech`, iOS `AVSpeechSynthesizer`; web stub)
- **Speech Input**: Hold-to-record via `SpeechRecognizer` expect/actual (Android `android.speech`, iOS `SFSpeechRecognizer`)
- **API Keys**: `Secrets` expect/actual `defaultApiKeys: Map<String, String>` keyed by provider id, bundled at build time (see Key Design Decisions below).

### Target Platforms

| Platform | Min SDK | Target SDK | Details |
|----------|---------|------------|---------|
| Android | 29 | 36 | OkHttp client, Google Play Services on Android |
| iOS | 13+ | Arm64 + SimulatorArm64 | Darwin (native) HTTP client |
| Web | N/A | Modern browsers | JS and WASM targets (audio TTS not implemented) |
| Desktop | N/A | macOS/Windows/Linux (JVM 17) | `jvm("desktop")`; OkHttp client (streams SSE); TTS/speech stubbed |

## Build Commands

### Android
```bash
# Debug build + install
./gradlew :composeApp:assembleDebug
./gradlew :composeApp:installDebug

# Run on connected device/emulator
./gradlew :composeApp:installDebug

# Tests
./gradlew :composeApp:connectedAndroidTest

# Signed release bundle for Play Store (needs keystore.properties at repo root)
./gradlew :composeApp:bundleRelease   # -> composeApp/build/outputs/bundle/release/composeApp-release.aab
```

**Release signing**: `signingConfigs.release` reads `storeFile`/`storePassword`/`keyAlias`/`keyPassword` from `keystore.properties` (repo root, **gitignored**, alongside the `*.jks`). If absent, release builds are unsigned. Uses Play App Signing (upload key).

**versionCode**: auto-derived from the git commit count (`git rev-list --count HEAD` via config-cache-safe `providers.exec`) — every commit bumps it by 1, no manual edits. Release builds need full git history (not a shallow clone); don't squash already-released history or the count can regress below a code Play has accepted. `versionName` stays manual.

### iOS
Open `/iosApp` in Xcode and run via IDE (KMP bridging through framework in `composeApp/build/` after Gradle sync).

**Fresh-Mac prerequisites** (one-time): JDK 17 (`brew install openjdk@17`, then symlink into `/Library/Java/JavaVirtualMachines/` so Xcode's build phase can find it — a bare `openjdk` install is keg-only); full Xcode selected via `xcode-select -s /Applications/Xcode.app/Contents/Developer` (not just Command Line Tools) plus `sudo xcodebuild -license accept`; iOS Simulator runtime via `xcodebuild -downloadPlatform iOS`.

CLI equivalent of Xcode's Run button: `xcodebuild -scheme iosApp -sdk iphonesimulator -destination 'platform=iOS Simulator,name=<device>' build`, then `xcrun simctl install`/`launch` the `.app` from DerivedData.

### Web
```bash
# WASM (faster, modern browsers)
./gradlew :composeApp:wasmJsBrowserDevelopmentRun

# JS (slower, older browser support)
./gradlew :composeApp:jsBrowserDevelopmentRun
```

### Desktop (JVM)
```bash
# Run the app
./gradlew :composeApp:run

# Native app image (bundled JRE) -> build/compose/binaries/main/app/DeconstructChinese.app
./gradlew :composeApp:createDistributable

# Native installer (dmg/msi/deb for the current OS)
./gradlew :composeApp:packageDistributionForCurrentOS
```
Entry point: `desktopMain/.../main.kt` (`MainKt`). Bundled keys via `generateDesktopSecrets` (mirrors iOS/Android — see API Keys above).

### Common
```bash
# Shared code tests
./gradlew commonTest

# Full test suite
./gradlew test
```

## Architecture

### State Management (ViewModel Pattern)

**TranslatorViewModel** holds UI state, owns coroutine scope (viewModelScope):
- `translationState`: Current translation (Idle, Loading, Success, Error — sealed class)
- `inputText`: User input with 800ms debounce before translate
- `toEnglish`: Direction toggle
- `useSimplified`: Traditional vs simplified preference
- `savedVocabulary`: StateFlow from VocabularyStore
- `isPlaying`: Audio playback status
- `recordingPhase`: `RecordingPhase` enum (`Idle`/`Armed`/`Listening`) driven by `SpeechRecognizer.results` flow
- `snackbarMessage`: SharedFlow for speech errors (non-fatal, shown as snackbar)
- `onSharedText(String)`: entry point from `IncomingText` bus; auto-sets direction by detecting Han chars
- `startRecording()` / `stopRecording()`: wraps SpeechRecognizer; locale derived from `toEnglish` + `useSimplified`

`TranslationState` sealed class lives in `model/TranslationResult.kt` alongside `TranslationResult`, `VocabularyItem`, `Language`.

ViewModel created once per app lifecycle; state flows collected in Compose via plain `collectAsState()`. **Do not use `collectAsStateWithLifecycle()`** — it only collects while a `LifecycleOwner` is ≥ STARTED, which Compose Desktop's window lifecycle never reaches, so the UI silently stops observing updates there.

**TranslatorPopupViewModel** (`viewmodel/`) — stripped-down VM for the Android floating popup (see TranslatePopupActivity below). Single `translationState: StateFlow<TranslationState>`, fixed Chinese→English, `includeGrammarNote = false`, no audio/OCR/vocab/debounce. `translate(text)` early-exits on empty / non-Chinese / missing API key before calling the `TranslationService`.

### Data Layer

**VocabularyStore** (object singleton) — source of truth for saved words:
- Loads/saves from Multiplatform Settings (SharedPreferences on Android, UserDefaults on iOS, localStorage on Web)
- Sorts by frequency (highest first)
- `saveWord()`: Prevents duplicates via word match
- `bumpFrequency()`: Increments frequency when already-saved word appears in translation
- Exposes `savedVocabulary: StateFlow<List<VocabularyItem>>`

**AppSettings** — typed preferences wrapper:
- `useSimplified`: Boolean — traditional vs simplified preference
- `apiKey(provider: String)` / `setApiKey(provider, value)`: falls back to bundled `defaultApiKeys[provider]` when no user override is set
- Backed by Multiplatform Settings

**IncomingText** — `Channel<String>(CONFLATED)` bus for text handed in from outside the app (Android `ACTION_PROCESS_TEXT`/`SEND` intents, iOS share extension via URL scheme). `submitSharedText(text)` is exposed for Swift. `TranslatorRoute` collects `IncomingText.texts` and forwards to `viewModel.onSharedText()`.

**ChineseScriptConverter** (in `util/`) — character-level Simplified↔Traditional mapping (~400 pairs, OpenCC-derived). Unknown chars pass through. For Chinese→English, this is the **authoritative** normalization (`OpenAiCompatibleTranslator.parseResponse` calls it on the raw input); the LLM is no longer asked to also return a redundant normalized copy.

### Network

**TranslationService** (`network/`) — interface with two entry points: `translate(...) -> TranslationResult` (full JSON: translation + pinyin + vocab breakdown) and `translateStream(...) -> Flow<String>` (plain translation only, streamed token-by-token). Call sites (`TranslatorRoute`, `TranslatePopupActivity`) get their instance from `createTranslationService()`, never construct an adapter directly.

**AutoSwitchingTranslationService** (`network/`) — tries an ordered `Candidate(label, apiKey, service)` list, skipping blank keys and falling through on any thrown exception; sticks with whichever candidate last worked. `createTranslationService()` (`listOf(Qwen, Gemini)`) is the sole composition root and the only place provider identity is meaningful — `Secrets`/`AppSettings` below it just handle opaque provider-id strings.

**Two-phase translation (latency optimization)**: both ViewModels run a two-stage pipeline. **Stage 1** calls `translateStream` and emits `TranslationState.Success(result, vocabLoading = true)` as tokens arrive — the translation + whole-sentence pinyin paint immediately (Doubao-app-fast). The stream prompt asks for `<translation>|||<pinyin>` (delimiter `OpenAiCompatibleTranslator.STREAM_DELIMITER`); the base parses it into `PartialTranslation(translation, pinyin)` so the translation fills first, then pinyin. **Stage 2** calls `translate` for the full per-word breakdown and replaces it with `vocabLoading = false`. If stage 2 fails but stage 1 succeeded, the streamed result is kept (`vocabLoading = false`, no error). `TranslationResultCard` renders the raw Chinese with whole-sentence pinyin above it (no per-word segmentation) while `vocabulary` is empty, and shows a "Loading breakdown…" spinner under the VOCABULARY BREAKDOWN label.

**OpenAiCompatibleTranslator** — abstract base implementing `TranslationService` against any OpenAI-compatible chat/completions endpoint:
- Single shared `HttpClient` (lazy singleton) across all subclass instances — pooled connections; request/socket timeout 120s
- `translate`: builds the JSON request, conditionally includes a grammar-note instruction per `includeGrammarNote`
- `translateStream`: SSE streaming (`stream = true`), parses `data:` lines into `StreamChunk` deltas, accumulates and emits; tiny stage-1 system prompt (`STREAM_SYSTEM` — translation text only, no JSON/pinyin)
- `disableThinking` ctor flag → sends `thinking: {type: disabled}`. **Critical for latency**: Doubao's seed models are hybrid reasoning models that otherwise stream a chain-of-thought (`reasoning_content`) before the answer (~5x slower). Adapters hitting such a model set this true.
- `reasoningEffort` ctor flag → sends `reasoning_effort: <value>` (Gemini's equivalent of `disableThinking`). Gotcha: `"none"` 400s on Gemini 3.x though 2.5 accepted it — `"minimal"` is the lowest value 3.x accepts, and this has already changed once.
- `userPromptPrefix` ctor flag → prepends a token (e.g. `/no_think` for Qwen3) to the user message
- Strips markdown fences and parses with kotlinx.serialization (lenient)
- Platform HTTP engines injected via sourceSets (OkHttp/Darwin/Browser default)
- `translate()` results are memoized (mutex-guarded LRU, capacity 50, keyed on provider+text+direction+script+grammar-note) so repeat lookups skip the network; `translateStream()` isn't cached.

**Adapters**:
- `QwenService` — tried first. Model `qwen-plus`, endpoint `https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions` (Alibaba DashScope, international/Singapore). Not a hybrid reasoning model, no `disableThinking` needed.
- `GeminiService` — automatic fallback, chosen for its permanent (not trial) free rate-limited tier. Model `gemini-3.6-flash`, endpoint `https://generativelanguage.googleapis.com/v1beta/openai/chat/completions`, `reasoningEffort = "minimal"`. Google retires model IDs periodically (even "-latest" aliases) — bump `DEFAULT_MODEL` when it 404s; check https://ai.google.dev/gemini-api/docs/models.
- `DoubaoService` — present, unused. Model `seed-2-0-lite-260228`, endpoint `https://ark.ap-southeast.bytepluses.com/api/v3/chat/completions`, `disableThinking = true`.
- `OpenRouterService` — present, unused. Model `qwen/qwen3-14b`, endpoint `https://openrouter.ai/api/v1/chat/completions`, `userPromptPrefix = "/no_think"`.

**grammarNote**: `TranslationResult.grammarNote` (`model/TranslationResult.kt`) is populated when `includeGrammarNote = true` and rendered in `TranslationResultCard` when non-blank. The popup VM disables it.

**Error handling**: Network exceptions caught in ViewModel and mapped to user-friendly `TranslationState.Error` messages (auth, rate limit, connectivity, etc.).

### UI Layer

**App.kt** — thin wrapper: theme + `TranslatorRoute` with `apiKey` state from `AppSettings`.

**TranslatorRoute** (`ui/screens/`) — owns `TranslatorViewModel` (created once via `createTranslationService()`), wires snackbar host, `IncomingText` collector, image picker, the `SettingsDialog` (Chinese-script toggle), and the bottom-`NavigationBar` Scaffold across all platforms (Translate / Saved tabs).

**TranslateScreen** — Input, translation display, vocab actions:
- Debounced input (800ms delay before API call)
- Shows TranslationResult card with original, translation, pinyin, vocabulary breakdown
- Vocab cards show save/remove buttons and frequency badges

**VocabularyScreen** — Saved words list, frequency sorting

**Components** (`ui/components/`): TranslationResultCard, VocabularyCard, ErrorCard, InputPanel, MicButton, LanguageDirectionBar, ImageSourceDialog, SettingsDialog, SectionLabel.

### Platform-Specific (expect/actual)

**AudioPlayer** (`audio/`):
- `speak(text, language)`, `stop()`, `playListenCue()`, `release()`
- Android: `android.speech.tts.TextToSpeech`, initialized on first construct, locale set per `speak`; `playListenCue` synthesizes a single low "pop" (200Hz PCM, fast exponential decay) on a worker thread via `AudioTrack`
- iOS: `AVSpeechSynthesizer` (forces `zh-CN` voice, rate 0.45); `playListenCue` plays system sound 1113 (begin-record tone)
- Web: empty stub
- `playListenCue` fires from `TranslatorViewModel` when the recognizer arms (`RecordingPhase.Armed` on `SpeechResult.Ready`) to cue "Speak now"

**SpeechRecognizer** (`speech/`) — emits `SpeechResult` (`Ready` / `SpeechStarted` / `Partial` / `Final` / `Cancelled` / `Error`); ViewModel maps these to `RecordingPhase` transitions.



**AppContext** (Android, in `audio/` package): holds `applicationContext`; set from `MainActivity.onCreate`. Required because `AudioPlayer`/recognizers are constructed from common code.

**Android entry points**:
- `MainActivity` — `singleTop` launcher (MAIN/LAUNCHER only); receives shared text via `SEND` and forwards to `IncomingText`.
- `TranslatePopupActivity` (androidMain) — handles `PROCESS_TEXT` in a translucent floating dialog (`singleTask`, `excludeFromRecents`, Popup theme, intent-filter `priority=100`). Drives `TranslatorPopupViewModel`, reuses the shared `TranslationResultCard`/`ErrorCard` (card actions mapped to "Open in App", which bridges to `MainActivity`). PROCESS_TEXT now lives here, not on `MainActivity`.

**`webMain` sourceSet** — intermediate parent of `jsMain` + `wasmJsMain` (wired via the default hierarchy template + matching `src/webMain` directory). Hosts no-op stubs for AudioPlayer, SpeechRecognizer, plus `isWebPlatform = true`.

**`desktopMain` sourceSet** (`jvm("desktop")`) — Compose Desktop entry `main.kt`, OkHttp HTTP client (`ktor-client-okhttp` — the Java engine doesn't stream SSE, breaking the two-phase stage-1 flow), `kotlinx-coroutines-swing`. Actuals: `AudioPlayer` (speak/stop no-op, `playListenCue` = AWT beep), `SpeechRecognizer` (unsupported — emits `Error`), `Platform` (`isWebPlatform = false`), `Secrets` (`defaultApiKeys` from `generateDesktopSecrets`). Mic button still renders on desktop but recording is a no-op.

## Key Design Decisions

1. **ViewModel in common**: AndroidX ViewModel is multiplatform-compatible (via lifecycle-viewmodel-compose); used in all platforms for consistency.

2. **Frequency tracking cross-script**: Vocabulary items matched by word OR (phonetic + meaning) to handle traditional/simplified variants with shared frequency.

3. **No manual JSON**: kotlinx.serialization with `@Serializable` on all data classes; Ktor handles JSON automatically.

4. **No exceptions for expected failures**: Translation/network errors are modeled as `TranslationState.Error` sealed class variant, not thrown.

5. **Multiplatform Settings over platform-specific**: Unified persistence API; serialization plugin for complex types (List<VocabularyItem>).

6. **No provider is structurally privileged**: Android/iOS/Desktop all get keys from one shared `registerGenerateSecretsTask()` (`build.gradle.kts`) writing a `Map<String, String>` keyed by provider id (from `local.properties`/env vars); Web gets an empty map (public JS bundle). Adding a provider means one new id in `apiKeyProviders`, one new adapter, one new `Candidate` in `createTranslationService()` — nothing else changes. `AppSettings.setApiKey(provider, value)` supports a user override, no UI yet.

## Common Workflows

### Adding a new user preference
1. Add field to `AppSettings` with getter/setter
2. Expose in ViewModel as `StateFlow`
3. Collect in UI and pass to composables
4. Preference persists automatically via Multiplatform Settings

### Fixing a translation issue
1. Check prompt/request logic in `OpenAiCompatibleTranslator` (shared) and the active adapter (`QwenService`)
2. Verify `TranslationResult` data class matches API response
3. Add error case to `ViewModel.translate()` catch block if needed
4. Test via Android debug build (fastest iteration)

### Adding platform-specific feature (e.g., iOS-only gesture)
1. Create `expect` interface in commonMain
2. Add `actual` in iosMain with native API calls
3. Use from common code (no conditional imports)

## Dependencies

- **Compose**: Material3 with extended icons
- **HTTP**: Ktor client (platform engines auto-selected)
- **Serialization**: kotlinx.serialization (JSON)
- **Coroutines**: kotlinx.coroutines (Main dispatcher implicit in ViewModel)
- **Persistence**: Multiplatform Settings (with serialization plugin)
- **Testing**: JUnit 4, Espresso (minimal test suite currently)

Add new deps to `libs.versions.toml` version catalog only; do not hardcode versions in build.gradle.kts.

## Notes

- **No strict null safety for API responses**: `OpenAiCompatibleTranslator` uses lenient JSON parsing; malformed responses logged but non-fatal. Markdown fences (```json``` / ``` ```) are stripped before parsing.
- **Audio resource cleanup**: `TranslatorViewModel.onCleared()` releases `AudioPlayer` and `SpeechRecognizer`.
- **iOS framework**: Gradle builds framework binary to `composeApp/build/XCFramework/` after Kotlin compilation; Xcode links it.
- **Web audio stub**: TTS not implemented for JS/WASM; UI gracefully hides audio buttons on web.
- **Shared text entry**: Android `PROCESS_TEXT` is handled by `TranslatePopupActivity` (floating popup), while `SEND` shares go to `MainActivity` → `IncomingText`. iOS share extension is currently reverted (see commit `fd42978`); `submitSharedText()` remains in commonMain for re-introduction.
- **Android-only permissions** (`AndroidManifest.xml`): `INTERNET`, `RECORD_AUDIO`. `MainActivity` is `singleTop` (launcher); `TranslatePopupActivity` is `singleTask` + `excludeFromRecents` for the PROCESS_TEXT popup.
