"""Провайдер Anthropic (Claude).

У Anthropic свой API, несовместимый с OpenAI: эндпоинт /v1/messages, авторизация
через заголовок x-api-key, системный промпт отдельным полем (а не сообщением),
обязательный max_tokens и свой формат SSE-событий. Поэтому отдельный класс.
"""

import json
from typing import Iterator

import requests

from providers.base import LlmProvider

ANTHROPIC_VERSION = "2023-06-01"

# Сообщения короткие (реплика в мессенджер), но потолок берём с запасом на
# длинную диктовку с переводом.
DEFAULT_MAX_TOKENS = 4096


def _headers(api_key: str) -> dict:
    return {
        "x-api-key": api_key,
        "anthropic-version": ANTHROPIC_VERSION,
        "content-type": "application/json",
    }


class AnthropicProvider(LlmProvider):
    def __init__(self, base_url: str, api_key: str, model: str, temperature: float | None = None,
                 max_tokens: int = DEFAULT_MAX_TOKENS):
        super().__init__()
        self.base_url = (base_url or "https://api.anthropic.com/v1").rstrip("/")
        self.api_key = api_key or ""
        self.model = model
        # У части моделей (Claude 5+) temperature устарел и вызывает 400 —
        # по умолчанию не шлём его вовсе; задаётся только явно.
        self.temperature = temperature
        self.max_tokens = max_tokens

    def stream(self, system: str, user: str) -> Iterator[str]:
        if not self.api_key:
            raise RuntimeError("Не задан API-ключ активного провайдера (заполни в админ-панели).")

        self.last_usage = {}
        payload = {
            "model": self.model,
            "max_tokens": self.max_tokens,
            "system": system,
            "messages": [{"role": "user", "content": user}],
            "stream": True,
        }
        if self.temperature is not None:
            payload["temperature"] = self.temperature

        with requests.post(f"{self.base_url}/messages", headers=_headers(self.api_key),
                           json=payload, stream=True, timeout=120) as resp:
            if resp.status_code >= 400:
                raise RuntimeError(f"{resp.status_code}: {resp.text[:300]}")
            for raw in resp.iter_lines(decode_unicode=True):
                # Поток идёт парами «event: ... / data: ...» — строки event и
                # пустые разделители пропускаем, разбираем только data.
                if not raw or not raw.startswith("data: "):
                    continue
                try:
                    obj = json.loads(raw[6:])
                except json.JSONDecodeError:
                    continue
                kind = obj.get("type")
                # Расход приходит двумя порциями: вход известен сразу (message_start),
                # выход — накопительным итогом в последнем message_delta.
                if kind == "message_start":
                    usage = (obj.get("message") or {}).get("usage") or {}
                    self.last_usage = {
                        "input_tokens": usage.get("input_tokens") or 0,
                        "output_tokens": usage.get("output_tokens") or 0,
                        "cached_tokens": usage.get("cache_read_input_tokens") or 0,
                        "cache_write_tokens": usage.get("cache_creation_input_tokens") or 0,
                    }
                elif kind == "message_delta":
                    usage = obj.get("usage") or {}
                    if usage.get("output_tokens") is not None:
                        self.last_usage["output_tokens"] = usage["output_tokens"]
                elif kind == "content_block_delta":
                    delta = obj.get("delta") or {}
                    if delta.get("type") == "text_delta" and delta.get("text"):
                        yield delta["text"]
                elif kind == "error":
                    err = obj.get("error") or {}
                    raise RuntimeError(err.get("message") or "ошибка Anthropic")
                elif kind == "message_stop":
                    break


def list_models(base_url: str, api_key: str) -> list[str]:
    """Список доступных моделей аккаунта (GET /v1/models)."""
    if not api_key:
        raise RuntimeError("Не задан API-ключ провайдера.")
    url = f"{(base_url or 'https://api.anthropic.com/v1').rstrip('/')}/models?limit=100"
    resp = requests.get(url, headers=_headers(api_key), timeout=30)
    if resp.status_code >= 400:
        raise RuntimeError(f"{resp.status_code}: {resp.text[:300]}")
    return [m["id"] for m in resp.json().get("data", []) if m.get("id")]
