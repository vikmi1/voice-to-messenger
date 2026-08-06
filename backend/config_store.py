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

import catalog
from prompts import DEFAULT_BASE_PROMPT

_DB_PATH = os.environ.get(
    "DB_PATH", os.path.join(os.path.dirname(os.path.abspath(__file__)), "config.sqlite3")
)
_lock = threading.Lock()


_MISTRAL_URL = "https://api.mistral.ai/v1"


def _default_live() -> dict:
    models = catalog.default_live_models(_MISTRAL_URL)
    return {
        "stt_provider": "mistral",
        "stt_model": models["stt_model"],
        "translate_provider": "mistral",
        "translate_model": "mistral-small-latest",
        "tts_provider": "mistral",
        "tts_model": models["tts_model"],
        # Голос синтеза. У Mistral обязателен: без него API отвечает 400
        # «Either ref_audio or voice must be provided».
        "tts_voice": "en_paul_neutral",
        # Настроение для провайдеров с полем instructions (OpenAI). У Mistral
        # настроение выбирается самим голосом, и это поле не используется.
        "tts_mood": "neutral",
        # Потолок одной сессии. Живой перевод тратит деньги, пока включён, поэтому
        # оставленный без присмотра микрофон должен выключаться сам.
        "session_limit_minutes": 10,
    }


def _default_config() -> dict:
    return {
        "active_provider": "mistral",
        # Токен, который приложение шлёт в заголовке Authorization: Bearer <token>
        "api_token": secrets.token_urlsafe(24),
        # Токен админ-панели в приложении (заголовок X-Admin-Token). Отдельный от
        # api_token: обычный токен даёт только обработку текста, этот — правку настроек.
        "admin_token": secrets.token_urlsafe(24),
        # asr_model — модель распознавания речи этого же провайдера. Лежит рядом с
        # моделью генерации, потому что у каждого провайдера она своя: whisper-1
        # у Mistral не существует, voxtral у OpenAI — тоже.
        "providers": {
            "openai": {
                "label": "OpenAI",
                "kind": "openai",
                "base_url": "https://api.openai.com/v1",
                "api_key": "",
                "model": "gpt-4o-mini",
                "asr_model": catalog.default_asr_model("https://api.openai.com/v1"),
            },
            "mistral": {
                "label": "Mistral",
                "kind": "openai",
                "base_url": "https://api.mistral.ai/v1",
                "api_key": "",
                "model": "mistral-small-latest",
                "asr_model": catalog.default_asr_model("https://api.mistral.ai/v1"),
            },
            "anthropic": {
                "label": "Anthropic (Claude)",
                "kind": "anthropic",
                "base_url": "https://api.anthropic.com/v1",
                "api_key": "",
                "model": "claude-sonnet-5",
                "asr_model": "",
            },
        },
        # Кто распознаёт речь на сервере. Модель — в карточке этого провайдера.
        "asr": {"provider": "openai"},
        # Живой перевод: три разные модели, поэтому настройки свои, а не из карточки
        # провайдера. Потоковое распознавание есть только у Mistral; перевод намеренно
        # держим на дешёвой быстрой модели — в живом режиме он вызывается на каждую фразу.
        "live": _default_live(),
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

    # Модель распознавания переехала из общего asr.model в карточку провайдера.
    # Раньше она была одна на всех: переключение ASR-провайдера оставляло чужую
    # модель и запрос падал. Старое значение отдаём тому провайдеру, который его и
    # использовал; остальным ставим рекомендуемую по их base_url.
    asr = cfg.setdefault("asr", {})
    if "model" in asr:
        legacy = (asr.pop("model") or "").strip()
        owner = providers.get(asr.get("provider") or "")
        if legacy and owner is not None and not owner.get("asr_model"):
            owner["asr_model"] = legacy
        changed = True

    for prov in providers.values():
        if "asr_model" in prov:
            continue
        prov["asr_model"] = (
            "" if prov.get("kind") == "anthropic" else catalog.default_asr_model(prov.get("base_url"))
        )
        changed = True

    # Живой перевод появился позже — у боевого конфига раздела нет вовсе.
    # Дополняем по полю, а не целиком: иначе правка одной настройки в панели
    # затиралась бы дефолтами при следующем чтении.
    live = cfg.setdefault("live", {})
    for key, value in _default_live().items():
        if key not in live:
            live[key] = value
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
