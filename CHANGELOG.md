# Changelog

All notable changes to LedgerForge Payments should be recorded here.

## Unreleased

### Added
- Initial repository bootstrap with baseline README, architecture docs, local developer tooling, and agent operating rules.
- Operator console data-source guardrails so live payment and ledger APIs remain the source of truth even when metrics or reconciliation endpoints are missing.
- Live manual-review decision wiring for approve/reject actions against `/api/fraud/reviews/{id}/decision`, with client-side session audit entries carrying correlation and idempotency metadata.
- Ledger replay and verification endpoints for rebuilding account projections from immutable entries and flagging broken journals or payment lifecycle mismatches.

### Changed
- Frontend operator docs and local demo runbook now document hybrid API mode, read-only degradation for missing review APIs, and the correct local Vite port (`5173`).
- Payment lifecycle docs now reflect the implemented confirm flow reserving funds, manual-review holds, and cancel guards.
- Ledger invariants documentation now includes the operator recovery flow for `/api/ledger/replay/accounts/{accountId}` and `/api/ledger/verification`.

### Fixed
- Backend payment integration tests now use transactional rollback so idempotency assertions stay isolated across test methods.

### Documentation Policy
- Every push must include corresponding documentation updates.
- Every push must include an update to this changelog.
- Documentation updates should cover the nearest relevant docs, such as `README.md`, files under `docs/`, API examples, or operator runbooks.
