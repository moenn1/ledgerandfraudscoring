# Changelog

All notable changes to LedgerForge Payments should be recorded here.

## Unreleased

### Added
- Initial repository bootstrap with baseline README, architecture docs, and local developer tooling.
- API contract publication guidance in `docs/api-contracts.md`, covering the Postman collection, local `curl` examples, versioning rules, and the compatibility checklist for API changes.
- Signed webhook endpoint registration, auditable delivery tracking, bounded retry dispatch, and idempotent callback acknowledgement APIs under `/api/webhooks`.
- OAuth2 resource-server protection for operator APIs, including Viewer/Operator/Reviewer/Admin role boundaries, JSON `401/403` responses, and audit events for denied access.
- Operator console data-source guardrails so live payment and ledger APIs remain the source of truth even when metrics or reconciliation endpoints are missing.
- Live manual-review decision wiring for approve/reject actions against `/api/fraud/reviews/{id}/decision`, with client-side session audit entries carrying correlation and idempotency metadata.
- Derived operator investigation views for payment audit trails, retry corridors, and reconciliation repair playbooks built from live payment, ledger, review, and anomaly data.
- A dedicated operator analytics surface for fraud trends, risk-score bands, settlement coverage, anomaly rollups, and backlog-aging reports derived from live payments and ledger state.
- Ledger replay and verification endpoints for rebuilding account projections from immutable entries and flagging broken journals or payment lifecycle mismatches.
- Repo-level `docker-compose.yml` with a lightweight default Postgres service and optional Redis/Kafka local profile.
- Concrete local API request examples and shell wrappers for bringing the local dependency stack up and down.
- Immutable payment-adjustment history plus dedicated `reverse`, `chargeback`, and adjustment-list payment APIs for post-capture money movement.
- Account-level currency permissions so a single account can safely participate in more than one ledger currency without allowing mixed-currency journals.
- Account status controls via `POST /api/accounts/{id}/status`, with audit events for freeze/unfreeze actions.
- Settlement batch and payout APIs that schedule captured payments onto the next cutoff, aggregate them into immutable batches, and execute ledger-backed payouts into a clearing account.
- A transactional outbox with lease-based relay delivery, durable payment/ledger/settlement event envelopes, and a viewer-scoped `/api/events/outbox` inspection endpoint.
- Kafka-backed broker publishing for outbox events, including consumer deduplication receipts, dead-letter auditing, and an async notification consumer workflow for `payment.*` events.
- Event delivery documentation covering the outbox contract, replay-safe semantics, and relay configuration knobs.
- A local `scripts/generate-operator-token.py` helper so demo, smoke, and frontend flows can mint bearer tokens against the secured backend without an external identity provider.
- Field-level data-protection controls for webhook secrets, operator/payment responses, and outbox inspection surfaces, including encrypted webhook signing-secret storage and masked replay identifiers.

### Changed
- The Postman collection and local environment now reflect the secured UUID-based API surface, including role-scoped bearer-token variables and current payment, fraud, ledger, settlement, webhook, and outbox routes.
- Payment and settlement lifecycle events now enqueue webhook delivery records alongside the existing audit and outbox history, while keeping the ledger as the source of truth.
- Audit, outbox, and webhook payload serialization now sanitize sensitive keys before persistence so raw secrets and idempotency values do not surface in operator-facing traces.
- Manual-review decisions now derive the audit actor from the authenticated principal instead of trusting an operator-supplied request body field.
- Frontend operator docs and local demo runbook now document hybrid API mode, read-only degradation for missing review APIs, and the correct local Vite port (`5173`).
- Operator review queues now surface retry and reconciliation counts, while payment detail pages expose richer audit and investigation context without introducing non-ledger sources of truth.
- The operator console navigation now includes a reporting workspace that summarizes approval/reject flow, operational latency, and ledger-backed settlement health without requiring new backend endpoints.
- Mock fallback messaging now explicitly distinguishes disabled live review APIs from still-available local demo review actions.
- Payment lifecycle docs now reflect the implemented confirm flow reserving funds, manual-review holds, and cancel guards.
- Ledger invariants documentation now includes the operator recovery flow for `/api/ledger/replay/accounts/{accountId}` and `/api/ledger/verification`.
- Root local-development docs, demo runbook, and developer scripts now match the backend routes that are actually implemented today.
- Ledger lifecycle verification and the operator console timeline now recognize `CHARGEBACK` and post-capture `REVERSED` payment states.
- Account balance and ledger replay flows are now currency-aware; multi-currency accounts must pass an explicit `currency` query parameter for projection and replay requests.
- Frozen payer/payee accounts now block create, confirm, capture, and manual-review approval flows while still allowing operator recovery journals for reversal, refund, and chargeback actions.
- Payment responses now expose settlement cutoff and batch metadata, while the operator docs include the settlement and payout runner workflow.
- Webhook delivery fan-out now shifts behind the broker when Kafka is enabled, while the direct in-process path remains available for fast local H2 runs.
- Repository-facing docs now use product and implementation language only, without internal workflow references.

### Fixed
- Local demo seeding and smoke validation scripts now create real UUID-backed accounts, verify payment idempotency, and exercise reserve/capture ledger flows against the live API.
- Backend payment integration tests now use transactional rollback so idempotency assertions stay isolated across test methods.
- Notification webhook hashing and payload-column mappings now align with the current schema so Hibernate validation and backend test startup succeed.
- Live manual-review decisions in the operator console now refresh payment and ledger data after the backend response so approved cases reflect the reserved status and posted reserve journal instead of a guessed local transition.

### Documentation Policy
- Every push must include corresponding documentation updates.
- Every push must include an update to this changelog.
- Documentation updates should cover the nearest relevant docs, such as `README.md`, files under `docs/`, API examples, or operator runbooks.
