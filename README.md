# LedgerForge Payments

LedgerForge Payments is a local-first fintech demo platform for real-time payments, double-entry ledgering, and fraud scoring.

## Planned MVP

- Spring Boot backend for accounts, payments, ledger, fraud, audit, and reporting
- React operator dashboard for payment review and reconciliation
- PostgreSQL-backed immutable ledger model
- Idempotent payment APIs with real-time fraud scoring
- Local developer workflow with Docker Compose
- Repeatable benchmark coverage for payment, fraud, ledger replay, and operator query paths

## Repository Layout

- `backend/` Spring Boot application
- `frontend/` React operator dashboard
- `docs/` architecture, API, and operations notes
- `scripts/` local developer, smoke, and benchmark helpers

## Benchmark Coverage

- `scripts/perf/benchmark_suite.py`: seeds benchmark datasets and runs concurrent HTTP scenarios against the backend
- `scripts/perf/thresholds.example.json`: example latency, throughput, and error-rate gates for local or CI runs
- `docs/performance-benchmarks.md`: benchmark scenario matrix, commands, outputs, and workflow usage
- `.github/workflows/performance-benchmarks.yml`: manual workflow that runs the suite and uploads result artifacts

## Status

The repository is being built out as the LedgerForge platform, with ongoing delivery across ledger, payments, fraud, and operator workflows.
