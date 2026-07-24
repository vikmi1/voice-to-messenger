"""Хранилище конфигурации приложения в SQLite.

Вся конфигурация (провайдеры, ключи, промпты, стили, языки, ASR) хранится одним
JSON-документом в таблице config. Редактируется только через админ-панель.
Читается на каждый запрос, поэтому изменения применяются без перезапуска сервера.
"""

import json
import os
import secrets
import sqlite3
import threading

from prompts import DEFAULT_BASE_PROMPT

_DB_PATH = os.environ.get(
    "DB_PATH", os.path.join(os.path.dirname(os.path.abspath(__file__)), "config.sqlite3")
)
_lock = threading.Lock()


def _default_config() -> dict:
    return {
        "active_provider": "mistral",
        # Токен, который приложение шлёт в заголовке Authorization: Bearer <token>
        "api_token": secrets.token_urlsafe(24),
        "providers": {
            "openai": {
                "label": "OpenAI",
                "base_url": "https://api.openai.com/v1",
                "api_key": "",
                "model": "gpt-4o-mini",
            },
            "mistral": {
                "label": "Mistral",
                "base_url": "https://api.mistral.ai/v1",
                "api_key": "",
                "model": "mistral-small-latest",
            },
        },
        # Настройки распознавания речи на сервере (Whisper-совместимый эндпоинт)
        "asr": {
            "provider": "openai",   # чей ключ/base_url использовать
            "model": "whisper-1",
        },
        "base_prompt": DEFAULT_BASE_PROMPT,
        "styles": [
            {"id": "neutral", "name": "Нейтрально", "prompt": "нейтральный, спокойный тон."},
            {"id": "short", "name": "Кратко",
             "prompt": "максимально кратко и по делу, убери второстепенное, сохрани суть и ключевые факты."},
            {"id": "friendly", "name": "Дружелюбно",
             "prompt": "тёплый, дружелюбный тон, естественный для личной переписки."},
            {"id": "business", "name": "Делово",
             "prompt": "деловой и вежливый тон, при необходимости структурируй; подходит для рабочего чата."},
        ],
        "languages": [
            {"code": "off", "name": "Без перевода"},
            {"code": "de", "name": "Немецкий"},
            {"code": "en", "name": "Английский"},
            {"code": "ru", "name": "Русский"},
        ],
    }


def _connect() -> sqlite3.Connection:
    conn = sqlite3.connect(_DB_PATH)
    conn.execute("CREATE TABLE IF NOT EXISTS config (id INTEGER PRIMARY KEY, data TEXT NOT NULL)")
    return conn


def init_db() -> None:
    with _lock, _connect() as conn:
        row = conn.execute("SELECT data FROM config WHERE id = 1").fetchone()
        if row is None:
            conn.execute(
                "INSERT INTO config (id, data) VALUES (1, ?)",
                (json.dumps(_default_config(), ensure_ascii=False),),
            )


def load() -> dict:
    with _lock, _connect() as conn:
        row = conn.execute("SELECT data FROM config WHERE id = 1").fetchone()
        if row is None:
            cfg = _default_config()
            conn.execute(
                "INSERT INTO config (id, data) VALUES (1, ?)",
                (json.dumps(cfg, ensure_ascii=False),),
            )
            return cfg
        return json.loads(row[0])


def save(cfg: dict) -> None:
    with _lock, _connect() as conn:
        conn.execute(
            "UPDATE config SET data = ? WHERE id = 1",
            (json.dumps(cfg, ensure_ascii=False),),
        )
