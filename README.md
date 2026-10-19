**English** · [Deutsch](README.de.md) · [Русский](README.ru.md)

# Voice-to-Messenger

Speak — and get a ready-to-send message. The app turns spontaneous speech into clean,
ready-to-send text (choice of tone, your own style learned from previous messages, emojis,
list formatting, optional translation) in 2–3 taps:
dictate → **Copy / Share / Save as note**.

The main pain point it solves: no more typing long messages by hand or sending voice notes,
and no more fighting with ChatGPT, which adds unnecessary phrases to its output.

## Structure

- **`backend/`** — a thin Flask proxy: it holds the API keys, applies the system prompt and styles,
  and is managed through a web panel. LLM providers (OpenAI-compatible and Anthropic)
  can be switched without code changes; each has its own text-processing model and its own
  speech recognition model, and the panel suggests curated options with prices — each
  list is verified with the very call it is offered for, so that a choice in the panel
  never turns into a provider error.
  Speech recognition runs on the device or via server-side Whisper (`/api/transcribe`).
  Every request to a provider is written to a usage log: how many tokens and seconds of audio
  it cost (for now this is monitoring only, nothing is billed).
- **`android/`** — a native app (Kotlin + Jetpack Compose): dictation screen,
  live translation, notes, settings and a floating bubble on top of messengers. Screens are
  switched via a bottom bar shared by the whole app. The interface is available in Russian,
  German and English. A single dictation is limited to five minutes: when the limit is
  reached, recording stops automatically and asks whether to send or delete it.
  Plus a hidden admin panel: the same backend settings can be edited right from the phone,
  a usage tab, and a games section (2048, Schulte tables, Stroop test) to keep your hands
  busy while the model is thinking.

## Live translation

A separate screen: the microphone listens to the person you are talking to and translates into the chosen language — as text
on the screen and as voice in your earphone. The speaker's language is detected by the model and is not set
manually; you only choose the language to translate **into** (Russian, German,
English, Italian, Chinese).

It works in two modes, switched in the admin panel with one button (by choosing the recognition model):

```
three models: microphone (PCM 16 kHz) → WebSocket /api/live → Voxtral Realtime (recognition,
    detects the language itself) → text chunks → LLM translation of each phrase → Voxtral TTS → earphone

one model: microphone → /api/live → gpt-realtime-translate (hears, translates and speaks
    by itself) → text feed and continuous audio in the earphone
```

Three models are cheaper and show the original in the feed; one model is faster and translates more coherently,
but is billed per minute while the session is open.

What keeps costs down: silence is not sent to the model (a volume gate on the
device), the session closes automatically after the limit set in the admin panel (10 minutes
by default), and if people speak in the target language, translation is not called at all. Usage
is written to the same log as three lines per session: `live_stt`, `live_translate`, `live_tts`.

Limitation: translation runs only while the screen is open. In the background the system takes the microphone
away from the app, so the screen stays on during a session.

## Running locally

### 1. Backend

Requires Python 3.11+ and an API key of at least one LLM provider (OpenAI, Mistral, Anthropic or any OpenAI-compatible one).

```bash
git clone https://github.com/vikmi1/voice-to-messenger.git
cd voice-to-messenger/backend
python -m venv venv
venv\Scripts\activate            # Windows; on Linux/macOS: source venv/bin/activate
pip install -r requirements.txt
copy .env.example .env           # cp on Linux/macOS
python app.py
```

In `.env` set `ADMIN_PASS` and `SECRET_KEY`; provider keys can go there too (`OPENAI_API_KEY`, `MISTRAL_API_KEY`, `ANTHROPIC_API_KEY`) or be entered later in the admin panel.

The server starts on `http://localhost:8000`. Open `http://localhost:8000/admin`, log in with `ADMIN_USER` / `ADMIN_PASS` from `.env`, choose the active provider and models on the **Провайдеры** (Providers) tab, and copy the API token from the **Приложение** (App) tab — the Android app needs it.

### 2. Android app

1. Install [Android Studio](https://developer.android.com/studio) — it installs the Android SDK and Gradle.
2. `File → Open` → select the `android/` folder (not the repository root) and wait for Gradle Sync.
   `gradle-wrapper.jar` is not stored in the repository, so the first sync must happen in Android Studio.
3. Connect a phone with USB debugging enabled (or start an emulator) and press ▶.
4. In the app open **Settings** and fill in:
   - **Backend address** — `http://10.0.2.2:8000` for an emulator with a local backend, or your server's address;
   - **API token** — from the **Приложение** (App) tab of the admin panel (the panel is in Russian).

   The **Check connection** button calls `/api/options` and loads the current styles and languages.

Unit tests of the app (local text cleanup, game logic): `cd android && ./gradlew test`.

More details (in Russian): [backend/README.md](backend/README.md), [android/README.md](android/README.md), server deployment — [deploy/README.md](deploy/README.md).

## Architecture

```
[Android] microphone → ASR (on device / server-side Whisper) → text
        → "Local / Server" toggle → ready text → Copy / Share / Note
        ↳ the bubble on top of other apps follows the same path
        ↳ live translation: microphone → WebSocket → text and voice in the earphone
[Backend] /api/transcribe (Whisper) · /api/process (LLM + system prompt)
        · /api/live (live translation, WebSocket) · /api/options (styles and languages)
        · /admin (panel) · /admin/api/* (the same panel from the phone)
        ↳ every request to a provider → usage log (tokens and seconds of audio, raw)
```

Details are in `backend/README.md` (in Russian). The development plan is in the approved design document.
