> **Статус:** реализованный дизайн и исторический журнал решений. Актуальное состояние дано в согласованных уточнениях и разделах реализации до горизонтальной черты. Текст после черты — первоначальное предложение и не является текущей спецификацией. Инструкции запуска находятся в `README.md`.

## Согласованные уточнения перед этапом 1

- Modular monolith, предложенное разделение пакетов, PostgreSQL + Flyway приняты.
- Приняты snapshot amount + currency в PaymentHistory, current_period_id, anchor_day / anchor_month, notification_jobs, persistent SNOOZE, long polling Telegram, Clock, PostgreSQL unique constraints и Testcontainers PostgreSQL.
- MVP: один пользователь, один Telegram CHAT_ID.
- APP_TIME_ZONE=Europe/Moscow; время уведомлений — 09:00.
- Прошедшая nextPaymentDate разрешена и означает просроченный платёж; reminders могут быть пустыми.
- Одна операция «Оплачено» закрывает один платёжный период.
- SNOOZE — следующий календарный день в 09:00; SNOOZE и OVERDUE в один день допускаются.
- Пропущенные REGULAR после простоя не догоняются; актуальный SNOOZE должен быть обработан.
- Календарная привязка сохраняется: 31.01 → 28.02 → 31.03; 29.02.2024 → 28.02.2025 → … → 29.02.2028.
- expectedPeriodId не является обязательным параметром REST POST /api/payments/{id}/pay. Без идентификатора периода повторный REST-запрос не гарантирует идемпотентность и может закрыть следующий период.
- Для Telegram обязательна защита от старого/повторного сообщения через notificationId → periodId.
- @Version допустим внутри persistence-модели; version не включается в публичный REST-контракт.
- Последующее согласованное уточнение: добавлены multi-stage `Dockerfile` и Compose-сервисы `postgres` и `app`. PostgreSQL использует именованный том `postgres-data`; порты БД и HTTP привязаны к loopback. Testcontainers используется для интеграционных тестов.
- Этапы 1 (bootstrap), 2 (Flyway/schema), 3 (Payment domain), 4 (REST CRUD), 5 (PaymentHistory), 6 (payment recurrence calculation), 7 (reminder persistence), 8 (scheduler), 9 (Telegram integration), 10 (Telegram callbacks), 11 (snooze), 12 (overdue reminders), 13 (tests) и 14 (README) согласованы и выполнены. MVP реализован полностью.

### Реализация этапа 2: PostgreSQL и Flyway

- Миграция: `src/main/resources/db/migration/V1__create_initial_schema.sql`.
- Миграция V1 создала пять исходных таблиц. Миграции V2 и V3 добавили шестую прикладную таблицу `telegram_conversations` и `prompt_message_id`; служебную таблицу `flyway_schema_history` создаёт Flyway.
- Hibernate работает с `ddl-auto=validate`, `open-in-view=false`. На этапе 3 добавлена сущность Payment, и её соответствие таблице подтверждено при запуске приложения и тестов.
- Локальные параметры подключения соответствуют `compose.yml`: `localhost:5432/payment_reminder`, пользователь и пароль `payment_reminder`. `.env` не требуется; параметры можно переопределить через `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`.
- UUID периода задаётся приложением. Для MONTHLY `anchor_month` отсутствует; для YEARLY обязателен месяц 1–12. Anchor-день не привязан ограничением к текущей дате, чтобы сохранить 31-е число и 29 февраля.
- `created_at`, `updated_at` получают время вставки по умолчанию. Обновление `updated_at` при изменениях будет ответственностью приложения; SQL-триггеры не добавлены.
- История и задания ссылаются на платёж, но не на его изменяемый `current_period_id`: старые периоды должны сохраняться. Для удаления связанных данных каскады не используются.
- Интеграционные тесты используют отдельный PostgreSQL 17 через Testcontainers и `@ServiceConnection`; жизненным циклом контейнера управляет Spring Boot.
- Проверено 43 теста, применение миграции на пустой БД, повторный запуск Flyway и запуск собранного JAR с локальной БД. При локальной проверке применена миграция V1; приложение после проверки остановлено.

Запуск приложения:

```bash
docker compose up -d
./gradlew bootRun
```

Проверки с OrbStack (Docker endpoint берётся из текущего Docker context):

```bash
DOCKER_HOST="$(docker context inspect --format '{{.Endpoints.docker.Host}}')" ./gradlew test bootJar --console=plain
```

### Реализация этапа 3: Payment domain

- `com.paymentreminder.payment.entity.Currency` — RUB/USD/EUR; значения хранятся в БД строками без ordinal.
- `com.paymentreminder.payment.entity.Recurrence` — MONTHLY/YEARLY; расширяемость обеспечена строковой колонкой и enum без жёсткой магии в логике.
- `com.paymentreminder.payment.entity.Payment` — JPA-сущность с маппингом на таблицу `payments`, `@Version` для оптимистичной блокировки, `updatable=false` для `createdAt`, `update` для `updatedAt`.
- `Payment.create(...)` — фабричный метод: нормализует имя (трим, длина по code points), сумму (предотвращает округление разрядов, требование Scale 2 и предел Precision 19), задаёт `current_period_id` и календарные anchor-поля из `next_payment_date`. Прошедшая дата разрешена.
- `Payment.deactivate(...)` — деактивация без удаления истории; период не меняется; повторный вызов не меняет состояние и `updatedAt`.
- `PaymentRepository` — `JpaRepository` и пагинированный `findByActiveTrue` для списка по умолчанию.
- Модель не знает о представлении в REST и не выполняет внешних вызовов. Полное редактирование полей появится на следующих этапах; пока у сущности только создание и деактивация.

### Реализация этапа 4: REST CRUD

- `PaymentController` (`/api/payments`): `POST` создаёт (201 + `Location`), `GET` возвращает список активных с пагинацией, `GET /{id}`, `PUT /{id}` (полная замена редактируемых полей), `PATCH /{id}/deactivate` (204).
- DTO: `PaymentCreateRequest` (без `active`), `PaymentUpdateRequest` (включает `active`), `PaymentResponse` (без `version` и `current_period_id`, с `reminders`). Сущности наружу не отдаются.
- Bean Validation: `@NotBlank` для имени, `@DecimalMin`/`@Digits` для суммы, `@NotNull` для enum и даты, `@PositiveOrZero` для элементов `reminders`.
- `PaymentService` нормализует напоминания: без дубликатов, отсортированы по возрастанию. Полная замена списка правил при create/update. Деактивация через доменный метод `Payment.deactivate`.
- `Payment.update(...)`: изменение имени/суммы/валюты/`active` сохраняет период; изменение даты или периодичности генерирует новый `current_period_id` и переопределяет anchor-поля.
- `PaymentReminder` и `PaymentReminderRepository` — персистентность правил; список в списке платежей загружается пакетно (без N+1).
- Ошибки: единый `@RestControllerAdvice` на `ProblemDetail`. 404 — платёж не найден; 400 — ошибки валидации/неизвестный enum/некорректные значения; 409 — конкурентное изменение (`OptimisticLockingFailureException`) или конфликт данных. Stack trace наружу не отдаётся.
- Пагинация отдаётся как стабильный DTO через `@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)`.
- Проверено: 79 тестов, smoke-проверка REST на локальном PostgreSQL (create/get/list/update/deactivate/404/400), локальная БД очищена после проверки.

### Реализация этапа 5: PaymentHistory

- `history/entity/PaymentHistory` — неизменяемый снимок завершённого периода: `payment`, `period_id`, `scheduled_date`, `paid_at`, `amount`, `currency`. Сумма и валюта копируются при оплате и не зависят от последующих правок платежа.
- `history/repository/PaymentHistoryRepository` — `findByPaymentIdOrderByPaidAtDescIdDesc(..., Pageable)` (свежие сверху, tie-break по `id`) и `findByPaymentIdAndPeriodId`.
- `history/service/PaymentHistoryService` — чтение истории с проверкой существования платежа (иначе 404).
- `POST /api/payments/{id}/pay`: `PaymentService.pay(...)` в одной транзакции блокирует строку платежа (`PaymentRepository.findLockedById`, `PESSIMISTIC_WRITE`), проверяет необязательный `expectedPeriodId`, активность, пишет историю и вызывает `Payment.completeCurrentPeriod(...)`.
- `Payment.completeCurrentPeriod(...)` рассчитывает новую дату от плановой даты (не от «сейчас»), сохраняя исходную календарную привязку: MONTHLY берёт anchor-день с ограничением последним днём месяца, YEARLY — anchor-месяц и день с восстановлением 29 февраля в високосный год; затем выдаёт новый `current_period_id`.
- Идемпотентность REST: при переданном `expectedPeriodId`, отличном от текущего, уже оплаченный период возвращает прежний результат (200), иначе 409 `Stale payment period`. Без `expectedPeriodId` повторный запрос не идемпотентен и может закрыть следующий период — как согласовано.
- Неактивный платёж при оплате — 409 `Payment is not active`. Отсутствующий платёж — 404.
- Публичный контракт: по согласованию в `PaymentResponse` добавлен `currentPeriodId`, чтобы `expectedPeriodId` можно было получить при чтении платежа. `GET /api/payments/{id}/history` отдаёт `PaymentHistoryResponse` с `periodId`.
- Отложено до этапов уведомлений: отмена неотправленных заданий старого периода (шаг 8 операции оплаты) будет добавлена вместе с персистентностью `notification_jobs`.
- Проверено: 93 теста, smoke-проверка pay/history на локальном PostgreSQL (идемпотентность, stale 409, inactive 409, 404, порядок истории), локальная БД очищена после проверки.

### Реализация этапа 6: payment recurrence calculation

- `payment/service/RecurrenceCalculator` — отдельный stateless-компонент: `next(plannedDate, recurrence, anchorDay, anchorMonth)`. Расчёт всегда идёт от предыдущей плановой даты, а не от «сейчас».
- MONTHLY: следующий месяц, день — минимум из anchor-дня и длины целевого месяца (31.01 → 28.02 → 31.03, в високосный 29.02).
- YEARLY: следующий год, anchor-месяц и anchor-день; 29 февраля восстанавливается в високосный год (29.02.2024 → 28.02.2025 → … → 29.02.2028).
- Один вызов сдвигает ровно один период: просроченный платёж остаётся просроченным, пока его не оплатят нужное число раз; периоды не пропускаются автоматически.
- Валидация: `anchorDay` 1–31; для YEARLY обязателен `anchorMonth` 1–12 (для MONTHLY игнорируется).
- `Payment` больше не считает дату сам: `completeCurrentPeriod(nextPaymentDate, now)` принимает уже вычисленную дату и выдаёт новый `current_period_id`. `PaymentService.pay(...)` вызывает калькулятор до записи снимка истории. Layering: домен не зависит от сервиса.
- Время берётся из внедрённого `Clock`; расчёт повторений от времени не зависит.
- Тесты: `RecurrenceCalculatorTests` (10 MONTHLY-кейсов, 6 YEARLY-кейсов, цикл 29.02.2024→2032, один период за просроченную оплату, валидация), REST-тест просроченной оплаты (2025-01-31 → 2025-02-28).
- Проверено: 113 тестов, `bootJar` собирается.

### Реализация этапа 7: reminder persistence

- `reminder/service/ReminderService` — владелец правил напоминаний: `replaceForPayment`, `daysForPayment`, `daysByPayment` и нормализация (уникальность, сортировка по возрастанию, неотрицательность).
- Полная замена набора правил при create/update: старые строки удаляются bulk-запросом, затем сохраняется нормализованный набор. Дубликаты — `IllegalArgumentException` → 400.
- `PaymentService` больше не содержит логику напоминаний и зависит от `ReminderService`; пакет `reminder` владеет своими данными.
- Правила разных платежей независимы; уникальность `(payment_id, days_before)` и `days_before >= 0` обеспечены и на уровне БД.
- Тесты: `ReminderServiceTests` (сортировка/дубликаты/негатив/null), `PaymentReminderRepositoryTests` (сортировка, уникальность в рамках платежа, одинаковый день у разных платежей, удаление только правил платежа, выборка по нескольким платежам, запрет отрицательного дня).
- Проверено: 123 теста, `bootJar` собирается.

### Реализация этапа 8: scheduler

- `notification/entity`: `NotificationJob` (полный маппинг `notification_jobs`), `NotificationKind` (REGULAR/SNOOZE/OVERDUE), `NotificationStatus` (PENDING/PROCESSING/SENT/FAILED/UNKNOWN/CANCELLED).
- `notification/repository/NotificationJobRepository`: вставка `insertRegularIfAbsent` / `insertOverdueIfAbsent` через нативный `INSERT ... ON CONFLICT DO NOTHING` (идемпотентно и безопасно при конкуренции, без SELECT-then-INSERT); отмена PENDING по платежу, по платежу+периоду и по конкретному REGULAR-правилу; выборки по статусу.
- `reminder/service/ReminderPlanningService.plan(today)`: для активных платежей создаёт REGULAR-задания там, где `nextPaymentDate - daysBefore == today`, и по одному OVERDUE в день для просроченных (`nextPaymentDate < today`). `available_at` = локальная дата уведомления в `app.notification-time` и `app.time-zone`. Пропущенные дни не догоняются; повторный запуск за тот же день не создаёт дублей.
- `notification/scheduler/NotificationScheduler`: `@Scheduled(cron = "${app.planning-cron}", zone = "${app.time-zone}")`, «сегодня» считается через `Clock` в часовом поясе приложения. `@EnableScheduling` на приложении. Новое свойство `app.planning-cron` (по умолчанию `0 0 9 * * *`; в тестах `-` отключает запуск).
- `notification/service/NotificationJobService`: отмена только PENDING-заданий — по платежу, по платежу+периоду, по REGULAR-правилу.
- Хуки жизненного цикла в `PaymentService`: оплата отменяет PENDING старого периода; деактивация — все PENDING платежа; изменение даты/периодичности — PENDING старого периода; удаление правила — PENDING этого правила (период при этом сохраняется).
- Доставка (claim `FOR UPDATE SKIP LOCKED`, Telegram, lease/claim token) и SNOOZE остаются на последующие этапы.
- Тесты: `ReminderPlanningServiceTests` (REGULAR по дню, отсутствие на других днях, идемпотентность, по заданию на правило, OVERDUE раз в день, отсутствие OVERDUE в день платежа, пропуск неактивных), `NotificationJobCancellationTests` (оплата/деактивация/смена периода/удаление правила). Smoke на локальном PostgreSQL с `planning-cron=*/1 * * * * *`: job появился сам, после оплаты стал CANCELLED.
- Проверено: 134 теста, `bootJar` собирается.

### Реализация этапа 9: Telegram integration

- `common/config/TelegramProperties` (`telegram.bot-token`, `telegram.chat-id`, `max-attempts`, `retry-delay`, `connect-timeout`, `read-timeout`). Секреты приходят из окружения (`TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`); при пустых значениях `isConfigured()` = false и доставка полностью выключена. Токен никогда не логируется и не попадает в текст ошибок (он только в base URL клиента).
- `notification/telegram`: `TelegramClient` (интерфейс), `TelegramBotClient` (HTTP-реализация на `RestClient` с таймаутами), `TelegramButton`/`TelegramKeyboard`, `TelegramDeliveryException` (известная неудача, с флагом `retryable`) и `TelegramUncertainException` (неопределённый результат).
  - 2xx с `ok=false` и HTTP 4xx → известная неудача; HTTP 429 и 5xx → retryable.
  - `ConnectException`/`UnknownHostException`/`NoRouteToHostException` → известно, что не отправлено (retryable); таймаут чтения и прочие I/O → неопределённый результат.
- `notification/telegram/TelegramMessageFormatter`: формат из ТЗ (💳 предстоящий / 🔴 просроченный), суммы с пробелом-разделителем и символом валюты, даты по-русски в родительном падеже, склонение «день/дня/дней», inline-кнопки `pay:<id>` / `snooze:<id>`.
- `notification/service`: `NotificationMessage` (данные для рендера), `ClaimedNotification` (id + claim token + попытка), `NotificationJobService.claimReady` (короткая транзакция: просроченные PROCESSING → UNKNOWN, затем `select ... for update skip locked`, `markProcessing` с lease и токеном), `NotificationDeliveryService` (claim → HTTP вне транзакции → запись результата в отдельной транзакции) и `recordSent/Retry/Failed/Unknown` с проверкой claim token (устаревший воркер не перезаписывает новую попытку).
- Политика повторных попыток: автоматически повторяются только известные неудачи, и только пока `attempt_count < telegram.max-attempts` (backoff через `retry-delay`); постоянные ошибки → FAILED, неопределённые → UNKNOWN и больше не переотправляются. Прерванная обработка (истёкший lease) тоже переводится в UNKNOWN — это осознанный выбор в пользу отсутствия дублей.
- `NotificationJob` получил доменные методы `markProcessing/markSent/markRetry/markFailed/markUnknown`.
- `notification/scheduler/NotificationDeliveryScheduler` — `@Scheduled(cron="${app.delivery-cron}", zone="${app.time-zone}")`. Новые свойства `app.delivery-cron`, `app.delivery-lease`, `app.delivery-batch`; в тестах доставка выключена (`app.delivery-cron=-`).
- Создание SNOOZE-заданий и обработка нажатий кнопок — отдельные этапы (11 и 10); формат сообщения и кнопки уже финальные.
- Тесты: `TelegramMessageFormatterTests` (формат, валюты, склонения, кнопки), `NotificationDeliveryServiceTests` (успех, повторный запуск без дублей, retryable до исчерпания попыток → FAILED, постоянная ошибка → FAILED, неопределённый результат → UNKNOWN, истёкший lease → UNKNOWN, задание из будущего не берётся), `NotificationDeliveryDisabledTests` (без credentials доставка не выполняется).
- Проверено: 156 тестов, `bootJar` собирается. Smoke на локальном PostgreSQL: с `APP_DELIVERY_CRON=*/1 * * * * *` и пустыми credentials планировщик создаёт задание, доставка корректно ничего не делает (job остаётся PENDING, без ошибок).

### Реализация этапа 10: Telegram callbacks

- Получение обновлений — **long polling** (`TelegramUpdatePoller`, `@Scheduled(initialDelayString/fixedDelayString = "${app.polling-delay-ms}")`). Стартовая задержка равна интервалу, поэтому поллер не дёргает Telegram на подъёме; в тестах polling выключен (`app.polling-enabled=false`).
- Позиция обработки хранится в PostgreSQL: `notification/entity/TelegramPollingState` + нативный upsert `TelegramPollingStateRepository.saveOffset` (`on conflict ... greatest(...)`, никогда не откатывает offset назад) и `TelegramPollingStateService` (`nextUpdateId`, `saveNextUpdateId`). Offset сдвигается после каждого update, поэтому рестарт не теряет и не переотправляет очередь, а повторный `getUpdates` всегда идёт с сохранённой позиции.
- `TelegramClient` расширен методами `getUpdates(offset, timeout)` (`allowed_updates`: `callback_query` и `message`), `answerCallbackQuery(id, text, showAlert)` и операциями над сообщениями. Общий helper `call(...)` переиспользует ту же политику ошибок, что и `sendMessage`; `read-timeout` (35 с) больше `polling-timeout` (25 с), чтобы long poll не обрывался.
- `TelegramCallbackData` — парсинг `pay:<id>` / `snooze:<id>` (только id задания, остальное тянется из БД). `TelegramUpdate`/`TelegramCallbackQuery` — разбор updates.
- `TelegramCallbackHandler` (`@Transactional`, сеть не выполняет) проверяет `chat_id`, находит `NotificationJob`, сверяет его `period_id` с текущим периодом платежа (`уже оплачен` при наличии истории, иначе `устарело`), проверяет активность и вызывает **тот же** `PaymentService.pay(id, periodId)` — бизнес-логика Telegram-кодом не дублируется. Ответы: успех (с новой датой и снятием кнопок), «Этот период уже оплачен», «Уведомление устарело», «Платёж неактивен», «Уведомление не найдено», «Действие недоступно».
- Сетевые ответы делает поллер: `answerCallbackQuery` (текст + alert), при успешной оплате — best-effort `clearInlineKeyboard`. Ошибки обработки/ответа логируются и не роняют поллер.
- Кнопка `snooze:` уже маршрутизируется, но создание переноса — следующий этап (11); сейчас отвечает «Перенос напоминания пока недоступен».
- Новые свойства: `app.polling-enabled`, `app.polling-delay-ms`, `telegram.polling-timeout`.
- Тесты: `TelegramCallbackDataTests` (валидные/битые команды), `TelegramCallbackHandlerTests` (оплата и снятие кнопок, повторное нажатие, устаревшее уведомление после смены периода, неактивный платёж, неизвестное уведомление, чужой чат, snooze-заглушка), `TelegramUpdatePollerTests` (обработка callback и сохранение offset, пропуск update без callback, повторный опрос с новой позиции).
- Проверено: 176 тестов, `bootJar` собирается и приложение стартует с новыми бинами без ошибок.

### Реализация этапа 11: snooze

- `reminder/service/SnoozeService.snoozeUntilTomorrow(source)` создаёт отложенное задание на следующий **календарный день** в `app.notification-time` (`available_at`), не меняя дату платежа и обычные правила.
- `notification/repository/NotificationJobRepository.insertSnoozeIfAbsent` — нативный `INSERT ... ON CONFLICT DO NOTHING`; частичный уникальный индекс `uq_notification_jobs_snooze` гарантирует максимум один перенос на исходное сообщение.
- `TelegramCallbackHandler` в ветке `snooze:` проверяет актуальность периода и активность платежа (как и для оплаты), затем вызывает `SnoozeService`; повторное нажатие отвечает «Перенос уже создан». Перенос из самого snooze-сообщения разрешён (следующий перенос из нового сообщения).
- Отмена переносов уже обеспечена общими хуками: оплата/смена периода/деактивация отменяют PENDING-задания (включая SNOOZE) через `cancelPendingForPeriod`/`cancelPendingForPayment`.
- `TelegramMessageFormatter` пересчитывает «осталось/просрочено» из дат (`scheduledDate` vs `notificationDate`), поэтому snooze после наступления даты автоматически рендерится как просроченный, а до — как предстоящий.
- Тесты: `SnoozeServiceTests` (дата/время, идемпотентность, snooze из snooze), дополнения в `TelegramCallbackHandlerTests` (создание, повтор, устаревший период), `TelegramMessageFormatterTests` (SNOOZE до/после даты).
- Проверено: тесты зелёные, `bootJar` собирается.

### Реализация этапа 12: overdue reminders

- Планирование, формат, ежедневная уникальность и отмена просроченных заданий уже были реализованы на этапах 8–10; этап закрывает сквозные проверки.
- Ежедневное задание OVERDUE создаётся не более одного раза в сутки (частичный уникальный индекс `uq_notification_jobs_overdue` по `(payment_id, period_id, notification_date)`), в день платежа — не создаётся.
- `TelegramMessageFormatter` рендерит `🔴 Просроченный платеж` с числом дней просрочки.
- `OverdueReminderTests`: доставка просроченного задания (текст «Просрочено: N дней», дата без года) и оплата из просроченного уведомления — период сдвигается ровно на один, оставшиеся OVERDUE-задания старого периода отменяются, доставленное остаётся SENT.
- Проверено: тесты зелёные, `bootJar` собирается.

### Реализация этапа 13: tests

- `MvpScenarioTests` закрывает сценарии из раздела 23 ТЗ:
  - создание → планирование → доставка → нажатие «Оплачено» → запись в `payment_history` → пересчёт `nextPaymentDate` → старое уведомление не отправляется повторно;
  - напоминание → «Напомнить завтра» → перенос сохранён в PostgreSQL (переживает рестарт) → дата платежа и правила не изменены.
- Тесты бизнес-логики расчёта дат, репозиториев и сервисов на PostgreSQL через Testcontainers (H2 не используется) были добавлены по ходу всех этапов.
- Число тестов не фиксируется как характеристика проекта: актуальное состояние проверяется командой `./gradlew test`. Последний сохранённый локальный XML-отчёт содержит 190 успешных тестов.

### Реализация этапа 14: README

- `README.md`: назначение, стек, требования, переменные окружения (таблица), запуск PostgreSQL и приложения без контейнеризации, настройка Telegram, основные REST-эндпоинты и коды ошибок, запуск тестов, структура проекта, раздел «Что не добавлялось».
- `.env.example` дополнен переменными Telegram, расписаний и polling (безопасные примеры, без реальных credentials).
- `planning-cron` поддерживает имя из ТЗ `REMINDER_SCHEDULER_CRON` (fallback на `APP_PLANNING_CRON`).

### Реализация этапа 15: Telegram-меню и мастер

- Long polling принимает `callback_query` и обычные `message`; slash-команды и текстовые ответы маршрутизируются отдельно от callback уведомлений.
- Команды `/start`, `/menu`, `/list`, `/add`, `/cancel`, `/help` открывают главное меню, списки и мастер создания.
- Inline-меню предоставляет карточку платежа, список всех платежей, последние 10 записей истории, редактирование, деактивацию и повторную активацию.
- `PaymentQueryService` формирует read model для Telegram UI; изменения выполняются через тот же `PaymentService`, что и REST API.
- Миграции V2/V3 создали `telegram_conversations` (`chat_id`, `step`, JSON draft, `updated_at`, `prompt_message_id`). Черновик переживает рестарт, считается активным 30 минут, а prompt по возможности редактируется на месте.
- Доступ к меню проверяется по единственному настроенному `TELEGRAM_CHAT_ID`. В групповом чате отдельная проверка пользователя отсутствует.
- Полного отдельного тестового покрытия всех веток menu/wizard пока нет; существующие polling-тесты проверяют приём сообщений и обновление prompt.

---

Изучил `payment-reminder-spec.md`. Ниже — предлагаемый проект MVP в рамках раздела 24. **Код и файлы не создавались.** Решения, которые расширяют или уточняют ТЗ, отдельно обозначены для согласования.

## 1. Архитектура и структура проекта

Предлагаю **один Spring Boot сервис и один Gradle-модуль**, с разделением пакетов по функциональности. PostgreSQL хранит платежи, историю и всё состояние доставки уведомлений.

Без дополнительных архитектурных фреймворков: Spring MVC, Spring Data JPA, Flyway, Spring Scheduler и встроенный HTTP-клиент Spring для Telegram Bot API.

```text
payment-reminder/
├── build.gradle.kts
├── settings.gradle.kts
├── gradlew
├── gradlew.bat
├── gradle/wrapper/
├── src/
│   ├── main/
│   │   ├── java/<base-package>/
│   │   │   ├── PaymentReminderApplication.java
│   │   │   ├── payment/
│   │   │   │   ├── controller/
│   │   │   │   ├── dto/
│   │   │   │   ├── entity/
│   │   │   │   ├── repository/
│   │   │   │   └── service/
│   │   │   │       ├── PaymentService
│   │   │   │       ├── PaymentCompletionService
│   │   │   │       └── RecurrenceCalculator
│   │   │   ├── history/
│   │   │   │   ├── controller/
│   │   │   │   ├── dto/
│   │   │   │   ├── entity/
│   │   │   │   ├── repository/
│   │   │   │   └── service/
│   │   │   ├── reminder/
│   │   │   │   ├── entity/
│   │   │   │   ├── repository/
│   │   │   │   └── service/
│   │   │   │       ├── ReminderPlanningService
│   │   │   │       └── SnoozeService
│   │   │   ├── notification/
│   │   │   │   ├── entity/
│   │   │   │   ├── repository/
│   │   │   │   ├── service/
│   │   │   │   ├── scheduler/
│   │   │   │   └── telegram/
│   │   │   │       ├── TelegramClient
│   │   │   │       ├── TelegramUpdatePoller
│   │   │   │       ├── TelegramCallbackHandler
│   │   │   │       └── TelegramMessageFormatter
│   │   │   └── common/
│   │   │       ├── config/
│   │   │       ├── error/
│   │   │       └── time/
│   │   └── resources/
│   │       ├── application.yml
│   │       └── db/migration/
│   └── test/
│       ├── java/<base-package>/
│       └── resources/
├── .env.example
├── .gitignore
└── README.md
```

Границы ответственности:

- `payment` — управление платежами и единая операция «оплачено».
- `history` — хранение и чтение истории.
- `reminder` — правила напоминаний, определение сроков, перенос на завтра.
- `notification` — задания на отправку, доставка и взаимодействие с Telegram.
- `common` — конфигурация, часы приложения и единая обработка ошибок.

REST-контроллеры и Telegram callbacks вызывают одни и те же бизнес-сервисы. JPA-сущности не возвращаются из API напрямую.

## 2. Предлагаемая модель PostgreSQL

Общие правила:

- Основные идентификаторы — `bigint generated ... as identity`.
- Денежные значения — `numeric(19,2)` ↔ `BigDecimal`.
- Календарные даты — `date` ↔ `LocalDate`.
- Моменты времени — `timestamptz` ↔ `Instant`.
- Enum — строковые значения, не ordinal и не PostgreSQL native enum.
- Обязательные поля — `NOT NULL`.
- Схему создаёт только Flyway; Hibernate работает с `ddl-auto=validate`.

### 2.1. `payments`

| Поле | Тип | Назначение |
|---|---|---|
| `id` | bigint, PK | Идентификатор платежа |
| `name` | varchar(255) | Название |
| `amount` | numeric(19,2) | Текущая сумма, больше нуля |
| `currency` | varchar(3) | RUB, USD, EUR |
| `recurrence` | varchar(16) | MONTHLY, YEARLY |
| `next_payment_date` | date | Дата текущего ожидающего оплаты периода |
| `active` | boolean | По умолчанию `true` |
| `current_period_id` | uuid | Идентификатор текущего платёжного периода |
| `anchor_day` | smallint | Исходный день для расчёта повторений |
| `anchor_month` | smallint, nullable | Исходный месяц для YEARLY |
| `version` | bigint | Оптимистическая блокировка JPA |
| `created_at` | timestamptz | Время создания |
| `updated_at` | timestamptz | Время изменения |

Ограничения:

- `amount > 0`;
- непустое после удаления пробелов `name`;
- допустимые `currency`, `recurrence`;
- `anchor_day` от 1 до 31;
- для YEARLY обязателен `anchor_month` от 1 до 12.

Индекс:

- частичный индекс по `next_payment_date WHERE active = true`.

**Дополнения к ТЗ:**

- `current_period_id` защищает от старых кнопок и повторной оплаты. Одной даты недостаточно, если пользователь вручную меняет её, а затем возвращает прежнее значение.
- `anchor_day` и `anchor_month` нужны для сохранения исходной календарной привязки после коротких месяцев и невисокосных лет.

Отдельную таблицу ожидаемых платёжных периодов для MVP не предлагаю: текущий период находится в `payments`, завершённый — в истории.

### 2.2. `payment_reminders`

| Поле | Тип | Назначение |
|---|---|---|
| `id` | bigint, PK | Идентификатор правила |
| `payment_id` | bigint, FK → payments | Платёж |
| `days_before` | integer | За сколько дней напомнить |

Ограничения:

- `days_before >= 0`;
- `UNIQUE(payment_id, days_before)`.

При обновлении списка правил удаление правила не удаляет уже сохранённые сведения о доставке.

### 2.3. `payment_history`

| Поле | Тип | Назначение |
|---|---|---|
| `id` | bigint, PK | Идентификатор записи |
| `payment_id` | bigint, FK → payments | Платёж |
| `period_id` | uuid | Оплаченный период |
| `scheduled_date` | date | Ожидавшаяся дата платежа |
| `paid_at` | timestamptz | Время отметки об оплате |
| `amount` | numeric(19,2) | Снимок суммы |
| `currency` | varchar(3) | Снимок валюты |

Ограничения и индексы:

- `amount > 0`;
- `UNIQUE(payment_id, period_id)` — один период нельзя оплатить дважды;
- индекс `(payment_id, paid_at DESC, id DESC)` для истории.

**Валюту предлагаю добавить к ТЗ:** без неё после изменения валюты платежа историческая сумма становится неоднозначной.

История неизменяема при редактировании платежа. Каскадное удаление истории не предусматривается.

### 2.4. `notification_jobs`

Одна таблица для обычных, отложенных и просроченных уведомлений. Она одновременно хранит задания и результат доставки.

| Поле | Тип | Назначение |
|---|---|---|
| `id` | bigint, PK | Идентификатор задания и ссылка из callback |
| `payment_id` | bigint, FK → payments | Платёж |
| `period_id` | uuid | Период, к которому относится сообщение |
| `scheduled_date` | date | Снимок даты платежа |
| `kind` | varchar(16) | REGULAR, SNOOZE, OVERDUE |
| `days_before` | integer, nullable | Снимок правила для REGULAR |
| `notification_date` | date | Локальная дата напоминания |
| `source_notification_id` | bigint, nullable, FK → notification_jobs | Источник SNOOZE |
| `available_at` | timestamptz | Не отправлять раньше этого времени |
| `status` | varchar(24) | Состояние доставки |
| `attempt_count` | integer | Число попыток |
| `lease_until` | timestamptz, nullable | Срок захвата задания обработчиком |
| `claim_token` | uuid, nullable | Идентификатор конкретного захвата |
| `sent_at` | timestamptz, nullable | Подтверждённое время отправки |
| `telegram_message_id` | bigint, nullable | Идентификатор сообщения |
| `last_error` | text, nullable | Ошибка без токенов и секретов |
| `created_at`, `updated_at` | timestamptz | Аудит |

Состояния: `PENDING`, `PROCESSING`, `SENT`, `FAILED`, `UNKNOWN`, `CANCELLED`.

Частичные уникальные индексы:

| Вид | Уникальный ключ | Гарантия |
|---|---|---|
| REGULAR | `(payment_id, period_id, days_before)` | Одно задание на правило в периоде |
| OVERDUE | `(payment_id, period_id, notification_date)` | Одно задание о просрочке на локальный день |
| SNOOZE | `source_notification_id` | Повторное нажатие одной кнопки не создаёт новые задания |

Дополнительно:

- индекс по `available_at` для ожидающих заданий;
- индекс по `lease_until` для обрабатываемых;
- индекс `(payment_id, period_id)` для отмены заданий;
- проверки обязательности полей в зависимости от `kind`.

`days_before` сохраняется как снимок, а не только как ссылка на правило: удаление и повторное создание одинакового правила не должно порождать дубликат.

### 2.5. `telegram_polling_state`

Для предлагаемого long polling:

- `consumer_name` — PK;
- `next_update_id` — bigint;
- `updated_at` — timestamptz.

Позиция чтения обновляется после обработки Telegram update. Повторная доставка update безопасна благодаря идемпотентным бизнес-операциям.

### Связи

```text
payments
 ├── 1 : N ── payment_reminders
 ├── 1 : N ── payment_history
 └── 1 : N ── notification_jobs
                   │
                   └── source_notification_id → notification_jobs.id

telegram_polling_state — отдельное техническое состояние
```

## 3. Ключевые бизнес-операции

### Отметка «оплачено»

Одна транзакция:

1. Заблокировать строку платежа через `SELECT ... FOR UPDATE`.
2. Проверить идентификатор ожидаемого периода.
3. При уже завершённом периоде вернуть результат предыдущей операции.
4. Проверить активность платежа.
5. Создать историю со снимками даты, суммы и валюты.
6. Рассчитать следующую дату от **плановой даты**, а не от момента нажатия кнопки.
7. Сгенерировать новый `current_period_id`.
8. Отменить неотправленные задания старого периода.
9. Сохранить изменения.

**Важное уточнение API:** предлагаю передавать в `POST /api/payments/{id}/pay` поле `expectedPeriodId`, полученное при чтении платежа.

Без такого признака повторный HTTP-запрос после успешной оплаты неотличим от намерения оплатить уже следующий месяц. Одна блокировка строки эту проблему не решает.

### Изменение платежа

Предлагаемая семантика:

- изменение суммы, названия и валюты не меняет период;
- изменение даты или периодичности создаёт новый идентификатор периода и отменяет старые ожидающие задания;
- изменение даты задаёт новую календарную привязку;
- удаление правила отменяет его неотправленные обычные задания;
- деактивация отменяет ожидающие задания, история сохраняется;
- повторная активация выполняется через `PUT`.

Для конкурентных изменений — `@Version`. Контракт передачи версии клиентом нужно согласовать.

## 4. Scheduler и идемпотентность уведомлений

Разделяю работу на две части.

### Планирование

Scheduler по конфигурируемому расписанию:

- определяет текущую дату в часовом поясе приложения;
- находит активные платежи, соответствующие правилу `nextPaymentDate - today = daysBefore`;
- создаёт обычные задания;
- создаёт ежедневные задания для просроченных платежей.

Повторные вставки защищены уникальными индексами PostgreSQL. Проверки вида «сначала SELECT, потом INSERT» недостаточны при конкуренции.

SNOOZE создаётся сразу при обработке кнопки и хранится до срока отправки.

### Доставка

Обработчик:

1. В короткой транзакции захватывает готовые задания, используя `FOR UPDATE SKIP LOCKED`.
2. Фиксирует `PROCESSING`, срок захвата и его токен.
3. Проверяет актуальность периода и активность платежа.
4. Выполняет HTTP-запрос в Telegram **вне транзакции БД**.
5. Сохраняет результат, проверяя токен захвата.

Это не требует внешней очереди. Для MVP достаточно одного экземпляра приложения; координация заданий при этом не завязана исключительно на память JVM.

### Ограничение гарантии доставки

**Уникальность задания в PostgreSQL не означает exactly-once доставку в Telegram.**

Возможен сценарий:

1. Telegram принял сообщение.
2. Ответ потерялся или приложение остановилось.
3. В PostgreSQL нет подтверждения отправки.

Telegram `sendMessage` не предоставляет прикладного ключа идемпотентности, позволяющего безопасно повторить такой запрос.

С учётом требования «максимум одно обычное уведомление» предлагаю:

- автоматически повторять только попытки, для которых известно, что отправка не состоялась;
- неопределённые результаты переводить в `UNKNOWN`;
- не переотправлять `UNKNOWN` автоматически.

Это уменьшает риск дублей, но допускает пропуск уведомления. Если важнее гарантировать повторные попытки доставки, потребуется согласовать возможность редких дублей.

Также уже начатую отправку нельзя надёжно отозвать при одновременной оплате. Пришедшее с задержкой сообщение будет безопасным: его кнопка не оплатит следующий период.

## 5. Telegram callbacks

### Получение updates

Для MVP предлагаю **long polling**:

- не нужен публичный HTTPS endpoint;
- проще локальный запуск;
- один активный poller на bot token;
- позиция обработки хранится в PostgreSQL.

Webhook можно выбрать вместо polling, если уже предусмотрен публичный адрес, но для текущего MVP он не необходим.

### Формат и обработка

В `callback_data` передаётся короткая команда и идентификатор задания:

```text
pay:<notificationId>
snooze:<notificationId>
```

Остальные данные, включая `period_id`, загружаются из БД. Такой формат укладывается в ограничение Telegram в 64 байта.

Обработка:

1. Проверить формат callback и разрешённый `chat_id`.
2. Найти исходное уведомление.
3. Проверить активность платежа и актуальность периода.
4. Вызвать общий сервис оплаты или переноса.
5. Ответить через `answerCallbackQuery`.
6. По возможности убрать или обновить кнопки после оплаты.

Повторное нажатие «Оплачено» возвращает «Этот период уже оплачен». Кнопка старого, заменённого периода сообщает об устаревшем уведомлении.

Для «Напомнить завтра» предлагаю:

- создавать дополнительное задание на следующий **календарный день**;
- не менять дату платежа и обычные правила;
- разрешать один перенос из каждого сообщения;
- разрешать следующий перенос уже из нового сообщения;
- отменять перенос при оплате, деактивации или замене периода.

Если используется групповой чат, одного `chat_id` недостаточно для ограничения действий конкретным человеком — нужно согласовать разрешённые Telegram user IDs.

## 6. Расчёт повторений и работа со временем

Предлагаю сохранять исходную календарную привязку.

### MONTHLY

Следующий месяц, день — минимальный из:

- исходного `anchor_day`;
- последнего дня целевого месяца.

Пример:

```text
31.01.2026 → 28.02.2026 → 31.03.2026
```

### YEARLY

Следующий год, исходные месяц и день, с ограничением последним днём месяца:

```text
29.02.2024 → 28.02.2025 → … → 29.02.2028
```

Простое последовательное `plusMonths(1)` или `plusYears(1)` обеспечивает допустимую дату, но может потерять исходную привязку. ТЗ не определяет, какое поведение требуется после такого сокращения даты.

Для просроченных платежей предлагаю **сдвиг на один период за одну оплату**, даже если новая дата остаётся в прошлом. Это соответствует операции из ТЗ и не пропускает неоплаченные периоды автоматически.

Время:

- единый настраиваемый `APP_TIME_ZONE`;
- `Clock` внедряется в сервисы для проверяемой работы с датами;
- «сегодня», «завтра» и сутки просрочки вычисляются в этом часовом поясе;
- для напоминаний — настраиваемое локальное время, например 09:00;
- cron явно использует тот же часовой пояс.

## 7. API, конфигурация и проверка реализации

Предлагаемые HTTP-ответы:

- `201` — создание;
- `200` — чтение, изменение, результат оплаты;
- `204` — деактивация;
- `400` — некорректные поля, enum, сумма, дубли правил;
- `404` — платёж не найден;
- `409` — неактивный платёж при оплате, устаревший период, конфликт версии.

Ошибки — единый JSON на основе Spring `ProblemDetail`, с кодом ошибки и деталями валидации, без stack trace.

Дополнительно предлагаю:

- пагинацию списка платежей и истории;
- по умолчанию только активные платежи, явный фильтр для остальных;
- сортировку истории по `paid_at DESC, id DESC`;
- полную замену редактируемых полей через `PUT`.

Конфигурация включает переменные из ТЗ, часовой пояс, время уведомлений, таймауты Telegram и параметры повторных попыток. В Actuator открываются только `health` и `info`.

Основные будущие проверки:

- календарные переходы, включая восстановление 31-го числа и 29 февраля;
- атомарность оплаты и конкурентные повторные запросы;
- неизменность исторических суммы и валюты;
- уникальность заданий при повторных запусках scheduler;
- повторные и устаревшие callbacks;
- сохранение snooze после перезапуска;
- граница суток в выбранном часовом поясе;
- неопределённый результат Telegram-запроса.

Интеграционные тесты — на PostgreSQL через Testcontainers. Здесь есть уточнение ТЗ: запрет Docker-файлов и контейнеризации приложения совместим с Testcontainers, но **для запуска этих тестов нужен совместимый контейнерный runtime**.

## 8. Что нужно согласовать перед реализацией

| Вопрос | Предлагаемое решение |
|---|---|
| Один пользователь или несколько? | MVP — один общий набор платежей и один Telegram chat |
| Как развёрнут REST API без авторизации? | Доступ в доверенной среде; публичное размещение требует отдельного решения |
| Потеря уведомления или редкий дубль при неопределённом результате отправки? | Приоритет отсутствия дублей: `UNKNOWN` без автоматического повтора |
| Как защитить REST-оплату от повторного запроса? | Необязательное `expectedPeriodId`; без него повтор не идемпотентен |
| Что делать после 31 января и 29 февраля? | Сохранять исходную привязку через anchor-поля |
| Как оплачивать сильно просроченный платёж? | Одна отметка закрывает один период |
| Что означает «завтра»? | Следующий календарный день в `APP_TIME_ZONE`, в настроенное время |
| Какой часовой пояс и время отправки? | Нужно указать; например `Europe/Moscow`, 09:00 |
| Догонять ли обычные напоминания после простоя? | По умолчанию соблюдать точное равенство из ТЗ; старые обычные напоминания не восстанавливать. Просроченные snooze отправлять после восстановления, если период актуален |
| Могут ли SNOOZE и OVERDUE прийти в один день? | Для простоты MVP — да, как разные виды. Если нужен общий лимит, потребуется правило объединения |
| Что делает редактирование даты/периодичности? | Заменяет текущий период, делает старые кнопки неактуальными |
| Можно ли создавать платежи с прошедшей датой? | Да, сразу считать просроченными |
| Можно ли оставить список правил пустым? | Да; ежедневные уведомления о просрочке сохраняются |
| Как принимать Telegram updates? | Long polling, один экземпляр приложения в MVP |
| Допустимы ли дополнительные поля истории и периода? | Добавить валюту, идентификатор периода и календарные anchor-поля |
| Как трактовать запрет Docker при Testcontainers? | Без контейнеризации приложения; runtime разрешён для тестов |

После согласования можно двигаться по этапам раздела 24, начиная с bootstrap и Flyway. **На этом этапе останавливаюсь и жду подтверждения предложенных решений и ответов на спорные пункты.**

### Реализация: контейнеризация (по запросу пользователя)

- Изначально раздел 21 ТЗ запрещал Docker/Docker Compose для приложения. По прямому указанию пользователя запрет снят: добавлены `Dockerfile` (multi-stage: `eclipse-temurin:21-jdk` сборка `bootJar` → `eclipse-temurin:21-jre` рантайм, непривилегированный пользователь, healthcheck) и `.dockerignore`.
- `compose.yml` расширен сервисом `app`: сборка из `Dockerfile`, `depends_on: postgres: condition: service_healthy`, `DB_URL=jdbc:postgresql://postgres:5432/...`, Telegram-креды и `SPRING_PROFILES_ACTIVE` из окружения/`.env`, порт `127.0.0.1:8080`.
- Данные PostgreSQL переведены с bind-mount на именованный том `postgres-data`, чтобы стек был самодостаточным и переносимым.
- По умолчанию активен профиль `local` (быстрые расписания, отправка сразу); для production-like режима используется явно пустой `SPRING_PROFILES_ACTIVE`. Compose применяет `${SPRING_PROFILES_ACTIVE-local}`, поэтому пустое значение сохраняется.
- Проверено: `docker compose up -d --build` — оба контейнера `healthy`, `/actuator/health` = `UP`, планировщик создаёт `REGULAR/PENDING` за ~10 секунд; с фейковым токеном задание уходит в `FAILED` (`401`, `attempt_count=1`), т.е. конвейер доставки внутри контейнера рабочий.

### Исправление: контрольная сумма Gradle wrapper

- В `gradle/wrapper/gradle-wrapper.properties` был указан некорректный `distributionSha256Sum` (на 2 символа длиннее). Локальные сборки проходили только потому, что дистрибутив Gradle уже был в кэше и проверка не выполнялась; в Docker при чистой загрузке сборка падала с `Expected checksum ... Actual checksum ...`.
- Значение приведено к официальному `bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531` (сверено с `https://services.gradle.org/distributions/gradle-8.14.3-bin.zip.sha256`).
