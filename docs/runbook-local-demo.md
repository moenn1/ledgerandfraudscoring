# Local Demo Runbook

This runbook provides a practical path to demo the platform once backend/frontend code is available.

## Prerequisites

- Java 17+
- Maven 3.8+
- Node.js 18+
- Docker + Docker Compose (optional, for the planned Postgres/Kafka/Redis stack tracked separately from the current in-memory smoke flow)

## Suggested Local Topology

```mermaid
flowchart LR
    UI[Operator UI :3000] --> API[Backend API :8080]
    API --> PG[(PostgreSQL :5432)]
    API --> Redis[(Redis :6379)]
    API --> Kafka[(Kafka :9092)]
```

## Startup Sequence (Target)

1. Start dependencies:
   - `docker compose up -d postgres redis kafka`
2. Start backend:
   - `cd backend`
   - `mvn spring-boot:run`
3. Start frontend:
   - `cd frontend`
   - `npm ci`
   - `npm run dev`
4. Open dashboard:
   - `http://localhost:3000`

For the currently checked-in fast path, `./scripts/demo-run.sh` starts from the in-memory backend profile and now validates account creation, payment create/confirm/capture, and ledger verification without requiring the optional container stack.

## Demo Script

1. Create two accounts (`payer`, `payee`) in the same currency.
2. Create a payment intent with an idempotency key.
3. Confirm payment and show the fraud score plus decision.
4. Capture payment and inspect ledger entries.
5. Run `/api/ledger/verification` and show that all ledger checks pass.
6. Query account balances and show projection correctness.
7. Trigger a high-risk payment and show the manual review queue.
8. Approve or reject the review case and verify state transitions.
9. Run reconciliation endpoints and show no mismatches.

## Verification Checklist

- Journal entries balance to zero for each transaction.
- Duplicate `POST /payments` with same key is idempotent.
- Duplicate `capture` does not double-charge.
- Audit timeline includes every mutation.
- Fraud decisions include reason codes.
- Dashboard reflects ledger and payment state consistently.

## Troubleshooting

- If backend fails on migrations: reset local DB volume and rerun.
- If stale balances appear: replay projection endpoint and refresh UI.
- If events are delayed: inspect outbox backlog and broker health.
- If fraud service timeout spikes: force fallback to `REVIEW` and inspect latency metrics.
