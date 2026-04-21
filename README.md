# LedgerForge Payments

LedgerForge Payments is a local-first fintech demo platform for real-time payments, double-entry ledgering, and fraud scoring.

## Planned MVP

- Spring Boot backend for accounts, payments, ledger, fraud, audit, and reporting
- React operator dashboard for payment review and reconciliation
- PostgreSQL-backed immutable ledger model
- Idempotent payment APIs with real-time fraud scoring
- Local developer workflow with Docker Compose

## Repository Layout

- `backend/` Spring Boot application
- `frontend/` React operator dashboard
- `docs/` architecture, API, and operations notes

## Delivery Controls

- `.github/workflows/governance.yml`: changelog and documentation policy checks
- `.github/workflows/docs-ci.yml`: documentation index and workflow coverage validation
- `.github/workflows/backend-ci.yml`: backend test and package validation
- `.github/workflows/frontend-ci.yml`: lockfile-backed frontend install and build validation
- `.github/workflows/smoke-demo.yml`: backend-in-process smoke coverage for account creation, payment lifecycle, and ledger verification through the local demo scripts
- `.github/workflows/release.yml`: tagged or manual artifact assembly with a pre-publish smoke gate, checksum output, and tag-driven GitHub release publishing
- `docs/repository-governance.md`: workflow ownership, branch policy, and release expectations

## Status

The repository is being built out as the LedgerForge platform, with ongoing delivery across ledger, payments, fraud, and operator workflows.
