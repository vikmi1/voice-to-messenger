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
  готовый текст в буфере обмена. Выключить — кнопка «Выключить» в уведомлении.

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

Из командной строки (после первого Gradle Sync в Android Studio):

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$gradle = (Get-ChildItem "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.9-bin" -Recurse -Filter gradle.bat | Select -First 1).FullName
Set-Location android
& $gradle assembleDebug
```

APK окажется в `app/build/outputs/apk/debug/`.

Установить через ADB:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
```

> `gradle-wrapper.jar` не хранится в репозитории — `./gradlew` без предварительного
> Gradle Sync в Android Studio не сработает.

Юнит-тесты локальной чистки текста:

```bash
cd android && ./gradlew test
```

## Первый запуск

Открой «Настройки» в приложении и заполни:

- **Адрес бэкенда** — прод: `https://voiceapp.aithinglab.com`
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
