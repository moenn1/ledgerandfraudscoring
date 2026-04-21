# Event Delivery and Outbox Relay

LedgerForge keeps the immutable ledger as the source of truth and uses `outbox_events` as the durable handoff point for downstream delivery.

Payment reserve/capture/refund/cancel flows still write ledger mutations, audit events, and outbox rows inside the same application transaction. The relay is responsible only for advancing the outbox row toward delivery after that durable write succeeds.

## Relay Lifecycle

Each outbox row now carries relay control state in addition to the original event payload:

- `attempt_count`: number of publish attempts made so far
- `next_attempt_at`: when the relay should retry the row
- `last_attempt_at`: timestamp of the last relay attempt
- `claim_token` and `claim_expires_at`: short-lived lease used while a worker is processing the row
- `dead_lettered_at`: marks poison or exhausted events that require operator intervention
- `last_error`: truncated failure message from the most recent failed delivery
- `published_at`: success timestamp after the publisher acknowledges delivery

The scheduler polls for rows where `published_at` and `dead_lettered_at` are both null, `next_attempt_at` is due, and any prior lease has expired. Successful delivery sets `published_at`. Retriable failures keep the row pending and advance `next_attempt_at`. Poison messages or exhausted retries set `dead_lettered_at`.

## Runtime Controls

The relay is configured with these environment-backed properties:

- `LEDGERFORGE_OUTBOX_RELAY_ENABLED` default `true`
- `LEDGERFORGE_OUTBOX_RELAY_FIXED_DELAY_MS` default `5000`
- `LEDGERFORGE_OUTBOX_BATCH_SIZE` default `25`
- `LEDGERFORGE_OUTBOX_CLAIM_TIMEOUT_MS` default `30000`
- `LEDGERFORGE_OUTBOX_BASE_DELAY_MS` default `1000`
- `LEDGERFORGE_OUTBOX_MAX_DELAY_MS` default `60000`
- `LEDGERFORGE_OUTBOX_MAX_ATTEMPTS` default `5`

Backoff is exponential from the base delay and capped by the max delay. With the defaults, the retry sequence is `1s`, `2s`, `4s`, `8s`, `16s`, then dead-letter.

## Metrics and Telemetry

Actuator now exposes:

- `ledgerforge.outbox.queue.depth`
- `ledgerforge.outbox.queue.lag.seconds`
- `ledgerforge.outbox.publish.success`
- `ledgerforge.outbox.publish.retry`
- `ledgerforge.outbox.publish.dead_letter`

Queue lag is calculated from the oldest pending row by `created_at`, which makes it suitable for paging on delivery stalls even when the ledger itself remains correct.

If you ship metrics to another collector, scrape or bridge the Actuator metrics endpoint instead of relying on the relay logs alone.

The default `OutboxPublisher` implementation writes publish-ready payloads to application logs. Replace that bean with a broker-specific publisher when integrating Kafka, RabbitMQ, or another transport.

## Operator Controls

All outbox operations require an authenticated `Admin`.

- `GET /api/outbox/events?state=PENDING|PUBLISHED|DEAD_LETTER&limit=50`
- `POST /api/outbox/relay/run?limit=25`
- `POST /api/outbox/events/{eventId}/requeue`

`POST /api/outbox/relay/run` is intended for controlled catch-up after recovering a downstream dependency. `POST /api/outbox/events/{eventId}/requeue` clears the dead-letter marker, resets attempts, and makes the row eligible for delivery again without rewriting the original payment or ledger history.

## Recovery Workflow

1. Confirm the ledger mutation exists and that the issue is limited to downstream delivery.
2. Check `ledgerforge.outbox.queue.depth` and `ledgerforge.outbox.queue.lag.seconds` to size the backlog.
3. Inspect dead-lettered rows with `GET /api/outbox/events?state=DEAD_LETTER`.
4. Fix the downstream publisher or payload issue.
5. Requeue the affected rows with `POST /api/outbox/events/{eventId}/requeue`.
6. Trigger a catch-up batch with `POST /api/outbox/relay/run` if you need immediate drain instead of waiting for the next scheduler interval.

This keeps repair additive: the original ledger entries and outbox payload remain intact, and only the relay control fields move forward.
