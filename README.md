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

## Deployment and Release Planning

- `docs/deployment-topology.md`: target staging/production topology, runtime config boundaries, migration safety, and promotion rules
- `docs/repository-governance.md`: repository contribution policy, branch protection, ownership routing, and documentation requirements
- `docs/runbook-local-demo.md`: local topology and demo workflow
- `docs/observability-security.md`: telemetry, auditability, and operator/security controls that releases must preserve

## Status

The repository is being built out as the LedgerForge platform, with ongoing delivery across ledger, payments, fraud, and operator workflows.
