# Developer Scripts

These scripts provide the local dependency lifecycle, backend readiness checks, seeded demo data, and smoke validation for LedgerForge Payments.

## Prerequisites

- `bash`
- `curl`
- `python3`
- `docker` with the Compose plugin (`dev-up.sh` / `dev-down.sh` only)

## Configuration

Optional environment variables:

- `API_BASE_URL` (default: `http://localhost:8080`)
- `DEFAULT_CURRENCY` (default: `USD`)
- `IDEMPOTENCY_PREFIX` (default: `ledgerforge-local`)
- `TIMEOUT_SECONDS` for readiness wait (default: `60`)
- `DEMO_NAMESPACE` to group objects created by `seed-demo.sh`
- `SMOKE_NAMESPACE` to group objects created by `smoke-test.sh`
- `LEDGERFORGE_AUTH_HMAC_SECRET` to override the local JWT signing secret
- `LEDGERFORGE_DATA_PROTECTION_KEY` to override the local field-encryption key used for persisted webhook secrets
- `OPERATOR_SUBJECT` for the generated local operator token subject
- `OPERATOR_ROLE` for the generated local operator token role (defaults to `ADMIN`)
- `OPERATOR_TOKEN` to supply a pre-generated bearer token instead of minting one in-script

## Commands

- `./scripts/dev-up.sh`: starts PostgreSQL for local backend work
- `./scripts/dev-up.sh --extended`: also starts Redis and Kafka
- `./scripts/dev-down.sh`: stops local dependencies
- `./scripts/dev-down.sh --volumes`: stops dependencies and removes compose volumes
- `./scripts/generate-operator-token.py --subject operator.admin@ledgerforge.local --role ADMIN`: mints a local HS256 bearer token for secured API calls
- `./scripts/wait-for-backend.sh`: waits for `/actuator/health` (fallback `/api/health`)
- `./scripts/seed-demo.sh`: creates fresh demo accounts, one captured payment, and one manual-review case
- `./scripts/smoke-test.sh`: health + idempotency + confirm/capture + ledger checks against a fresh namespace
- `./scripts/demo-run.sh`: runs all of the above in order

## Notes

- `seed-demo.sh` prints the generated payment and review-case ids so they can be used in the UI or in manual `curl` flows.
- `smoke-test.sh` is intended to fail loudly when the current API contract regresses.
- The API helpers automatically attach an `Authorization: Bearer ...` header using either `OPERATOR_TOKEN` or a token minted by `generate-operator-token.py`.
- The backend can run against H2 with no Docker, or against PostgreSQL with `SPRING_PROFILES_ACTIVE=postgres`.
- Shared environments should override both `LEDGERFORGE_AUTH_HMAC_SECRET` and `LEDGERFORGE_DATA_PROTECTION_KEY`; the defaults are for local demos only.
