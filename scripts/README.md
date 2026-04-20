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
- `GITHUB_TOKEN` for repository-governance automation when git credentials are not available
- `IDEMPOTENCY_PREFIX` (default: `ledgerforge-local`)
- `REQUIRED_STATUS_CHECKS` as a comma-separated list of GitHub check names to enforce on `main`
- `TIMEOUT_SECONDS` for readiness wait (default: `60`)

## Commands

- `./scripts/wait-for-backend.sh`: waits for `/actuator/health` (fallback `/api/health`)
- `./scripts/seed-demo.sh`: tries to create demo accounts and one payment
- `./scripts/smoke-test.sh`: health + basic payment API checks
- `./scripts/demo-run.sh`: runs all of the above in order
- `./scripts/check-governance-docs.sh`: verifies a change updates both `CHANGELOG.md` and the nearest relevant documentation
- `./scripts/apply-branch-protection.sh`: applies the repository merge policy and protected-branch settings to GitHub

## Notes

- Scripts are intentionally tolerant while backend endpoints are still evolving.
- Failed optional API calls are logged and skipped so local iteration stays fast.
- The branch-protection script falls back to the stored git credential for `github.com` when `GITHUB_TOKEN` is not set.
