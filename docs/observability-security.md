# Observability and Security Notes

## Observability

### Implemented Telemetry

- Correlation IDs at API ingress via `X-Correlation-Id`
- Request-completion logs with stable key-value fields
- Actuator metrics for payment lifecycle, fraud review, outbox backlog, and ledger verification health

### Trace Propagation

- Generate `correlation_id` at ingress if missing.
- Propagate through API -> fraud -> ledger -> outbox.
- Include correlation id in audit events and operator timeline payloads.
- Persist payment mutation outbox rows for reserve/capture/refund/cancel so reconciliation can compare downstream event durability against posted ledger mutations.
- Use the same `payment.reserved` audit and outbox events for manual-review approvals that post a reserve journal, so reserved funds remain reconcilable regardless of whether approval was automated or operator-driven.
- The backend now echoes `X-Correlation-Id` on `/api/**` responses and logs request completion with `method`, `path`, `status`, `duration_ms`, `correlation_id`, `operator`, and `idempotency_key`.

### Metric Inventory

Query metrics through `/actuator/metrics/{name}`.

Payment domain:

- `ledgerforge.payment.operation.latency` tagged by `operation` and `outcome`
- `ledgerforge.payment.lifecycle.time_to_status` tagged by `status` and `risk_decision`
- `ledgerforge.payment.outcome.total` tagged by `status` and `risk_decision`

Fraud domain:

- `ledgerforge.fraud.scoring.latency` tagged by `decision`
- `ledgerforge.fraud.scoring.outcome.total` tagged by `decision`
- `ledgerforge.fraud.scoring.failure.total`
- `ledgerforge.fraud.review.queue.depth`
- `ledgerforge.fraud.review.queue.age.seconds`
- `ledgerforge.fraud.review.case.opened`
- `ledgerforge.fraud.review.case.decided` tagged by `decision`
- `ledgerforge.fraud.review.case.open_duration` tagged by `decision`

Outbox domain:

- `ledgerforge.outbox.queue.depth`
- `ledgerforge.outbox.queue.lag.seconds`
- `ledgerforge.outbox.publish.success`
- `ledgerforge.outbox.publish.retry`
- `ledgerforge.outbox.publish.dead_letter`

Ledger verification and reconciliation:

- `ledgerforge.ledger.verification.latency` tagged by `result`
- `ledgerforge.ledger.verification.last.issue_count`
- `ledgerforge.ledger.verification.last.healthy`
- `ledgerforge.ledger.verification.last.run.epoch.seconds`
- `ledgerforge.ledger.verification.last.finding_count` tagged by `category`

Finding categories currently emitted are:

- `unbalanced_journals`
- `mixed_currency_journals`
- `account_currency_mismatches`
- `duplicate_payment_journals`
- `mutation_event_reconciliation_findings`
- `payment_lifecycle_mismatches`

### Alerting Expectations

- Page when `ledgerforge.outbox.queue.lag.seconds` keeps climbing or `ledgerforge.outbox.publish.dead_letter` increases during normal traffic.
- Page when `ledgerforge.fraud.review.queue.depth` or `ledgerforge.fraud.review.queue.age.seconds` breaches the team review SLA.
- Investigate when `ledgerforge.fraud.scoring.failure.total` increments, because payment confirmation will only stay reliable if fraud decisions keep persisting.
- Treat any `ledgerforge.ledger.verification.last.issue_count` above `0` or `ledgerforge.ledger.verification.last.healthy` equal to `0` as a reconciliation incident until operators review the finding categories.
- Alert when `ledgerforge.ledger.verification.last.run.epoch.seconds` becomes stale enough that operators no longer have a recent ledger verification signal.

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
- Sanitize unexpected `500` responses to a generic message and keep stack traces server-side only.
- Keep the H2 console disabled by default. Enable it only for local debugging with `H2_CONSOLE_ENABLED=true`.

### Operator API Role Matrix

- `Viewer`: authenticated read access to operator dashboards, payment and account inspection APIs, ledger journal reads, and fraud queue listings
- `Operator`: `capture`, `refund`, and `cancel` payment mutations
- `Reviewer`: fraud review decisions (`POST /api/fraud/reviews/{id}/decision`)
- `Admin`: ledger replay, ledger verification, and repair-oriented journal actions
- `Admin`: outbox relay inspection, manual relay runs, and dead-letter requeue actions

The current backend protects these routes directly:

- `GET /api/accounts`, `GET /api/accounts/{id}`, `GET /api/accounts/{id}/balance`, and `GET /api/accounts/{id}/ledger` require an authenticated operator with at least `Viewer`
- `GET /api/payments`, `GET /api/payments/{id}`, `GET /api/payments/{id}/risk`, and `GET /api/payments/{id}/ledger` require an authenticated operator with at least `Viewer`
- `POST /api/payments/{id}/capture`, `POST /api/payments/{id}/refund`, `POST /api/payments/{id}/cancel` require `Operator`
- `GET /api/fraud/reviews` requires an authenticated operator with at least `Viewer`
- `POST /api/fraud/reviews/{id}/decision` requires `Reviewer`
- `GET /api/ledger/**` and `POST /api/ledger/journals` require authenticated operator access, with `Admin` enforced on replay/verification and journal mutation paths
- `GET /api/outbox/events`, `POST /api/outbox/relay/run`, and `POST /api/outbox/events/{id}/requeue` require `Admin`

Reviewer audit fields are derived from the authenticated token subject or preferred username. The decision payload no longer needs to supply a trusted actor identifier.

### Local Development Tokens

For local development and tests, the backend accepts HS256-signed JWTs using the configured LedgerForge operator audience and issuer defaults.

Generate a token with:

```bash
python3 scripts/generate-operator-token.py \
  --subject risk.reviewer@ledgerforge.local \
  --role REVIEWER
```

Relevant backend configuration:

- `LEDGERFORGE_AUTH_ISSUER_URI` or `LEDGERFORGE_AUTH_JWK_SET_URI` for an external OIDC issuer
- `LEDGERFORGE_AUTH_ISSUER` and `LEDGERFORGE_AUTH_AUDIENCE` for issuer/audience validation
- `LEDGERFORGE_AUTH_HMAC_SECRET` for local HS256 token signing
- `LEDGERFORGE_ALLOWED_ORIGIN_*` for local operator UI CORS origins

## Compliance-Inspired Practices

- Immutable ledger and audit trails
- Replay capability for deterministic incident analysis
- Separation of duties via role permissions
