#!/usr/bin/env python3

from __future__ import annotations

import argparse
import concurrent.futures
import itertools
import json
import math
import os
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

DEFAULT_API_BASE_URL = os.getenv("API_BASE_URL", "http://127.0.0.1:8080")
DEFAULT_CURRENCY = os.getenv("DEFAULT_CURRENCY", "USD")
DEFAULT_STATE_FILE = Path("tmp/perf/seed-state.json")
DEFAULT_RESULTS_FILE = Path("tmp/perf/results.json")

LOW_RISK_CONFIRM_REQUEST = {
    "newDevice": False,
    "ipCountry": "US",
    "accountCountry": "US",
    "recentDeclines": 0,
    "accountAgeMinutes": 1440,
}

REVIEW_CONFIRM_REQUEST = {
    "newDevice": False,
    "ipCountry": "GB",
    "accountCountry": "US",
    "recentDeclines": 2,
    "accountAgeMinutes": 1440,
}


class ApiError(RuntimeError):
    pass


def utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def default_dataset_prefix() -> str:
    return "benchmark-" + datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S")


class ApiClient:
    def __init__(self, base_url: str, timeout_seconds: float) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout_seconds = timeout_seconds

    def get(self, path: str, headers: dict[str, str] | None = None) -> Any:
        return self.request("GET", path, None, headers)

    def post(self, path: str, body: Any, headers: dict[str, str] | None = None) -> Any:
        return self.request("POST", path, body, headers)

    def request(
        self,
        method: str,
        path: str,
        body: Any,
        headers: dict[str, str] | None = None,
    ) -> Any:
        url = f"{self.base_url}{path}"
        request_headers = {"Accept": "application/json"}
        if headers:
            request_headers.update(headers)

        data: bytes | None = None
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            request_headers["Content-Type"] = "application/json"

        request = urllib.request.Request(url, data=data, method=method)
        for header_name, header_value in request_headers.items():
            request.add_header(header_name, header_value)

        try:
            with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
                payload = response.read().decode("utf-8")
                if not payload:
                    return None
                return json.loads(payload)
        except urllib.error.HTTPError as exc:
            payload = exc.read().decode("utf-8")
            message = payload or exc.reason
            try:
                decoded = json.loads(payload)
                if isinstance(decoded, dict) and decoded.get("message"):
                    message = decoded["message"]
            except json.JSONDecodeError:
                pass
            raise ApiError(f"{method} {path} failed with {exc.code}: {message}") from exc
        except urllib.error.URLError as exc:
            raise ApiError(f"{method} {path} failed: {exc.reason}") from exc


def ensure_parent_dir(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)


def write_json(path: Path, payload: Any) -> None:
    ensure_parent_dir(path)
    path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def percentiles(values: list[float]) -> dict[str, float]:
    if not values:
        return {"p50": 0.0, "p95": 0.0, "p99": 0.0}
    ordered = sorted(values)
    return {
        "p50": percentile(ordered, 50),
        "p95": percentile(ordered, 95),
        "p99": percentile(ordered, 99),
    }


def percentile(sorted_values: list[float], value: int) -> float:
    if len(sorted_values) == 1:
        return round(sorted_values[0], 2)
    rank = (len(sorted_values) - 1) * (value / 100)
    lower = math.floor(rank)
    upper = math.ceil(rank)
    if lower == upper:
        return round(sorted_values[lower], 2)
    lower_value = sorted_values[lower]
    upper_value = sorted_values[upper]
    return round(lower_value + (upper_value - lower_value) * (rank - lower), 2)


def cycle_items(items: list[Any], count: int) -> list[Any]:
    return list(itertools.islice(itertools.cycle(items), count))


def create_account(client: ApiClient, owner_id: str, currency: str) -> dict[str, Any]:
    response = client.post(
        "/api/accounts",
        {
            "ownerId": owner_id,
            "currency": currency,
        },
    )
    if not isinstance(response, dict) or "id" not in response:
        raise ApiError(f"account creation returned an unexpected payload for owner {owner_id}")
    return response


def create_payment(
    client: ApiClient,
    payer_account_id: str,
    payee_account_id: str,
    amount_cents: int,
    currency: str,
    idempotency_key: str,
) -> dict[str, Any]:
    response = client.post(
        "/api/payments",
        {
            "payerAccountId": payer_account_id,
            "payeeAccountId": payee_account_id,
            "amountCents": amount_cents,
            "currency": currency,
            "idempotencyKey": idempotency_key,
        },
        headers={"Idempotency-Key": idempotency_key},
    )
    if not isinstance(response, dict) or response.get("status") != "CREATED":
        raise ApiError(f"payment create returned an unexpected status for key {idempotency_key}: {response}")
    return response


def confirm_payment(
    client: ApiClient,
    payment_id: str,
    idempotency_key: str,
    payload: dict[str, Any],
    expected_status: str,
) -> dict[str, Any]:
    response = client.post(
        f"/api/payments/{payment_id}/confirm",
        payload,
        headers={"Idempotency-Key": idempotency_key},
    )
    if not isinstance(response, dict) or response.get("status") != expected_status:
        raise ApiError(f"payment confirm returned an unexpected status for payment {payment_id}: {response}")
    return response


def capture_payment(client: ApiClient, payment_id: str, idempotency_key: str) -> dict[str, Any]:
    response = client.post(
        f"/api/payments/{payment_id}/capture",
        {},
        headers={"Idempotency-Key": idempotency_key},
    )
    if not isinstance(response, dict) or response.get("status") != "CAPTURED":
        raise ApiError(f"payment capture returned an unexpected status for payment {payment_id}: {response}")
    return response


def refund_payment(client: ApiClient, payment_id: str, idempotency_key: str) -> dict[str, Any]:
    response = client.post(
        f"/api/payments/{payment_id}/refund",
        {"reason": "benchmark"},
        headers={"Idempotency-Key": idempotency_key},
    )
    if not isinstance(response, dict) or response.get("status") != "REFUNDED":
        raise ApiError(f"payment refund returned an unexpected status for payment {payment_id}: {response}")
    return response


def seed_dataset(args: argparse.Namespace) -> int:
    client = ApiClient(args.api_base_url, args.timeout_seconds)
    state_file = Path(args.output)
    dataset_prefix = args.dataset_prefix or default_dataset_prefix()

    payers = [
        create_account(client, f"{dataset_prefix}-payer-{index:02d}", args.currency)
        for index in range(args.payer_count)
    ]
    payees = [
        create_account(client, f"{dataset_prefix}-payee-{index:02d}", args.currency)
        for index in range(args.payee_count)
    ]

    captured_payments: list[dict[str, Any]] = []
    refunded_payments: list[dict[str, Any]] = []
    review_payments: list[dict[str, Any]] = []
    payment_counts_by_account: dict[str, int] = {}

    def increment_hot_account(account_id: str) -> None:
        payment_counts_by_account[account_id] = payment_counts_by_account.get(account_id, 0) + 1

    total_settled_like = args.captured_payments + args.refunded_payments
    for index in range(total_settled_like):
        payer = payers[index % len(payers)]
        payee = payees[(index * 3) % len(payees)]
        increment_hot_account(payer["id"])
        amount_cents = 1500 + (index % 7) * 250
        payment = create_payment(
            client,
            payer["id"],
            payee["id"],
            amount_cents,
            args.currency,
            f"{dataset_prefix}-approved-{index}",
        )
        confirm_payment(
            client,
            payment["id"],
            f"{dataset_prefix}-approved-confirm-{index}",
            LOW_RISK_CONFIRM_REQUEST,
            "RESERVED",
        )
        capture_payment(client, payment["id"], f"{dataset_prefix}-approved-capture-{index}")

        if index < args.refunded_payments:
            refund_payment(client, payment["id"], f"{dataset_prefix}-approved-refund-{index}")
            refunded_payments.append({"id": payment["id"], "payerAccountId": payer["id"], "payeeAccountId": payee["id"]})
        else:
            captured_payments.append({"id": payment["id"], "payerAccountId": payer["id"], "payeeAccountId": payee["id"]})

    for index in range(args.review_payments):
        payer = payers[index % len(payers)]
        payee = payees[(index * 5) % len(payees)]
        increment_hot_account(payer["id"])
        payment = create_payment(
            client,
            payer["id"],
            payee["id"],
            2400,
            args.currency,
            f"{dataset_prefix}-review-{index}",
        )
        confirm_payment(
            client,
            payment["id"],
            f"{dataset_prefix}-review-confirm-{index}",
            REVIEW_CONFIRM_REQUEST,
            "RISK_SCORING",
        )
        review_payments.append({"id": payment["id"], "payerAccountId": payer["id"], "payeeAccountId": payee["id"]})

    hot_payer_id = max(payment_counts_by_account, key=payment_counts_by_account.get) if payment_counts_by_account else payers[0]["id"]

    state = {
        "generatedAt": utc_now(),
        "apiBaseUrl": args.api_base_url,
        "currency": args.currency,
        "datasetPrefix": dataset_prefix,
        "counts": {
            "payers": len(payers),
            "payees": len(payees),
            "capturedPayments": len(captured_payments),
            "refundedPayments": len(refunded_payments),
            "reviewPayments": len(review_payments),
        },
        "accounts": {
            "payers": payers,
            "payees": payees,
            "hotPayerId": hot_payer_id,
        },
        "payments": {
            "captured": captured_payments,
            "refunded": refunded_payments,
            "review": review_payments,
        },
    }

    write_json(state_file, state)
    print(f"Seeded benchmark dataset at {state_file}")
    print(json.dumps(state["counts"], indent=2))
    return 0


def create_round_robin_pairs(state: dict[str, Any], count: int) -> list[dict[str, str]]:
    payers = state["accounts"]["payers"]
    payees = state["accounts"]["payees"]
    pairs = []
    for index in range(count):
        pairs.append(
            {
                "payerAccountId": payers[index % len(payers)]["id"],
                "payeeAccountId": payees[(index * 3) % len(payees)]["id"],
            }
        )
    return pairs


def prepare_confirm_contexts(
    client: ApiClient,
    state: dict[str, Any],
    count: int,
    dataset_label: str,
    amount_cents: int,
) -> list[dict[str, str]]:
    currency = state["currency"]
    contexts = []
    for index, pair in enumerate(create_round_robin_pairs(state, count)):
        payment = create_payment(
            client,
            pair["payerAccountId"],
            pair["payeeAccountId"],
            amount_cents,
            currency,
            f"{dataset_label}-create-{index}-{uuid.uuid4()}",
        )
        contexts.append({"paymentId": payment["id"]})
    return contexts


def prepare_capture_contexts(client: ApiClient, state: dict[str, Any], count: int, dataset_label: str) -> list[dict[str, str]]:
    contexts = prepare_confirm_contexts(client, state, count, dataset_label, 2400)
    for index, context in enumerate(contexts):
        confirm_payment(
            client,
            context["paymentId"],
            f"{dataset_label}-confirm-{index}-{uuid.uuid4()}",
            LOW_RISK_CONFIRM_REQUEST,
            "RESERVED",
        )
    return contexts


def prepare_refund_contexts(client: ApiClient, state: dict[str, Any], count: int, dataset_label: str) -> list[dict[str, str]]:
    contexts = prepare_capture_contexts(client, state, count, dataset_label)
    for index, context in enumerate(contexts):
        capture_payment(client, context["paymentId"], f"{dataset_label}-capture-{index}-{uuid.uuid4()}")
    return contexts


def benchmark_scenario(
    name: str,
    concurrency: int,
    iterations: int,
    warmup: int,
    worker: Any,
    contexts: list[Any],
) -> dict[str, Any]:
    warmup_contexts = contexts[:warmup]
    for context in warmup_contexts:
        worker(context)

    measured_contexts = contexts[warmup:]
    started_at = time.perf_counter()
    durations_ms: list[float] = []
    failures: list[str] = []
    failure_count = 0

    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = [executor.submit(execute_operation, worker, context) for context in measured_contexts]
        for future in concurrent.futures.as_completed(futures):
            duration_ms, error = future.result()
            durations_ms.append(duration_ms)
            if error is not None:
                failure_count += 1
                if len(failures) < 5:
                    failures.append(error)

    elapsed_seconds = max(time.perf_counter() - started_at, 0.0001)
    success_count = len(durations_ms) - failure_count
    latency = percentiles(durations_ms)
    return {
        "name": name,
        "requests": len(durations_ms),
        "concurrency": concurrency,
        "warmupRequests": warmup,
        "successCount": success_count,
        "failureCount": failure_count,
        "errorRate": round(failure_count / len(durations_ms), 4) if durations_ms else 0.0,
        "throughputRps": round(len(durations_ms) / elapsed_seconds, 2),
        "latencyMs": {
            "min": round(min(durations_ms), 2) if durations_ms else 0.0,
            "mean": round(sum(durations_ms) / len(durations_ms), 2) if durations_ms else 0.0,
            "max": round(max(durations_ms), 2) if durations_ms else 0.0,
            **latency,
        },
        "sampleFailures": failures,
    }


def execute_operation(worker: Any, context: Any) -> tuple[float, str | None]:
    started_at = time.perf_counter()
    try:
        worker(context)
        return round((time.perf_counter() - started_at) * 1000, 2), None
    except Exception as exc:  # noqa: BLE001
        return round((time.perf_counter() - started_at) * 1000, 2), str(exc)


def evaluate_thresholds(results: list[dict[str, Any]], thresholds: dict[str, Any]) -> tuple[bool, dict[str, Any]]:
    evaluations: dict[str, Any] = {}
    overall_passed = True
    for result in results:
        scenario_name = result["name"]
        threshold = thresholds.get(scenario_name)
        if threshold is None:
            continue

        failure_reasons: list[str] = []
        max_p95 = threshold.get("maxP95Ms")
        if max_p95 is not None and result["latencyMs"]["p95"] > max_p95:
            failure_reasons.append(f"p95 {result['latencyMs']['p95']}ms > {max_p95}ms")

        max_error_rate = threshold.get("maxErrorRate")
        if max_error_rate is not None and result["errorRate"] > max_error_rate:
            failure_reasons.append(f"errorRate {result['errorRate']} > {max_error_rate}")

        min_throughput = threshold.get("minThroughputRps")
        if min_throughput is not None and result["throughputRps"] < min_throughput:
            failure_reasons.append(f"throughput {result['throughputRps']}rps < {min_throughput}rps")

        passed = not failure_reasons
        evaluations[scenario_name] = {
            "passed": passed,
            "threshold": threshold,
            "failureReasons": failure_reasons,
        }
        overall_passed = overall_passed and passed

    return overall_passed, evaluations


def run_suite(args: argparse.Namespace) -> int:
    state_file = Path(args.state_file)
    if not state_file.exists():
        print(f"State file not found: {state_file}", file=sys.stderr)
        print("Run the seed subcommand first or point --state-file at an existing dataset snapshot.", file=sys.stderr)
        return 2

    state = read_json(state_file)
    api_base_url = args.api_base_url or state.get("apiBaseUrl") or DEFAULT_API_BASE_URL
    client = ApiClient(api_base_url, args.timeout_seconds)
    results_file = Path(args.output)

    captured_or_refunded = state["payments"]["captured"] + state["payments"]["refunded"]
    if not captured_or_refunded:
        print("Seed state does not contain any captured or refunded payments for read scenarios.", file=sys.stderr)
        return 2

    created_pairs = create_round_robin_pairs(state, args.requests + args.warmup)
    confirm_contexts = prepare_confirm_contexts(
        client,
        state,
        args.requests + args.warmup,
        "bench-confirm",
        1800,
    )
    review_contexts = prepare_confirm_contexts(
        client,
        state,
        args.requests + args.warmup,
        "bench-review",
        2400,
    )
    capture_contexts = prepare_capture_contexts(client, state, args.requests + args.warmup, "bench-capture")
    refund_contexts = prepare_refund_contexts(client, state, args.requests + args.warmup, "bench-refund")
    payment_ledger_contexts = cycle_items(captured_or_refunded, args.requests + args.warmup)

    def payment_create_worker(context: dict[str, str]) -> None:
        create_payment(
            client,
            context["payerAccountId"],
            context["payeeAccountId"],
            2100,
            state["currency"],
            f"bench-create-{uuid.uuid4()}",
        )

    def payment_confirm_worker(context: dict[str, str]) -> None:
        confirm_payment(
            client,
            context["paymentId"],
            f"bench-confirm-{uuid.uuid4()}",
            LOW_RISK_CONFIRM_REQUEST,
            "RESERVED",
        )

    def fraud_review_worker(context: dict[str, str]) -> None:
        confirm_payment(
            client,
            context["paymentId"],
            f"bench-review-{uuid.uuid4()}",
            REVIEW_CONFIRM_REQUEST,
            "RISK_SCORING",
        )

    def payment_capture_worker(context: dict[str, str]) -> None:
        capture_payment(client, context["paymentId"], f"bench-capture-{uuid.uuid4()}")

    def payment_refund_worker(context: dict[str, str]) -> None:
        refund_payment(client, context["paymentId"], f"bench-refund-{uuid.uuid4()}")

    def payments_list_worker(_: Any) -> None:
        response = client.get("/api/payments")
        if not isinstance(response, list):
            raise ApiError("/api/payments did not return a list")

    def review_queue_worker(_: Any) -> None:
        response = client.get("/api/fraud/reviews")
        if not isinstance(response, list):
            raise ApiError("/api/fraud/reviews did not return a list")

    def payment_ledger_worker(context: dict[str, str]) -> None:
        response = client.get(f"/api/payments/{context['id']}/ledger")
        if not isinstance(response, list):
            raise ApiError(f"/api/payments/{context['id']}/ledger did not return a list")

    def account_replay_worker(_: Any) -> None:
        response = client.get(f"/api/ledger/replay/accounts/{state['accounts']['hotPayerId']}")
        if not isinstance(response, dict) or response.get("accountId") != state["accounts"]["hotPayerId"]:
            raise ApiError("ledger replay returned an unexpected payload")

    def ledger_verification_worker(_: Any) -> None:
        response = client.get("/api/ledger/verification")
        if not isinstance(response, dict) or "allChecksPassed" not in response:
            raise ApiError("ledger verification returned an unexpected payload")

    scenarios = [
        ("payment_create", payment_create_worker, created_pairs),
        ("payment_confirm", payment_confirm_worker, confirm_contexts),
        ("fraud_review_confirm", fraud_review_worker, review_contexts),
        ("payment_capture", payment_capture_worker, capture_contexts),
        ("payment_refund", payment_refund_worker, refund_contexts),
        ("operator_payments_list", payments_list_worker, [None] * (args.requests + args.warmup)),
        ("operator_review_queue", review_queue_worker, [None] * (args.requests + args.warmup)),
        ("operator_payment_ledger", payment_ledger_worker, payment_ledger_contexts),
        ("ledger_account_replay", account_replay_worker, [None] * (args.requests + args.warmup)),
        ("ledger_verification", ledger_verification_worker, [None] * (args.requests + args.warmup)),
    ]

    results = []
    for scenario_name, worker, contexts in scenarios:
        results.append(
            benchmark_scenario(
                scenario_name,
                args.concurrency,
                args.requests,
                args.warmup,
                worker,
                contexts,
            )
        )

    thresholds = read_json(Path(args.thresholds)) if args.thresholds else {}
    thresholds_passed, threshold_evaluations = evaluate_thresholds(results, thresholds)

    payload = {
        "generatedAt": utc_now(),
        "apiBaseUrl": api_base_url,
        "config": {
            "stateFile": str(state_file),
            "requests": args.requests,
            "warmup": args.warmup,
            "concurrency": args.concurrency,
            "timeoutSeconds": args.timeout_seconds,
        },
        "dataset": {
            "datasetPrefix": state.get("datasetPrefix"),
            "counts": state.get("counts"),
        },
        "thresholdsApplied": bool(thresholds),
        "thresholdsPassed": thresholds_passed,
        "thresholdEvaluations": threshold_evaluations,
        "results": results,
    }
    write_json(results_file, payload)

    print("Scenario,Requests,Errors,p95_ms,Throughput_rps")
    for result in results:
        print(
            f"{result['name']},{result['requests']},{result['failureCount']},"
            f"{result['latencyMs']['p95']},{result['throughputRps']}"
        )

    print(f"Saved benchmark results to {results_file}")

    if thresholds and not thresholds_passed:
        print("Threshold check failed.", file=sys.stderr)
        for scenario_name, evaluation in threshold_evaluations.items():
            if evaluation["passed"]:
                continue
            print(f"- {scenario_name}: {', '.join(evaluation['failureReasons'])}", file=sys.stderr)
        return 1

    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Seed and run LedgerForge performance benchmarks.")
    subparsers = parser.add_subparsers(dest="command", required=True)

    seed_parser = subparsers.add_parser("seed", help="Create a repeatable benchmark dataset through the live API.")
    seed_parser.add_argument("--api-base-url", default=DEFAULT_API_BASE_URL)
    seed_parser.add_argument("--currency", default=DEFAULT_CURRENCY)
    seed_parser.add_argument("--dataset-prefix", default=None)
    seed_parser.add_argument("--payer-count", type=int, default=8)
    seed_parser.add_argument("--payee-count", type=int, default=8)
    seed_parser.add_argument("--captured-payments", type=int, default=48)
    seed_parser.add_argument("--refunded-payments", type=int, default=12)
    seed_parser.add_argument("--review-payments", type=int, default=16)
    seed_parser.add_argument("--timeout-seconds", type=float, default=30.0)
    seed_parser.add_argument("--output", default=str(DEFAULT_STATE_FILE))
    seed_parser.set_defaults(func=seed_dataset)

    run_parser = subparsers.add_parser("run", help="Run the benchmark suite against an existing seeded dataset.")
    run_parser.add_argument("--api-base-url", default=None)
    run_parser.add_argument("--state-file", default=str(DEFAULT_STATE_FILE))
    run_parser.add_argument("--output", default=str(DEFAULT_RESULTS_FILE))
    run_parser.add_argument("--requests", type=int, default=24)
    run_parser.add_argument("--warmup", type=int, default=3)
    run_parser.add_argument("--concurrency", type=int, default=6)
    run_parser.add_argument("--timeout-seconds", type=float, default=30.0)
    run_parser.add_argument("--thresholds", default=None)
    run_parser.set_defaults(func=run_suite)

    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
