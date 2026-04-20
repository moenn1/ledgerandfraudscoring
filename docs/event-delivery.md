# Event Delivery and Dead-Letter Handling

LedgerForge now persists every immutable audit event into a transactional outbox inside the same database transaction as the originating payment, fraud, or ledger mutation. The relay then delivers those messages asynchronously through a pluggable publisher with explicit retry, backoff, and dead-letter policies.

## Delivery Contract

- The audit write and outbox enqueue happen atomically. If the transaction rolls back, neither record is committed.
- Each outbox message carries the immutable audit event payload, correlation id, destination, attempt counters, and the last delivery error.
- Delivery is pull-based inside the backend. A scheduled relay scans ready `PENDING` records and hands them to the configured publisher.
- Published records remain queryable as immutable delivery history. Failed records keep retry metadata for investigation and replay.

## Retry and Poison-Message Policy

- Default destination: `ledgerforge.audit-events`
- Relay cadence: every `5s`
- Batch size: `25` ready messages per relay pass
- Retry schedule: capped exponential backoff starting at `5s`
- Attempt ceiling: `5` total delivery attempts per message by default
- Poison messages: if the publisher throws `PoisonMessageException`, the relay dead-letters the message immediately
- Retry exhaustion: any non-poison failure dead-letters the message once the configured attempt ceiling is reached

## Admin Endpoints

- `GET /api/admin/outbox/messages?status=PENDING|PUBLISHED|DEAD_LETTER&limit=50`
  Returns the newest outbox records for investigation, including payload JSON, attempt counters, and last error.
- `POST /api/admin/outbox/process?limit=25`
  Triggers an immediate relay pass and returns how many messages were published, retried, or dead-lettered.
- `POST /api/admin/outbox/messages/{id}/requeue`
  Resets a dead-lettered message back to `PENDING` so it can be retried after an operator fixes the downstream issue.

## Configuration

The relay is controlled through `backend/src/main/resources/application.yml` and matching environment variables:

- `OUTBOX_DESTINATION`
- `OUTBOX_RELAY_ENABLED`
- `OUTBOX_RELAY_BATCH_SIZE`
- `OUTBOX_RELAY_DELAY_MS`
- `OUTBOX_MAX_ATTEMPTS`
- `OUTBOX_BASE_DELAY_MS`
- `OUTBOX_MAX_DELAY_MS`

## Operational Expectations

- Dead-letter growth is operator-visible backlog and should alert the owning team.
- Requeue only after the downstream sink or payload issue is understood; requeue is not a substitute for diagnosis.
- The outbox message id is the delivery key for downstream idempotency when the publisher is wired to an external broker or webhook sink.
