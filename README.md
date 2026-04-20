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
- `scripts/ci/require-docs-changelog.sh`: enforces changelog and nearest-doc updates against the current branch diff
- `scripts/ci/postgres-smoke.sh`: starts the backend with the PostgreSQL profile and waits for a healthy API

## Repository Layout

- `backend/` Spring Boot application
- `frontend/` React operator dashboard
- `docs/` architecture, API, and operations notes

## Documentation

- `docs/README.md`: documentation index
- `docs/ci-quality-gates.md`: GitHub Actions jobs, docs and changelog policy checks, and manual compose smoke guidance
- `scripts/README.md`: local scripts plus CI helper command reference

## Status

The repository is being built out as the LedgerForge platform, with ongoing delivery across ledger, payments, fraud, and operator workflows.
