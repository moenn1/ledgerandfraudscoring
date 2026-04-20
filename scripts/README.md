# Developer Scripts

These scripts provide the local dependency lifecycle, backend readiness checks, seeded demo data, and smoke validation for LedgerForge Payments.

## Prerequisites

- `bash`
- `curl`
- `python3`
- `docker` with the Compose plugin (`dev-up.sh` / `dev-down.sh` only)

## Configuration

Optional environment variables:

- `API_BASE_URL` (default: `http://localhost:8080`)
- `COMPOSE_ENV_FILE` to override the generated compose env location (default: `tmp/docker-compose.env`)
- `DEFAULT_CURRENCY` (default: `USD`)
- `IDEMPOTENCY_PREFIX` (default: `ledgerforge-local`)
- `TIMEOUT_SECONDS` for readiness wait (default: `60`)
- `DEMO_NAMESPACE` to group objects created by `seed-demo.sh`
- `SMOKE_NAMESPACE` to group objects created by `smoke-test.sh`
- `LEDGERFORGE_AUTH_HMAC_SECRET` to override the local JWT signing secret
- `LEDGERFORGE_DATA_PROTECTION_KEY` to override the local field-encryption key used for persisted webhook secrets
- `LEDGERFORGE_KAFKA_ENABLED=true` to route outbox relay publishes and notification fan-out through Kafka
- `LEDGERFORGE_KAFKA_BOOTSTRAP_SERVERS` to override the Kafka broker address (default: `localhost:9092`)
- `LEDGERFORGE_KEYCLOAK_BASE_URL`, `LEDGERFORGE_KEYCLOAK_REALM`, `LEDGERFORGE_KEYCLOAK_CLIENT_ID`, `LEDGERFORGE_KEYCLOAK_USERNAME`, and `LEDGERFORGE_KEYCLOAK_PASSWORD` to override the optional OIDC bootstrap defaults
- `OPERATOR_SUBJECT` for the generated local operator token subject
- `OPERATOR_ROLE` for the generated local operator token role (defaults to `ADMIN`)
- `OPERATOR_TOKEN` to supply a pre-generated bearer token instead of minting one in-script

## Commands

- `./scripts/dev-up.sh`: starts PostgreSQL for local backend work
- `./scripts/dev-up.sh --extended`: starts the platform profile (`postgres`, `redis`, `kafka`, `zipkin`, `prometheus`)
- `./scripts/dev-up.sh --full-stack`: starts the platform profile plus backend/frontend containers
- `./scripts/dev-up.sh --full-stack --auth`: also starts Keycloak, seeds a local realm, fetches an operator token, and injects it into the frontend runtime config
- `./scripts/dev-down.sh`: stops local dependencies
- `./scripts/dev-down.sh --volumes`: stops dependencies and removes compose volumes
- `./scripts/render-compose-env.sh`: writes the compose env file used by the full-stack flows
- `./scripts/fetch-keycloak-token.sh`: exchanges the seeded Keycloak demo user credentials for an operator bearer token
- `./scripts/generate-operator-token.py --subject operator.admin@ledgerforge.local --role ADMIN`: mints a local HS256 bearer token for secured API calls
- `./scripts/wait-for-backend.sh`: waits for `/actuator/health` (fallback `/api/health`)
- `./scripts/seed-demo.sh`: creates fresh demo accounts, one captured payment, and one manual-review case
- `./scripts/smoke-test.sh`: health + idempotency + confirm/capture + ledger checks against a fresh namespace
- `./scripts/demo-run.sh`: runs all of the above in order

## Notes

- `seed-demo.sh` prints the generated payment and review-case ids so they can be used in the UI or in manual `curl` flows.
- `smoke-test.sh` is intended to fail loudly when the current API contract regresses.
- The API helpers automatically attach an `Authorization: Bearer ...` header using either `OPERATOR_TOKEN` or a token minted by `generate-operator-token.py`.
- The backend can run against H2 with no Docker, against PostgreSQL with `SPRING_PROFILES_ACTIVE=postgres`, or fully containerized through `./scripts/dev-up.sh --full-stack`.
- The generated compose env keeps containerized API/auth settings out of the committed repo while still making the frontend image reusable across demo modes.
- Container builds honor `HTTP_PROXY`, `HTTPS_PROXY`, `NO_PROXY`, and optional `NPM_CONFIG_REGISTRY` environment variables when those are required to reach base images or package mirrors.
- To exercise async broker workflows locally outside the containerized full stack, start `./scripts/dev-up.sh --extended` and run the backend with `LEDGERFORGE_KAFKA_ENABLED=true`.
- Shared environments should override both `LEDGERFORGE_AUTH_HMAC_SECRET` and `LEDGERFORGE_DATA_PROTECTION_KEY`; the defaults are for local demos only.
