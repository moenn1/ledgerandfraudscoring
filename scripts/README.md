# Developer Scripts

These scripts provide a lightweight local workflow for backend readiness checks, demo seeding, and smoke validation.

## Prerequisites

- `bash`
- `curl`
- `jq`
- `python3`

## Configuration

Optional environment variables:

- `API_BASE_URL` (default: `http://127.0.0.1:8080`)
- `DEFAULT_CURRENCY` (default: `USD`)
- `IDEMPOTENCY_PREFIX` (default: `ledgerforge-local`)
- `TIMEOUT_SECONDS` for readiness wait (default: `60`)

## Commands

- `scripts/wait-for-backend.sh`: waits for `/actuator/health` (fallback `/api/health`)
- `scripts/seed-demo.sh`: tries to create demo accounts and one payment
- `scripts/smoke-test.sh`: health + basic payment API checks
- `scripts/demo-run.sh`: runs all of the above in order
- `python3 scripts/ci/validate-docs.py`: validates markdown links and documented repo paths
- `python3 scripts/ci/validate-changelog.py`: validates the supported `../CHANGELOG.md` section layout
- `CI_DOCS_BASE=origin/main bash scripts/ci/require-docs-changelog.sh`: enforces changelog updates and area-specific documentation coverage for the current branch diff
- `bash scripts/ci/postgres-smoke.sh`: starts the backend against PostgreSQL and waits for a healthy API
- `bash scripts/install-git-hooks.sh`: configures `.githooks/pre-push` so docs and changelog checks run before local pushes

## Notes

- Scripts are intentionally tolerant while backend endpoints are still evolving.
- Failed optional API calls are logged and skipped so local iteration stays fast.
- The docs and changelog gate accepts `CI_DOCS_BASE` so contributors can compare a local branch against `origin/main` before pushing.
- The pre-push hook runs `scripts/ci/validate-docs.py`, `scripts/ci/validate-changelog.py`, and `scripts/ci/require-docs-changelog.sh` from the repo root.
- The PostgreSQL smoke helper expects a reachable database at the `backend/src/main/resources/application-postgres.yml` defaults unless `DB_URL`, `DB_USER`, or `DB_PASSWORD` are overridden.
