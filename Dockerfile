# Прод-образ бэкенда: Flask под gunicorn.
FROM python:3.12-slim

WORKDIR /app

COPY backend/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt gunicorn

COPY backend/ .

# Непривилегированный пользователь + каталог для SQLite-конфига (том)
RUN useradd -m app && mkdir -p /app/data && chown -R app:app /app
USER app

ENV DB_PATH=/app/data/config.sqlite3 \
    PYTHONUNBUFFERED=1

EXPOSE 8000

# gthread: стриминг ответа LLM держит воркер занятым на время потока;
# несколько потоков дают параллельные запросы. timeout > llm-таймаута (120с).
CMD ["gunicorn", "-b", "0.0.0.0:8000", "-k", "gthread", "-w", "2", "--threads", "4", "--timeout", "180", "app:app"]
