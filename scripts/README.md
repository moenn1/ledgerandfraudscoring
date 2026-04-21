# Developer Scripts

These scripts provide a lightweight local workflow for backend readiness checks, demo seeding, and smoke validation.

## Prerequisites

- `bash`
- `curl`
- `python3`

## Configuration

Optional environment variables:

- `API_BASE_URL` (default: `http://127.0.0.1:8080`)
- `DEFAULT_CURRENCY` (default: `USD`)
- `IDEMPOTENCY_PREFIX` (default: `ledgerforge-local`)
- `PAYER_OWNER_ID` / `PAYEE_OWNER_ID` for demo seeding account owners
- `SMOKE_PAYMENT_AMOUNT_CENTS` for the smoke payment amount (default: `500`)
- `TIMEOUT_SECONDS` for readiness wait (default: `60`)
- `LEDGERFORGE_AUTH_ISSUER` (default: `https://auth.ledgerforge.local`)
- `LEDGERFORGE_AUTH_AUDIENCE` (default: `ledgerforge-operator-api`)
- `LEDGERFORGE_AUTH_HMAC_SECRET` for locally signed operator tokens
- `LEDGERFORGE_OPERATOR_BEARER_TOKEN` or `OPERATOR_BEARER_TOKEN` to supply an explicit bearer token for protected operator routes
- `LEDGERFORGE_OPERATOR_TOKEN_SUBJECT` and `LEDGERFORGE_OPERATOR_TOKEN_ROLES` (default: `OPERATOR,ADMIN`) to control the auto-generated local smoke token

## Commands

- `./scripts/wait-for-backend.sh`: waits for `/actuator/health` (fallback `/api/health`)
- `./scripts/seed-demo.sh`: creates demo accounts against `/api/accounts` and drives a sample payment lifecycle when the backend supports it
- `./scripts/smoke-test.sh`: health + account creation + payment create/confirm/capture + ledger verification checks
- `./scripts/demo-run.sh`: runs all of the above in order
- `./scripts/check-governance-docs.sh`: validates changelog and nearest-doc updates for workflow and code changes
- `./scripts/check-docs-index.sh`: validates that docs indexes and workflow references match the repository layout
- `python3 ./scripts/generate-operator-token.py --subject operator.ui@ledgerforge.local --role VIEWER`: prints a local operator bearer token

## Notes

- Scripts are intentionally tolerant while backend endpoints are still evolving.
- `seed-demo.sh` stays tolerant for local iteration, while `smoke-test.sh` is the stricter release and CI gate.
- Protected operator reads and mutation routes automatically receive a locally signed bearer token unless you override it with `LEDGERFORGE_OPERATOR_BEARER_TOKEN` or `OPERATOR_BEARER_TOKEN`.
- Governance and docs validation scripts are kept compatible with the default macOS Bash runtime as well as GitHub-hosted runners.
