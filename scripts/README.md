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
- `LEDGERFORGE_AUTH_HMAC_SECRET` to override the local JWT signing secret
- `OPERATOR_SUBJECT` for the generated local operator token subject
- `OPERATOR_ROLE` for the generated local operator token role (defaults to `ADMIN`)
- `OPERATOR_TOKEN` to supply a pre-generated bearer token instead of minting one in-script
- `REQUIRED_STATUS_CHECKS` as a comma-separated list of GitHub check names to enforce on `main`
- `TIMEOUT_SECONDS` for readiness wait (default: `60`)

## Commands

- `./scripts/wait-for-backend.sh`: waits for `/actuator/health` (fallback `/api/health`)
- `./scripts/generate-operator-token.py --subject operator.admin@ledgerforge.local --role ADMIN`: mints a local HS256 bearer token for secured API calls
- `./scripts/seed-demo.sh`: creates demo accounts plus one captured payment using authenticated operator calls
- `./scripts/smoke-test.sh`: health plus authenticated payment API checks
- `./scripts/demo-run.sh`: runs all of the above in order
- `./scripts/check-governance-docs.sh`: verifies a change updates both `CHANGELOG.md` and the nearest relevant documentation
- `./scripts/apply-branch-protection.sh`: applies the repository merge policy and protected-branch settings to GitHub

## Notes

- Scripts are intentionally tolerant while backend endpoints are still evolving.
- API helpers attach `Authorization: Bearer ...` automatically using either `OPERATOR_TOKEN` or a token minted by `generate-operator-token.py`.
- The branch-protection script falls back to the stored git credential for `github.com` when `GITHUB_TOKEN` is not set.
