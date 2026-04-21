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
- `GOVERNANCE_DOCS_BASE_REF` and `GOVERNANCE_DOCS_HEAD_REF` to override the committed diff range that `check-governance-docs.sh` validates

## Commands

- `./scripts/wait-for-backend.sh`: waits for `/actuator/health` (fallback `/api/health`)
- `./scripts/seed-demo.sh`: tries to create demo accounts and one payment
- `./scripts/smoke-test.sh`: health + basic payment API checks
- `./scripts/demo-run.sh`: runs all of the above in order
- `./scripts/check-governance-docs.sh`: validates changelog and nearest-doc updates for workflow and code changes
- `./scripts/check-governance-docs-test.sh`: regression-tests governance range resolution for aggregate branch diffs versus actual pushed commit windows
- `./scripts/check-docs-index.sh`: validates that docs indexes and workflow references match the repository layout

## Notes

- Scripts are intentionally tolerant while backend endpoints are still evolving.
- Failed optional API calls are logged and skipped so local iteration stays fast.
- Governance and docs validation scripts are kept compatible with the default macOS Bash runtime as well as GitHub-hosted runners.
- GitHub Actions passes the pushed `before` and `after` SHAs into `check-governance-docs.sh`, so a later code-only push cannot inherit an earlier docs update from the same branch.
