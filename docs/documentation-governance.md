# Documentation Governance

LedgerForge treats repository documentation, API examples, and `CHANGELOG.md` as part of the product contract. Behavior changes are not complete until the nearest relevant documentation is updated in the same branch.

## Canonical Sources

- `README.md`: repository entry point, contributor workflow, and high-level platform shape
- `docs/architecture.md`: service boundaries, data flow, and platform topology
- `docs/state-machine.md`: payment lifecycle, transitions, status rules, and operator-visible behavior
- `docs/ledger-invariants.md`: journal balancing, replay, and reconciliation rules
- `docs/fraud-scoring.md`: fraud decisions, manual review, and investigation flow
- `docs/failure-scenarios.md`: retries, idempotency, compensations, and failure recovery
- `docs/observability-security.md`: auditability, monitoring, access control, and security controls
- `docs/runbook-local-demo.md`: local validation, operator walkthroughs, and demo steps
- `docs/ci-quality-gates.md`: branch checks, CI expectations, and local verification commands
- `frontend/README.md`: operator console behavior and local frontend workflow
- `postman/README.md` and `postman/*.json`: request examples and collection-level API contracts
- `scripts/README.md`: local helper scripts, CI helpers, and hook installation workflow

## Update Matrix

- API routes, request or response payloads, idempotency semantics, auth requirements, or settlement and reconciliation endpoints:
  update `postman/*.json` plus the nearest product docs in `docs/architecture.md`, `docs/state-machine.md`, `docs/failure-scenarios.md`, `docs/ledger-invariants.md`, or `docs/fraud-scoring.md`.
- Payment lifecycle, ledger posting behavior, reserve or release flows, retry corridors, or compensations:
  update `docs/state-machine.md`, `docs/ledger-invariants.md`, and `docs/failure-scenarios.md` as applicable.
- Fraud scoring, manual review, operator investigations, audit trails, or anomaly workflows:
  update `docs/fraud-scoring.md`, `docs/observability-security.md`, `docs/runbook-local-demo.md`, and `frontend/README.md` when the operator surface changes.
- Operator dashboard layout, actions, polling, filtering, or investigation UX:
  update `frontend/README.md` and the nearest runbook or workflow docs under `docs/`.
- Local scripts, CI workflows, git hooks, branch checks, or repository policy enforcement:
  update `README.md`, `docs/ci-quality-gates.md`, `scripts/README.md`, and this file when expectations change.

## Changelog Rules

- Keep release notes under `## Unreleased`.
- Use only these subsection headings: `### Added`, `### Changed`, `### Fixed`, `### Removed`, `### Security`, and `### Docs`.
- Add concise bullet entries that explain the user-facing, operational, or maintenance impact of the branch.
- Internal-only changes still need a concise entry that explains what got safer, stricter, or easier to operate.
- Do not store standing repository policy text in the changelog. Permanent policy belongs in documentation, not release notes.

## Ownership

- `README.md`, `docs/README.md`, `CHANGELOG.md`, and `.github/CODEOWNERS`:
  repository maintainers own the baseline contributor contract.
- `docs/architecture.md`, `docs/state-machine.md`, `docs/failure-scenarios.md`, `postman/README.md`, and `postman/*.json`:
  backend and API owners keep the product contract accurate.
- `docs/ledger-invariants.md`:
  ledger owners maintain balancing, replay, and settlement rules.
- `docs/fraud-scoring.md` and `docs/observability-security.md`:
  fraud, audit, and security owners maintain risk and control coverage.
- `frontend/README.md` and `docs/runbook-local-demo.md`:
  operator experience owners maintain investigation and dashboard guidance.
- `.github/workflows/*`, `.githooks/*`, `scripts/README.md`, and `docs/ci-quality-gates.md`:
  platform and developer-experience owners maintain delivery guardrails.

## Enforcement

- `python3 scripts/ci/validate-docs.py` validates markdown links and documented repository paths.
- `python3 scripts/ci/validate-changelog.py` enforces the supported `## Unreleased` structure.
- `bash scripts/ci/require-docs-changelog.sh` enforces changelog updates and area-specific documentation coverage for the branch diff.
- `bash scripts/install-git-hooks.sh` installs `.githooks/pre-push` so the same checks run before local pushes.
