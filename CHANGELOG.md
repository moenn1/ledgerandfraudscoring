# Changelog

All notable changes to LedgerForge Payments should be recorded here.

## Unreleased

### Added
- GitHub Actions workflows for governance checks, backend test/package validation, frontend build validation, backend demo smoke coverage, and tagged release artifact publishing.
- A dedicated documentation CI workflow and validation script that keep workflow coverage, docs indexes, and the changelog entrypoint aligned.
- A repository governance guide that documents workflow ownership, branch naming policy, and release expectations.
- Initial repository bootstrap with baseline README, architecture docs, and local developer tooling.
- Operator console data-source guardrails so live payment and ledger APIs remain the source of truth even when metrics or reconciliation endpoints are missing.
- Live manual-review decision wiring for approve/reject actions against `/api/fraud/reviews/{id}/decision`, with client-side session audit entries carrying correlation and idempotency metadata.
- Derived operator investigation views for payment audit trails, retry corridors, and reconciliation repair playbooks built from live payment, ledger, review, and anomaly data.
- A dedicated operator analytics surface for fraud trends, risk-score bands, settlement coverage, anomaly rollups, and backlog-aging reports derived from live payments and ledger state.
- Ledger replay and verification endpoints for rebuilding account projections from immutable entries and flagging broken journals or payment lifecycle mismatches.
- Transactional outbox persistence for reserve/capture/refund/cancel payment mutations, plus ledger verification findings for missing or duplicate audit/outbox events per payment mutation.

### Changed
- Repository indexes and script docs now describe the CI/CD workflow suite and governance checker entrypoint.
- GitHub Actions quality gates now cancel superseded branch runs, preserve backend test reports on failure, and publish release bundles with versioned filenames plus SHA-256 manifests.
- Frontend delivery automation now uses a committed portable lockfile with `npm ci`, npm cache reuse, and repo-level npm registry settings that avoid host-specific tarball URLs in version control.
- Demo smoke automation now creates real accounts, executes payment create/confirm/capture transitions, and fails the gate when ledger verification reports issues.
- Release automation now reruns the backend smoke gate before assembling or publishing artifacts.
- Frontend operator docs now document hybrid API mode, optional bearer-token configuration, and live refresh behavior after manual-review decisions.
- Repository-facing docs now use product and implementation language only, without internal workflow references.
- Payment lifecycle docs now reflect the implemented confirm flow reserving funds, manual-review holds, and cancel guards.
- Ledger invariants documentation now includes the operator recovery flow for `/api/ledger/replay/accounts/{accountId}` and `/api/ledger/verification`.

### Fixed
- Governance documentation validation now uses Bash 3 compatible iteration so the same check runs on macOS workstations and GitHub-hosted Linux runners.
- Backend payment integration tests now use transactional rollback so idempotency assertions stay isolated across test methods.
- Frontend analytics typing now preserves `PaymentStatus` keys through grouped status rollups so the production build passes in CI.
- Live manual-review decisions in the operator console now refresh payment and ledger data after the backend response so approved cases reflect the reserved status and posted reserve journal instead of a guessed local transition.
- Ledger verification now flags duplicate reserve/capture/reversal payment journals by payment and action, including the observed references, so repeated lifecycle journals no longer pass verification just because the payment status still matches the distinct journal types.
- Account replay now refuses to project balances when an account has ledger entries in another currency, and ledger verification reports the affected account, journal, and entry ids for operator repair.
- Database migrations now reject invalid journal and ledger row shapes, assign stable per-journal `line_number` values, and block `UPDATE`/`DELETE` mutations on `journal_transactions` and `ledger_entries`, preserving append-only ledger semantics even when writes bypass the service layer.
- Manual-review approvals now emit the same `payment.reserved` audit and outbox events as straight-through reserve flows, preventing reconciliation false positives when operator review posts the reserve journal.
- Unexpected backend failures now return a sanitized `500` response, missing routes preserve `404`, and the H2 console is disabled by default unless explicitly enabled for local debugging.

### Documentation Policy
- Every push must include corresponding documentation updates.
- Every push must include an update to this changelog.
- Documentation updates should cover the nearest relevant docs, such as `README.md`, files under `docs/`, API examples, or operator runbooks.
