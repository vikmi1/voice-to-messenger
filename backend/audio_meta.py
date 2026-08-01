"""Длительность аудио из заголовка файла — без распаковки самого звука.

Нужна для учёта расхода: распознавание тарифицируется по минутам, а провайдер длительность
сообщает не всегда (проверено на проде 01.08.2026: voxtral её не отдаёт).

До 01.08.2026 длительность в таких случаях оценивалась по размеру файла, исходя из битрейта,
который приложение просит у рекордера (32 кбит/с). Для его собственных записей оценка близка
к правде, но она держится на двух допущениях, ни одно из которых не гарантировано:
`setAudioEncodingBitRate` — только пожелание, кодер устройства волен писать плотнее, и чужой
файл вообще приходит в своём формате. Измерено на синтетических записях 01.08.2026: тот же
звук в 64 кбит/с оценивается как 40 с вместо 24.5, трёхсекундный WAV — как 24 с.

Заголовок контейнера знает точную длительность, поэтому спрашиваем его, а не арифметику:
на деньгах гадать незачем, когда точное число лежит в первых килобайтах файла.

Разбираем руками, без сторонних библиотек: нужно ровно одно число, а mutagen/ffprobe — это
лишняя зависимость в образе ради двух десятков строк. Формат не распознан — возвращаем None,
вызывающий откатится на оценку по размеру.
"""

from typing import Iterator

# Длительность в mvhd 32-битной версии; 0xFFFFFFFF — «неизвестна», а не 49 суток.
_UNKNOWN_32 = 0xFFFFFFFF
_UNKNOWN_64 = 0xFFFFFFFFFFFFFFFF

# Выше этого считаем, что заголовок разобран криво: наши записи — минуты, не сутки.
_SANE_MAX_SECONDS = 24 * 3600


def duration_seconds(data: bytes) -> float | None:
    """Точная длительность в секундах из заголовка. None — формат неизвестен или заголовок битый."""
    for reader in (_mp4_seconds, _wav_seconds):
        try:
            seconds = reader(data)
        except Exception:  # noqa: BLE001 — битый файл не должен ронять запрос пользователя
            continue
        if seconds is not None and 0 < seconds <= _SANE_MAX_SECONDS:
            return seconds
    return None


# --- MP4 / M4A (то, что пишет приложение) ----------------------------------

def _boxes(data: bytes, start: int = 0, end: int | None = None) -> Iterator[tuple[bytes, int, int]]:
    """Боксы MP4 на одном уровне вложенности: (тип, начало содержимого, конец бокса)."""
    end = len(data) if end is None else end
    pos = start
    while pos + 8 <= end:
        size = int.from_bytes(data[pos:pos + 4], "big")
        box_type = data[pos + 4:pos + 8]
        header = 8
        if size == 1:  # 64-битный размер лежит сразу за типом
            if pos + 16 > end:
                return
            size = int.from_bytes(data[pos + 8:pos + 16], "big")
            header = 16
        elif size == 0:  # последний бокс, тянется до конца файла
            size = end - pos
        if size < header or pos + size > end:
            return
        yield box_type, pos + header, pos + size
        pos += size


def _mp4_seconds(data: bytes) -> float | None:
    """Длительность из moov/mvhd. Это длительность всего фильма, у нас в нём одна аудиодорожка."""
    for box_type, body, box_end in _boxes(data):
        if box_type != b"moov":
            continue
        for inner_type, inner_body, inner_end in _boxes(data, body, box_end):
            if inner_type == b"mvhd":
                return _mvhd_seconds(data, inner_body, inner_end)
    return None


def _mvhd_seconds(data: bytes, start: int, end: int) -> float | None:
    if start + 4 > end:
        return None
    version = data[start]
    # За версией и флагами идут время создания и время правки, дальше — то, что нам нужно.
    # В версии 1 времена 64-битные, поэтому смещение и ширина длительности другие.
    if version == 1:
        offset, width, unknown = start + 4 + 16, 8, _UNKNOWN_64
    else:
        offset, width, unknown = start + 4 + 8, 4, _UNKNOWN_32
    if offset + 4 + width > end:
        return None
    timescale = int.from_bytes(data[offset:offset + 4], "big")
    duration = int.from_bytes(data[offset + 4:offset + 4 + width], "big")
    if not timescale or not duration or duration == unknown:
        return None
    return duration / timescale


# --- WAV (чем удобно дёргать API руками из curl) ---------------------------

def _wav_seconds(data: bytes) -> float | None:
    """Длительность как размер звуковых данных, делённый на байты в секунду из заголовка fmt."""
    if len(data) < 12 or data[0:4] != b"RIFF" or data[8:12] != b"WAVE":
        return None

    byte_rate = data_size = None
    pos = 12
    while pos + 8 <= len(data):
        chunk_id = data[pos:pos + 4]
        chunk_size = int.from_bytes(data[pos + 4:pos + 8], "little")
        body = pos + 8
        if chunk_id == b"fmt " and body + 12 <= len(data):
            byte_rate = int.from_bytes(data[body + 8:body + 12], "little")
        elif chunk_id == b"data":
            # Размер из заголовка, но не больше того, что реально дошло: у оборванной
            # записи в заголовке остаётся обещанная длина, а байтов меньше.
            data_size = min(chunk_size, len(data) - body)
            break
        pos = body + chunk_size + (chunk_size % 2)  # чанки выровнены по чётной границе

    if not byte_rate or not data_size:
        return None
    return data_size / byte_rate
