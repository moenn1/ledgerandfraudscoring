# Postman Assets

## Files

- `LedgerForge.postman_collection.json`
- `LedgerForge-local.postman_environment.json`

The collection tracks the current `/api` contract baseline for local development. It assumes UUID-based resource ids and bearer-token auth for viewer, operator, reviewer, and admin requests.

## Import Order

1. Import the environment file.
2. Import the collection file.
3. Select the `LedgerForge Local` environment.
4. Populate `operatorToken`, `reviewerToken`, and `adminToken` in the environment before running secured requests.

## Environment Variables

- `baseUrl`: backend origin, typically `http://127.0.0.1:8080`
- `currency`: request currency for examples
- `operatorToken`, `reviewerToken`, `adminToken`: local bearer tokens
- `payerAccountId`, `payeeAccountId`, `paymentId`, `reviewCaseId`, `webhookEndpointId`: populated by request scripts during the flow

## Suggested Run Flow

1. `Health/GET /actuator/health`
2. `Accounts/POST /api/accounts (payer)`
3. `Accounts/POST /api/accounts (payee)`
4. `Payments/POST /api/payments`
5. `Payments/GET /api/payments/{id}`
6. `Payments/POST /api/payments/{id}/confirm`
7. `Payments/POST /api/payments/{id}/capture`
8. `Fraud/GET /api/fraud/reviews` when exercising manual-review flows
9. `Ledger, Settlement, Webhooks, and Outbox` requests as needed

Use `docs/local-api-requests.md` when you need the full settlement, payout, webhook-callback, or signed-request examples that are easier to execute from a shell.
