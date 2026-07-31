"""Журнал расхода: сколько токенов и секунд аудио стоил каждый запрос.

Отдельный файл БД, а не таблица рядом с конфигом. Конфиг читается на КАЖДЫЙ запрос
(`config_store.load()`), а сюда на каждый запрос пишут — в одном файле запись держала
бы блокировку и тормозила чтение настроек.

Храним токены сырыми, деньги не храним. Цена привязана к модели на момент запроса и
меняется (в каталоге уже помечено «sonnet-5 $2/$10 до 01.09.2026, дальше $3/$15»);
если записать доллары, история станет несопоставимой при первой же смене прайса.
Стоимость считается на чтении, из текущего каталога.

Строк мало по природе продукта (сотни в месяц), поэтому чистки и ротации нет.
"""

import os
import sqlite3
import threading
from datetime import datetime, timezone

import catalog

_CONFIG_DB = os.environ.get(
    "DB_PATH", os.path.join(os.path.dirname(os.path.abspath(__file__)), "config.sqlite3")
)
_DB_PATH = os.environ.get(
    "USAGE_DB_PATH", os.path.join(os.path.dirname(_CONFIG_DB), "usage.sqlite3")
)
_lock = threading.Lock()

# Наш рекордер пишет 32 кбит/с моно (см. AudioRecorder.kt) — это ровно 4000 байт в секунду.
# Оценка по размеру нужна там, где провайдер не сказал длительность сам.
_BYTES_PER_SECOND = 4000

_SCHEMA = """
CREATE TABLE IF NOT EXISTS usage (
    id TEXT PRIMARY KEY,
    ts TEXT NOT NULL,
    op TEXT NOT NULL,                 -- 'process' | 'transcribe'
    provider TEXT NOT NULL,
    base_url TEXT NOT NULL,
    model TEXT NOT NULL,
    tier TEXT,                        -- задел под тумблер бюджет/премиум, пока пусто
    input_tokens INTEGER DEFAULT 0,
    output_tokens INTEGER DEFAULT 0,
    cached_tokens INTEGER DEFAULT 0,  -- прочитано из кэша промпта, входит в input_tokens
    cache_write_tokens INTEGER DEFAULT 0,
    audio_bytes INTEGER DEFAULT 0,
    audio_seconds REAL,
    audio_seconds_source TEXT,        -- 'provider' | 'estimate'
    style TEXT,
    target_lang TEXT,
    ok INTEGER NOT NULL DEFAULT 1,
    error TEXT
);
CREATE INDEX IF NOT EXISTS usage_ts ON usage (ts);
"""


def _connect() -> sqlite3.Connection:
    # timeout: два воркера по четыре потока могут писать одновременно.
    conn = sqlite3.connect(_DB_PATH, timeout=10)
    conn.row_factory = sqlite3.Row
    return conn


def init_db() -> None:
    with _lock, _connect() as conn:
        # WAL: писатель не блокирует читателей — вкладка расхода открывается во время диктовки.
        conn.execute("PRAGMA journal_mode=WAL")
        conn.executescript(_SCHEMA)


def estimate_seconds(audio_bytes: int) -> float:
    """Длительность аудио по размеру файла — когда провайдер её не сообщил."""
    return round((audio_bytes or 0) / _BYTES_PER_SECOND, 1)


def record(**row) -> None:
    """Пишет строку расхода. Никогда не бросает: учёт не должен ронять запрос пользователя."""
    fields = (
        "id", "ts", "op", "provider", "base_url", "model", "tier",
        "input_tokens", "output_tokens", "cached_tokens", "cache_write_tokens",
        "audio_bytes", "audio_seconds", "audio_seconds_source",
        "style", "target_lang", "ok", "error",
    )
    row.setdefault("ts", datetime.now(timezone.utc).isoformat(timespec="seconds"))
    row.setdefault("ok", 1)
    values = [row.get(f) for f in fields]
    try:
        with _lock, _connect() as conn:
            conn.execute(
                f"INSERT OR REPLACE INTO usage ({','.join(fields)}) "
                f"VALUES ({','.join('?' * len(fields))})",
                values,
            )
    except Exception:  # noqa: BLE001 — журнал расхода не важнее ответа пользователю
        pass


def _priced(row: sqlite3.Row) -> dict:
    """Строка из базы + посчитанная по текущему каталогу стоимость."""
    item = dict(row)
    if item["op"] == "transcribe":
        usd = catalog.asr_cost_usd(item["base_url"], item["model"], item["audio_seconds"])
    else:
        usd = catalog.llm_cost_usd(
            item["base_url"], item["model"],
            item["input_tokens"] or 0, item["output_tokens"] or 0, item["cached_tokens"] or 0,
        )
    item["usd"] = round(usd, 6) if usd is not None else None
    item["credits"] = catalog.credits(usd)
    return item


def get(usage_id: str) -> dict | None:
    with _lock, _connect() as conn:
        row = conn.execute("SELECT * FROM usage WHERE id = ?", (usage_id,)).fetchone()
    return _priced(row) if row else None


def report(days: int = 30, limit: int = 100) -> dict:
    """Сводка за период: итоги, разбивка по дням и по моделям, последние запросы.

    Считаем в Python, а не в SQL: стоимость зависит от каталога, которого в базе нет.
    """
    since = f"-{max(1, int(days))} days"
    with _lock, _connect() as conn:
        # strftime с нашим форматом, а не datetime(): ts лежит в ISO с «T» и смещением,
        # а datetime('now') вернул бы «YYYY-MM-DD HH:MM:SS» — строки сравнились бы криво.
        rows = conn.execute(
            "SELECT * FROM usage WHERE ts >= strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now', ?) "
            "ORDER BY ts DESC",
            (since,),
        ).fetchall()

    priced = [_priced(r) for r in rows]

    total = {
        "requests": len(priced),
        "input_tokens": sum(p["input_tokens"] or 0 for p in priced),
        "output_tokens": sum(p["output_tokens"] or 0 for p in priced),
        "cached_tokens": sum(p["cached_tokens"] or 0 for p in priced),
        "audio_seconds": round(sum(p["audio_seconds"] or 0 for p in priced), 1),
        "usd": round(sum(p["usd"] or 0 for p in priced), 4),
        "errors": sum(1 for p in priced if not p["ok"]),
        # Сколько запросов посчитать не удалось — модели нет в каталоге или нет длительности.
        "unpriced": sum(1 for p in priced if p["usd"] is None),
    }
    total["credits"] = catalog.credits(total["usd"])

    by_day: dict[str, dict] = {}
    by_model: dict[str, dict] = {}
    for p in priced:
        # unpriced в каждой группе: без него сумма нулей выглядела бы как «бесплатно»,
        # хотя на деле цена неизвестна. Панель по этому счётчику рисует прочерк.
        day = by_day.setdefault(p["ts"][:10],
                                {"day": p["ts"][:10], "requests": 0, "usd": 0.0, "unpriced": 0})
        day["requests"] += 1
        day["unpriced"] += p["usd"] is None
        day["usd"] = round(day["usd"] + (p["usd"] or 0), 6)

        key = f"{p['provider']}/{p['model']}"
        m = by_model.setdefault(key, {
            "key": key, "op": p["op"], "requests": 0, "usd": 0.0, "unpriced": 0,
            "input_tokens": 0, "output_tokens": 0, "audio_seconds": 0.0,
        })
        m["requests"] += 1
        m["unpriced"] += p["usd"] is None
        m["usd"] = round(m["usd"] + (p["usd"] or 0), 6)
        m["input_tokens"] += p["input_tokens"] or 0
        m["output_tokens"] += p["output_tokens"] or 0
        m["audio_seconds"] = round(m["audio_seconds"] + (p["audio_seconds"] or 0), 1)

    return {
        "days": days,
        "credit_usd": catalog.CREDIT_USD,
        "total": total,
        "by_day": sorted(by_day.values(), key=lambda d: d["day"]),
        "by_model": sorted(by_model.values(), key=lambda m: -m["usd"]),
        "recent": priced[:limit],
    }
