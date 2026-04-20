# Changelog

All notable changes to LedgerForge Payments should be recorded here.

## Unreleased

### Added
- GitHub Actions workflows for branch CI and an optional PostgreSQL-backed backend smoke check, covering docs policy gates, backend `mvn verify`, frontend build checks, and failure artifacts.
- Repo-owned CI helper scripts for validating markdown and documented repo paths, enforcing changelog plus nearest-doc updates, and smoke-checking backend startup against PostgreSQL.
- Initial repository bootstrap with baseline README, architecture docs, and local developer tooling.
- Operator console data-source guardrails so live payment and ledger APIs remain the source of truth even when metrics or reconciliation endpoints are missing.
- Live manual-review decision wiring for approve/reject actions against `/api/fraud/reviews/{id}/decision`, with client-side session audit entries carrying correlation and idempotency metadata.
- Derived operator investigation views for payment audit trails, retry corridors, and reconciliation repair playbooks built from live payment, ledger, review, and anomaly data.
- A dedicated operator analytics surface for fraud trends, risk-score bands, settlement coverage, anomaly rollups, and backlog-aging reports derived from live payments and ledger state.
- Ledger replay and verification endpoints for rebuilding account projections from immutable entries and flagging broken journals or payment lifecycle mismatches.

### Changed
- Repository docs now describe the GitHub Actions quality gates and the local commands that mirror those checks before push.
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
