"""Voice-to-Messenger — бэкенд.

Эндпоинты:
  GET  /                  — health-check
  POST /api/process       — текст → чистое сообщение (стриминг), Bearer-токен
  GET  /api/options       — стили и языки для приложения, Bearer-токен
  POST /api/transcribe    — аудио → сырой текст (Whisper), Bearer-токен
  GET  /api/usage/<id>    — расход конкретного запроса, Bearer-токен
  GET  /admin             — админ-панель (Basic Auth)
  GET  /admin/api/config  — текущая конфигурация (админ-доступ)
  POST /admin/api/config  — сохранить конфигурацию (админ-доступ)
  GET  /admin/api/models  — список моделей провайдера (админ-доступ)
  GET  /admin/api/catalog — подобранные модели с ценами для выпадашек (админ-доступ)
  GET  /admin/api/usage   — сводка расхода за период (админ-доступ)
  POST /admin/api/test    — прогнать текст через текущие настройки (админ-доступ)

Админ-доступ — это Basic Auth (веб) ИЛИ заголовок X-Admin-Token (приложение).
"""

import functools
import os
import secrets
import uuid

from dotenv import load_dotenv
from flask import Flask, Response, jsonify, render_template, request

import audio_meta
import catalog
import config_store
import usage_store
from prompts import build_system
from providers import anthropic as anthropic_provider
from providers.anthropic import AnthropicProvider
from providers.openai_compatible import OpenAICompatibleProvider, transcribe_audio
from providers.openai_compatible import list_models as openai_list_models

load_dotenv()

ADMIN_USER = os.environ.get("ADMIN_USER", "admin")
ADMIN_PASS = os.environ.get("ADMIN_PASS", "changeme")

app = Flask(__name__)
app.secret_key = os.environ.get("SECRET_KEY", "dev-insecure-key")

config_store.init_db()
usage_store.init_db()
if ADMIN_PASS == "changeme":
    app.logger.warning("ADMIN_PASS не задан — используется небезопасный пароль по умолчанию!")


# --- Авторизация -----------------------------------------------------------

def _basic_auth_ok() -> bool:
    auth = request.authorization
    return bool(auth) and auth.username == ADMIN_USER and auth.password == ADMIN_PASS


def _admin_token_ok() -> bool:
    """Админ-токен приложения. Отдельный от api_token: даёт право менять настройки."""
    supplied = request.headers.get("X-Admin-Token", "")
    expected = config_store.load().get("admin_token") or ""
    # Сравниваем байты: compare_digest падает на строках с не-ASCII символами.
    return bool(expected) and secrets.compare_digest(supplied.encode(), expected.encode())


def require_admin(view):
    """Только для страницы панели: браузеру нужен именно Basic Auth с 401-челленджем."""
    @functools.wraps(view)
    def wrapper(*args, **kwargs):
        if not _basic_auth_ok():
            return Response(
                "Требуется авторизация", 401,
                {"WWW-Authenticate": 'Basic realm="voice-to-messenger admin"'},
            )
        return view(*args, **kwargs)
    return wrapper


def require_admin_api(view):
    """Для админского API: пускаем и браузер (Basic Auth), и приложение (X-Admin-Token)."""
    @functools.wraps(view)
    def wrapper(*args, **kwargs):
        if _basic_auth_ok() or _admin_token_ok():
            return view(*args, **kwargs)
        if request.headers.get("X-Admin-Token"):
            return jsonify(error="unauthorized"), 401
        return Response(
            "Требуется авторизация", 401,
            {"WWW-Authenticate": 'Basic realm="voice-to-messenger admin"'},
        )
    return wrapper


def require_token(view):
    @functools.wraps(view)
    def wrapper(*args, **kwargs):
        cfg = config_store.load()
        header = request.headers.get("Authorization", "")
        token = header[7:] if header.startswith("Bearer ") else ""
        if not cfg.get("api_token") or token != cfg["api_token"]:
            return jsonify(error="unauthorized"), 401
        request.cfg = cfg  # прокидываем уже загруженный конфиг во view
        return view(*args, **kwargs)
    return wrapper


def resolve_api_key(name: str, prov: dict) -> str:
    """Ключ из конфига, а если пуст — из переменной окружения <ПРОВАЙДЕР>_API_KEY.

    Позволяет держать ключи в .env (не попадает в git), а не только в базе.
    """
    return prov.get("api_key") or os.environ.get(f"{name.upper()}_API_KEY", "")


def make_provider(name: str, prov: dict):
    """Собирает провайдера нужного типа. kind: 'anthropic' или 'openai' (по умолчанию)."""
    api_key = resolve_api_key(name, prov)
    if prov.get("kind") == "anthropic":
        return AnthropicProvider(prov["base_url"], api_key, prov["model"])
    return OpenAICompatibleProvider(prov["base_url"], api_key, prov["model"])


def _audio_duration(file_bytes: bytes, from_provider: float | None) -> tuple[float, str]:
    """Длительность аудио и откуда она взялась — для тарификации распознавания.

    По убыванию доверия: сказал провайдер → прочитали в заголовке файла → прикинули по размеру.
    Источник пишем в журнал рядом с числом, чтобы потом не гадать, насколько ему верить.
    """
    if from_provider is not None:
        return from_provider, "provider"
    exact = audio_meta.duration_seconds(file_bytes)
    if exact is not None:
        return round(exact, 1), "container"
    return usage_store.estimate_seconds(len(file_bytes)), "estimate"


def _examples(data: dict) -> list[str]:
    """Few-shot-примеры для «Моего стиля»: не больше 8 непустых строк из тела запроса."""
    raw = data.get("examples")
    if not isinstance(raw, list):
        return []
    return [str(e).strip() for e in raw if str(e).strip()][:8]


# --- Публичные эндпоинты для приложения ------------------------------------

@app.get("/")
def health():
    return jsonify(status="ok", service="voice-to-messenger")


@app.post("/api/process")
@require_token
def api_process():
    cfg = request.cfg
    data = request.get_json(force=True, silent=True) or {}
    text = (data.get("text") or "").strip()
    if not text:
        return jsonify(error="empty text"), 400

    style_id = data.get("style") or "mine"
    target = data.get("target_lang") or "off"
    style_prompt = next((s["prompt"] for s in cfg["styles"] if s["id"] == style_id), "")
    lang_name = next((l["name"] for l in cfg["languages"] if l["code"] == target), target)
    system = build_system(
        cfg["base_prompt"], style_prompt, target, lang_name,
        examples=_examples(data), emoji=bool(data.get("emoji")), as_list=bool(data.get("as_list")),
    )

    name = cfg["active_provider"]
    prov = cfg["providers"][name]
    provider = make_provider(name, prov)

    # Всё, что нужно журналу, снимаем здесь: генератор ниже работает уже вне
    # контекста запроса, там ни request, ни cfg трогать нельзя.
    usage_id = uuid.uuid4().hex
    meta = {
        "id": usage_id, "op": "process", "provider": name,
        "base_url": prov.get("base_url") or "", "model": prov.get("model") or "",
        "style": style_id, "target_lang": target,
    }

    def generate():
        ok, error = 1, None
        try:
            for chunk in provider.stream(system, text):
                yield chunk
        except Exception as exc:  # noqa: BLE001 — отдаём ошибку в поток
            ok, error = 0, str(exc)
            yield f"\n[Ошибка обработки: {exc}]"
        finally:
            # finally, а не хвост try: сюда попадаем и когда клиент отвалился на середине —
            # токены к этому моменту уже потрачены и должны попасть в журнал.
            usage_store.record(**meta, ok=ok, error=error, **provider.last_usage)

    return Response(
        generate(),
        mimetype="text/plain; charset=utf-8",
        # Заголовки уходят до тела, поэтому приложение знает id ещё до конца стрима
        # и может спросить стоимость через /api/usage/<id>, когда текст дочитан.
        headers={"X-Usage-Id": usage_id},
    )


@app.get("/api/usage/<usage_id>")
@require_token
def api_usage(usage_id: str):
    """Сколько стоил конкретный запрос — для строки «потрачено кредитов» в приложении."""
    row = usage_store.get(usage_id)
    if not row:
        return jsonify(error="unknown usage id"), 404
    return jsonify(
        id=row["id"], credits=row["credits"], usd=row["usd"],
        input_tokens=row["input_tokens"], output_tokens=row["output_tokens"],
        audio_seconds=row["audio_seconds"],
    )


@app.get("/api/options")
@require_token
def api_options():
    """Стили и языки для приложения — чтобы правки в панели не требовали пересборки APK."""
    cfg = request.cfg
    return jsonify(
        styles=[{"id": s["id"], "name": s["name"]} for s in cfg["styles"]],
        languages=[{"code": l["code"], "name": l["name"]} for l in cfg["languages"]],
    )


@app.post("/api/transcribe")
@require_token
def api_transcribe():
    cfg = request.cfg
    if "file" not in request.files:
        return jsonify(error="no audio file"), 400
    audio = request.files["file"]

    asr = cfg.get("asr", {})
    asr_name = asr.get("provider", cfg["active_provider"])
    prov = cfg["providers"].get(asr_name)
    if not prov:
        return jsonify(error="asr provider not configured"), 400
    if prov.get("kind") == "anthropic":
        # У Anthropic нет /audio/transcriptions — без этой проверки был бы 502 без объяснений.
        return jsonify(
            error=f"Провайдер «{asr_name}» (Anthropic) не умеет распознавать речь. "
                  "Выбери OpenAI-совместимого провайдера на вкладке «Распознавание».",
        ), 400

    # Модель распознавания живёт в карточке провайдера: у каждого она своя.
    model = (prov.get("asr_model") or "").strip() or catalog.default_asr_model(prov.get("base_url"))
    if not model:
        return jsonify(
            error=f"У провайдера «{asr_name}» не задана модель распознавания. "
                  "Впиши её в карточке провайдера на вкладке «Провайдеры».",
        ), 400

    file_bytes = audio.read()
    app.logger.info(
        "transcribe: provider=%s model=%s filename=%s mime=%s size=%d",
        asr_name, model, audio.filename, audio.mimetype, len(file_bytes),
    )
    usage_id = uuid.uuid4().hex
    meta = {
        "id": usage_id, "op": "transcribe", "provider": asr_name,
        "base_url": prov.get("base_url") or "", "model": model,
        "audio_bytes": len(file_bytes),
    }
    try:
        result = transcribe_audio(
            prov["base_url"], resolve_api_key(asr_name, prov), model,
            file_bytes, filename=audio.filename or "audio.m4a",
            content_type=audio.mimetype or "audio/m4a",
        )
    except Exception as exc:  # noqa: BLE001
        app.logger.error("transcribe error: %s", exc, exc_info=True)
        # Неудачный запрос тоже пишем: провайдер мог успеть посчитать аудио, а нам важно
        # видеть, что деньги ушли в ошибку. Провайдер тут ничего не сказал — считаем сами.
        seconds, source = _audio_duration(file_bytes, None)
        usage_store.record(**meta, ok=0, error=str(exc),
                           audio_seconds=seconds, audio_seconds_source=source)
        return jsonify(error=str(exc)), 502

    seconds, source = _audio_duration(file_bytes, result.seconds)
    usage_store.record(**meta, audio_seconds=seconds, audio_seconds_source=source)

    app.logger.info("transcribe ok: %d chars", len(result.text))
    return jsonify(text=result.text, usage_id=usage_id)


# --- Админ-панель ----------------------------------------------------------

@app.get("/admin")
@require_admin
def admin_page():
    return render_template("admin.html")


@app.get("/admin/api/config")
@require_admin_api
def admin_get_config():
    return jsonify(config_store.load())


@app.post("/admin/api/config")
@require_admin_api
def admin_save_config():
    incoming = request.get_json(force=True, silent=True)
    if not isinstance(incoming, dict):
        return jsonify(error="invalid config"), 400
    if not incoming.get("admin_token"):
        # Пустой admin_token закрыл бы доступ к панели из приложения навсегда.
        return jsonify(error="admin_token не может быть пустым"), 400
    config_store.save(incoming)
    return jsonify(status="saved")


@app.get("/admin/api/models")
@require_admin_api
def admin_list_models():
    """Живой список моделей провайдера — чтобы не вбивать ID модели руками."""
    cfg = config_store.load()
    name = request.args.get("provider") or cfg["active_provider"]
    prov = cfg["providers"].get(name)
    if not prov:
        return jsonify(error="unknown provider"), 404

    api_key = resolve_api_key(name, prov)
    lister = anthropic_provider.list_models if prov.get("kind") == "anthropic" else openai_list_models
    try:
        models = lister(prov["base_url"], api_key)
    except Exception as exc:  # noqa: BLE001 — показываем причину прямо в панели
        return jsonify(error=str(exc)), 502
    return jsonify(provider=name, models=sorted(models))


@app.get("/admin/api/catalog")
@require_admin_api
def admin_catalog():
    """Подобранные модели с ценами — для выпадашек в обеих панелях.

    Отдаём с сервера, чтобы обновление цен не требовало пересборки приложения.
    """
    return jsonify(catalog=catalog.CATALOG, tiers=catalog.TIER_TITLES)


@app.post("/admin/api/test")
@require_admin_api
def admin_test():
    """Прогон текста через сохранённые настройки — результат целиком, без стриминга.

    provider/model можно переопределить в теле запроса, чтобы сравнить модели,
    ничего не сохраняя.
    """
    cfg = config_store.load()
    data = request.get_json(force=True, silent=True) or {}
    text = (data.get("text") or "").strip()
    if not text:
        return jsonify(error="empty text"), 400

    name = data.get("provider") or cfg["active_provider"]
    prov = cfg["providers"].get(name)
    if not prov:
        return jsonify(error="unknown provider"), 404
    if data.get("model"):
        prov = {**prov, "model": data["model"]}

    style_id = data.get("style") or "mine"
    target = data.get("target_lang") or "off"
    style_prompt = next((s["prompt"] for s in cfg["styles"] if s["id"] == style_id), "")
    lang_name = next((l["name"] for l in cfg["languages"] if l["code"] == target), target)
    system = build_system(
        cfg["base_prompt"], style_prompt, target, lang_name,
        examples=_examples(data), emoji=bool(data.get("emoji")), as_list=bool(data.get("as_list")),
    )

    provider = make_provider(name, prov)
    # Прогоны из панели тоже стоят денег и тоже идут в журнал (op='test'), иначе
    # сравнение моделей тратило бы бюджет невидимо для отчёта.
    meta = {
        "id": uuid.uuid4().hex, "op": "test", "provider": name,
        "base_url": prov.get("base_url") or "", "model": prov["model"],
        "style": style_id, "target_lang": target,
    }
    try:
        result = "".join(provider.stream(system, text))
    except Exception as exc:  # noqa: BLE001
        app.logger.error("test error: %s", exc, exc_info=True)
        usage_store.record(**meta, ok=0, error=str(exc), **provider.last_usage)
        return jsonify(error=str(exc)), 502
    usage_store.record(**meta, **provider.last_usage)

    row = usage_store.get(meta["id"]) or {}
    return jsonify(text=result, provider=name, model=prov["model"],
                   credits=row.get("credits"), usd=row.get("usd"),
                   input_tokens=row.get("input_tokens"), output_tokens=row.get("output_tokens"))


@app.get("/admin/api/usage")
@require_admin_api
def admin_usage():
    """Сводка расхода за период — для вкладки «Расход» в панелях."""
    def as_int(key: str, default: int, top: int) -> int:
        raw = request.args.get(key)
        return min(top, max(1, int(raw))) if (raw or "").isdigit() else default

    # Сдвиг пояса панели от UTC, чтобы «сегодня» и разбивка по дням совпадали с часами
    # того, кто смотрит. isdigit() не годится — сдвиг бывает отрицательным.
    raw_tz = request.args.get("tz_offset") or ""
    try:
        tz_offset = max(-14 * 60, min(14 * 60, int(raw_tz)))
    except ValueError:
        tz_offset = 0

    return jsonify(usage_store.report(
        days=as_int("days", 30, 365), limit=as_int("limit", 100, 500),
        tz_offset_minutes=tz_offset,
    ))


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", 8000)), debug=True)
