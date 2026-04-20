# Repository Governance

LedgerForge Payments treats CI/CD and release automation as part of production safety. Workflow coverage must protect ledger balancing, idempotent payment behavior, audit history, and operator-facing controls before changes reach `main`.

## Branch and Merge Policy

- Delivery branches use the `feature/<short-scope>` or `fix/<short-scope>` form.
- `main` is the integration branch and should receive changes through reviewed pull requests.
- CI workflows are split by concern so backend, frontend, smoke validation, governance checks, and release automation can fail independently and stay reviewable.

## GitHub Actions Coverage

- `.github/workflows/governance.yml` enforces changelog and nearest-doc updates on pushes and pull requests.
- `.github/workflows/docs-ci.yml` verifies that repository indexes and workflow documentation stay in sync with the actual workflow inventory.
- `.github/workflows/backend-ci.yml` runs backend tests and packages the Spring Boot artifact with Java 17.
- `.github/workflows/frontend-ci.yml` installs frontend dependencies and builds the operator console with Node.js 20.
- `.github/workflows/smoke-demo.yml` packages the backend, starts it with the in-memory runtime profile, and runs the local demo scripts as a smoke gate.
- `.github/workflows/release.yml` builds backend and frontend release artifacts on tags that match `v*`, supports manual dispatch artifact builds, uploads workflow artifacts with SHA-256 manifests, and publishes a GitHub release for tagged versions.
- Branch workflows use per-workflow concurrency groups so superseded feature or fix branch runs are cancelled rather than competing for runner time.
- Frontend workflows use `npm install` rather than `npm ci` until a repository-portable lockfile is committed, because local-generated lockfiles may point at adapter-specific registries that are not valid on GitHub-hosted runners.

## Release Expectations

- Tagged releases should come from already-reviewed `main` history.
- Backend releases publish the packaged Spring Boot jar.
- Frontend releases publish the built operator console bundle as a compressed artifact.
- Release bundles include a `SHA256SUMS.txt` manifest so operators can validate downloaded artifacts before promotion.
- Manual dispatch runs may set a version label for pre-release artifact bundles without publishing a GitHub release.
- Release automation is intentionally separate from branch CI so routine pushes stay fast while tagged releases remain reproducible.

## Documentation Hygiene

- Every repository change updates `CHANGELOG.md`.
- Every repository change updates the nearest relevant documentation in `README.md`, `docs/`, or `scripts/README.md`.
- CI/CD changes should document which workflow owns each validation step so operators can trace failures quickly.
