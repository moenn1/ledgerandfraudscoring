# Architecture Overview

LedgerForge Payments is a local-first fintech backend that processes payments through a deterministic workflow:

1. API receives a payment intent.
2. Fraud service computes real-time risk.
3. Orchestrator decides approve/review/reject.
4. Ledger writes immutable balanced entries for reserve/capture/refund/reversal.
5. Account freeze controls stop new money movement without mutating prior ledger history.
6. Audit and events are emitted for operator views and downstream consumers.

The ledger is the source of truth. Balances are projections from immutable entries.

Operational control note:

- `ACTIVE` accounts can participate in normal create, confirm, capture, and manual-review approval flows.
- `FROZEN` accounts block new outward payment progression and manual-review approvals.
- `FROZEN` accounts can still participate in `REFUND` and `REVERSAL` journals so operators can unwind exposure without rewriting history.

## Modular Monolith Structure

The recommended MVP implementation is a modular monolith with explicit boundaries:

- `payments`: payment intents, lifecycle transitions, idempotency
- `ledger`: accounts, journals, entries, balance projection
- `fraud`: scoring, rules, review cases
- `orchestrator`: flow coordination and compensation
- `audit`: immutable event trail for compliance and debugging
- `admin`: operator-facing reporting and reconciliation endpoints

## High-Level Component Diagram

```mermaid
flowchart LR
    Client[Client API Consumer] --> API[Payment API]
    API --> Orch[Orchestrator]
    Orch --> Fraud[Fraud Scoring Service]
    Orch --> Ledger[Ledger Service]
    Orch --> Audit[Audit Event Service]
    Ledger --> DB[(PostgreSQL)]
    Fraud --> DB
    Audit --> DB
    Orch --> Outbox[Transactional Outbox]
    Outbox --> Bus[(Kafka or RabbitMQ)]
    Bus --> Proj[Read Model Projections]
    Proj --> Admin[Operator Dashboard APIs]
```

## Core Data Relationships

```mermaid
erDiagram
    ACCOUNT ||--o{ LEDGER_ENTRY : owns
    JOURNAL_TRANSACTION ||--o{ LEDGER_ENTRY : contains
    PAYMENT_INTENT ||--o| JOURNAL_TRANSACTION : references
    PAYMENT_INTENT ||--o{ FRAUD_SIGNAL : receives
    PAYMENT_INTENT ||--o| REVIEW_CASE : may_create
    PAYMENT_INTENT ||--o{ AUDIT_EVENT : emits
```

## Request and Event Flow

```mermaid
sequenceDiagram
    participant C as Client
    participant P as Payment API
    participant O as Orchestrator
    participant F as Fraud
    participant L as Ledger
    participant A as Audit
    participant B as Event Bus

    C->>P: POST /payments (idempotency-key)
    P->>O: create intent
    O->>F: score(payment, signals)
    F-->>O: score + decision + reasons
    alt decision = APPROVE
        O->>L: reserve/capture journals
        L-->>O: balanced entries persisted
        O->>A: append audit events
        O->>B: publish payment.approved, ledger.entry.created
        O-->>P: approved/captured response
    else decision = REVIEW
        O->>A: create review case
        O->>B: publish review.required
        O-->>P: pending_review response
    else decision = REJECT
        O->>A: append reject reason
        O->>B: publish fraud.flagged, payment.failed
        O-->>P: rejected response
    end
```

## Deployment Notes

- Start as one service with module boundaries and separate packages.
- Persist all financial state in PostgreSQL with Flyway/Liquibase migrations.
- Use Redis for idempotency key cache and velocity counters (optional in MVP).
- Introduce async bus and outbox in phase 2 for higher throughput and decoupling.
