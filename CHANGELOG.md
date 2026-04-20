# Changelog

All notable changes to LedgerForge Payments should be recorded here.

## Unreleased

### Added
- Transactional outbox delivery for immutable audit events, including scheduled relay processing, capped exponential backoff, dead-letter handling, poison-message short-circuiting, and admin inspection/requeue endpoints.
- GitHub Actions workflows for branch CI and an optional PostgreSQL-backed backend smoke check, covering docs policy gates, backend `mvn verify`, frontend build checks, and failure artifacts.
- Repo-owned CI helper scripts for validating markdown and documented repo paths, enforcing changelog plus nearest-doc updates, and smoke-checking backend startup against PostgreSQL.
- Initial repository bootstrap with baseline README, architecture docs, and local developer tooling.
- Operator console data-source guardrails so live payment and ledger APIs remain the source of truth even when metrics or reconciliation endpoints are missing.
- Live manual-review decision wiring for approve/reject actions against `/api/fraud/reviews/{id}/decision`, with client-side session audit entries carrying correlation and idempotency metadata.
- Derived operator investigation views for payment audit trails, retry corridors, and reconciliation repair playbooks built from live payment, ledger, review, and anomaly data.
- A dedicated operator analytics surface for fraud trends, risk-score bands, settlement coverage, anomaly rollups, and backlog-aging reports derived from live payments and ledger state.
- Ledger replay and verification endpoints for rebuilding account projections from immutable entries and flagging broken journals or payment lifecycle mismatches.
- A documentation governance reference, installable git pre-push hook, and CODEOWNERS coverage for changelog, docs, API examples, and delivery guardrails.

### Changed
- Architecture, observability, and failure-handling docs now describe the implemented transactional outbox contract and the live `/api/admin/outbox` operator workflow.
- Repository docs now describe the GitHub Actions quality gates and the local commands that mirror those checks before push.
- Frontend operator docs now document hybrid API mode, optional bearer-token configuration, and live refresh behavior after manual-review decisions.
- Repository-facing docs now use product and implementation language only, without internal workflow references.
- Payment lifecycle docs now reflect the implemented confirm flow reserving funds, manual-review holds, and cancel guards.
- Ledger invariants documentation now includes the operator recovery flow for `/api/ledger/replay/accounts/{accountId}` and `/api/ledger/verification`.
- Documentation policy enforcement now validates the changelog structure and requires area-specific doc updates for backend, frontend, platform, and Postman changes instead of accepting unrelated doc edits.

### Fixed
- Backend payment integration tests now use transactional rollback so idempotency assertions stay isolated across test methods.
- Live manual-review decisions in the operator console now refresh payment and ledger data after the backend response so approved cases reflect the reserved status and posted reserve journal instead of a guessed local transition.

### Docs
- Added canonical documentation ownership, change-to-doc update expectations, and contributor hook installation guidance for repository governance.
