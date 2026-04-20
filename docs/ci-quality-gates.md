# CI and Quality Gates

LedgerForge uses GitHub Actions to keep the platform buildable, testable, and documented on every branch.

## Workflows

- `.github/workflows/ci.yml`: runs on every push and pull request
- `.github/workflows/container-smoke.yml`: manual workflow for a PostgreSQL-backed backend smoke check

## CI coverage

### Docs and policy gates

- `python3 scripts/ci/validate-docs.py` validates markdown links and the repository paths documented in the main README indexes
- `bash scripts/ci/require-docs-changelog.sh` enforces two repository policies against the branch diff:
  - `CHANGELOG.md` must be updated in every push or pull request
  - source, workflow, script, compose, or Postman changes must include a nearest relevant documentation update

### Backend build and test

- Uses Java 17 with Maven dependency caching
- Runs `mvn -B verify` in `backend/`
- Uploads Surefire reports on failure

### Frontend build

- Uses Node.js 20 with npm download caching
- Runs `npm install --no-fund --no-audit` and then `npm run build` in `frontend/`
- Uploads npm debug logs on failure

## Optional container smoke

The manual `Container Smoke` workflow provisions a PostgreSQL service container and runs `bash scripts/ci/postgres-smoke.sh`, which:

- starts the backend with `SPRING_PROFILES_ACTIVE=postgres`
- waits for the API health endpoint to report ready
- fails with a backend log artifact if startup regresses

## Local verification

Run the same repo-owned gates locally before pushing:

```bash
python3 scripts/ci/validate-docs.py
CI_DOCS_BASE=origin/main bash scripts/ci/require-docs-changelog.sh
bash scripts/ci/postgres-smoke.sh
```

The PostgreSQL smoke helper expects a reachable PostgreSQL instance at the `application-postgres.yml` defaults unless `DB_URL`, `DB_USER`, or `DB_PASSWORD` are overridden. The docs/changelog example uses `origin/main` so contributors can validate the exact diff they are about to push.
