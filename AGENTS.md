# AGENTS.md

## Назначение

Payment Reminder — однопользовательский Spring Boot backend для регулярных ручных платежей. Он хранит данные в PostgreSQL, предоставляет REST API, планирует и доставляет Telegram-уведомления, а также поддерживает Telegram-меню и мастер создания/редактирования платежей.

Перед изменениями изучать `README.md`, затронутый функциональный пакет и соответствующие миграции/тесты. `payment-reminder-spec.md` является историческим исходным ТЗ, а нижняя часть `payment-reminder-design.md` после горизонтальной черты — историческим проектным предложением.

## Стек и команды

- Java 21, Spring Boot 3.5, Gradle Kotlin DSL.
- PostgreSQL 17, Flyway, Spring Data JPA.
- JUnit 5, Spring Boot Test, Testcontainers PostgreSQL.

```bash
# Полный локальный стек
docker compose up -d --build

# Только БД и запуск приложения из исходников
docker compose up -d postgres
./gradlew bootRun

# Быстрые локальные расписания
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun

# Обязательная проверка перед завершением существенных изменений
./gradlew test
./gradlew bootJar
```

Для Testcontainers нужен Docker-совместимый runtime. При OrbStack при необходимости использовать:

```bash
DOCKER_HOST="$(docker context inspect --format '{{.Endpoints.docker.Host}}')" ./gradlew test
```

## Архитектурные границы

- `payment` — CRUD, единая операция оплаты, read model и расчёт следующего периода.
- `history` — неизменяемые снимки оплаченных периодов.
- `reminder` — правила, планирование и snooze.
- `notification` — jobs, доставка, schedulers, Telegram client/polling/callback/menu/wizard.
- `common` — конфигурация, `Clock` и REST error handling.

Сохранять modular-monolith и общий бизнес-слой для REST и Telegram. Не дублировать оплату или обновление платежа в Telegram-обработчиках. Не добавлять внешние очереди, кэш или микросервисы без отдельного требования.

## Правила реализации

- Не возвращать JPA-сущности из REST; использовать DTO.
- Денежные значения хранить в `BigDecimal`, даты платежей в `LocalDate`, моменты в `Instant`.
- Текущее время получать через внедрённый `Clock`; «сегодня» и «завтра» считать в `app.time-zone`.
- Enum хранить строками, не ordinal.
- Одна операция оплаты закрывает ровно один период.
- Сохранять календарную привязку 31-го числа и 29 февраля.
- При смене периода, оплате и деактивации учитывать отмену ожидающих notification jobs.
- Telegram callback уведомления связывать с `notificationId` и проверять `periodId`.
- Telegram HTTP не выполнять внутри долгой транзакции БД.
- Не логировать token, пароли и другие credentials.
- REST-ошибки возвращать через `ProblemDetail` без stack trace.
- Учитывать пагинацию REST и ограничения Telegram `callback_data` (64 байта).
- Состояние, которое должно пережить рестарт, хранить в PostgreSQL, не только в памяти JVM.

## База данных

Схема изменяется только новой версионированной миграцией в `src/main/resources/db/migration/`. Уже применённые миграции не переписывать. Hibernate должен оставаться в режиме `ddl-auto=validate`.

При изменении схемы обновить JPA mapping, `DatabaseSchemaTests`, документацию модели данных и выполнить интеграционные тесты на PostgreSQL. H2 не использовать как замену PostgreSQL.

## Тестирование

Сначала можно запускать затронутый класс, затем весь набор:

```bash
./gradlew test --tests 'com.paymentreminder.PaymentRestControllerTests'
./gradlew test --tests 'com.paymentreminder.payment.service.RecurrenceCalculatorTests'
./gradlew test --tests 'com.paymentreminder.TelegramCallbackHandlerTests'
./gradlew test --tests 'com.paymentreminder.TelegramUpdatePollerTests'
./gradlew test
```

Для исправления дефекта добавлять регрессионный тест, если поведение можно стабильно воспроизвести. Не фиксировать число тестов в документации как постоянное значение.

## Документация и конфигурация

При изменении поведения синхронно проверять:

- `README.md`;
- актуальную часть `payment-reminder-design.md`;
- `.env.example` и `compose.yml`;
- REST-контракты, Telegram-команды и модель данных.

Spring Boot при bare run не читает `.env`. Реальные Telegram credentials не коммитить. Не документировать Swagger/OpenAPI endpoints, пока соответствующей реализации нет.
