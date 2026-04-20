# Observability and Security Notes

## Observability

### Required Telemetry

- Distributed tracing with correlation IDs (`payment_id`, `journal_id`, `account_id`)
- Structured logs (JSON) with stable field names
- Metrics:
  - payment create/confirm/capture latency
  - approval/review/reject rates
  - fraud timeout/error rates
  - reconciliation mismatches
  - outbox queue depth and lag
  - webhook delivery success/retry/failure counts

### Trace Propagation

- Generate `correlation_id` at ingress if missing.
- Propagate through API -> fraud -> ledger -> outbox.
- Include correlation id in audit events and operator timeline payloads.

### Outbox Visibility

- Inspect `/api/events/outbox` to confirm whether a domain event is still pending or has been published.
- Alert on sustained growth in pending rows, increasing `attemptCount`, or `lastError` values that stop changing.
- Treat `eventId` as the downstream deduplication key; replayed delivery attempts keep the same identifier.
- Expect `idempotencyKey` values in outbox inspection responses to be masked; use `eventId`, `correlationId`, and payment identifiers for operator correlation instead of replaying with copied keys.

### Webhook Visibility

- Inspect `/api/webhooks/deliveries` to confirm transport status, callback receipt state, response code, and retry schedule per delivery.
- Treat the delivery id as the webhook transport deduplication key and `(endpoint_id, callback_id)` as the callback deduplication key.
- Correlate webhook audit events with the originating payment and outbox event before replaying a failed notification.
- Inspect `/api/webhooks/endpoints` for the masked signing-secret hint only; the raw webhook secret is now encrypted at rest and is not returned once created.

### Audit Event Standard

Every financial mutation should emit an immutable event:

```json
{
  "eventType": "payment.captured",
  "paymentId": "pay_123",
  "journalId": "jrnl_123",
  "actorType": "system",
  "actorId": "orchestrator",
  "correlationId": "corr_123",
  "createdAt": "2026-04-20T16:00:00Z"
}
```

Webhook delivery and callback handling should also emit immutable audit events tied to `deliveryId`, `eventType`, and callback receipt state so operators can explain every retry.

## Security

### Access and Roles

- `Viewer`: read-only dashboards
- `Operator`: payment operations
- `Reviewer`: fraud review decisions
- `Admin`: configuration, replay, and repair actions

### Controls

- OAuth2/OIDC authentication for all operator APIs
- RBAC checks on sensitive actions (`capture`, `refund`, `review approve/reject`, `settlement run`, `ledger replay`)
- Mask account identifiers in UI and logs where possible
- Encrypt sensitive columns at rest if available
- Sign internal service calls (phase 2 distributed mode)

### Implemented Local Boundary

- The backend now runs as an OAuth2 resource server and validates bearer tokens against either `issuer-uri`, `jwk-set-uri`, or the local HS256 dev secret.
- Role hierarchy is enforced as `Admin -> Operator/Reviewer -> Viewer`.
- Manual-review decisions no longer trust a request-body actor field; the authenticated principal becomes the audit actor.
- `401` and `403` responses are emitted as JSON and appended to the immutable audit trail as security events.
- The local helper `scripts/generate-operator-token.py` mints HS256 tokens for demo and smoke flows; shared environments should override the dev secret or use a real issuer.
- Webhook endpoint signing secrets are encrypted before persistence using `LEDGERFORGE_DATA_PROTECTION_KEY`; shared environments must override the dev default.
- Payment, adjustment, webhook, and outbox responses now expose masked idempotency or secret fields so operators can correlate records without copying replay material.

### Abuse and Data Safety

- Rate limit payment mutation endpoints
- Validate request signatures for external callbacks/webhooks
- Keep webhook delivery payload hashes and callback fingerprints so duplicate or tampered notifications can be explained after the fact
- Keep audit logs append-only and tamper-evident
- Never expose raw secrets in logs or API responses
- Sanitize structured audit/outbox/webhook payloads by field name before persistence so sensitive keys remain masked in legacy operator tooling and ad hoc JSON inspection

## Compliance-Inspired Practices

- Immutable ledger and audit trails
- Replay capability for deterministic incident analysis
- Separation of duties via role permissions
