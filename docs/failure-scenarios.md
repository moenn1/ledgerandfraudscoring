# Failure Scenarios and Recovery

This project should explicitly handle partial failures and retries without breaking ledger correctness.

## Scenario Matrix

| Scenario | Risk | Required Handling |
|---|---|---|
| Payment request retried twice | Duplicate processing | Enforce idempotency key on create/confirm |
| Ledger write succeeds, event publish fails | Lost downstream updates | Transactional outbox + async relay retry |
| Fraud service timeout | Indeterminate decision | Timeout policy: retry, then `REVIEW` fallback |
| Duplicate webhook/callback | Duplicate state changes | Deduplicate by event id + optimistic checks |
| Webhook consumer rejects a delivered notification | Lost downstream side-effect | Record the callback, mark the receipt as rejected, and reschedule bounded retries without mutating ledger history |
| Projection lag behind ledger | Stale reads | Display as eventual; provide replay endpoint |
| Late manual review decision | Invalid transition | Reject stale action if state already final |
| Refund after settlement | Complex reversal | Create refund journal with full audit trail |
| Capture must be compensated after a downstream failure | Funds already moved | Execute reversal workflow and persist immutable reversal history |
| Chargeback received after settlement | Dispute traceability gap | Post chargeback journal and expose immutable adjustment history |
| Payout run after the merchant balance was reduced by a refund/chargeback | Overdrawn payout | Mark payout `DELAYED`, keep the existing settlement batch immutable, and retry only after balance is restored |
| Concurrent payments overspend account | Negative funds risk | Locking/compare-and-swap on available balance check |

## Recommended Patterns

- Idempotency keys per mutation endpoint
- Transactional outbox for reliable publish
- Optimistic locking on payment version
- Retries with exponential backoff and jitter
- Dead-letter queue for poison events
- Saga coordinator for multi-step cross-module actions

## Compensation Strategy

If a downstream step fails after ledger mutation:

1. Mark payment as `REVERSED_PENDING`
2. Create compensating journal entries
3. Transition payment to `REVERSED`
4. Persist immutable reversal history with the committed journal reference
5. Emit `payment.reversed` and audit event

## Retry Policy Baseline

- Fraud call: `3` retries (`100ms`, `300ms`, `900ms`) then fallback to review
- Event publish relay: unbounded retries with backoff + dead-letter after threshold
- External webhook dispatch: bounded retries with idempotent delivery id
- Callback acknowledgements: deduplicate by `(endpoint_id, callback_id)` and reject payload drift for a reused callback id

## Operational Alerts

- Unbalanced journals detected
- Outbox backlog age above threshold
- Notification retry backlog age above threshold
- Fraud timeout rate spike
- Reconciliation mismatch count > 0
