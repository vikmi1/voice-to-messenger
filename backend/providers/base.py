"""Абстракция LLM-провайдера.

Каждый провайдер умеет стримить ответ модели по паре (system, user).
Реализации лежат рядом (openai_compatible.py и т.п.).
"""

from abc import ABC, abstractmethod
from typing import Iterator


class LlmProvider(ABC):
    #: Расход последнего вызова [stream]: input_tokens, output_tokens, cached_tokens,
    #: cache_write_tokens. Заполняется по ходу стрима, готов после его исчерпания.
    #: Отдаём полем, а не значением генератора: вызывающие ждут Iterator[str], и
    #: `"".join(provider.stream(...))` должен продолжать работать.
    #: Пустой словарь означает «провайдер расход не сообщил».
    last_usage: dict

    def __init__(self):
        self.last_usage = {}

    @abstractmethod
    def stream(self, system: str, user: str) -> Iterator[str]:
        """Возвращает генератор текстовых чанков ответа модели."""
        raise NotImplementedError
