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

## Status

The repository is being built out as the LedgerForge platform, with ongoing delivery across ledger, payments, fraud, and operator workflows.

## Local Operator Auth

Operator-only backend routes now require a bearer token with one of the documented roles in `docs/observability-security.md`.

For local development, generate an HMAC-signed token with:

```bash
python3 scripts/generate-operator-token.py \
  --subject operator.ui@ledgerforge.local \
  --role VIEWER
```

Override `LEDGERFORGE_AUTH_ISSUER`, `LEDGERFORGE_AUTH_AUDIENCE`, or `LEDGERFORGE_AUTH_HMAC_SECRET` if your local backend config differs from the defaults.
