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
- `TIMEOUT_SECONDS` for readiness wait (default: `60`)

## Commands

- `./scripts/wait-for-backend.sh`: waits for `/actuator/health` (fallback `/api/health`)
- `./scripts/seed-demo.sh`: tries to create demo accounts and one payment
- `./scripts/smoke-test.sh`: health + basic payment API checks
- `./scripts/demo-run.sh`: runs all of the above in order

## Notes

- Scripts are intentionally tolerant while backend endpoints are still evolving.
- Failed optional API calls are logged and skipped so local iteration stays fast.
