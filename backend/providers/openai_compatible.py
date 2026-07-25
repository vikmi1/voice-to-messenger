"""Провайдер для любого OpenAI-совместимого API.

Один класс покрывает и OpenAI, и Mistral, и локальные OpenAI-совместимые эндпоинты
(Ollama, LM Studio и т.п.) — различаются только base_url, api_key и model.
"""

import json
from typing import Iterator

import requests

from providers.base import LlmProvider


class OpenAICompatibleProvider(LlmProvider):
    def __init__(self, base_url: str, api_key: str, model: str, temperature: float = 0.3):
        self.base_url = (base_url or "").rstrip("/")
        self.api_key = api_key or ""
        self.model = model
        self.temperature = temperature

    def stream(self, system: str, user: str) -> Iterator[str]:
        if not self.api_key:
            raise RuntimeError("Не задан API-ключ активного провайдера (заполни в админ-панели).")

        url = f"{self.base_url}/chat/completions"
        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
        }
        payload = {
            "model": self.model,
            "stream": True,
            "temperature": self.temperature,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
        }

        with requests.post(url, headers=headers, json=payload, stream=True, timeout=120) as resp:
            if resp.status_code >= 400:
                raise RuntimeError(f"{resp.status_code}: {resp.text[:300]}")
            for raw in resp.iter_lines(decode_unicode=True):
                if not raw:
                    continue
                line = raw[6:] if raw.startswith("data: ") else raw
                if line.strip() == "[DONE]":
                    break
                try:
                    obj = json.loads(line)
                except json.JSONDecodeError:
                    continue
                choices = obj.get("choices") or [{}]
                delta = (choices[0].get("delta") or {}).get("content")
                if delta:
                    yield delta


def list_models(base_url: str, api_key: str) -> list[str]:
    """Список моделей провайдера (GET /models) — для выпадающего списка в панели."""
    if not api_key:
        raise RuntimeError("Не задан API-ключ провайдера.")

    url = f"{(base_url or '').rstrip('/')}/models"
    resp = requests.get(url, headers={"Authorization": f"Bearer {api_key}"}, timeout=30)
    if resp.status_code >= 400:
        raise RuntimeError(f"{resp.status_code}: {resp.text[:300]}")
    data = resp.json().get("data") or []
    return [m["id"] for m in data if isinstance(m, dict) and m.get("id")]


def transcribe_audio(base_url: str, api_key: str, model: str, file_bytes: bytes,
                     filename: str = "audio.m4a", content_type: str = "audio/m4a") -> str:
    """Распознаёт речь через OpenAI-совместимый /audio/transcriptions (Whisper).

    Язык определяется автоматически — специально не передаём параметр language,
    чтобы корректно работать со смешанной речью (например RU+DE).
    """
    if not api_key:
        raise RuntimeError("Не задан API-ключ ASR-провайдера (заполни в админ-панели).")

    url = f"{(base_url or '').rstrip('/')}/audio/transcriptions"
    headers = {"Authorization": f"Bearer {api_key}"}
    files = {"file": (filename, file_bytes, content_type)}
    data = {"model": model, "response_format": "json"}

    resp = requests.post(url, headers=headers, files=files, data=data, timeout=180)
    if resp.status_code >= 400:
        raise RuntimeError(f"{resp.status_code}: {resp.text[:300]}")
    return (resp.json().get("text") or "").strip()
