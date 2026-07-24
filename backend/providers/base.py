"""Абстракция LLM-провайдера.

Каждый провайдер умеет стримить ответ модели по паре (system, user).
Реализации лежат рядом (openai_compatible.py и т.п.).
"""

from abc import ABC, abstractmethod
from typing import Iterator


class LlmProvider(ABC):
    @abstractmethod
    def stream(self, system: str, user: str) -> Iterator[str]:
        """Возвращает генератор текстовых чанков ответа модели."""
        raise NotImplementedError
