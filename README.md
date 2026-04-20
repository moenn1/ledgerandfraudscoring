# LedgerForge Payments

LedgerForge Payments is a local-first fintech demo platform for real-time payments, double-entry ledgering, and fraud scoring.

## Planned MVP

- Spring Boot backend for accounts, payments, ledger, fraud, audit, and reporting
- React operator dashboard for payment review and reconciliation
- PostgreSQL-backed immutable ledger model
- Idempotent payment APIs with real-time fraud scoring
- Local developer workflow with Docker Compose

## Continuous Integration

- `.github/workflows/ci.yml`: runs docs and policy gates, backend `mvn -B verify`, and frontend `npm install` plus `npm run build` on pushes and pull requests
- `.github/workflows/container-smoke.yml`: manual PostgreSQL-backed smoke workflow for validating backend startup in GitHub Actions
- `scripts/ci/validate-docs.py`: validates markdown links and documented repository paths
- `scripts/ci/validate-changelog.py`: enforces the supported `CHANGELOG.md` structure under `## Unreleased`
- `scripts/ci/require-docs-changelog.sh`: enforces changelog updates and area-specific documentation coverage against the current branch diff
- `scripts/ci/postgres-smoke.sh`: starts the backend with the PostgreSQL profile and waits for a healthy API

## Repository Layout

- `backend/` Spring Boot application
- `frontend/` React operator dashboard
- `docs/` architecture, API, and operations notes

## Documentation

- `docs/README.md`: documentation index
- `docs/ci-quality-gates.md`: GitHub Actions jobs, docs and changelog policy checks, and manual compose smoke guidance
- `docs/documentation-governance.md`: canonical doc ownership, update expectations, and changelog rules
- `scripts/README.md`: local scripts plus CI helper command reference

## Repository Governance

- `CHANGELOG.md` must be updated in every push and pull request
- Changes to backend APIs, frontend operator flows, Postman collections, scripts, hooks, workflows, or compose files must update the nearest relevant docs in the same branch
- `bash scripts/install-git-hooks.sh` installs the local `.githooks/pre-push` checks so contributors run the same docs and changelog gates before pushing

## Status

The repository is being built out as the LedgerForge platform, with ongoing delivery across ledger, payments, fraud, and operator workflows.
