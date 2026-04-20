# Failure Scenarios and Recovery

This project should explicitly handle partial failures and retries without breaking ledger correctness.

## Scenario Matrix

| Scenario | Risk | Required Handling |
|---|---|---|
| Payment request retried twice | Duplicate processing | Enforce idempotency key on create/confirm |
| Ledger write succeeds, event publish fails | Lost downstream updates | Transactional outbox captures the event in the same commit, then relay retry handles publish failure |
| Fraud service timeout | Indeterminate decision | Timeout policy: retry, then `REVIEW` fallback |
| Duplicate webhook/callback | Duplicate state changes | Deduplicate by event id + optimistic checks |
| Projection lag behind ledger | Stale reads | Display as eventual; provide replay endpoint |
| Late manual review decision | Invalid transition | Reject stale action if state already final |
| Refund after settlement | Complex reversal | Create refund journal with full audit trail |
| Concurrent payments overspend account | Negative funds risk | Locking/compare-and-swap on available balance check |

## Recommended Patterns

- Idempotency keys per mutation endpoint
- Transactional outbox for reliable publish
- Optimistic locking on payment version
- Retries with capped exponential backoff
- Dead-letter queue for poison events and retry exhaustion
- Saga coordinator for multi-step cross-module actions

## Compensation Strategy

If a downstream step fails after ledger mutation:

1. Mark payment as `REVERSED_PENDING`
2. Create compensating journal entries
3. Transition payment to `REVERSED`
4. Emit `payment.reversed` and audit event

## Retry Policy Baseline

- Fraud call: `3` retries (`100ms`, `300ms`, `900ms`) then fallback to review
- Event publish relay: `5` attempts by default (`5s`, `10s`, `20s`, `40s`, `80s`) then dead-letter
- External webhook dispatch: bounded retries with idempotent delivery id
- Poison payloads: dead-letter immediately without consuming the remaining retry budget

## Operational Alerts

- Unbalanced journals detected
- Outbox backlog age above threshold
- Dead-letter backlog count above threshold
- Fraud timeout rate spike
- Reconciliation mismatch count > 0
