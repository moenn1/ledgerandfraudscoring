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
- `./scripts/seed-demo.sh`: creates demo accounts, creates a payment, and drives it through reserve and capture
- `./scripts/smoke-test.sh`: health + idempotent payment lifecycle checks through refund and ledger verification
- `./scripts/demo-run.sh`: runs all of the above in order
- `./scripts/check-governance-docs.sh`: validates changelog and nearest-doc updates for workflow and code changes
- `./scripts/check-governance-docs-test.sh`: regression coverage for governance validation across aggregate branch diffs and explicit pushed commit ranges
- `./scripts/check-docs-index.sh`: validates that docs indexes and workflow references match the repository layout

## Notes

- Demo and smoke scripts fail fast when the payment, ledger, or verification APIs drift from the documented contract so CI cannot pass on partial coverage.
- `./scripts/demo-run.sh` now proves account creation, payment idempotency, reserve/capture, refund, and ledger verification rather than logging and continuing on API failures.
- Governance and docs validation scripts are kept compatible with the default macOS Bash runtime as well as GitHub-hosted runners.
