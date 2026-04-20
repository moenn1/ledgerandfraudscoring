# Local API Requests

These `curl` examples mirror the backend routes that are implemented today. Use them directly or as a reference for Postman and demo walkthroughs.

## Setup

```bash
export API_BASE_URL=http://localhost:8080
export IDEMPOTENCY_PREFIX=ledgerforge-local
export OPERATOR_TOKEN="$(./scripts/generate-operator-token.py --subject ops.operator@ledgerforge.local --role OPERATOR)"
export REVIEWER_TOKEN="$(./scripts/generate-operator-token.py --subject risk.reviewer@ledgerforge.local --role REVIEWER)"
export ADMIN_TOKEN="$(./scripts/generate-operator-token.py --subject ops.admin@ledgerforge.local --role ADMIN)"
export OPERATOR_AUTH_HEADER="Authorization: Bearer $OPERATOR_TOKEN"
export REVIEWER_AUTH_HEADER="Authorization: Bearer $REVIEWER_TOKEN"
export ADMIN_AUTH_HEADER="Authorization: Bearer $ADMIN_TOKEN"
```

The secured operator API enforces these effective roles:

- `Viewer`: read-only payment, account, review-queue, settlement-batch, and payout inspection
- `Operator`: payment create, confirm, capture, cancel, refund, reverse, and chargeback
- `Reviewer`: manual-review approve/reject decisions
- `Admin`: account administration, ledger replay/verification, settlement runs, payout runs, and all inherited viewer/operator/reviewer access

## 1. Create accounts

```bash
PAYER_ACCOUNT_ID="$(
  curl -sS -X POST "$API_BASE_URL/api/accounts" \
    -H "$ADMIN_AUTH_HEADER" \
    -H 'Content-Type: application/json' \
    --data '{"ownerId":"demo-payer","currency":"USD","supportedCurrencies":["USD","EUR"]}' | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])'
)"

PAYEE_ACCOUNT_ID="$(
  curl -sS -X POST "$API_BASE_URL/api/accounts" \
    -H "$ADMIN_AUTH_HEADER" \
    -H 'Content-Type: application/json' \
    --data '{"ownerId":"demo-payee","currency":"USD","supportedCurrencies":["USD","EUR"]}' | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])'
)"
```

`supportedCurrencies` is optional. If omitted, the account only accepts the primary `currency`.

## 2. Freeze or reactivate an account

```bash
curl -sS -X POST "$API_BASE_URL/api/accounts/$PAYER_ACCOUNT_ID/status" \
  -H "$ADMIN_AUTH_HEADER" \
  -H 'Content-Type: application/json' \
  -H "X-Correlation-Id: ${IDEMPOTENCY_PREFIX}-account-freeze-001" \
  --data '{
    "status": "FROZEN",
    "reason": "Manual intervention pending"
  }' | python3 -m json.tool
```

Expected outcome: the account response returns `status=FROZEN`. While frozen, new payment creation, confirmation, capture, and manual-review approval attempts that depend on that account will fail with a conflict.

To reactivate the account:

```bash
curl -sS -X POST "$API_BASE_URL/api/accounts/$PAYER_ACCOUNT_ID/status" \
  -H "$ADMIN_AUTH_HEADER" \
  -H 'Content-Type: application/json' \
  -H "X-Correlation-Id: ${IDEMPOTENCY_PREFIX}-account-unfreeze-001" \
  --data '{
    "status": "ACTIVE",
    "reason": "Investigation resolved"
  }' | python3 -m json.tool
```

## 3. Create a payment intent

```bash
PAYMENT_ID="$(
  curl -sS -X POST "$API_BASE_URL/api/payments" \
    -H "$OPERATOR_AUTH_HEADER" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: ${IDEMPOTENCY_PREFIX}-create-001" \
    --data "{
      \"payerAccountId\": \"$PAYER_ACCOUNT_ID\",
      \"payeeAccountId\": \"$PAYEE_ACCOUNT_ID\",
      \"amountCents\": 12500,
      \"currency\": \"USD\",
      \"idempotencyKey\": \"${IDEMPOTENCY_PREFIX}-create-001\"
    }" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])'
)"
```

Replay the same request with the same idempotency key to verify the response returns the same payment id.

## 4. Confirm the payment through straight-through approval

```bash
curl -sS -X POST "$API_BASE_URL/api/payments/$PAYMENT_ID/confirm" \
  -H "$OPERATOR_AUTH_HEADER" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: ${IDEMPOTENCY_PREFIX}-confirm-001" \
  --data '{
    "newDevice": false,
    "ipCountry": "US",
    "accountCountry": "US",
    "recentDeclines": 0,
    "accountAgeMinutes": 1440
  }' | python3 -m json.tool
```

Expected outcome: `riskDecision=APPROVE` and `status=RESERVED`.

## 5. Capture and inspect ledger entries

```bash
curl -sS -X POST "$API_BASE_URL/api/payments/$PAYMENT_ID/capture" \
  -H "$OPERATOR_AUTH_HEADER" \
  -H "Idempotency-Key: ${IDEMPOTENCY_PREFIX}-capture-001" | python3 -m json.tool

curl -sS -H "$OPERATOR_AUTH_HEADER" "$API_BASE_URL/api/payments/$PAYMENT_ID/ledger" | python3 -m json.tool
curl -sS -H "$OPERATOR_AUTH_HEADER" "$API_BASE_URL/api/accounts/$PAYEE_ACCOUNT_ID/balance?currency=USD" | python3 -m json.tool
curl -sS -H "$ADMIN_AUTH_HEADER" "$API_BASE_URL/api/ledger/replay/accounts/$PAYEE_ACCOUNT_ID?currency=USD" | python3 -m json.tool
```

Expected outcome: one reserve journal plus one capture journal, with balanced ledger legs.

If an account has more than one enabled currency, `currency` is required for balance and replay queries so the projection stays explicit.

## 6. Create a manual-review case

```bash
REVIEW_PAYMENT_ID="$(
  curl -sS -X POST "$API_BASE_URL/api/payments" \
    -H "$OPERATOR_AUTH_HEADER" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: ${IDEMPOTENCY_PREFIX}-review-create-001" \
    --data "{
      \"payerAccountId\": \"$PAYER_ACCOUNT_ID\",
      \"payeeAccountId\": \"$PAYEE_ACCOUNT_ID\",
      \"amountCents\": 150000,
      \"currency\": \"USD\",
      \"idempotencyKey\": \"${IDEMPOTENCY_PREFIX}-review-create-001\"
    }" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])'
)"

curl -sS -X POST "$API_BASE_URL/api/payments/$REVIEW_PAYMENT_ID/confirm" \
  -H "$OPERATOR_AUTH_HEADER" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: ${IDEMPOTENCY_PREFIX}-review-confirm-001" \
  --data '{
    "newDevice": true,
    "ipCountry": "US",
    "accountCountry": "CA",
    "recentDeclines": 0,
    "accountAgeMinutes": 15
  }' | python3 -m json.tool

curl -sS -H "$OPERATOR_AUTH_HEADER" "$API_BASE_URL/api/payments/$REVIEW_PAYMENT_ID/risk" | python3 -m json.tool
curl -sS -H "$OPERATOR_AUTH_HEADER" "$API_BASE_URL/api/fraud/reviews" | python3 -m json.tool
```

Expected outcome: `riskDecision=REVIEW`, the payment remains in `RISK_SCORING`, and a review case appears in `/api/fraud/reviews`.

## 7. Approve or reject the manual review

```bash
REVIEW_CASE_ID="$(
  REVIEW_PAYMENT_ID="$REVIEW_PAYMENT_ID" curl -sS -H "$OPERATOR_AUTH_HEADER" "$API_BASE_URL/api/fraud/reviews" | python3 -c 'import json,os,sys; payment_id=os.environ["REVIEW_PAYMENT_ID"]; items=json.load(sys.stdin); print(next(item["id"] for item in items if item["paymentId"] == payment_id))'
)"

curl -sS -X POST "$API_BASE_URL/api/fraud/reviews/$REVIEW_CASE_ID/decision" \
  -H "$REVIEWER_AUTH_HEADER" \
  -H 'Content-Type: application/json' \
  -H "X-Correlation-Id: ${IDEMPOTENCY_PREFIX}-manual-review-001" \
  --data '{
    "decision": "APPROVE",
    "note": "Approved during local demo walkthrough."
  }' | python3 -m json.tool
```

Approving the case moves the payment to `RESERVED`, after which the normal capture flow can continue.

If either the payer or payee account is `FROZEN`, approval will fail until the account is reactivated or the operator chooses a recovery action instead.

## 8. Reverse a reserved payment while the payer stays frozen

```bash
curl -sS -X POST "$API_BASE_URL/api/accounts/$PAYER_ACCOUNT_ID/status" \
  -H "$ADMIN_AUTH_HEADER" \
  -H 'Content-Type: application/json' \
  -H "X-Correlation-Id: ${IDEMPOTENCY_PREFIX}-account-freeze-002" \
  --data '{
    "status": "FROZEN",
    "reason": "Stop payout while investigation continues"
  }' | python3 -m json.tool

curl -sS -X POST "$API_BASE_URL/api/payments/$PAYMENT_ID/reverse" \
  -H "$OPERATOR_AUTH_HEADER" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: ${IDEMPOTENCY_PREFIX}-reverse-001" \
  -H "X-Correlation-Id: ${IDEMPOTENCY_PREFIX}-reverse-001" \
  --data '{
    "reason": "Release reserved funds back to the frozen payer"
  }' | python3 -m json.tool

curl -sS -H "$OPERATOR_AUTH_HEADER" "$API_BASE_URL/api/payments/$PAYMENT_ID/adjustments" | python3 -m json.tool
```

Expected outcome: the payment transitions to `REVERSED`, an immutable adjustment record is appended, and the unwind succeeds even though the payer account remains frozen.

## 9. Run settlement for captured payments

```bash
SETTLEMENT_CUTOFF="$(
  curl -sS -H "$OPERATOR_AUTH_HEADER" "$API_BASE_URL/api/payments/$PAYMENT_ID" | python3 -c 'import json,sys; print(json.load(sys.stdin)["settlementScheduledFor"])'
)"

curl -sS -X POST "$API_BASE_URL/api/settlements/run" \
  -H "$ADMIN_AUTH_HEADER" \
  -H 'Content-Type: application/json' \
  --data "{
    \"asOf\": \"$SETTLEMENT_CUTOFF\",
    \"payoutDelayMinutes\": 0
  }" | python3 -m json.tool

curl -sS -H "$ADMIN_AUTH_HEADER" "$API_BASE_URL/api/settlements/batches" | python3 -m json.tool
curl -sS -H "$ADMIN_AUTH_HEADER" "$API_BASE_URL/api/payouts" | python3 -m json.tool
```

Expected outcome: the captured payment moves to `SETTLED`, a settlement batch is recorded for the cutoff, and one payout is scheduled per payee/currency combination.

## 10. Execute due payouts

```bash
curl -sS -X POST "$API_BASE_URL/api/payouts/run" \
  -H "$ADMIN_AUTH_HEADER" \
  -H 'Content-Type: application/json' \
  --data "{
    \"asOf\": \"$SETTLEMENT_CUTOFF\"
  }" | python3 -m json.tool

curl -sS -H "$OPERATOR_AUTH_HEADER" "$API_BASE_URL/api/accounts/$PAYEE_ACCOUNT_ID/balance?currency=USD" | python3 -m json.tool
curl -sS -H "$ADMIN_AUTH_HEADER" "$API_BASE_URL/api/payouts" | python3 -m json.tool
```

Expected outcome: the payout becomes `PAID`, the merchant balance decreases by the settled net amount, and the balancing journal credits `SYSTEM_PAYOUT_CLEARING`.

If a refund or chargeback drained the merchant balance after settlement but before payout execution, rerunning `/api/payouts/run` marks that payout `DELAYED` instead of overdrawing the account. The endpoint is safe to rerun after the balance issue is resolved.

## 11. Register a webhook endpoint and dispatch pending deliveries

```bash
WEBHOOK_SECRET=ledgerforge-webhook-secret
WEBHOOK_ENDPOINT_ID="$(
  curl -sS -X POST "$API_BASE_URL/api/webhooks/endpoints" \
    -H "$ADMIN_AUTH_HEADER" \
    -H 'Content-Type: application/json' \
    --data "{
      \"name\": \"local-consumer\",
      \"url\": \"http://127.0.0.1:9001/hooks\",
      \"subscribedEvents\": [\"payment.*\"],
      \"maxAttempts\": 3,
      \"signingSecret\": \"$WEBHOOK_SECRET\"
    }" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])'
)"

curl -sS -X POST "$API_BASE_URL/api/webhooks/deliveries/dispatch?limit=25" \
  -H "$ADMIN_AUTH_HEADER" | python3 -m json.tool

curl -sS -H "$OPERATOR_AUTH_HEADER" \
  "$API_BASE_URL/api/webhooks/deliveries?paymentId=$PAYMENT_ID" | python3 -m json.tool
```

Expected outcome: payment lifecycle events subscribed by the endpoint appear as auditable deliveries with `status=SUCCEEDED`, `RETRY_PENDING`, or `FAILED`, plus response metadata for the last attempt.

## 12. Acknowledge or reject a webhook via signed callback

```bash
DELIVERY_ID="$(
  curl -sS -H "$OPERATOR_AUTH_HEADER" \
    "$API_BASE_URL/api/webhooks/deliveries?paymentId=$PAYMENT_ID" | python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["id"])'
)"

CALLBACK_BODY="$(cat <<JSON
{"deliveryId":"$DELIVERY_ID","status":"ACKNOWLEDGED","reason":"consumer accepted"}
JSON
)"

CALLBACK_TS="$(date +%s)"
CALLBACK_SIG="t=${CALLBACK_TS},v1=$(printf '%s' "${CALLBACK_TS}.${CALLBACK_BODY}" | openssl dgst -sha256 -hmac "$WEBHOOK_SECRET" -binary | xxd -p -c 256)"

curl -sS -X POST "$API_BASE_URL/api/webhooks/callbacks/$WEBHOOK_ENDPOINT_ID" \
  -H 'Content-Type: application/json' \
  -H 'X-LedgerForge-Callback-Id: callback-001' \
  -H "X-LedgerForge-Signature: $CALLBACK_SIG" \
  --data "$CALLBACK_BODY" | python3 -m json.tool
```

Change `status` to `REJECTED` to reschedule the delivery while attempts remain. Replaying the same callback id with the same payload is idempotent; replaying it with different payload content returns a conflict.

## 13. Inspect transactional outbox delivery state

```bash
curl -sS -H "$OPERATOR_AUTH_HEADER" \
  "$API_BASE_URL/api/events/outbox?status=pending&limit=20" | python3 -m json.tool

curl -sS -H "$OPERATOR_AUTH_HEADER" \
  "$API_BASE_URL/api/events/outbox?status=published&limit=20" | python3 -m json.tool
```

Expected outcome: payment, ledger, settlement, and payout domain events appear with stable ids, correlation ids, and delivery metadata such as `attemptCount`, `availableAt`, `publishedAt`, and `lastError`.
