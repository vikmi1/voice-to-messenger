"""Подобранные модели провайдеров: что имеет смысл выбирать и сколько это стоит.

Живёт на сервере, а не в панелях, чтобы обновление списка и цен не требовало
пересборки APK — обе панели (веб и приложение) тянут его из `/admin/api/catalog`.

Список намеренно короткий: у провайдеров сотни моделей, но для переписывания
коротких сообщений осмысленны единицы. Поле модели в панели остаётся текстовым —
любую другую модель можно вписать руками, каталог только подсказывает.

Провайдер узнаётся по base_url: ключ в конфиге может быть любым («groq», «work»),
а адрес API — нет.

Цены — из официальных прайсов на 31.07.2026, в долларах США. У каждой модели две
формы записи: `price` — строка для человека (в ней бывают оговорки вроде «до такой-то
даты»), `in`/`out`/`per_min` — числа для подсчёта расхода. Строка ведущая: если правишь
цену, правь обе.
"""

import math

BUDGET = "budget"
BALANCED = "balanced"
PREMIUM = "premium"

TIER_TITLES = {
    BUDGET: "бюджет",
    BALANCED: "оптимум",
    PREMIUM: "премиум",
}

# Курс кредита: 1 кредит = одна десятая цента. Взят так, чтобы обычное сообщение
# стоило единицы кредитов, а не тысячные доли — число должно читаться человеком.
# Ориентиры при этом курсе: сообщение на бюджетной связке ≈ 4 кредита, на премиальной ≈ 21.
CREDIT_USD = 0.001

# Внутри каждого списка порядок = порядок рекомендации: первая модель считается
# разумным выбором по умолчанию, дальше — альтернативы.
CATALOG = [
    {
        "match": "api.openai.com",
        # Во сколько раз дешевле входной токен, прочитанный из кэша промпта.
        # У OpenAI кэш включается сам от 1024 токенов промпта и даёт примерно половину цены.
        "cache_read_factor": 0.5,
        "llm": [
            {"id": "gpt-4o-mini", "tier": BUDGET, "in": 0.15, "out": 0.60,
             "price": "$0.15 / $0.60 за 1M", "note": "дёшево и достаточно для коротких сообщений"},
            {"id": "gpt-4o", "tier": BALANCED, "in": 2.50, "out": 10.0,
             "price": "$2.50 / $10 за 1M", "note": "предыдущий флагман; стоял в проде до появления каталога"},
            {"id": "gpt-5.6-luna", "tier": BUDGET, "in": 0.20, "out": 1.20,
             "price": "$0.20 / $1.20 за 1M", "note": "новое поколение почти по цене mini"},
            {"id": "gpt-5.6-terra", "tier": BALANCED, "in": 2.0, "out": 12.0,
             "price": "$2 / $12 за 1M", "note": "баланс качества и цены"},
            {"id": "gpt-5.6-sol", "tier": PREMIUM, "in": 5.0, "out": 30.0,
             "price": "$5 / $30 за 1M", "note": "флагман, для сложных и длинных текстов"},
        ],
        # Синтез речи. Цена не указана намеренно: OpenAI тарифицирует его по звуковым
        # токенам, а не по символам, и пересчёт был бы догадкой — расход по этой модели
        # честно покажется прочерком, пока цену не сверили с прайсом.
        "tts": [
            {"id": "gpt-4o-mini-tts", "tier": BALANCED, "price": "$12 / 1M звуковых токенов",
             "note": "11 голосов, настроение задаётся полем instructions"},
            {"id": "tts-1", "tier": BUDGET, "per_1m_chars": 15.0,
             "price": "$15 / 1M символов", "note": "классическая, быстрая; настроение не понимает"},
            {"id": "tts-1-hd", "tier": PREMIUM, "per_1m_chars": 30.0,
             "price": "$30 / 1M символов", "note": "та же, но качественнее и вдвое дороже"},
        ],
        "asr": [
            {"id": "gpt-transcribe", "tier": BALANCED, "per_min": 0.0045,
             "price": "$0.0045 / мин", "note": "рекомендуемая OpenAI, точнее Whisper"},
            {"id": "gpt-4o-mini-transcribe", "tier": BUDGET, "per_min": 0.003,
             "price": "$0.003 / мин", "note": "самая дешёвая"},
            {"id": "whisper-1", "tier": BALANCED, "per_min": 0.006,
             "price": "$0.006 / мин", "note": "классика, максимум совместимости"},
            {"id": "gpt-4o-transcribe", "tier": PREMIUM, "per_min": 0.006,
             "price": "$0.006 / мин", "note": "предыдущее поколение точных моделей"},
            # Единственная модель, которая делает весь живой перевод одна: слышит,
            # переводит и озвучивает. Отсюда `mode` — по нему мост выбирает протокол.
            # Счёт идёт за минуты выходного аудио, а его модель гонит по своей
            # временно́й шкале — то есть примерно за время сессии, а не за время речи.
            {"id": "gpt-realtime-translate", "tier": PREMIUM, "per_min": 0.034,
             "mode": "translate", "price": "$0.034 / мин",
             "note": "слышит, переводит и озвучивает сама; «Перевод» и «Озвучка» не нужны"},
        ],
    },
    {
        "match": "api.mistral.ai",
        # Кэш промпта у Mistral не заявлен — считаем такие токены по полной цене.
        "cache_read_factor": 1.0,
        "llm": [
            {"id": "mistral-small-latest", "tier": BUDGET, "in": 0.15, "out": 0.60,
             "price": "$0.15 / $0.60 за 1M", "note": "Small 4, рабочая лошадка"},
            {"id": "ministral-8b-latest", "tier": BUDGET, "in": 0.15, "out": 0.15,
             "price": "$0.15 / $0.15 за 1M", "note": "самая дешёвая на выходе"},
            {"id": "mistral-large-latest", "tier": BALANCED, "in": 0.50, "out": 1.50,
             "price": "$0.50 / $1.50 за 1M", "note": "Large 3, флагман — и дешевле Medium"},
            {"id": "mistral-medium-latest", "tier": PREMIUM, "in": 1.50, "out": 7.50,
             "price": "$1.50 / $7.50 за 1M", "note": "Medium 3.5, самая дорогая у Mistral"},
        ],
        "asr": [
            {"id": "voxtral-mini-latest", "tier": BUDGET, "per_min": 0.003,
             "price": "$0.003 / мин", "note": "Voxtral Mini Transcribe 2, 13 языков"},
            {"id": "voxtral-small-latest", "tier": BALANCED, "per_min": 0.004,
             "price": "$0.004 / мин", "note": "крупнее, понимает контекст"},
            {"id": "voxtral-mini-transcribe-realtime-2602", "tier": BALANCED, "per_min": 0.006,
             "price": "$0.006 / мин", "note": "потоковая, для живого перевода; язык определяет сама"},
        ],
        # Синтез речи. Тарифицируется по символам, а не по минутам, поэтому список
        # отдельный от asr: `per_1m_chars` — цена за миллион символов входного текста.
        "tts": [
            {"id": "voxtral-mini-tts-2603", "tier": BALANCED, "per_1m_chars": 16.0,
             "price": "$0.016 / 1000 символов", "note": "Voxtral TTS, 9 языков"},
        ],
    },
    {
        "match": "api.anthropic.com",
        # Чтение из кэша промпта у Anthropic — десятая часть цены обычного входа.
        "cache_read_factor": 0.1,
        "llm": [
            {"id": "claude-haiku-4-5", "tier": BUDGET, "in": 1.0, "out": 5.0,
             "price": "$1 / $5 за 1M", "note": "самая дешёвая у Anthropic"},
            {"id": "claude-sonnet-5", "tier": BALANCED, "in": 2.0, "out": 10.0,
             "price": "$2 / $10 за 1M (до 01.09.2026, дальше $3 / $15)", "note": "рабочий выбор"},
            {"id": "claude-opus-5", "tier": PREMIUM, "in": 5.0, "out": 25.0,
             "price": "$5 / $25 за 1M", "note": "флагман"},
        ],
        # У Anthropic нет эндпоинта транскрипции — распознавать речь нечем.
        "asr": [],
    },
]


def for_base_url(base_url: str) -> dict:
    """Подобранные модели для провайдера с таким адресом API. Незнакомый — пустые списки."""
    url = (base_url or "").lower()
    for entry in CATALOG:
        if entry["match"] in url:
            return entry
    return {"llm": [], "asr": [], "tts": []}


def default_asr_model(base_url: str) -> str:
    """Рекомендуемая модель распознавания — первая в списке. Пусто, если провайдер её не умеет."""
    asr = for_base_url(base_url).get("asr") or []
    return asr[0]["id"] if asr else ""


def _find(base_url: str, kind: str, model: str) -> dict | None:
    model = (model or "").strip()
    for item in for_base_url(base_url).get(kind) or []:
        if item["id"] == model:
            return item
    return None


def llm_cost_usd(base_url: str, model: str, input_tokens: int, output_tokens: int,
                 cached_tokens: int = 0) -> float | None:
    """Стоимость генерации в долларах. None — если модели нет в каталоге (цена неизвестна).

    Токены, прочитанные из кэша промпта, приходят внутри input_tokens и тарифицируются
    дешевле — вычитаем их и добавляем со скидкой провайдера.
    """
    entry = _find(base_url, "llm", model)
    if not entry:
        return None
    cached = max(0, min(cached_tokens or 0, input_tokens or 0))
    plain = (input_tokens or 0) - cached
    factor = for_base_url(base_url).get("cache_read_factor", 1.0)
    return (
        plain / 1_000_000 * entry["in"]
        + cached / 1_000_000 * entry["in"] * factor
        + (output_tokens or 0) / 1_000_000 * entry["out"]
    )


def asr_cost_usd(base_url: str, model: str, seconds: float | None) -> float | None:
    """Стоимость распознавания в долларах. None — если модель или длительность неизвестны."""
    entry = _find(base_url, "asr", model)
    if not entry or not seconds:
        return None
    return seconds / 60 * entry["per_min"]


def tts_cost_usd(base_url: str, model: str, chars: int | None) -> float | None:
    """Стоимость синтеза речи в долларах. None — если модели нет в каталоге."""
    entry = _find(base_url, "tts", model)
    if not entry or not chars or not entry.get("per_1m_chars"):
        return None
    return chars / 1_000_000 * entry["per_1m_chars"]


def realtime_asr_models(base_url: str) -> list[dict]:
    """Модели потокового распознавания провайдера — только они годятся для живого перевода."""
    return [m for m in for_base_url(base_url).get("asr") or [] if "realtime" in m["id"]]


def has_realtime_asr(base_url: str) -> bool | None:
    """Есть ли у провайдера потоковое распознавание. None — адрес незнаком каталогу.

    Три состояния, а не два: свой сервер (self-hosted Voxtral) каталогу неизвестен,
    и запрещать ему живой перевод мы не вправе — пусть пробует подключиться.
    """
    if not for_base_url(base_url).get("match"):
        return None
    return bool(realtime_asr_models(base_url))


def realtime_mode(base_url: str, model: str) -> str | None:
    """Каким протоколом говорить с этой потоковой моделью.

    `transcribe` — модель только распознаёт, перевод и озвучка отдельными вызовами
    (диалект Mistral). `translate` — делает всё сама (диалект OpenAI). None — модель
    каталогу неизвестна: это чужой или самодельный сервер, считаем его мистралевским,
    потому что весь остальной код написан под этот протокол.
    """
    entry = _find(base_url, "asr", model)
    if not entry:
        return None
    return entry.get("mode", "transcribe")


def default_live_models(base_url: str) -> dict:
    """Рекомендуемые модели живого перевода для провайдера с таким адресом.

    Realtime-распознавание есть только у Mistral, поэтому для остальных вернётся
    пусто — и админ-панель честно покажет, что живой перевод не настроен.
    """
    entry = for_base_url(base_url)
    realtime = next((m for m in entry.get("asr") or [] if "realtime" in m["id"]), None)
    tts = (entry.get("tts") or [None])[0]
    return {
        "stt_model": realtime["id"] if realtime else "",
        "tts_model": tts["id"] if tts else "",
    }


def credits(usd: float | None) -> int | None:
    """Доллары → кредиты, вверх до целого. Ненулевой расход никогда не показывается нулём."""
    if usd is None:
        return None
    return max(1, math.ceil(usd / CREDIT_USD)) if usd > 0 else 0
