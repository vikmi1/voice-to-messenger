"""Voice-to-Messenger — бэкенд.

Эндпоинты:
  GET  /                  — health-check
  POST /api/process       — текст → чистое сообщение (стриминг), Bearer-токен
  POST /api/transcribe    — аудио → сырой текст (Whisper), Bearer-токен
  GET  /admin             — админ-панель (Basic Auth)
  GET  /admin/api/config  — текущая конфигурация (Basic Auth)
  POST /admin/api/config  — сохранить конфигурацию (Basic Auth)
"""

import functools
import os

from dotenv import load_dotenv
from flask import Flask, Response, jsonify, render_template, request

import config_store
from prompts import build_system
from providers.openai_compatible import OpenAICompatibleProvider, transcribe_audio

load_dotenv()

ADMIN_USER = os.environ.get("ADMIN_USER", "admin")
ADMIN_PASS = os.environ.get("ADMIN_PASS", "changeme")

app = Flask(__name__)
app.secret_key = os.environ.get("SECRET_KEY", "dev-insecure-key")

config_store.init_db()
if ADMIN_PASS == "changeme":
    app.logger.warning("ADMIN_PASS не задан — используется небезопасный пароль по умолчанию!")


# --- Авторизация -----------------------------------------------------------

def require_admin(view):
    @functools.wraps(view)
    def wrapper(*args, **kwargs):
        auth = request.authorization
        if not auth or auth.username != ADMIN_USER or auth.password != ADMIN_PASS:
            return Response(
                "Требуется авторизация", 401,
                {"WWW-Authenticate": 'Basic realm="voice-to-messenger admin"'},
            )
        return view(*args, **kwargs)
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

    style_id = data.get("style") or "neutral"
    target = data.get("target_lang") or "off"
    style_prompt = next((s["prompt"] for s in cfg["styles"] if s["id"] == style_id), "")
    lang_name = next((l["name"] for l in cfg["languages"] if l["code"] == target), target)
    system = build_system(cfg["base_prompt"], style_prompt, target, lang_name)

    prov = cfg["providers"][cfg["active_provider"]]
    provider = OpenAICompatibleProvider(prov["base_url"], prov["api_key"], prov["model"])

    def generate():
        try:
            for chunk in provider.stream(system, text):
                yield chunk
        except Exception as exc:  # noqa: BLE001 — отдаём ошибку в поток
            yield f"\n[Ошибка обработки: {exc}]"

    return Response(generate(), mimetype="text/plain; charset=utf-8")


@app.post("/api/transcribe")
@require_token
def api_transcribe():
    cfg = request.cfg
    if "file" not in request.files:
        return jsonify(error="no audio file"), 400
    audio = request.files["file"]

    asr = cfg.get("asr", {})
    prov = cfg["providers"].get(asr.get("provider", cfg["active_provider"]))
    if not prov:
        return jsonify(error="asr provider not configured"), 400

    try:
        text = transcribe_audio(
            prov["base_url"], prov["api_key"], asr.get("model", "whisper-1"),
            audio.read(), filename=audio.filename or "audio.m4a",
            content_type=audio.mimetype or "audio/m4a",
        )
    except Exception as exc:  # noqa: BLE001
        return jsonify(error=str(exc)), 502
    return jsonify(text=text)


# --- Админ-панель ----------------------------------------------------------

@app.get("/admin")
@require_admin
def admin_page():
    return render_template("admin.html")


@app.get("/admin/api/config")
@require_admin
def admin_get_config():
    return jsonify(config_store.load())


@app.post("/admin/api/config")
@require_admin
def admin_save_config():
    incoming = request.get_json(force=True, silent=True)
    if not isinstance(incoming, dict):
        return jsonify(error="invalid config"), 400
    config_store.save(incoming)
    return jsonify(status="saved")


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", 8000)), debug=True)
