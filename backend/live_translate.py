"""Живой перевод: мост между приложением и Voxtral Realtime.

Приложение открывает WebSocket на `/api/live` и льёт в него PCM 16 кГц с микрофона.
Мы держим второй WebSocket — к realtime-распознаванию Mistral, — режем поток
распознанного текста на смысловые куски и переводим каждый кусок отдельной
(дешёвой и быстрой) моделью. Если включена озвучка, перевод ещё и синтезируется
в mp3 и уходит обратно в наушник.

Почему не одна модель: живого перевода «звук → звук» у Mistral нет. Realtime-модель
только распознаёт (зато сама определяет язык говорящего), перевод и синтез —
отдельные вызовы. Отсюда и три строки расхода на сессию: live_stt, live_translate,
live_tts.

Протокол с приложением
    приложение → сервер
        {"type":"start","target_lang":"ru","speak":true}   первым сообщением
        <binary>                                           PCM_S16LE, 16 кГц, моно
        {"type":"config","target_lang":"de","speak":false} смена на ходу
        {"type":"stop"}                                    штатное завершение
    сервер → приложение
        {"type":"ready","limit_seconds":600}
        {"type":"language","code":"en"}          язык говорящего, определён моделью
        {"type":"partial","text":"…"}            распознанное, ещё не переведённое
        {"type":"unit","id":1,"original":"…"}    кусок закрыт, ушёл на перевод
        {"type":"translation","id":1,"text":"…"}
        {"type":"audio","id":1,"format":"mp3","data":"<base64>"}
        {"type":"credits","spent":42}
        {"type":"limit"}                         вышло время сессии, дальше закрытие
        {"type":"error","message":"…"}

Протокол с Mistral (`/v1/audio/transcriptions/realtime`) снят с официального SDK
`mistralai.extra.realtime` — сюда он перенесён вручную, чтобы не тащить в образ
весь SDK ради полутора десятков строк JSON.
"""

import asyncio
import base64
import json
import logging
import threading
import time
from urllib.parse import quote

import websockets

import catalog
import usage_store
import voices
from providers.openai_compatible import complete, synthesize_speech

log = logging.getLogger(__name__)

#: Формат аудио, который шлёт приложение и ждёт realtime-модель.
SAMPLE_RATE = 16_000
_BYTES_PER_SECOND = SAMPLE_RATE * 2  # 16 бит моно

#: Молчание после последней порции текста, после которого фраза считается законченной.
#: Меньше — перевод дробится на обрывки, больше — растёт отставание от говорящего.
#:
#: 1.8, а не 1.2: распознавание отдаёт слова пачками с промежутками, и на коротком
#: пороге его собственная пауза резала фразу («…and I am very glad to» | «meet you
#: today»). Главный ориентир всё равно знаки препинания — этот таймер лишь страхует
#: речь без них.
_UNIT_SILENCE_S = 1.8

#: Сколько ждать после последнего звука речи, прежде чем считать, что говорящий замолчал.
#:
#: Приложение шлёт звук только пока слышит речь (ворота тишины на устройстве), поэтому
#: «давно не было звука» — надёжный признак паузы. По этому же событию досылаем flush,
#: см. ниже.
_VOICE_TAIL_S = 0.8

#: Длина, после которой кусок отдаём на перевод, не дожидаясь точки: иначе на
#: безостановочной речи перевод не появится вовсе.
_UNIT_MAX_CHARS = 220
_UNIT_MIN_CHARS = 12

_SENTENCE_END = ".!?…。！？"

#: Как часто пересчитывать и слать в приложение накопленную стоимость сессии.
_CREDITS_EVERY_S = 5.0

#: Как часто напоминать распознаванию о себе, если приложение молчит (см. _pump_client).
_KEEPALIVE_S = 10.0
_SILENCE = b"\x00" * (SAMPLE_RATE // 10 * 2)  # 100 мс тишины

#: Названия языков для промпта перевода. Список закрытый — ровно то, что
#: предлагается в приложении.
LANG_NAMES = {
    "ru": "русский",
    "de": "немецкий",
    "en": "английский",
    "it": "итальянский",
    "zh": "китайский",
}

DEFAULT_LIMIT_MINUTES = 10


class LiveConfigError(RuntimeError):
    """Живой перевод не настроен — текст показывается пользователю как есть."""


# --- Настройки -------------------------------------------------------------

def resolve(cfg: dict, api_key_for) -> dict:
    """Собирает рабочие параметры живого перевода из конфига.

    [api_key_for] — функция (имя провайдера, карточка) → ключ; в app.py она умеет
    подставлять ключ из переменной окружения, поэтому передаётся снаружи.
    """
    live = cfg.get("live") or {}
    providers = cfg.get("providers") or {}

    def part(role: str, model_field: str) -> dict:
        name = live.get(f"{role}_provider") or ""
        prov = providers.get(name)
        if not prov:
            raise LiveConfigError(
                f"Для живого перевода не выбран провайдер «{role}». "
                "Открой админ-панель → «Живой перевод».",
            )
        model = (live.get(model_field) or "").strip()
        if not model:
            raise LiveConfigError(
                f"Для живого перевода не задана модель «{model_field}». "
                "Открой админ-панель → «Живой перевод».",
            )
        key = api_key_for(name, prov)
        if not key:
            raise LiveConfigError(f"У провайдера «{name}» не задан API-ключ.")
        return {
            "provider": name,
            "base_url": (prov.get("base_url") or "").rstrip("/"),
            "api_key": key,
            "model": model,
        }

    limit = live.get("session_limit_minutes")
    try:
        limit = max(1, min(120, int(limit)))
    except (TypeError, ValueError):
        limit = DEFAULT_LIMIT_MINUTES

    # Озвучка необязательна: без неё живой перевод работает как бегущий текст.
    tts = part("tts", "tts_model") if (live.get("tts_provider") and live.get("tts_model")) else None

    # Настроение отдельным полем понимает только часть провайдеров; у остальных оно
    # выбирается самим голосом, и лишнее поле в запросе даёт 400.
    instructions = ""
    if tts and voices.supports_instructions(tts["base_url"]):
        instructions = voices.instructions_for(live.get("tts_mood") or "")

    stt = part("stt", "stt_model")
    # Эндпоинт `/audio/transcriptions/realtime` есть не у всех: у OpenAI потоковое
    # распознавание живёт по другому адресу и говорит на другом протоколе, и попытка
    # подключиться к нему заканчивается невнятным «HTTP 503» из их шлюза.
    if catalog.has_realtime_asr(stt["base_url"]) is False:
        raise LiveConfigError(
            f"У провайдера «{stt['provider']}» нет потокового распознавания: "
            "WebSocket /audio/transcriptions/realtime на 06.08.2026 есть только у Mistral. "
            "Открой админ-панель → «Живой перевод» и выбери Mistral для распознавания "
            "(перевод и озвучку можно оставить любые).",
        )

    return {
        "stt": stt,
        "translate": part("translate", "translate_model"),
        "tts": tts,
        # strip: голос нередко попадает в настройки копипастом с пробелом по краям,
        # а API отвечает на такой id 404 «Voice not found».
        "tts_voice": (live.get("tts_voice") or "").strip(),
        "tts_instructions": instructions,
        "limit_seconds": limit * 60,
    }


# --- Сборка кусков ---------------------------------------------------------

class _Assembler:
    """Копит распознанный текст и решает, когда кусок пора переводить.

    Realtime-модель отдаёт текст порциями и не обещает знаков препинания, поэтому
    границу ищем тремя способами сразу: конец предложения, накопившаяся длина и
    пауза в речи. Событие `transcription.segment` (если приходит) используем только
    как сигнал границы, а текст берём из дельт — иначе одно и то же попало бы в
    перевод дважды.
    """

    def __init__(self) -> None:
        self.buffer = ""
        self.updated_at = time.monotonic()

    def add(self, text: str) -> str | None:
        if not text:
            return None
        self.buffer += text
        self.updated_at = time.monotonic()

        stripped = self.buffer.strip()
        if len(stripped) >= _UNIT_MIN_CHARS and stripped[-1] in _SENTENCE_END:
            return self.take()
        if len(stripped) >= _UNIT_MAX_CHARS:
            return self.take()
        return None

    def due_by_silence(self) -> str | None:
        """Кусок, закрытый паузой: говорящий замолчал, ждать точку больше незачем."""
        if not self.buffer.strip():
            return None
        if time.monotonic() - self.updated_at < _UNIT_SILENCE_S:
            return None
        return self.take()

    def take(self) -> str | None:
        text = self.buffer.strip()
        self.buffer = ""
        self.updated_at = time.monotonic()
        return text or None


# --- Учёт расхода ----------------------------------------------------------

class _Ledger:
    """Расход сессии. Копим и пишем тремя строками в конце, а не по куску.

    Один разговор — это десятки кусков; отдельная строка журнала на каждый
    превратила бы вкладку «Расход» в ленту из сотен записей ни о чём.
    """

    def __init__(self, parts: dict) -> None:
        self.parts = parts
        self.audio_bytes = 0
        self.input_tokens = 0
        self.output_tokens = 0
        self.tts_chars = 0

    @property
    def audio_seconds(self) -> float:
        return round(self.audio_bytes / _BYTES_PER_SECOND, 1)

    def usd(self) -> float:
        stt, tr, tts = self.parts["stt"], self.parts["translate"], self.parts["tts"]
        total = catalog.asr_cost_usd(stt["base_url"], stt["model"], self.audio_seconds) or 0.0
        total += catalog.llm_cost_usd(
            tr["base_url"], tr["model"], self.input_tokens, self.output_tokens,
        ) or 0.0
        if tts:
            total += catalog.tts_cost_usd(tts["base_url"], tts["model"], self.tts_chars) or 0.0
        return total

    def credits(self) -> int:
        return catalog.credits(self.usd()) or 0

    def flush(self, session_id: str, target_lang: str, error: str | None = None) -> None:
        """Пишет строки расхода. Вызывается один раз, в finally — даже если связь оборвалась."""
        stt, tr, tts = self.parts["stt"], self.parts["translate"], self.parts["tts"]
        ok = 0 if error else 1
        if self.audio_bytes:
            usage_store.record(
                id=f"{session_id}-stt", op="live_stt", provider=stt["provider"],
                base_url=stt["base_url"], model=stt["model"],
                audio_bytes=self.audio_bytes, audio_seconds=self.audio_seconds,
                audio_seconds_source="stream", target_lang=target_lang, ok=ok, error=error,
            )
        if self.input_tokens or self.output_tokens:
            usage_store.record(
                id=f"{session_id}-tr", op="live_translate", provider=tr["provider"],
                base_url=tr["base_url"], model=tr["model"],
                input_tokens=self.input_tokens, output_tokens=self.output_tokens,
                target_lang=target_lang, ok=ok, error=error,
            )
        if tts and self.tts_chars:
            usage_store.record(
                id=f"{session_id}-tts", op="live_tts", provider=tts["provider"],
                base_url=tts["base_url"], model=tts["model"],
                chars=self.tts_chars, target_lang=target_lang, ok=ok, error=error,
            )


# --- Мост ------------------------------------------------------------------

def _prompt(target_lang: str) -> str:
    name = LANG_NAMES.get(target_lang, target_lang)
    return (
        f"Ты синхронный переводчик. Переводи реплику на {name} язык.\n"
        "Правила: отдавай ТОЛЬКО перевод — без пояснений, без кавычек, без оригинала. "
        "Держи разговорный тон говорящего, ничего не добавляй и не сокращай. "
        "Реплика может быть оборвана на полуслове — переводи как есть, не дописывай. "
        "Если переводить нечего, верни пустую строку."
    )


class Bridge:
    """Одна сессия живого перевода. Живёт в потоке воркера, внутри — свой цикл asyncio."""

    def __init__(self, client, parts: dict, session_id: str, target_lang: str, speak: bool):
        self.client = client          # WebSocket приложения (flask-sock, блокирующий)
        self.parts = parts
        self.session_id = session_id
        self.target_lang = target_lang if target_lang in LANG_NAMES else "ru"
        # Что просило приложение и что мы реально можем — разные вещи: без модели
        # синтеза в конфиге озвучки не будет, и об этом надо сказать вслух.
        self.speak_requested = bool(speak)
        self.speak = speak and parts["tts"] is not None

        self.ledger = _Ledger(parts)
        self.assembler = _Assembler()
        self.units: asyncio.Queue = asyncio.Queue()
        self.audio_language: str | None = None
        self.previous_original = ""
        self.unit_no = 0
        self.stopping = False
        # Попросили ли распознавание закончить. Отличает «кусок закрыт» от «сессия
        # закончилась»: событие `transcription.done` приходит и там, и там.
        self.ending = False
        self.error: str | None = None

    # -- отправка в приложение ------------------------------------------

    def _send(self, payload: dict) -> None:
        """Пишет в сокет приложения. Обрыв связи здесь — не ошибка, а конец сессии."""
        try:
            self.client.send(json.dumps(payload, ensure_ascii=False))
        except Exception:  # noqa: BLE001 — приложение свернули или потеряло сеть
            self.stopping = True

    # -- чтение из приложения -------------------------------------------

    def _reader(self, loop: asyncio.AbstractEventLoop, inbox: asyncio.Queue) -> None:
        """Блокирующее чтение сокета приложения в отдельном потоке.

        flask-sock умеет только блокирующий receive(), поэтому переносим его в поток
        и перекладываем сообщения в очередь asyncio.
        """
        try:
            while not self.stopping:
                message = self.client.receive(timeout=1)
                if message is None:  # таймаут: просто проверяем, не пора ли закрываться
                    continue
                loop.call_soon_threadsafe(inbox.put_nowait, message)
        except Exception:  # noqa: BLE001 — клиент отвалился
            pass
        finally:
            loop.call_soon_threadsafe(inbox.put_nowait, None)

    # -- перевод и озвучка ----------------------------------------------

    async def _translate_worker(self, loop: asyncio.AbstractEventLoop) -> None:
        """Переводит куски строго по очереди.

        Последовательно, а не параллельно: иначе фразы приезжали бы в наушник
        вперемешку, а на слух это хуже, чем лишняя секунда задержки.
        """
        while True:
            item = await self.units.get()
            if item is None:
                return
            unit_id, original = item

            # Говорят на том же языке, на который переводим, — платить не за что.
            same = self.audio_language is not None and self.audio_language.lower().startswith(
                self.target_lang,
            )
            if same:
                self._send({"type": "translation", "id": unit_id, "text": original,
                            "same_language": True})
                continue

            part = self.parts["translate"]
            user = original
            if self.previous_original:
                user = (
                    f"Предыдущая реплика (только для контекста, не переводить): "
                    f"{self.previous_original}\n\nПереведи: {original}"
                )
            self.previous_original = original

            try:
                result = await loop.run_in_executor(
                    None, complete, part["base_url"], part["api_key"], part["model"],
                    _prompt(self.target_lang), user,
                )
            except Exception as exc:  # noqa: BLE001 — один кусок не должен ронять сессию
                log.warning("live translate failed: %s", exc)
                self._send({"type": "error", "message": f"Перевод не удался: {exc}"})
                continue

            self.ledger.input_tokens += result.usage.get("input_tokens") or 0
            self.ledger.output_tokens += result.usage.get("output_tokens") or 0
            text = result.text.strip()
            self._send({"type": "translation", "id": unit_id, "text": text})

            if text and self.speak:
                await self._speak(loop, unit_id, text)

    async def _speak(self, loop: asyncio.AbstractEventLoop, unit_id: int, text: str) -> None:
        part = self.parts["tts"]
        try:
            audio = await loop.run_in_executor(
                None, synthesize_speech, part["base_url"], part["api_key"], part["model"],
                text, self.parts["tts_voice"], "mp3", self.parts["tts_instructions"],
            )
        except Exception as exc:  # noqa: BLE001 — без озвучки перевод всё равно виден
            log.warning("live tts failed: %s", exc)
            self._send({"type": "error", "message": f"Озвучка не удалась: {exc}"})
            return
        self.ledger.tts_chars += len(text)
        self._send({
            "type": "audio", "id": unit_id, "format": "mp3",
            "data": base64.b64encode(audio).decode("ascii"),
        })

    def _queue_unit(self, text: str) -> None:
        self.unit_no += 1
        self._send({"type": "unit", "id": self.unit_no, "original": text})
        self.units.put_nowait((self.unit_no, text))

    # -- разбор событий Mistral -----------------------------------------

    def _on_event(self, event: dict) -> bool:
        """Обрабатывает событие распознавания. False — распознавание закончилось."""
        kind = event.get("type")

        if kind == "transcription.text.delta":
            unit = self.assembler.add(event.get("text") or "")
            # Шлём весь накопленный кусок, а не пришедшую дельту: приложению не нужно
            # ничего склеивать, а потерянное по дороге сообщение само себя чинит.
            self._send({"type": "partial", "text": self.assembler.buffer.strip()})
            if unit:
                self._queue_unit(unit)

        elif kind == "transcription.segment":
            # Граница фразы от модели. Текст берём из накопленных дельт; если дельт
            # не было вовсе (модель шлёт только сегменты) — берём текст сегмента.
            unit = self.assembler.take() or (event.get("text") or "").strip() or None
            if unit:
                self._queue_unit(unit)

        elif kind == "transcription.language":
            self.audio_language = event.get("audio_language")
            self._send({"type": "language", "code": self.audio_language})

        elif kind == "transcription.done":
            # Конец куска, а не сессии: Mistral отвечает этим событием на каждый
            # `input_audio.flush`, то есть после каждой замолчавшей фразы. Пока мы
            # считали его концом распознавания, переводилась ровно одна фраза:
            # чтение событий останавливалось, звук продолжал литься в пустоту.
            # Расходимся только если сами попросили закончить (`input_audio.end`).
            unit = self.assembler.take()
            if unit:
                self._queue_unit(unit)
            return not self.ending

        elif kind == "error":
            detail = event.get("error") or {}
            message = detail.get("message") if isinstance(detail, dict) else str(detail)
            self.error = str(message or "ошибка распознавания")
            self._send({"type": "error", "message": self.error})
            # После своей ошибки распознавание рвёт связь с моделью, но сокет ещё
            # открыт. Если продолжать лить в него звук, настоящая причина теряется:
            # человек видит не «Cannot flush audio…», а «1011 Upstream connection
            # error» от следующей отправки. Останавливаемся сразу.
            self.stopping = True
            return False

        return True

    # -- основной цикл ---------------------------------------------------

    async def run(self) -> None:
        speak_requested = self.speak_requested
        loop = asyncio.get_running_loop()
        inbox: asyncio.Queue = asyncio.Queue()

        stt = self.parts["stt"]
        scheme = "ws" if stt["base_url"].startswith("http://") else "wss"
        host = stt["base_url"].split("://", 1)[-1]
        url = (
            f"{scheme}://{host}/audio/transcriptions/realtime"
            f"?model={quote(stt['model'])}"
        )

        reader = threading.Thread(
            target=self._reader, args=(loop, inbox), name="live-reader", daemon=True,
        )
        reader.start()

        started = time.monotonic()
        try:
            async with websockets.connect(
                url,
                additional_headers={"Authorization": f"Bearer {stt['api_key']}"},
                open_timeout=20,
                max_size=None,
            ) as upstream:
                await self._handshake(upstream)
                self._send({"type": "ready", "limit_seconds": self.parts["limit_seconds"]})
                if speak_requested and not self.speak:
                    # Тумблер озвучки в приложении включён, а модели синтеза в панели нет.
                    # Молчащий наушник без объяснения выглядел бы поломкой.
                    self._send({"type": "error", "message": (
                        "Озвучка не настроена: задай модель синтеза речи в админ-панели "
                        "(«Живой перевод»). Перевод пока только текстом."
                    )})

                worker = asyncio.create_task(self._translate_worker(loop))
                pump = asyncio.create_task(self._pump_upstream(upstream))
                try:
                    await self._pump_client(upstream, inbox, started)
                finally:
                    # Досылаем «аудио кончилось»: модель отдаст остаток текста и закроет поток.
                    self.ending = True
                    try:
                        # Пустой буфер флашить нельзя — распознавание отвечает на это
                        # ошибкой и рвёт связь. Если человек так и не заговорил, сразу end.
                        if self.ledger.audio_bytes:
                            await upstream.send(json.dumps({"type": "input_audio.flush"}))
                        await upstream.send(json.dumps({"type": "input_audio.end"}))
                        await asyncio.wait_for(pump, timeout=8)
                    except Exception:  # noqa: BLE001 — связь могла уже оборваться
                        pump.cancel()
                    # Хвост очереди перевода добираем, но не бесконечно: последняя
                    # фраза договаривается в наушник уже после нажатия «стоп».
                    self.units.put_nowait(None)
                    try:
                        await asyncio.wait_for(worker, timeout=25)
                    except (asyncio.TimeoutError, asyncio.CancelledError):
                        worker.cancel()
        except LiveConfigError:
            raise
        except Exception as exc:  # noqa: BLE001 — показываем причину в приложении
            self.error = str(exc)
            # Секунды и аудио в строке — чтобы по логу отличать обрыв на рукопожатии
            # (звука ещё не было) от обрыва посреди речи: причины у них разные.
            log.error(
                "live session failed after %.1f s, %.1f s of audio, %d units: %s",
                time.monotonic() - started, self.ledger.audio_seconds, self.unit_no, exc,
                exc_info=True,
            )
            self._send({"type": "error", "message": f"Распознавание недоступно: {exc}"})
        finally:
            self.stopping = True
            self.ledger.flush(self.session_id, self.target_lang, self.error)
            self._send({"type": "credits", "spent": self.ledger.credits()})
            self._send({"type": "done"})

    async def _handshake(self, upstream) -> None:
        """Ждём session.created и сразу сообщаем формат аудио."""
        while True:
            raw = await asyncio.wait_for(upstream.recv(), timeout=20)
            event = _decode(raw)
            if event.get("type") == "session.created":
                break
            if event.get("type") == "error":
                detail = event.get("error") or {}
                raise RuntimeError(
                    detail.get("message") if isinstance(detail, dict) else str(detail),
                )
        await upstream.send(json.dumps({
            "type": "session.update",
            "session": {
                "audio_format": {"encoding": "pcm_s16le", "sample_rate": SAMPLE_RATE},
            },
        }))

    async def _pump_upstream(self, upstream) -> None:
        """События распознавания → приложение."""
        async for raw in upstream:
            if not self._on_event(_decode(raw)):
                return

    async def _pump_client(self, upstream, inbox: asyncio.Queue, started: float) -> None:
        """Аудио и команды приложения → распознавание."""
        last_credits = time.monotonic()
        last_audio = time.monotonic()
        # Отдельно от last_audio: тот сдвигает и служебная тишина keepalive, а здесь
        # нужен именно последний звук живой речи.
        last_voice = time.monotonic()
        # Просили ли уже отдать хвост текущей фразы. Сбрасывается новой речью.
        #
        # True на старте, хотя ничего ещё не просили: между «сессия готова» и первым
        # словом человека проходит больше _VOICE_TAIL_S, и с False мы просили бы
        # распознавание дослать хвост пустого буфера — ещё до первой порции звука.
        flushed = True
        while not self.stopping:
            try:
                message = await asyncio.wait_for(inbox.get(), timeout=0.3)
            except asyncio.TimeoutError:
                message = _NOTHING

            if message is None:  # приложение закрыло сокет
                return

            if message is not _NOTHING:
                if isinstance(message, (bytes, bytearray)):
                    self.ledger.audio_bytes += len(message)
                    last_audio = last_voice = time.monotonic()
                    flushed = False
                    await self._append_audio(upstream, bytes(message))
                elif not self._on_command(message):
                    return

            # Говорящий замолчал.
            if time.monotonic() - last_voice >= _VOICE_TAIL_S:
                # Дослышанные слова модель придерживает: замеры показали, что после
                # конца звука хвост фразы приходит только через ~3 секунды. Просим
                # отдать его сразу — иначе таймер ниже закрыл бы кусок раньше, и хвост
                # приехал бы отдельной репликой («…and I am very glad to» | «meet you
                # today»), которую и переводить приходится дважды, и звучит она рвано.
                if not flushed:
                    flushed = True
                    await upstream.send(json.dumps({"type": "input_audio.flush"}))

                # Пауза закрывает фразу: ждать точку от молчащего смысла нет.
                unit = self.assembler.due_by_silence()
                if unit:
                    self._queue_unit(unit)

            now = time.monotonic()

            # Приложение не шлёт тишину (это главная экономия сессии), но совсем без
            # звука распознавание может счесть сессию брошенной и закрыть её. Раз в
            # KEEPALIVE_S отправляем каплю тишины — 0.1 с на 10 с эфира.
            if now - last_audio >= _KEEPALIVE_S:
                last_audio = now
                self.ledger.audio_bytes += len(_SILENCE)
                await self._append_audio(upstream, _SILENCE)
            if now - last_credits >= _CREDITS_EVERY_S:
                last_credits = now
                self._send({"type": "credits", "spent": self.ledger.credits()})

            if now - started >= self.parts["limit_seconds"]:
                self._send({"type": "limit"})
                return

    async def _append_audio(self, upstream, pcm: bytes) -> None:
        await upstream.send(json.dumps({
            "type": "input_audio.append",
            "audio": base64.b64encode(pcm).decode("ascii"),
        }))

    def _on_command(self, raw: str) -> bool:
        """Текстовая команда приложения. False — пора закрываться."""
        try:
            data = json.loads(raw)
        except (TypeError, ValueError):
            return True
        kind = data.get("type")
        if kind == "stop":
            return False
        if kind == "config":
            lang = data.get("target_lang")
            if lang in LANG_NAMES:
                self.target_lang = lang
            if "speak" in data:
                self.speak = bool(data["speak"]) and self.parts["tts"] is not None
        return True


class _Nothing:
    """Метка «сообщений не было» — отличает таймаут от закрытого сокета (None)."""


_NOTHING = _Nothing()


def _decode(raw) -> dict:
    text = raw.decode("utf-8", "replace") if isinstance(raw, (bytes, bytearray)) else raw
    try:
        data = json.loads(text)
    except (TypeError, ValueError):
        return {}
    return data if isinstance(data, dict) else {}


def serve(client, parts: dict, session_id: str, target_lang: str, speak: bool) -> None:
    """Точка входа из Flask: своя петля asyncio на время сессии."""
    bridge = Bridge(client, parts, session_id, target_lang, speak)
    asyncio.run(bridge.run())
