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
        # Токен админ-панели в приложении (заголовок X-Admin-Token). Отдельный от
        # api_token: обычный токен даёт только обработку текста, этот — правку настроек.
        "admin_token": secrets.token_urlsafe(24),
        "providers": {
            "openai": {
                "label": "OpenAI",
                "kind": "openai",
                "base_url": "https://api.openai.com/v1",
                "api_key": "",
                "model": "gpt-4o-mini",
            },
            "mistral": {
                "label": "Mistral",
                "kind": "openai",
                "base_url": "https://api.mistral.ai/v1",
                "api_key": "",
                "model": "mistral-small-latest",
            },
            "anthropic": {
                "label": "Anthropic (Claude)",
                "kind": "anthropic",
                "base_url": "https://api.anthropic.com/v1",
                "api_key": "",
                "model": "claude-sonnet-5",
            },
        },
        # Настройки распознавания речи на сервере (Whisper-совместимый эндпоинт)
        "asr": {
            "provider": "openai",   # чей ключ/base_url использовать
            "model": "whisper-1",
        },
        "base_prompt": DEFAULT_BASE_PROMPT,
        "styles": [
            # «Мой стиль» — пустой prompt: тон задаётся few-shot-примерами сообщений
            # самого пользователя, которые приложение шлёт в поле examples.
            {"id": "mine", "name": "Мой стиль", "prompt": ""},
            {"id": "friendly", "name": "Дружелюбно",
             "prompt": "тёплый, дружелюбный тон, естественный для личной переписки."},
            {"id": "business", "name": "Деловой",
             "prompt": "деловой и вежливый тон, при необходимости структурируй; подходит для рабочего чата."},
        ],
        "languages": [
            {"code": "off", "name": "Без перевода"},
            {"code": "de", "name": "Немецкий"},
            {"code": "en", "name": "Английский"},
            {"code": "ru", "name": "Русский"},
        ],
    }


def _migrate(cfg: dict) -> bool:
    """Дополняет конфиг полями, появившимися позже. Возвращает True, если что-то поменялось.

    Нужно, потому что конфиг хранится одним JSON-документом: у боевой базы нет
    ни admin_token, ни kind у провайдеров, ни провайдера anthropic.
    """
    changed = False

    if not cfg.get("admin_token"):
        cfg["admin_token"] = secrets.token_urlsafe(24)
        changed = True

    providers = cfg.setdefault("providers", {})
    for name, prov in providers.items():
        if not prov.get("kind"):
            # Единственный несовместимый с OpenAI вариант — Anthropic, узнаём по адресу.
            prov["kind"] = "anthropic" if "anthropic" in (prov.get("base_url") or "") else "openai"
            changed = True

    if "anthropic" not in providers:
        providers["anthropic"] = _default_config()["providers"]["anthropic"]
        changed = True

    # Переход на три стиля с «Моим стилем». Признак старого набора — отсутствие id "mine".
    # Заменяем только дефолтный набор; если админ уже завёл свой стиль mine — не трогаем.
    styles = cfg.get("styles") or []
    if not any(s.get("id") == "mine" for s in styles):
        cfg["styles"] = _default_config()["styles"]
        changed = True

    return changed


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
            return
        cfg = json.loads(row[0])
        if _migrate(cfg):
            conn.execute(
                "UPDATE config SET data = ? WHERE id = 1",
                (json.dumps(cfg, ensure_ascii=False),),
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
        cfg = json.loads(row[0])
        _migrate(cfg)  # на чтении не пишем в базу: init_db уже сохранил результат
        return cfg


def save(cfg: dict) -> None:
    with _lock, _connect() as conn:
        conn.execute(
            "UPDATE config SET data = ? WHERE id = 1",
            (json.dumps(cfg, ensure_ascii=False),),
        )
