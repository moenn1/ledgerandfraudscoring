# Ledger Invariants

Financial correctness depends on explicit invariants. These must be enforced at write time and monitored continuously.

## Core Invariants

1. **Double-entry balance**: each journal transaction sums to zero across all entries.
2. **Append-only entries**: ledger entries are immutable; correction is reversal, never overwrite.
3. **Currency consistency**: all entries in a journal share one currency.
4. **No phantom balances**: account balance is projection from entries, not mutable source value.
5. **Traceability**: every journal links to business reference (`payment_id`, `refund_id`, etc.).

## Journal Patterns

### Payment capture with platform fee ($100 gross, $3 fee)

```text
DEBIT   payer_cash_account              100.00 USD
CREDIT  payee_settlement_account         97.00 USD
CREDIT  platform_revenue_account          3.00 USD
Net = 0
```

### Refund

```text
DEBIT   payee_settlement_account         97.00 USD
DEBIT   platform_revenue_account          3.00 USD
CREDIT  payer_cash_account              100.00 USD
Net = 0
```

## Reconciliation Checks

- Daily scan for unbalanced journals (must be zero results).
- Verify each payment lifecycle stage has expected journal types.
- Detect duplicate reserve/capture journals for same reference.
- Compare event stream counts vs ledger mutation counts.

## Suggested Database Constraints

- `ledger_entries(amount > 0)`
- `ledger_entries(direction in ('DEBIT','CREDIT'))`
- unique `(journal_id, account_id, direction, amount, line_number)` if line numbers used
- foreign key from `journal_transactions.reference_id` to business object when feasible

## Projection Guidance

Balance queries should aggregate entries by account:

```text
balance = SUM(CREDIT amounts) - SUM(DEBIT amounts)
```

Store snapshots only as cache/optimization. Rebuild from immutable entries must yield same balance.
