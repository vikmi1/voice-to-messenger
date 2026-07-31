"""Провайдер для любого OpenAI-совместимого API.

Один класс покрывает и OpenAI, и Mistral, и локальные OpenAI-совместимые эндпоинты
(Ollama, LM Studio и т.п.) — различаются только base_url, api_key и model.
"""

import json
from typing import Iterator, NamedTuple

import requests

from providers.base import LlmProvider


class OpenAICompatibleProvider(LlmProvider):
    def __init__(self, base_url: str, api_key: str, model: str, temperature: float = 0.3):
        super().__init__()
        self.base_url = (base_url or "").rstrip("/")
        self.api_key = api_key or ""
        self.model = model
        self.temperature = temperature

    def stream(self, system: str, user: str) -> Iterator[str]:
        if not self.api_key:
            raise RuntimeError("Не задан API-ключ активного провайдера (заполни в админ-панели).")

        self.last_usage = {}
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
        if "api.openai.com" in self.base_url:
            # Без этого при stream=True расход не приходит вовсе. Параметр — фирменный
            # для OpenAI, поэтому шлём только туда: незнакомый ключ другие совместимые
            # API могут отвергнуть с 400. Остальные отдают usage в финальном чанке сами —
            # разбор ниже подхватит его, если он есть.
            payload["stream_options"] = {"include_usage": True}

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
                # Чанк с расходом приходит последним, у него пустой choices.
                if obj.get("usage"):
                    self.last_usage = _parse_usage(obj["usage"])
                choices = obj.get("choices") or [{}]
                delta = (choices[0].get("delta") or {}).get("content")
                if delta:
                    yield delta


def _parse_usage(usage: dict) -> dict:
    """usage из OpenAI-совместимого ответа → наши поля."""
    details = usage.get("prompt_tokens_details") or {}
    return {
        "input_tokens": usage.get("prompt_tokens") or 0,
        "output_tokens": usage.get("completion_tokens") or 0,
        "cached_tokens": details.get("cached_tokens") or 0,
        "cache_write_tokens": 0,  # у OpenAI кэш заполняется сам и отдельно не тарифицируется
    }


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


class Transcription(NamedTuple):
    text: str
    #: Длительность аудио в секундах, если провайдер её сообщил. None — считать по размеру файла.
    seconds: float | None


def transcribe_audio(base_url: str, api_key: str, model: str, file_bytes: bytes,
                     filename: str = "audio.m4a", content_type: str = "audio/m4a") -> Transcription:
    """Распознаёт речь через OpenAI-совместимый /audio/transcriptions (Whisper).

    Язык определяется автоматически — специально не передаём параметр language,
    чтобы корректно работать со смешанной речью (например RU+DE).

    response_format оставлен json: verbose_json дал бы длительность у whisper-1, но новые
    gpt-*-transcribe его не поддерживают. Вместо этого берём длительность из usage, если
    провайдер его прислал, а если нет — вызывающий оценит её по размеру файла.
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
    body = resp.json()
    return Transcription((body.get("text") or "").strip(), _audio_seconds(body))


def _audio_seconds(body: dict) -> float | None:
    """Длительность аудио из ответа. Форматы разнятся по моделям, поэтому проверяем все известные."""
    # verbose_json (whisper-1) кладёт длительность в корень.
    if isinstance(body.get("duration"), (int, float)):
        return float(body["duration"])
    # Новые модели считают расход сами: usage type=duration.
    usage = body.get("usage") or {}
    for key in ("seconds", "duration"):
        if isinstance(usage.get(key), (int, float)):
            return float(usage[key])
    return None
