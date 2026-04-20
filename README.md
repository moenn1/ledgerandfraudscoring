# LedgerForge Payments

LedgerForge Payments is a local-first fintech demo platform for real-time payments, double-entry ledgering, and fraud scoring.

## Platform Scope

- Spring Boot backend for accounts, payments, ledger, fraud, audit, and reporting
- React operator dashboard for payment review, analytics, and reconciliation
- PostgreSQL-backed immutable ledger model
- Idempotent payment APIs with real-time fraud scoring
- OAuth2 resource-server protection with Viewer, Operator, Reviewer, and Admin role boundaries
- Scheduled settlement batches and payout execution that move merchant balances into payout-clearing accounts through the ledger
- Signed outbound webhooks with auditable delivery records, bounded retries, and idempotent callback acknowledgements
- Local developer workflow with Docker Compose

## Quickstart

### Fast path: backend on H2

1. Start the backend:
   - `cd backend`
   - `mvn spring-boot:run`
2. Start the frontend in another shell:
   - `export VITE_API_BEARER_TOKEN="$(./scripts/generate-operator-token.py --subject operator.admin@ledgerforge.local --role ADMIN)"`
   - `cd frontend`
   - `npm install`
   - `npm run dev`
3. Seed demo data and run the local smoke flow:
   - `./scripts/demo-run.sh`

This mode requires no Docker and is the quickest path for UI and API iteration.

### Durable path: backend on PostgreSQL

1. Start local infrastructure:
   - `./scripts/dev-up.sh`
   - optional extended stack: `./scripts/dev-up.sh --extended`
2. Start the backend with the PostgreSQL profile:
   - `cd backend`
   - `SPRING_PROFILES_ACTIVE=postgres mvn spring-boot:run`
   - to exercise broker-driven event delivery: `LEDGERFORGE_KAFKA_ENABLED=true SPRING_PROFILES_ACTIVE=postgres mvn spring-boot:run`
3. Start the frontend:
   - `export VITE_API_BEARER_TOKEN="$(./scripts/generate-operator-token.py --subject operator.admin@ledgerforge.local --role ADMIN)"`
   - `cd frontend`
   - `npm install`
   - `npm run dev`
4. Run seeded demo requests:
   - `./scripts/demo-run.sh`

### Full containerized path

1. Start the platform services plus application containers:
   - `./scripts/dev-up.sh --full-stack`
2. Open the operator console:
   - `http://127.0.0.1:4173`
3. Optional OIDC-backed variant:
   - `./scripts/dev-up.sh --full-stack --auth`
4. Stop the stack when finished:
   - `./scripts/dev-down.sh`

Default ports:

- backend API: `http://localhost:8080`
- frontend UI (Vite): `http://127.0.0.1:5173`
- frontend UI (containerized): `http://127.0.0.1:4173`
- PostgreSQL: `localhost:5432`
- Redis (extended profile): `localhost:6379`
- Kafka (extended profile): `localhost:9092`
- Zipkin (extended profile): `http://127.0.0.1:9411`
- Prometheus (extended profile): `http://127.0.0.1:9090`
- Keycloak (optional auth profile): `http://127.0.0.1:8081`

## Local Workflow Assets

- `docker-compose.yml`: local PostgreSQL plus platform, full-stack, and optional auth profiles
- `backend/Dockerfile` / `frontend/Dockerfile`: container builds for the API and operator console
- `scripts/dev-up.sh` / `scripts/dev-down.sh`: compose lifecycle wrappers for dependency-only and full-stack flows
- `scripts/render-compose-env.sh`: generates the local compose env file, including a demo operator token
- `scripts/fetch-keycloak-token.sh`: mints a Keycloak access token for the optional OIDC profile
- `scripts/generate-operator-token.py`: mints local HS256 operator tokens for the secured API
- `scripts/seed-demo.sh`: creates one captured payment and one manual-review case
- `scripts/smoke-test.sh`: validates health, create idempotency, confirm, capture, and ledger inspection
- `scripts/demo-run.sh`: waits for readiness, seeds demo data, then runs the smoke checks

## Repository Layout

- `backend/` Spring Boot application
- `frontend/` React operator dashboard
- `docs/` architecture, API, and operations notes

## Documentation

- `docs/api-contracts.md`: contract artifacts, versioning rules, and compatibility expectations
- `docs/runbook-local-demo.md`: end-to-end demo walkthrough
- `docs/deployment-topology.md`: local compose profiles and the target deployment shape
- `docs/local-api-requests.md`: concrete `curl` examples for local runs
- `docs/event-delivery.md`: outbox, Kafka relay, and consumer workflow details
- `docs/observability-security.md`: security boundaries, audit expectations, and webhook signature notes
- `scripts/README.md`: script configuration and command reference
- `postman/README.md`: collection/environment import flow

## Status

The repository is being built out as the full LedgerForge platform, with ongoing delivery across ledger, payments, fraud, operator workflows, observability, and local development tooling.
