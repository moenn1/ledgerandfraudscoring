# Developer Scripts

These scripts provide a lightweight local workflow for backend readiness checks, demo seeding, smoke validation, and repeatable performance coverage.

## Prerequisites

- `bash`
- `curl`
- `python3`
- Java 17+ and Maven when running the backend locally for the benchmark suite

## Configuration

Optional environment variables:

- `API_BASE_URL` (default: `http://127.0.0.1:8080`)
- `DEFAULT_CURRENCY` (default: `USD`)
- `IDEMPOTENCY_PREFIX` (default: `ledgerforge-local`)
- `TIMEOUT_SECONDS` for readiness wait (default: `60`)

## Commands

- `./scripts/wait-for-backend.sh`: waits for `/actuator/health` (fallback `/api/health`)
- `./scripts/seed-demo.sh`: tries to create demo accounts and one payment
- `./scripts/smoke-test.sh`: health + basic payment API checks
- `./scripts/demo-run.sh`: runs all of the above in order
- `python3 scripts/perf/benchmark_suite.py seed`: creates a benchmark dataset through the live API and writes reusable account/payment IDs to a state file
- `python3 scripts/perf/benchmark_suite.py run`: executes concurrent payment, fraud, ledger, and operator-query benchmark scenarios and writes JSON results
- `scripts/perf/thresholds.example.json`: example threshold file for gating latency, throughput, and error rate

## Notes

- Scripts are intentionally tolerant while backend endpoints are still evolving.
- Failed optional API calls are logged and skipped so local iteration stays fast.
- Benchmark outputs are written under `tmp/perf/`, which is already ignored by the repository.
- The benchmark suite is API-level by design so it measures the same fraud, ledger, idempotency, and query code paths that operators and clients will call.
