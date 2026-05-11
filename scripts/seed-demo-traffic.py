#!/usr/bin/env python3
"""Seed LedgerForge with realistic local demo traffic.

This script targets the current local API shape. It creates demo accounts, then
posts payments across the main lifecycle paths so the operator console has data
for dashboards, review queues, ledger entries, and investigation views.
"""

from __future__ import annotations

import argparse
import json
import time
import urllib.error
import urllib.request
from collections import Counter
from typing import Any


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Seed LedgerForge demo traffic.")
    parser.add_argument(
        "--base-url",
        default="http://localhost:8080",
        help="Backend base URL. Use localhost on this machine, not 127.0.0.1.",
    )
    parser.add_argument(
        "--currency",
        default="USD",
        help="Three-letter currency code for demo accounts and payments.",
    )
    parser.add_argument(
        "--payments",
        type=int,
        default=20,
        help="Number of payment records to create. Defaults to 20.",
    )
    return parser.parse_args()


class ApiClient:
    def __init__(self, base_url: str) -> None:
        self.base_url = base_url.rstrip("/")

    def request(
        self,
        method: str,
        path: str,
        payload: dict[str, Any] | None = None,
        headers: dict[str, str] | None = None,
        required: bool = True,
    ) -> Any:
        body = None if payload is None else json.dumps(payload).encode()
        request = urllib.request.Request(
            self.base_url + path,
            data=body,
            method=method,
            headers={
                "Content-Type": "application/json",
                "X-Correlation-Id": f"demo-{int(time.time())}",
                **(headers or {}),
            },
        )

        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                raw = response.read().decode()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as exc:
            raw = exc.read().decode()
            message = f"{method} {path} failed {exc.code}: {raw}"
            if required:
                raise RuntimeError(message) from exc
            print(f"WARN {message}")
            return None

    def get(self, path: str) -> Any:
        return self.request("GET", path)

    def post(
        self,
        path: str,
        payload: dict[str, Any],
        headers: dict[str, str] | None = None,
        required: bool = True,
    ) -> Any:
        return self.request("POST", path, payload, headers, required=required)


def create_account(client: ApiClient, owner: str, currency: str) -> dict[str, Any]:
    return client.post("/api/accounts", {"ownerId": owner, "currency": currency})


def create_payment(
    client: ApiClient,
    payer: dict[str, Any],
    payee: dict[str, Any],
    cents: int,
    key: str,
) -> dict[str, Any]:
    return client.post(
        "/api/payments",
        {
            "payerAccountId": payer["id"],
            "payeeAccountId": payee["id"],
            "amountCents": cents,
            "currency": payer["currency"],
            "idempotencyKey": key,
        },
        {"Idempotency-Key": key},
    )


def confirm(
    client: ApiClient,
    payment: dict[str, Any],
    key: str,
    risk_payload: dict[str, Any],
) -> dict[str, Any]:
    return client.post(
        f"/api/payments/{payment['id']}/confirm",
        risk_payload,
        {"Idempotency-Key": key},
    )


def capture(client: ApiClient, payment: dict[str, Any], key: str) -> dict[str, Any]:
    return client.post(
        f"/api/payments/{payment['id']}/capture",
        {},
        {"Idempotency-Key": key},
    )


def refund(client: ApiClient, payment: dict[str, Any], key: str) -> dict[str, Any] | None:
    return client.post(
        f"/api/payments/{payment['id']}/refund",
        {"reason": "demo full refund"},
        {"Idempotency-Key": key},
        required=False,
    )


def cancel(client: ApiClient, payment: dict[str, Any], key: str) -> dict[str, Any]:
    return client.post(
        f"/api/payments/{payment['id']}/cancel",
        {},
        {"Idempotency-Key": key},
    )


def decide_review(
    client: ApiClient,
    review_id: str,
    decision: str,
    note: str,
) -> dict[str, Any] | None:
    return client.post(
        f"/api/fraud/reviews/{review_id}/decision",
        {
            "decision": decision,
            "actor": "operator.demo@ledgerforge.local",
            "note": note,
        },
        required=False,
    )


def find_review_case(client: ApiClient, payment_id: str) -> dict[str, Any] | None:
    reviews = client.get("/api/fraud/reviews")
    for review in reviews:
        if review["paymentId"] == payment_id and review["status"] == "OPEN":
            return review
    return None


def amount_for(index: int, scenario: str) -> int:
    if scenario.startswith("review"):
        return 65_000 + (index % 9) * 4_750
    if scenario == "rejected":
        return 110_000 + (index % 8) * 12_500
    if scenario in {"created", "cancelled"}:
        return 3_000 + (index % 6) * 650
    return 2_500 + (index % 12) * 2_150


def main() -> None:
    args = parse_args()
    if args.payments < 1:
        raise SystemExit("--payments must be at least 1")

    client = ApiClient(args.base_url)
    stamp = int(time.time())

    health = client.get("/actuator/health")
    print(f"Backend health: {health.get('status', 'UNKNOWN')}")

    payer_a = create_account(client, f"demo-payer-{stamp}-a", args.currency)
    payer_b = create_account(client, f"demo-payer-{stamp}-b", args.currency)
    payer_c = create_account(client, f"demo-payer-{stamp}-c", args.currency)
    merchant_x = create_account(client, f"demo-merchant-{stamp}-x", args.currency)
    merchant_y = create_account(client, f"demo-merchant-{stamp}-y", args.currency)
    print("Created demo accounts: 5")

    low_risk = {
        "newDevice": False,
        "ipCountry": "US",
        "accountCountry": "US",
        "recentDeclines": 0,
        "accountAgeMinutes": 5000,
    }
    review_risk = {
        "newDevice": True,
        "ipCountry": "GB",
        "accountCountry": "US",
        "recentDeclines": 0,
        "accountAgeMinutes": 240,
    }
    reject_risk = {
        "newDevice": True,
        "ipCountry": "NG",
        "accountCountry": "US",
        "recentDeclines": 3,
        "accountAgeMinutes": 20,
    }

    scenarios = [
        "captured",
        "captured",
        "reserved",
        "refunded",
        "review_open",
        "review_approved",
        "review_rejected",
        "rejected",
        "created",
        "cancelled",
    ]
    scenario_counts: Counter[str] = Counter()

    for index in range(1, args.payments + 1):
        scenario = scenarios[(index - 1) % len(scenarios)]
        scenario_counts[scenario] += 1
        cents = amount_for(index, scenario)
        payee = merchant_x if index % 2 else merchant_y
        payer = payer_a
        risk_payload = low_risk

        if scenario.startswith("review"):
            payer = payer_b
            risk_payload = review_risk
        elif scenario == "rejected":
            payer = payer_c
            risk_payload = reject_risk

        payment = create_payment(
            client,
            payer,
            payee,
            cents,
            f"demo-{stamp}-{scenario}-{index}",
        )

        if scenario == "created":
            continue

        if scenario == "cancelled":
            cancel(client, payment, f"demo-{stamp}-cancel-{index}")
            continue

        payment = confirm(client, payment, f"demo-{stamp}-confirm-{scenario}-{index}", risk_payload)

        if scenario == "captured":
            capture(client, payment, f"demo-{stamp}-capture-{index}")
        elif scenario == "refunded":
            captured = capture(client, payment, f"demo-{stamp}-capture-refund-{index}")
            refund(client, captured, f"demo-{stamp}-refund-{index}")
        elif scenario in {"review_approved", "review_rejected"}:
            review = find_review_case(client, payment["id"])
            if review is not None and scenario == "review_approved":
                decide_review(
                    client,
                    review["id"],
                    "APPROVE",
                    "Demo approval to show review-to-reserve path.",
                )
            elif review is not None:
                decide_review(
                    client,
                    review["id"],
                    "REJECT",
                    "Demo rejection to show manual-review decline path.",
                )

        if args.payments >= 100 and index % 100 == 0:
            print(f"Created {index}/{args.payments} payments...")

    payments = client.get("/api/payments")
    reviews = client.get("/api/fraud/reviews")
    payment_counts = Counter(payment["status"] for payment in payments)
    review_counts = Counter(review["status"] for review in reviews)

    print(f"Payments now: {len(payments)}")
    print(f"Generated scenario mix: {dict(sorted(scenario_counts.items()))}")
    print(f"Payment statuses: {dict(sorted(payment_counts.items()))}")
    print(f"Review cases now: {len(reviews)}")
    print(f"Review statuses: {dict(sorted(review_counts.items()))}")
    print("Refresh http://localhost:5173/ to view the traffic.")


if __name__ == "__main__":
    main()
