# API Contracts And Versioning

This repository currently publishes its API contract through two implementation-aligned artifacts:

- `postman/LedgerForge.postman_collection.json` for importable request definitions, variable wiring, and happy-path operator flows
- `docs/local-api-requests.md` for concrete `curl` examples that cover the broader local surface, including reviewer, admin, settlement, payout, webhook, and outbox flows

## Contract Baseline

- The current contract baseline lives under the unversioned `/api` namespace.
- All operator-facing endpoints expect bearer-token authentication except webhook callbacks, which use signed callback headers instead.
- Role requirements are part of the contract:
  - `VIEWER`: read-only inspection endpoints
  - `OPERATOR`: payment creation and money-movement actions
  - `REVIEWER`: manual-review decisions
  - `ADMIN`: account administration, ledger replay and verification, settlement, payout, and webhook dispatch
- Mutation endpoints that create or change payment state require idempotency headers where documented in the request examples and Postman collection.

## Current Contract Artifacts

### Postman Collection

The Postman collection is the fastest way to exercise the current secured API shape. It includes:

- account creation with UUID-backed ids captured into environment variables
- payment create, confirm, capture, refund, reverse, chargeback, cancel, risk, ledger, and adjustment inspection
- review-queue and review-decision requests
- ledger replay and verification requests
- settlement, payout, webhook, and outbox inspection endpoints

Populate the environment with local bearer tokens before running operator, reviewer, or admin requests.

### Local Curl Examples

`docs/local-api-requests.md` is the more complete source for copy/pasteable local verification. It covers:

- account lifecycle and account-status controls
- straight-through payment approval and ledger inspection
- manual-review approval flow
- reversal, settlement, payout, webhook, and outbox examples
- example headers for correlation ids, idempotency keys, and webhook callback signatures

## Backward-Compatible Changes

The following changes are expected to remain backward compatible when they preserve existing semantics:

- adding new endpoints
- adding optional request fields
- adding response fields that existing clients can ignore safely
- adding new query parameters with safe defaults
- broadening filtering or inspection capabilities without changing existing defaults
- adding new event types or statuses while preserving prior values and meanings

When making a backward-compatible change:

1. Update the nearest contract artifact in the same branch.
2. Add or refresh request examples in Postman and/or `docs/local-api-requests.md`.
3. Update `CHANGELOG.md` and the nearest relevant docs.

## Breaking Changes

Treat the following as breaking changes:

- renaming or removing routes
- renaming or removing response fields
- making an optional request field required
- changing auth-role requirements for an existing endpoint
- changing idempotency behavior or replay semantics
- changing payment-state transition rules or ledger side effects for an existing action
- changing the meaning or shape of existing enum values

Breaking changes must not silently replace the current `/api` contract. Introduce a new versioned contract surface such as `/api/v2`, publish matching examples for both versions during migration, and document the migration path before removing the older version.

## Change-Management Checklist

Any API behavior change should update the relevant subset of these files in the same branch:

- `postman/LedgerForge.postman_collection.json`
- `postman/LedgerForge-local.postman_environment.json` when new reusable variables are needed
- `docs/local-api-requests.md`
- `docs/state-machine.md`, `docs/ledger-invariants.md`, or other domain docs when semantics changed
- `README.md` or `postman/README.md` when local usage changed
- `CHANGELOG.md`

If a route exists in code but is not yet reflected in either the Postman collection or `docs/local-api-requests.md`, the contract publication is incomplete.
