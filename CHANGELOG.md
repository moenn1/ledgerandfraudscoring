# Changelog

All notable changes to LedgerForge Payments should be recorded here.

## Unreleased

### Added
- Initial repository bootstrap with baseline README, architecture docs, and local developer tooling.
- A deployment topology and release-promotion operating model covering local, staging, and production environment boundaries; runtime config ownership; migration safety; and rollback expectations.
- OAuth2 resource-server protection for operator APIs, including Viewer/Operator/Reviewer/Admin role boundaries, JSON `401`/`403` responses, and audit events for denied access.
- Operator console data-source guardrails so live payment and ledger APIs remain the source of truth even when metrics or reconciliation endpoints are missing.
- Live manual-review decision wiring for approve/reject actions against `/api/fraud/reviews/{id}/decision`, with client-side session audit entries carrying correlation and idempotency metadata.
- Derived operator investigation views for payment audit trails, retry corridors, and reconciliation repair playbooks built from live payment, ledger, review, and anomaly data.
- A dedicated operator analytics surface for fraud trends, risk-score bands, settlement coverage, anomaly rollups, and backlog-aging reports derived from live payments and ledger state.
- Ledger replay and verification endpoints for rebuilding account projections from immutable entries and flagging broken journals or payment lifecycle mismatches.
- Repository governance assets for issue intake, pull-request controls, code ownership, and reusable branch-protection automation.
- A local `scripts/generate-operator-token.py` helper so frontend, script, and manual API flows can mint short-lived operator bearer tokens without an external identity provider.

### Changed
- Frontend operator docs now document hybrid API mode, local bearer-token generation, and live refresh behavior after manual-review decisions.
- Manual-review decisions now derive the audit actor from the authenticated principal instead of trusting a caller-supplied request body field.
- Local demo and smoke scripts now execute through authenticated operator calls so the secured backend remains usable in local workflows.
- Repository-facing docs now use product and implementation language only, without internal workflow references.
- Payment lifecycle docs now reflect the implemented confirm flow reserving funds, manual-review holds, and cancel guards.
- Ledger invariants documentation now includes the operator recovery flow for `/api/ledger/replay/accounts/{accountId}` and `/api/ledger/verification`.
- Architecture and repository indexes now surface deployment and release guidance alongside the existing local runbook and security notes.
- Repository merge policy now standardizes on protected pull requests, code-owner review, linear history, and squash merges on `main`.

### Fixed
- Backend payment integration tests now use transactional rollback so idempotency assertions stay isolated across test methods.
- Live manual-review decisions in the operator console now refresh payment and ledger data after the backend response so approved cases reflect the reserved status and posted reserve journal instead of a guessed local transition.
- Access-control audit migration now applies cleanly on the in-memory test database as well as the shared local runtime databases.

### Documentation Policy
- Every push must include corresponding documentation updates.
- Every push must include an update to this changelog.
- Documentation updates should cover the nearest relevant docs, such as `README.md`, files under `docs/`, API examples, or operator runbooks.
