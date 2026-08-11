"""Провайдер для любого OpenAI-совместимого API.

Один класс покрывает и OpenAI, и Mistral, и локальные OpenAI-совместимые эндпоинты
(Ollama, LM Studio и т.п.) — различаются только base_url, api_key и model.
"""

import base64
import json
from typing import Iterator, NamedTuple

import requests

from providers.base import LlmProvider

#: Пары «адрес API + модель», которые отвергли temperature. Заполняется на лету
#: первым же отказом и живёт до перезапуска процесса.
_NO_TEMPERATURE: set[tuple[str, str]] = set()


def _post_chat(base_url: str, headers: dict, payload: dict, temperature: float | None,
               stream: bool = False, timeout: int = 120):
    """POST в /chat/completions с оглядкой на то, принимает ли модель temperature.

    Новые модели OpenAI (семейство gpt-5.6) поддерживают только значение по умолчанию
    и отвечают `400 Unsupported value: 'temperature'` на любое другое — то есть весь
    наш поток падал бы на них ошибкой. Заранее знать список неоткуда: он растёт без
    нас, а в панели модель вписывается руками. Поэтому узнаём из первого же отказа —
    повторяем запрос без параметра и запоминаем модель, чтобы дальше не платить
    лишним заходом.
    """
    url = f"{base_url}/chat/completions"
    key = (base_url, payload.get("model") or "")
    send = temperature is not None and key not in _NO_TEMPERATURE
    payload.pop("temperature", None)
    if send:
        payload["temperature"] = temperature

    resp = requests.post(url, headers=headers, json=payload, stream=stream, timeout=timeout)
    if send and resp.status_code == 400 and "temperature" in resp.text.lower():
        _NO_TEMPERATURE.add(key)
        resp.close()
        payload.pop("temperature", None)
        resp = requests.post(url, headers=headers, json=payload, stream=stream, timeout=timeout)
    return resp


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
        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
        }
        payload = {
            "model": self.model,
            "stream": True,
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

        with _post_chat(self.base_url, headers, payload, self.temperature,
                        stream=True, timeout=120) as resp:
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


class Completion(NamedTuple):
    text: str
    #: Расход в наших полях (см. [_parse_usage]); пусто, если провайдер промолчал.
    usage: dict


def complete(base_url: str, api_key: str, model: str, system: str, user: str,
             temperature: float = 0.2, timeout: int = 60) -> Completion:
    """Ответ целиком, без стриминга — для коротких служебных запросов.

    Живому переводу поток не нужен: фраза отдаётся в наушник и на экран целиком,
    а один HTTP-запрос вместо разбора SSE проще и на пару десятков миллисекунд быстрее.
    """
    if not api_key:
        raise RuntimeError("Не задан API-ключ провайдера.")

    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ],
    }
    resp = _post_chat(
        (base_url or "").rstrip("/"),
        {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
        payload, temperature, timeout=timeout,
    )
    if resp.status_code >= 400:
        raise RuntimeError(f"{resp.status_code}: {resp.text[:300]}")
    body = resp.json()
    choices = body.get("choices") or [{}]
    text = ((choices[0].get("message") or {}).get("content") or "").strip()
    return Completion(text, _parse_usage(body.get("usage") or {}))


def synthesize_speech(base_url: str, api_key: str, model: str, text: str,
                      voice: str = "", response_format: str = "mp3",
                      instructions: str = "", timeout: int = 60) -> bytes:
    """Текст → аудио через `/audio/speech`. Возвращает готовый файл байтами.

    mp3, а не сырой PCM: приложению не нужно знать частоту дискретизации синтеза —
    оно просто отдаёт файл системному плееру.

    Форма ответа у провайдеров разная, и это не мелочь: **Mistral отдаёт JSON
    `{"audio_data": "<base64>"}`**, а OpenAI — сами байты файла. Отличаем по
    Content-Type. Первая версия этого кода возвращала `resp.content` как есть, и
    приложение честно скармливало плееру JSON-документ: перевод работал, деньги
    тратились, звука не было ни звука — MediaPlayer молча отказывался его играть.

    [instructions] — как произносить (тон, темп, настроение). Понимает OpenAI
    (`gpt-4o-mini-tts`); у Mistral настроение зашито в сам голос, поэтому
    параметр туда не отправляется — незнакомое поле там даёт 400.
    """
    if not api_key:
        raise RuntimeError("Не задан API-ключ провайдера озвучки.")

    payload = {"model": model, "input": text, "response_format": response_format}
    if voice:
        payload["voice"] = voice
    if instructions:
        payload["instructions"] = instructions

    resp = requests.post(
        f"{(base_url or '').rstrip('/')}/audio/speech",
        headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
        json=payload, timeout=timeout,
    )
    if resp.status_code >= 400:
        raise RuntimeError(f"{resp.status_code}: {resp.text[:300]}")

    if "application/json" in (resp.headers.get("content-type") or ""):
        body = resp.json()
        encoded = body.get("audio_data") or body.get("audio")
        if not encoded:
            raise RuntimeError(f"В ответе синтеза нет аудио: {str(body)[:200]}")
        return base64.b64decode(encoded)
    return resp.content


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
