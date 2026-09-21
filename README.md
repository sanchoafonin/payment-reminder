# Payment Reminder

Backend-сервис для учёта регулярных ручных платежей. Приложение хранит платежи и историю оплат в PostgreSQL, планирует обычные и просроченные напоминания, доставляет их в Telegram и предоставляет REST API и Telegram-меню.

Сервис не выполняет банковские операции и не хранит ссылки на оплату.

## Возможности

- платежи с периодичностью `MONTHLY` и `YEARLY`, валютами `RUB`, `USD`, `EUR`;
- несколько правил напоминаний `daysBefore` для одного платежа;
- история оплаченных периодов со снимком суммы и валюты;
- обычные, ежедневные просроченные и отложенные до завтра уведомления;
- Telegram-кнопки «Оплачено» и «Напомнить завтра»;
- Telegram-меню для просмотра, создания, редактирования, активации и деактивации платежей;
- персистентные задания доставки, Telegram polling offset и состояние мастера;
- сохранение календарной привязки: `31.01 -> 28/29.02 -> 31.03`, `29.02 -> 28.02 -> 29.02` в следующем високосном году.

## Стек

- Java 21, Spring Boot 3.5.16;
- Spring MVC, Bean Validation, Spring Data JPA, Spring Scheduler, `RestClient`;
- PostgreSQL 17, Flyway, Hibernate `ddl-auto=validate`;
- Spring Boot Actuator;
- Gradle Kotlin DSL;
- JUnit 5 и Testcontainers PostgreSQL.

Приложение является однопользовательским modular monolith. REST API не имеет аутентификации и предназначен для доверенной сети. Telegram-доступ ограничен одним `TELEGRAM_CHAT_ID`; в групповом чате действия не разграничиваются по участникам.

## Быстрый запуск через Docker Compose

Нужны Docker с Compose и свободные порты `5432` и `8080` на loopback-интерфейсе.

Автоматическая подготовка на macOS, Debian/Ubuntu и Fedora: скрипт проверит JDK 21, Docker и Compose, при необходимости установит их, создаст `.env` и запустит сервисы:

```bash
./setup.sh
```

Существующий `.env` скрипт не перезаписывает. На macOS для автоматической установки Docker Desktop нужен Homebrew, на Linux нужен `sudo` или запуск от `root`. `compose.yml` также содержит безопасные локальные значения по умолчанию, поэтому PostgreSQL можно запустить из IDE без `.env`.

```bash
cp .env.example .env
# При необходимости заполните TELEGRAM_BOT_TOKEN и TELEGRAM_CHAT_ID.
docker compose up -d --build
docker compose ps
curl -s http://localhost:8080/actuator/health
docker compose logs -f app
```

Compose запускает PostgreSQL и приложение. Данные БД хранятся в локальной директории из `POSTGRES_DATA_DIR`. По умолчанию активен профиль `local`: планирование и доставка выполняются каждые 10 секунд, `notification-time` равен `00:00`.

Production-like расписание из `application.yml`:

```bash
SPRING_PROFILES_ACTIVE= docker compose up -d --build
```

Запуск только PostgreSQL для разработки из IDE или через Gradle:

```bash
docker compose up -d postgres
./gradlew bootRun
```

Остановка:

```bash
docker compose down
docker compose down -v  # также удалить данные PostgreSQL
```

## Запуск без контейнеризации приложения

Нужны JDK 21 и PostgreSQL 17. Надёжный пример создания локального пользователя и БД:

```bash
psql -d postgres -c "CREATE USER payment_reminder WITH PASSWORD 'payment_reminder';"
createdb --owner=payment_reminder payment_reminder
./gradlew bootRun
```

Flyway автоматически применит все миграции из `src/main/resources/db/migration/`. Собранный JAR запускается так:

```bash
./gradlew bootJar
java -jar build/libs/payment-reminder-0.0.1-SNAPSHOT.jar
```

Spring Boot не читает `.env` при bare run: нужные переменные следует экспортировать в shell. Профиль с быстрыми расписаниями включается командой:

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

## Конфигурация

| Переменная | По умолчанию | Назначение |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/payment_reminder` | JDBC URL |
| `DB_USERNAME` | `payment_reminder` | Пользователь БД |
| `DB_PASSWORD` | `payment_reminder` | Пароль БД |
| `POSTGRES_DB` | `payment_reminder` | БД контейнера PostgreSQL |
| `POSTGRES_USER` | `payment_reminder` | Пользователь контейнера PostgreSQL |
| `POSTGRES_PASSWORD` | `payment_reminder` | Пароль контейнера PostgreSQL |
| `POSTGRES_DATA_DIR` | `./.data/postgres` | Локальная директория данных PostgreSQL для Compose |
| `SPRING_PROFILES_ACTIVE` | `local` в Compose | Активный Spring profile |
| `SERVER_PORT` | `8080` | HTTP-порт bare run; Compose фиксирован на `8080` |
| `APP_TIME_ZONE` | `Europe/Moscow` | Часовой пояс бизнес-дат |
| `APP_NOTIFICATION_TIME` | `09:00` | Локальное время уведомления |
| `REMINDER_SCHEDULER_CRON` | `0 0 9 * * *` | Планирование заданий |
| `APP_PLANNING_CRON` | то же | Fallback для `REMINDER_SCHEDULER_CRON` |
| `APP_DELIVERY_CRON` | `0 * * * * *` | Доставка готовых заданий |
| `APP_DELIVERY_LEASE` | `PT5M` | Срок захвата задания |
| `APP_DELIVERY_BATCH` | `20` | Размер одной выборки доставки |
| `APP_POLLING_ENABLED` | `true` | Включение Telegram long polling |
| `APP_POLLING_DELAY_MS` | `1000` | Пауза между long polling запросами |
| `TELEGRAM_BOT_TOKEN` | пусто | Токен; пустое значение выключает Telegram |
| `TELEGRAM_CHAT_ID` | пусто | Единственный разрешённый chat ID |
| `TELEGRAM_MAX_ATTEMPTS` | `3` | Максимум попыток известной неудачной доставки |
| `TELEGRAM_RETRY_DELAY` | `PT5M` | Задержка повторной доставки |
| `TELEGRAM_CONNECT_TIMEOUT` | `PT5S` | Таймаут соединения |
| `TELEGRAM_READ_TIMEOUT` | `PT35S` | Таймаут чтения; должен быть больше polling timeout |
| `TELEGRAM_POLLING_TIMEOUT` | `PT25S` | Таймаут Telegram long polling |

Значения `Duration` используют ISO-8601, например `PT5M`. `.env.example` содержит безопасный шаблон. Compose явно передаёт поддерживаемые параметры приложению; `SERVER_PORT` в текущем Compose не настраивается.

## Telegram

1. Создайте бота через `@BotFather`.
2. Получите chat ID через `getUpdates` при остановленном приложении.
3. Задайте `TELEGRAM_BOT_TOKEN` и `TELEGRAM_CHAT_ID` и перезапустите приложение.

Не вызывайте `getUpdates` вручную одновременно с приложением: Telegram вернёт `409 Conflict`.

Поддерживаемые команды:

| Команда | Действие |
| --- | --- |
| `/start`, `/menu` | Главное меню и сброс текущего мастера |
| `/list` | Неоплаченные платежи до конца текущего месяца |
| `/add` | Мастер создания платежа |
| `/cancel` | Отмена текущего мастера |
| `/help` | Справка |

Через inline-меню доступны все платежи, карточка платежа, последние 10 записей истории, редактирование полей, деактивация и повторная активация. Мастер принимает сумму с точкой или запятой, дату `ГГГГ-ММ-ДД` или `ДД.ММ.ГГГГ`, напоминания списком чисел либо `нет`. Черновик хранится в PostgreSQL и активен 30 минут.

## REST API

Базовый путь: `/api/payments`.

| Метод | Путь | Результат |
| --- | --- | --- |
| `POST` | `/api/payments` | Создать платеж, `201` и `Location` |
| `GET` | `/api/payments` | Страница активных платежей |
| `GET` | `/api/payments?active=false` | Страница всех платежей |
| `GET` | `/api/payments/{id}` | Получить платеж |
| `PUT` | `/api/payments/{id}` | Полностью заменить редактируемые поля |
| `PATCH` | `/api/payments/{id}/deactivate` | Деактивировать, `204` |
| `POST` | `/api/payments/{id}/pay` | Оплатить текущий период |
| `GET` | `/api/payments/{id}/history` | История, новые записи первыми |

Списки поддерживают стандартные `page`, `size`, `sort`. `active=false` означает «все», а не «только неактивные». Формат страницы стабилизирован режимом Spring Data `VIA_DTO`.

Создание:

```bash
curl -X POST http://localhost:8080/api/payments \
  -H 'Content-Type: application/json' \
  -d '{
    "name":"Ипотека",
    "amount":35300.00,
    "currency":"RUB",
    "recurrence":"MONTHLY",
    "nextPaymentDate":"<дата в формате ГГГГ-ММ-ДД>",
    "reminders":[5,3,1]
  }'
```

`PUT` требует `name`, `amount`, `currency`, `recurrence`, `nextPaymentDate`, `reminders` и `active`. Отсутствующий или `null` список `reminders` трактуется как пустой; дубликаты запрещены.

Оплата с защитой от повторного запроса:

```bash
curl -X POST http://localhost:8080/api/payments/1/pay \
  -H 'Content-Type: application/json' \
  -d '{"expectedPeriodId":"<currentPeriodId из GET /api/payments/1>"}'
```

Тело запроса необязательно. Без `expectedPeriodId` повторный запрос не является идемпотентным и может закрыть следующий период. Одна операция всегда сдвигает ровно один период.

Ошибки возвращаются как `application/problem+json` без stack trace:

- `400` — validation, malformed JSON, неизвестный enum или доменная валидация;
- `404` — платеж не найден;
- `409` — неактивный/устаревший период, конфликт версии или ограничений БД.

OpenAPI/Swagger UI в проекте пока не предоставляются.

## Фоновые процессы

- planning scheduler создаёт `REGULAR` только при точном совпадении даты и ежедневно создаёт `OVERDUE` для просрочки;
- пропущенные обычные напоминания после простоя не догоняются;
- delivery scheduler захватывает задания через `FOR UPDATE SKIP LOCKED`, а Telegram HTTP выполняет вне транзакции;
- известные временные ошибки повторяются, неопределённый результат становится `UNKNOWN` без автоматической повторной отправки;
- Telegram не поддерживает idempotency key для `sendMessage`, поэтому exactly-once доставка невозможна;
- оплата, смена периода и деактивация отменяют ожидающие (`PENDING`) задания старого состояния.

## Модель данных

Flyway-миграции создают:

- `payments` — текущий платежный период и календарная привязка;
- `payment_reminders` — правила напоминаний;
- `payment_history` — неизменяемые снимки оплаченных периодов;
- `notification_jobs` — задания и результат доставки;
- `telegram_polling_state` — позиция long polling;
- `telegram_conversations` — состояние Telegram-мастера и prompt message.

## Тесты

Интеграционные тесты используют PostgreSQL 17 через Testcontainers; H2 не используется.

```bash
./gradlew test
./gradlew bootJar
```

Для OrbStack:

```bash
DOCKER_HOST="$(docker context inspect --format '{{.Endpoints.docker.Host}}')" ./gradlew test
```

Покрыты REST CRUD и оплата, схема и репозитории, календарные расчёты, планирование, доставка и retry/`UNKNOWN`, callbacks, snooze, overdue и сквозные MVP-сценарии. Telegram menu/wizard пока не имеют полного отдельного покрытия всех пользовательских веток.

## Структура

```text
src/main/java/com/paymentreminder/
├── payment/       # CRUD, оплата, read model и расчёт периодов
├── history/       # история оплаченных периодов
├── reminder/      # правила, planning и snooze
├── notification/  # jobs, delivery, Telegram polling/callback/menu/wizard
└── common/        # конфигурация, Clock и REST errors
```

Не используются frontend, OAuth/JWT, банковские API, Redis, брокеры сообщений и микросервисы.
