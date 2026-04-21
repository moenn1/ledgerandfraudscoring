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

### Trace Propagation

- Generate `correlation_id` at ingress if missing.
- Propagate through API -> fraud -> ledger -> outbox.
- Include correlation id in audit events and operator timeline payloads.
- Persist payment mutation outbox rows for reserve/capture/refund/cancel so reconciliation can compare downstream event durability against posted ledger mutations.

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

## Security

### Access and Roles

- `Viewer`: read-only dashboards
- `Operator`: payment operations
- `Reviewer`: fraud review decisions
- `Admin`: configuration, replay, and repair actions

### Controls

- OAuth2/OIDC authentication for all operator APIs
- RBAC checks on sensitive actions (`capture`, `refund`, `review approve/reject`)
- Mask account identifiers in UI and logs where possible
- Encrypt sensitive columns at rest if available
- Sign internal service calls (phase 2 distributed mode)

### Abuse and Data Safety

- Rate limit payment mutation endpoints
- Validate request signatures for external callbacks/webhooks
- Keep audit logs append-only and tamper-evident
- Never expose raw secrets in logs or API responses

## Compliance-Inspired Practices

- Immutable ledger and audit trails
- Replay capability for deterministic incident analysis
- Separation of duties via role permissions
