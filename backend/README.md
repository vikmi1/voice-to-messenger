# Voice-to-Messenger — бэкенд

Тонкий Flask-прокси: держит API-ключи, применяет системный промпт и стили,
управляется через веб-панель. Приложение шлёт сюда текст (или аудио) и получает
готовое сообщение.

## Запуск локально

```bash
cd backend
python -m venv venv
venv\Scripts\activate            # Windows;  source venv/bin/activate — Linux/mac
pip install -r requirements.txt
copy .env.example .env           # cp на Linux/mac; впиши ADMIN_PASS и SECRET_KEY
python app.py
```

Сервер поднимется на `http://localhost:8000`.

## Настройка

Открой `http://localhost:8000/admin` (логин/пароль из `.env`). Вкладки:

- **Провайдеры** — base_url, API-ключ и модель для OpenAI / Mistral / любого
  OpenAI-совместимого API; выбор активного провайдера.
- **Распознавание** — провайдер и модель Whisper для серверного ASR.
- **Системный промпт** — базовое правило «вернуть только чистый текст».
- **Стили** — тоны (Нейтрально / Кратко / Дружелюбно / Делово).
- **Языки** — цели перевода.
- **Приложение** — API-токен, который вставляется в приложение.

Изменения применяются без перезапуска (конфиг читается на каждый запрос).

## Эндпоинты

| Метод | Путь               | Назначение                                  | Авторизация |
|-------|--------------------|---------------------------------------------|-------------|
| GET   | `/`                | health-check                                | —           |
| POST  | `/api/process`     | текст → чистое сообщение (стриминг)         | Bearer      |
| GET   | `/api/options`     | стили и языки для приложения                | Bearer      |
| POST  | `/api/transcribe`  | аудио (multipart `file`) → сырой текст      | Bearer      |
| GET   | `/admin`           | панель настроек                             | Basic       |

### Пример `/api/process`

```bash
curl -N -X POST http://localhost:8000/api/process \
  -H "Authorization: Bearer <API-токен из панели>" \
  -H "Content-Type: application/json" \
  -d '{"text":"ну это самое, короче надо завтра встретиться часа в три наверное","style":"business","target_lang":"off"}'
```
