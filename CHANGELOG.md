# Changelog

All notable changes to LedgerForge Payments should be recorded here.

## Unreleased

### Added
- Initial repository bootstrap with baseline README, architecture docs, and local developer tooling.
- Operator console data-source guardrails so live payment and ledger APIs remain the source of truth even when metrics or reconciliation endpoints are missing.
- Live manual-review decision wiring for approve/reject actions against `/api/fraud/reviews/{id}/decision`, with client-side session audit entries carrying correlation and idempotency metadata.
- Derived operator investigation views for payment audit trails, retry corridors, and reconciliation repair playbooks built from live payment, ledger, review, and anomaly data.
- A dedicated operator analytics surface for fraud trends, risk-score bands, settlement coverage, anomaly rollups, and backlog-aging reports derived from live payments and ledger state.
- Ledger replay and verification endpoints for rebuilding account projections from immutable entries and flagging broken journals or payment lifecycle mismatches.

### Changed
- Backend request hardening now disables the H2 console by default, normalizes unsafe correlation IDs before they reach audit storage, and returns generic 5xx payloads instead of raw exception text.
- Frontend live fraud-review submissions now send an explicit reviewer actor ID, and backend validation constrains those actor IDs to a bounded safe character set before they reach the audit trail.
- Frontend operator docs now document hybrid API mode, optional bearer-token configuration, and live refresh behavior after manual-review decisions.
- Repository-facing docs now use product and implementation language only, without internal workflow references.
- Payment lifecycle docs now reflect the implemented confirm flow reserving funds, manual-review holds, and cancel guards.
- Ledger invariants documentation now includes the operator recovery flow for `/api/ledger/replay/accounts/{accountId}` and `/api/ledger/verification`.

### Fixed
- Backend payment integration tests now use transactional rollback so idempotency assertions stay isolated across test methods.
- Live manual-review decisions in the operator console now refresh payment and ledger data after the backend response so approved cases reflect the reserved status and posted reserve journal instead of a guessed local transition.

### Documentation Policy
- Every push must include corresponding documentation updates.
- Every push must include an update to this changelog.
- Documentation updates should cover the nearest relevant docs, such as `README.md`, files under `docs/`, API examples, or operator runbooks.
