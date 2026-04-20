# Performance Benchmarks

LedgerForge ships a repo-owned benchmark suite for the implemented payment, fraud, ledger, and operator read paths.

The suite is intentionally API-level rather than database-direct so it exercises the same idempotency, fraud scoring, ledger posting, and query logic that production traffic will hit.

## Covered scenarios

- `payment_create`: create payment intents with unique idempotency keys
- `payment_confirm`: confirm low-risk payments through fraud scoring plus reserve journal posting
- `fraud_review_confirm`: confirm review-triggering payments that open manual-review cases
- `payment_capture`: capture reserved payments into payee and revenue accounts
- `payment_refund`: refund captured payments through immutable ledger writes
- `operator_payments_list`: read the operator payment queue
- `operator_review_queue`: read the manual-review queue
- `operator_payment_ledger`: inspect ledger detail for a payment
- `ledger_account_replay`: replay a high-volume account projection from immutable entries
- `ledger_verification`: verify journal balance and lifecycle consistency across the ledger

## Prerequisites

- Java 17+ and Maven for the backend
- `python3`
- A reachable LedgerForge backend at `http://127.0.0.1:8080` unless `API_BASE_URL` is overridden

## Seed a benchmark dataset

The seed step creates payer/payee accounts, captured payments, refunded payments, and open review cases, then writes the generated IDs to a reusable state file.

```bash
python3 scripts/perf/benchmark_suite.py seed \
  --api-base-url http://127.0.0.1:8080 \
  --output tmp/perf/seed-state.json \
  --captured-payments 48 \
  --refunded-payments 12 \
  --review-payments 16
```

Use `--dataset-prefix` when you want a stable label for a named run. If you are comparing results over time on the same database, prefer reseeding from a clean backend so list and replay queries stay comparable.

## Run the suite

```bash
python3 scripts/perf/benchmark_suite.py run \
  --api-base-url http://127.0.0.1:8080 \
  --state-file tmp/perf/seed-state.json \
  --output tmp/perf/results.json \
  --requests 24 \
  --warmup 3 \
  --concurrency 6 \
  --thresholds scripts/perf/thresholds.example.json
```

The runner prints a CSV-like summary to stdout and writes structured JSON results to `tmp/perf/results.json`.

## Results and thresholds

- `tmp/perf/seed-state.json` stores the created account and payment IDs so repeated runs target the same benchmark fixture
- `tmp/perf/results.json` stores scenario latency percentiles, throughput, error rate, and threshold evaluation
- `scripts/perf/thresholds.example.json` contains conservative example gates that can be tuned for a target machine or CI class

Threshold checks are optional. When a thresholds file is supplied, the suite exits non-zero if a scenario breaches its configured `maxP95Ms`, `maxErrorRate`, or `minThroughputRps`.

## Automation

The manual GitHub Actions workflow `.github/workflows/performance-benchmarks.yml` starts the backend, seeds a fixture, runs the benchmark suite, publishes a step summary, and uploads the seed state, result JSON, and backend log as artifacts.

Use the workflow when you want a shareable benchmark snapshot without reproducing the run locally.
