"""Подобранные модели провайдеров: что имеет смысл выбирать и сколько это стоит.

Живёт на сервере, а не в панелях, чтобы обновление списка и цен не требовало
пересборки APK — обе панели (веб и приложение) тянут его из `/admin/api/catalog`.

Список намеренно короткий: у провайдеров сотни моделей, но для переписывания
коротких сообщений осмысленны единицы. Поле модели в панели остаётся текстовым —
любую другую модель можно вписать руками, каталог только подсказывает.

Провайдер узнаётся по base_url: ключ в конфиге может быть любым («groq», «work»),
а адрес API — нет.

Цены — из официальных прайсов на 31.07.2026, в долларах США.
"""

BUDGET = "budget"
BALANCED = "balanced"
PREMIUM = "premium"

TIER_TITLES = {
    BUDGET: "бюджет",
    BALANCED: "оптимум",
    PREMIUM: "премиум",
}

# Внутри каждого списка порядок = порядок рекомендации: первая модель считается
# разумным выбором по умолчанию, дальше — альтернативы.
CATALOG = [
    {
        "match": "api.openai.com",
        "llm": [
            {"id": "gpt-4o-mini", "tier": BUDGET,
             "price": "$0.15 / $0.60 за 1M", "note": "дёшево и достаточно для коротких сообщений"},
            {"id": "gpt-5.6-luna", "tier": BUDGET,
             "price": "$0.20 / $1.20 за 1M", "note": "новое поколение почти по цене mini"},
            {"id": "gpt-5.6-terra", "tier": BALANCED,
             "price": "$2 / $12 за 1M", "note": "баланс качества и цены"},
            {"id": "gpt-5.6-sol", "tier": PREMIUM,
             "price": "$5 / $30 за 1M", "note": "флагман, для сложных и длинных текстов"},
        ],
        "asr": [
            {"id": "gpt-transcribe", "tier": BALANCED,
             "price": "$0.0045 / мин", "note": "рекомендуемая OpenAI, точнее Whisper"},
            {"id": "gpt-4o-mini-transcribe", "tier": BUDGET,
             "price": "$0.003 / мин", "note": "самая дешёвая"},
            {"id": "whisper-1", "tier": BALANCED,
             "price": "$0.006 / мин", "note": "классика, максимум совместимости"},
            {"id": "gpt-4o-transcribe", "tier": PREMIUM,
             "price": "$0.006 / мин", "note": "предыдущее поколение точных моделей"},
        ],
    },
    {
        "match": "api.mistral.ai",
        "llm": [
            {"id": "mistral-small-latest", "tier": BUDGET,
             "price": "$0.15 / $0.60 за 1M", "note": "Small 4, рабочая лошадка"},
            {"id": "ministral-8b-latest", "tier": BUDGET,
             "price": "$0.15 / $0.15 за 1M", "note": "самая дешёвая на выходе"},
            {"id": "mistral-large-latest", "tier": BALANCED,
             "price": "$0.50 / $1.50 за 1M", "note": "Large 3, флагман — и дешевле Medium"},
            {"id": "mistral-medium-latest", "tier": PREMIUM,
             "price": "$1.50 / $7.50 за 1M", "note": "Medium 3.5, самая дорогая у Mistral"},
        ],
        "asr": [
            {"id": "voxtral-mini-latest", "tier": BUDGET,
             "price": "$0.003 / мин", "note": "Voxtral Mini Transcribe 2, 13 языков"},
            {"id": "voxtral-small-latest", "tier": BALANCED,
             "price": "$0.004 / мин", "note": "крупнее, понимает контекст"},
        ],
    },
    {
        "match": "api.anthropic.com",
        "llm": [
            {"id": "claude-haiku-4-5", "tier": BUDGET,
             "price": "$1 / $5 за 1M", "note": "самая дешёвая у Anthropic"},
            {"id": "claude-sonnet-5", "tier": BALANCED,
             "price": "$2 / $10 за 1M (до 01.09.2026, дальше $3 / $15)", "note": "рабочий выбор"},
            {"id": "claude-opus-5", "tier": PREMIUM,
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
    return {"llm": [], "asr": []}


def default_asr_model(base_url: str) -> str:
    """Рекомендуемая модель распознавания — первая в списке. Пусто, если провайдер её не умеет."""
    asr = for_base_url(base_url).get("asr") or []
    return asr[0]["id"] if asr else ""
