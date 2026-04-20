# LedgerForge Payments

LedgerForge Payments is a local-first fintech demo platform for real-time payments, double-entry ledgering, and fraud scoring.

## Planned MVP

- Spring Boot backend for accounts, payments, ledger, fraud, audit, and reporting
- React operator dashboard for payment review and reconciliation
- PostgreSQL-backed immutable ledger model
- Idempotent payment APIs with real-time fraud scoring
- OAuth2 resource-server protection for operator APIs with Viewer, Operator, Reviewer, and Admin role boundaries
- Local developer workflow with Docker Compose

## Local Operator Auth

The backend now expects bearer authentication on operator APIs. For local development, mint a short-lived HS256 token with:

```bash
./scripts/generate-operator-token.py --subject operator.admin@ledgerforge.local --role ADMIN
```

Use that token in `Authorization: Bearer ...` headers for API calls, or export it as `VITE_API_BEARER_TOKEN` before starting the frontend.

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
