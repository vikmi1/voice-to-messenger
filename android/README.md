# Voice-to-Messenger — Android

Нативное приложение: Kotlin + Jetpack Compose, minSdk 26, targetSdk 35.

## Что уже есть

- **Диктовка** — системный `SpeechRecognizer` (с выбором языка) или запись в m4a
  и серверный Whisper через `/api/transcribe`.
- **Обработка** — тумблер «Сервер / Локально»: бэкенд с LLM (стили, перевод,
  стриминг ответа) либо локальная чистка текста без сети.
- **Действия** — Копировать / Поделиться / В заметки, правка результата вручную,
  «Показать исходный текст», быстрые правки «Короче / Мягче / Деловее».
- **Заметки** — локальная база (Room), без синхронизации.
- **Пузырь** — кнопка микрофона поверх мессенджеров: нажал → наговорил →
  готовый текст в буфере обмена.

## Структура

```
app/src/main/java/com/voicetomessenger/
├─ MainActivity.kt      — точка входа
├─ VoiceApp.kt          — Application + ручной DI (AppContainer)
├─ asr/                 — OnDeviceRecognizer, AudioRecorder
├─ capture/             — VoicePipeline: микрофон → текст → сообщение
├─ processing/          — TextProcessor: ServerProcessor, LocalProcessor
├─ net/                 — BackendClient (OkHttp, стриминг)
├─ data/                — настройки (DataStore) и заметки (Room)
├─ ui/                  — Compose-экраны: Main, Notes, Settings
└─ bubble/              — BubbleService: оверлей поверх других приложений
```

## Сборка

1. Установи [Android Studio](https://developer.android.com/studio) — он сам
   поставит Android SDK и Gradle.
2. `File → Open` → выбери папку `android/` (не корень репозитория).
3. Дождись Gradle Sync. Если Studio ругается на отсутствующий
   `gradle/wrapper/gradle-wrapper.jar` — согласись сгенерировать wrapper
   (`File → Sync Project with Gradle Files`) либо укажи
   `Settings → Build Tools → Gradle → Use Gradle from: 'gradle-wrapper.properties'`.
4. Подключи телефон с включённой отладкой по USB и нажми ▶.

Из командной строки (после установки SDK):

```bash
cd android && ./gradlew assembleDebug
```

APK окажется в `app/build/outputs/apk/debug/`.

Юнит-тесты локальной чистки текста:

```bash
cd android && ./gradlew test
```

## Первый запуск

Открой «Настройки» в приложении и заполни:

- **Адрес бэкенда** — например `https://voice.example.com`
  (для эмулятора и локального сервера — `http://10.0.2.2:8000`).
- **API-токен** — вкладка «Приложение» в админ-панели бэкенда.

Кнопка «Проверить связь» дёргает `/api/options` и заодно подтягивает актуальные
стили и языки из панели.

> Локальный HTTP-сервер: Android блокирует незашифрованный трафик. Для отладки
> по `http://` добавь `android:usesCleartextTraffic="true"` в `AndroidManifest.xml`
> или подними бэкенд за HTTPS.

## Приватность

- Аудио для серверного Whisper пишется во временный файл и удаляется сразу
  после расшифровки.
- Настройки (включая API-токен) исключены из системных бэкапов.
- Заметки хранятся только на устройстве.
- Системное распознавание отдаёт звук сервису распознавания ОС — у Google он
  может обращаться в облако; полностью офлайновый путь — это режим «Локально»
  плюс офлайн-модель распознавания, скачанная в настройках Android.
