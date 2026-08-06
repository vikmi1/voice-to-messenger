"""Голоса синтеза речи: единый список для панелей.

Провайдеры устроены по-разному, и панель не должна об этом знать:

- **Mistral** держит голоса на сервере и отдаёт их по `GET /audio/voices` — с полом,
  возрастом, языками и тегами. Настроение там зашито в сам голос: «Paul - Happy» и
  «Paul - Sad» — два разных голоса одного человека. Поэтому «пол → имя → настроение»
  ложится на список один в один, а `instructions` этому API не нужен (и не понимается).
- **OpenAI** списка голосов не отдаёт вовсе (`/audio/voices` → 404), набор фиксированный
  и зашит здесь руками. Настроение задаётся отдельным полем `instructions` — одним и тем
  же голосом можно говорить и бодро, и сухо.

Отсюда флаг `supports_instructions`: по нему панель решает, показывать ли готовые
настроения отдельными кнопками (OpenAI) или они уже внутри голоса (Mistral).

**Пол у OpenAI не указан намеренно.** Официально они его не публикуют, а угадывать по
именам — значит выдавать догадку за факт; в панели такие голоса показываются одним
списком без деления по полу.
"""

import requests

#: Готовые настроения для провайдеров с `instructions`. Текст идёт в API как есть,
#: поэтому он на английском — модели так надёжнее.
MOODS = [
    {"id": "neutral", "title": "Нейтрально",
     "instructions": "Speak in a neutral, clear tone at a natural conversational pace."},
    {"id": "friendly", "title": "Дружелюбно",
     "instructions": "Speak in a warm, friendly tone, as if talking to a good acquaintance."},
    {"id": "calm", "title": "Спокойно",
     "instructions": "Speak calmly and gently, with a relaxed, unhurried pace."},
    {"id": "upbeat", "title": "Воодушевлённо",
     "instructions": "Speak with energy and enthusiasm, lively but not shouting."},
    {"id": "serious", "title": "Серьёзно",
     "instructions": "Speak in a serious, measured, professional tone."},
]

#: Голоса OpenAI: списка через API нет, набор из документации `/audio/speech`.
#: Проверено обращением к боевому API 06.08.2026 — все одиннадцать принимаются
#: моделью `gpt-4o-mini-tts`.
_OPENAI_VOICES = [
    "alloy", "ash", "ballad", "coral", "echo",
    "fable", "nova", "onyx", "sage", "shimmer", "verse",
]


def _split_name(full: str, tags: list[str]) -> tuple[str, str]:
    """«Paul - Happy» → («Paul», «Happy»). Без разделителя настроение берём из тегов."""
    if " - " in full:
        name, _, mood = full.partition(" - ")
        return name.strip(), mood.strip()
    return full.strip(), (tags[-1].capitalize() if tags else "")


def _mistral_voices(base_url: str, api_key: str, timeout: int = 30) -> list[dict]:
    url = f"{(base_url or '').rstrip('/')}/audio/voices"
    headers = {"Authorization": f"Bearer {api_key}"}

    # Список постраничный, но на запрос с большим page_size приходит всё сразу и
    # total_pages при этом остаётся от размера страницы по умолчанию. Поэтому
    # страницы обходим, а повторы убираем по id — иначе список троился.
    seen: dict[str, dict] = {}
    page, pages = 1, 1
    while page <= pages:
        resp = requests.get(url, headers=headers, timeout=timeout,
                            params={"page": page, "page_size": 100})
        if resp.status_code >= 400:
            raise RuntimeError(f"{resp.status_code}: {resp.text[:300]}")
        body = resp.json()
        pages = min(body.get("total_pages") or 1, 20)
        for item in body.get("items") or []:
            if item.get("id") in seen:
                continue
            name, mood = _split_name(item.get("name") or "", item.get("tags") or [])
            seen[item["id"]] = {
                # Slug читаемее UUID и принимается API наравне с ним — в конфиге
                # «en_paul_happy» говорит сам за себя, а «e3596645-…» нет.
                "id": item.get("slug") or item["id"],
                "name": name,
                "mood": mood,
                "gender": item.get("gender") or "",
                "tags": item.get("tags") or [],
                "languages": item.get("languages") or [],
            }
        page += 1

    return sorted(seen.values(), key=lambda v: (v["gender"], v["name"], v["mood"]))


def _openai_voices() -> list[dict]:
    return [
        {"id": v, "name": v.capitalize(), "mood": "", "gender": "", "tags": [], "languages": []}
        for v in _OPENAI_VOICES
    ]


def supports_instructions(base_url: str) -> bool:
    """Понимает ли провайдер поле `instructions` (описание тона отдельной строкой).

    Определяется по адресу API, а не запросом: вызывается на каждую озвучиваемую
    фразу, и лишний поход в сеть здесь недопустим.
    """
    return "api.openai.com" in (base_url or "").lower()


def catalog(base_url: str, api_key: str) -> dict:
    """Голоса провайдера для панели.

    Незнакомый провайдер получает пустой список — поле голоса в панели остаётся
    обычным текстовым вводом, как и поля моделей.
    """
    url = (base_url or "").lower()

    if "api.mistral.ai" in url:
        return {
            "provider_kind": "mistral",
            # Настроение уже внутри голоса, отдельным полем его не задать.
            "supports_instructions": False,
            "moods": [],
            "voices": _mistral_voices(base_url, api_key),
        }

    if "api.openai.com" in url:
        return {
            "provider_kind": "openai",
            "supports_instructions": True,
            "moods": MOODS,
            "voices": _openai_voices(),
        }

    return {"provider_kind": "unknown", "supports_instructions": False,
            "moods": [], "voices": []}


def instructions_for(mood_id: str) -> str:
    """Текст инструкции по id настроения. Неизвестное настроение — пустая строка."""
    return next((m["instructions"] for m in MOODS if m["id"] == mood_id), "")
