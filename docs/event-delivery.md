# Event Delivery

LedgerForge persists domain events in a transactional outbox so payment and ledger state changes commit before any asynchronous delivery attempt begins.

## Delivery Model

- Source-of-truth writes and outbox rows are stored in the same database transaction.
- The relay polls pending rows, claims them with a short lease, and publishes them with at-least-once semantics.
- Every event carries a stable `eventId`; downstream consumers must deduplicate on that identifier.
- Failed deliveries are retried with exponential backoff. The event row keeps `attemptCount`, `availableAt`, `publishedAt`, and `lastError` for inspection.

## Implemented Event Families

- `payment.created`
- `payment.review_required`
- `payment.rejected`
- `payment.reserved`
- `payment.captured`
- `payment.refunded`
- `payment.reversed`
- `payment.charged_back`
- `payment.cancelled`
- `payment.settled`
- `fraud.review_case.opened`
- `fraud.review_case.decided`
- `ledger.journal.committed`
- `settlement.batch.completed`
- `payout.scheduled`
- `payout.delayed`
- `payout.paid`

## Event Envelope

```json
{
  "id": "7f0c0d5e-15fa-4a6d-8d4d-c6e2d9d3c82b",
  "eventType": "payment.captured",
  "aggregateType": "payment",
  "aggregateId": "d13cf5d7-1849-4e72-96c8-7f4b809463a1",
  "eventVersion": 1,
  "partitionKey": "d13cf5d7-1849-4e72-96c8-7f4b809463a1",
  "correlationId": "corr-demo-123",
  "idempotencyKey": "capture-demo-001",
  "deliveryStatus": "PUBLISHED",
  "attemptCount": 1,
  "createdAt": "2026-04-20T17:00:00Z",
  "publishedAt": "2026-04-20T17:00:05Z",
  "payload": {
    "paymentId": "d13cf5d7-1849-4e72-96c8-7f4b809463a1",
    "status": "CAPTURED",
    "payerAccountId": "188e1ff1-c89a-448e-a389-c39a7d3efce0",
    "payeeAccountId": "b5bc19f4-af87-4e2e-b352-084bf23def6e",
    "amount": 125.0000,
    "currency": "USD",
    "journalId": "0b99e53f-b26f-4c7d-9f10-fd6f8ff35677",
    "fee": 3.7500,
    "settlementScheduledFor": "2026-04-20T17:00:00Z"
  }
}
```

`ledger.journal.committed` payloads include the committed legs so downstream projections can rebuild ledger-side read models without querying mutable application state.

## Relay Controls

- `LEDGERFORGE_OUTBOX_RELAY_ENABLED`: enable or disable scheduled relay polling
- `LEDGERFORGE_OUTBOX_BATCH_SIZE`: max events claimed per poll
- `LEDGERFORGE_OUTBOX_POLL_DELAY_MS`: relay polling interval
- `LEDGERFORGE_OUTBOX_LEASE_DURATION_MS`: claim lease duration before another relay instance can retry the row

## Inspection API

Use the viewer-scoped endpoint below to inspect delivery state:

```bash
curl -sS -H "$OPERATOR_AUTH_HEADER" \
  "$API_BASE_URL/api/events/outbox?status=pending&limit=20" | python3 -m json.tool
```
