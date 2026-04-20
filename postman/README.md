# Postman Assets

## Files

- `LedgerForge-MVP.postman_collection.json`
- `LedgerForge-local.postman_environment.json`

## Import Order

1. Import the environment file.
2. Import the collection file.
3. Select the `LedgerForge Local` environment.

## Suggested Run Flow

1. `Health/GET /actuator/health`
2. `Accounts/POST /api/accounts (payer)`
3. `Accounts/POST /api/accounts (payee)`
4. `Payments/POST /api/payments`
5. `Payments/GET /api/payments/{id}`
6. `Payments/POST /api/payments/{id}/confirm`
7. `Payments/POST /api/payments/{id}/capture`
8. `Ledger & Fraud` requests as needed

The payment create request stores `paymentId` in the environment automatically when the backend returns an `id` field.
