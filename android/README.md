# Voice-to-Messenger — Android

Нативное приложение (Kotlin + Jetpack Compose). **В работе (Phase 2).**

Планируемая структура:

```
app/src/main/java/.../
├─ MainActivity.kt
├─ ui/            — Compose-экраны: Main, Notes, Settings
├─ asr/           — on-device SpeechRecognizer + серверный Whisper
├─ processing/    — TextProcessor (ServerProcessor, LocalLlmProcessor-stub)
├─ data/          — заметки (Room)
└─ share/         — ACTION_SEND
```

Функции v1:
- Распознавание речи: на устройстве (селектор языка) ↔ серверный Whisper (авто-язык).
- Обработка: тумблер «Локально / Сервер».
- Стили, перевод, Копировать / Поделиться / Заметки.
