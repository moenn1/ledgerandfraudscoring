# Local API Requests

These examples exercise the account-freeze controls against a local LedgerForge backend at `http://localhost:8080/api`.

## Freeze and unfreeze an account

```bash
curl -X POST http://localhost:8080/api/accounts/$ACCOUNT_ID/status \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-Id: account-freeze-001' \
  -d '{
    "status": "FROZEN",
    "reason": "fraud watch escalation"
  }'
```

```bash
curl -X POST http://localhost:8080/api/accounts/$ACCOUNT_ID/status \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-Id: account-unfreeze-001' \
  -d '{
    "status": "ACTIVE",
    "reason": "case cleared"
  }'
```

Expected outcome: the response reflects the new status and an `account.status_changed` audit event records the correlation ID, prior status, new status, and operator reason.

## Frozen-account behavior

- `POST /api/payments` rejects new intents when the payer or payee is frozen.
- `POST /api/payments/{id}/confirm` rejects reserve attempts when the payer or payee is frozen.
- `POST /api/payments/{id}/capture` rejects capture when the payer or payee is frozen.
- `POST /api/fraud/reviews/{id}/decision` rejects approval when the payer or payee is frozen.

Expected outcome: these endpoints return `409 Conflict` and the ledger remains unchanged for the blocked action.

## Recovery flow while an account stays frozen

Once a payment is already reserved or captured, operator recovery still uses immutable journals:

- `POST /api/payments/{id}/cancel` can post a `REVERSAL` journal for a reserved payment.
- `POST /api/payments/{id}/refund` can post a `REFUND` journal for a captured payment.

Expected outcome: freeze controls stop new progression, but recovery paths remain available so operators can unwind risk without deleting or rewriting prior entries.
