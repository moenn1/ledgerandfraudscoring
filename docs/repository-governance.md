# Repository Governance

LedgerForge Payments treats repository governance as part of production safety. Review flow, ownership routing, and merge policy must protect ledger integrity, idempotent payment behavior, audit history, and operator-facing controls.

## Branch and Merge Policy

- `main` accepts changes through pull requests only.
- The protected-branch policy requires one approving review, code-owner review, stale-review dismissal, last-push approval, resolved review conversations, and linear history.
- Force pushes and branch deletion are disabled on `main`, and the same policy applies to administrators.
- Repository merges are configured for squash merge only, with branch auto-delete after merge.

## Ownership Rules

- `CODEOWNERS` covers all product and governance surfaces: `.github/`, `backend/`, `frontend/`, `postman/`, `scripts/`, `docs/`, `README.md`, and `CHANGELOG.md`.
- Until GitHub teams exist for each domain, the repository maintainer `@moenn1` is the required code owner for protected paths.
- Pull requests must call out the domains they touch so payment, ledger, fraud, reconciliation, and operator-console changes stay reviewable.

## Required Documentation Hygiene

- Every repository change updates `CHANGELOG.md`.
- Every repository change updates the nearest relevant documentation in `README.md`, `docs/`, or `scripts/README.md`.
- Changes that affect payment lifecycle behavior, ledger postings, fraud controls, operator workflows, or reconciliation paths must explicitly describe the control impact in the pull request template.

## Templates and Automation

- Issue forms capture affected domain, severity, reproduction details, and control impact for both defects and change requests.
- The pull request template requires contributors to confirm ledger balancing, idempotency, auditability, validation steps, and documentation updates.
- `.github/workflows/governance.yml` runs `scripts/check-governance-docs.sh` on pushes and pull requests so changelog and documentation updates remain mandatory.

## Admin Maintenance

- Reapply GitHub branch protection with `./scripts/apply-branch-protection.sh`.
- Set `REQUIRED_STATUS_CHECKS=governance` before running the script if the governance workflow should be enforced as a required status check on `main`.
- The script uses `GITHUB_TOKEN` when present and otherwise falls back to the configured git credential for `github.com`.
