# Деплой бэкенда на VPS (Docker + Caddy)

Бэкенд живёт в `/opt/voiceapp` на том же Hetzner-сервере, что и `briefgenerator`.
Наружу порты не публикуются — трафик идёт через **общий Caddy** briefgenerator
(он держит 80/443 и делает авто-HTTPS). Маршрут добавляется файлом в его `conf.d`.

## Топология

```
Интернет → Caddy (briefgenerator, :443, авто-TLS)
              ├─ briefgenerator.example        → app:8000        (чужой проект)
              ├─ briefgenerator.aithinglab.com → brief-app-staging:8000
              └─ voiceapp.aithinglab.com       → voiceapp:8000   (наш)
                     ▲ conf.d/voiceapp.caddy
Контейнер voiceapp подключён к внешней сети briefgenerator_internal.
```

## Первый деплой

```bash
# 1) каталог
sudo mkdir -p /opt/voiceapp && sudo chown deploy:deploy /opt/voiceapp

# 2) код (с локальной машины): только отслеживаемые файлы
git archive HEAD | ssh hetzner 'tar -x -C /opt/voiceapp'

# 3) секреты
cp deploy/.env.docker.example /opt/voiceapp/.env.docker   # заполнить, chmod 600

# 4) сборка и запуск
cd /opt/voiceapp && docker compose -f docker-compose.prod.yml up -d --build

# 5) маршрут в Caddy + graceful reload (без даунтайма briefgenerator)
cp deploy/voiceapp.caddy /opt/briefgenerator/deploy/conf.d/voiceapp.caddy
docker exec briefgenerator-caddy-1 caddy reload --config /etc/caddy/Caddyfile
```

## Обновление

```bash
git archive HEAD | ssh hetzner 'tar -x -C /opt/voiceapp'
cd /opt/voiceapp && docker compose -f docker-compose.prod.yml up -d --build
```

Конфиг (ключи, промпты, стили, API-токен) хранится в SQLite на томе
`voiceapp_data` и переживает пересборку.

## Проверка

```bash
curl https://voiceapp.aithinglab.com/        # {"status":"ok",...}
# админ-панель: https://voiceapp.aithinglab.com/admin  (Basic Auth из .env.docker)
```
