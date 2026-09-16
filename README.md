# TraductorAndroid

Android translation prototype focused on practical multilingual communication through text and voice.

The project was developed as a functional Social Service prototype and as a portfolio project. Its current goal is to provide a compact translation flow for **Spanish, English, and French**, with local/on-device processing whenever the platform and installed models allow it.

## Current features

- Text translation between Spanish, English, and French.
- Press-and-hold voice input.
- Android `SpeechRecognizer` as the preferred recognition engine when suitable local recognition is available.
- Vosk as an alternative/fallback recognition engine.
- Bundled lightweight Vosk models for Spanish and English.
- Downloadable lightweight French Vosk model.
- **Detect** mode for automatic source-language identification.
- Automatic language detection for typed and pasted text.
- Voice detection flow that evaluates captured audio against supported recognition candidates.
- Text-to-Speech playback for translated text.
- Automatic TTS after voice translation, with manual playback available afterward.
- Copy, paste, clear, edit, language swap, and explicit edit-confirmation controls.
- Recent source/destination language history.
- Persistent language selection and Detect-mode state.
- Wi-Fi/mobile-data aware model downloads, including confirmation before using mobile data.
- Localized interface resources for Spanish, English, and French.

## Supported languages

| Language | Text translation | Voice recognition | TTS | Vosk model |
| --- | --- | --- | --- | --- |
| Spanish | Yes | Yes | Yes | Bundled |
| English | Yes | Yes | Yes | Bundled |
| French | Yes | Yes | Yes | Downloadable |

## Recognition architecture

TraductorAndroid separates the concept of a **language** from the **recognition engine** used to process speech.

The application can resolve between Android's local speech-recognition capabilities and Vosk depending on platform support and model availability. This keeps language selection independent from the recognition implementation and makes the fallback path explicit.

Important recognition-related classes include:

- `Idioma.kt` — language metadata used by recognition, ML Kit, and TTS.
- `MotorReconocimiento.kt` — recognition-engine abstractions and preferences.
- `ResolverMotorReconocimiento.kt` — selects an appropriate recognition engine.
- `GestorReconocimientoAndroid.kt` — checks Android recognition availability/support.
- `FuenteAudioReconocimientoAndroid.kt` — captures PCM audio for controlled recognition flows.
- `AnalizadorAudioReconocimientoAndroid.kt` — analyzes captured audio through Android recognition.
- `ModeloVosk.kt` — Vosk model catalog and installation metadata.

## Translation and language detection

Text translation uses **Google ML Kit Translation**.

Automatic text-language identification uses **Google ML Kit Language Identification**. In Detect mode, typed or pasted text is identified first and then translated according to the detected source language and the selected destination language.

## Voice workflow

The normal voice flow is designed around a press-and-hold microphone interaction:

1. Hold the microphone button.
2. Speak.
3. Release the button.
4. The app recognizes the text.
5. The text is translated.
6. The translated result is spoken automatically when TTS is available.

Detect mode keeps the source language automatic until the user explicitly chooses a manual source language.

## Editing workflow

The source text can be edited directly.

While editing:

- central/bottom controls are hidden to recover screen space,
- translation is automatically requested after a short inactivity delay,
- the source text is preserved when source or destination language changes,
- a check-mark control explicitly exits editing mode.

This avoids depending on a specific keyboard implementation exposing a `Done` action.

## Persistence

The app persists language-related UI state locally, including:

- source language,
- destination language,
- recent source languages,
- recent destination languages,
- Detect-mode state.

## Technology stack

- **Kotlin** for Android UI and application/platform orchestration.
- **C++17 / JNI** retained from the native Android project foundation.
- **Android View Binding**.
- **Android SpeechRecognizer**.
- **Android TextToSpeech**.
- **Vosk Android** for offline speech recognition fallback.
- **Google ML Kit Translation**.
- **Google ML Kit Language Identification**.
- **Gradle Kotlin DSL**.
- **CMake 3.22.1** for the native module.

Current Android configuration:

- Minimum SDK: **24** (Android 7.0)
- Target SDK: **37**
- Compile SDK: **37**
- Java compatibility: **11**
- Native C++ standard: **C++17**

## Project structure

```text
app/src/main/
├── assets/                         # Bundled Vosk models
├── cpp/                            # C++ / JNI native layer
├── java/com/example/traductorandroid/
│   ├── MainActivity.kt
│   ├── Idioma.kt
│   ├── ModeloVosk.kt
│   ├── MotorReconocimiento.kt
│   ├── ResolverMotorReconocimiento.kt
│   ├── GestorReconocimientoAndroid.kt
│   ├── FuenteAudioReconocimientoAndroid.kt
│   └── AnalizadorAudioReconocimientoAndroid.kt
└── res/
    ├── layout/
    ├── drawable/
    ├── values/
    ├── values-en/
    └── values-fr/
```

## Build

### Requirements

- Android Studio with a compatible Android SDK installation.
- JDK compatible with Java 11 source/target settings.
- Android NDK/CMake support for the native module.

### Clone

```bash
git clone https://github.com/SimDynamics/TraductorAndroid.git
cd TraductorAndroid
```

Open the project in Android Studio and allow Gradle to synchronize dependencies.

Spanish and English lightweight Vosk models are already included under `app/src/main/assets`. The French Vosk model is handled as a downloadable model by the application.

Google ML Kit translation models may require a network connection the first time a language pair is prepared on a device.

## Tested prototype

The current prototype has been tested primarily on a physical **Honor X8a running Android 14**.

The project keeps compatibility paths for older Android versions through its recognition-engine resolver and Vosk fallback architecture, although behavior can vary according to the speech services and models available on each device.

## Current scope and limitations

This repository represents a **functional prototype**, not a finished commercial translation product.

Current limitations include:

- Supported application languages are currently limited to Spanish, English, and French.
- Recognition quality depends on the selected engine, device speech services, microphone conditions, and installed models.
- Initial ML Kit model preparation can require connectivity.
- The French Vosk model must be downloaded before that fallback model is available locally.
- The `Options` control is intentionally reserved for future functionality.
- Automated test coverage is currently limited.
- The application has not been published to the Google Play Store.

## Social Service project context

The prototype was designed as a low-cost, measurable Social Service project focused on reducing language barriers for situations such as communication with visitors, tourists, and users who benefit from a simple voice-first interface.

The software is intentionally being kept stable while the remaining Social Service units focus on project execution, evidence, measurement, evaluation, and final reporting rather than continuously adding features.

## Third-party components and models

This project uses third-party libraries and speech models that retain their respective licenses and terms.

Notably, the repository currently includes the Vosk lightweight models:

- `vosk-model-small-en-us-0.15`
- `vosk-model-small-es-0.42`

The French fallback model configured by the application is:

- `vosk-model-small-fr-0.22`

Vosk models and Vosk Android are provided by the Vosk/Alpha Cephei project. Google ML Kit components are provided by Google.

A project-wide source-code license has not yet been declared for TraductorAndroid.

## Status

**Functional prototype / feature freeze for Social Service evaluation and portfolio use.**

Future changes should prioritize bug fixes, compatibility improvements, testing, documentation, and evidence collection over feature expansion.
