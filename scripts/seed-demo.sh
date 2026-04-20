#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

require_cmd curl
require_cmd python3

demo_namespace="${DEMO_NAMESPACE:-demo-$(date -u +%Y%m%d%H%M%S)-$(rand_id | cut -c1-8)}"
payer_owner_id="${PAYER_OWNER_ID:-${demo_namespace}-payer}"
payee_owner_id="${PAYEE_OWNER_ID:-${demo_namespace}-payee}"
approved_payment_amount_cents="${APPROVED_PAYMENT_AMOUNT_CENTS:-12500}"
review_payment_amount_cents="${REVIEW_PAYMENT_AMOUNT_CENTS:-150000}"
approved_create_key="${APPROVED_CREATE_KEY:-${IDEMPOTENCY_PREFIX}-${demo_namespace}-approved-create}"
approved_confirm_key="${APPROVED_CONFIRM_KEY:-${IDEMPOTENCY_PREFIX}-${demo_namespace}-approved-confirm}"
approved_capture_key="${APPROVED_CAPTURE_KEY:-${IDEMPOTENCY_PREFIX}-${demo_namespace}-approved-capture}"
review_create_key="${REVIEW_CREATE_KEY:-${IDEMPOTENCY_PREFIX}-${demo_namespace}-review-create}"
review_confirm_key="${REVIEW_CONFIRM_KEY:-${IDEMPOTENCY_PREFIX}-${demo_namespace}-review-confirm}"

log "seeding demo data"

payer_account_id="$(create_account "${payer_owner_id}")"
payee_account_id="$(create_account "${payee_owner_id}")"
log "created demo accounts payer=${payer_account_id} payee=${payee_account_id}"

approved_payment_id="$(create_payment "${payer_account_id}" "${payee_account_id}" "${approved_payment_amount_cents}" "${approved_create_key}")"
log "created straight-through payment=${approved_payment_id}"

approved_confirm_payload="$(python3 - <<PY
import json
print(json.dumps({
  "newDevice": False,
  "ipCountry": "US",
  "accountCountry": "US",
  "recentDeclines": 0,
  "accountAgeMinutes": 1440
}))
PY
)"

approved_confirm_response="$(http_json "POST" "${API_BASE_URL}/api/payments/${approved_payment_id}/confirm" "${approved_confirm_payload}" "Idempotency-Key: ${approved_confirm_key}")"
log "confirmed straight-through payment status=$(json_get "status" <<<"${approved_confirm_response}") decision=$(json_get "riskDecision" <<<"${approved_confirm_response}")"

approved_capture_response="$(http_json "POST" "${API_BASE_URL}/api/payments/${approved_payment_id}/capture" "" "Idempotency-Key: ${approved_capture_key}")"
log "captured straight-through payment status=$(json_get "status" <<<"${approved_capture_response}")"

review_payment_id="$(create_payment "${payer_account_id}" "${payee_account_id}" "${review_payment_amount_cents}" "${review_create_key}")"
log "created manual-review candidate payment=${review_payment_id}"

review_confirm_payload="$(python3 - <<PY
import json
print(json.dumps({
  "newDevice": True,
  "ipCountry": "US",
  "accountCountry": "CA",
  "recentDeclines": 0,
  "accountAgeMinutes": 15
}))
PY
)"

review_confirm_response="$(http_json "POST" "${API_BASE_URL}/api/payments/${review_payment_id}/confirm" "${review_confirm_payload}" "Idempotency-Key: ${review_confirm_key}")"
log "confirmed manual-review payment status=$(json_get "status" <<<"${review_confirm_response}") decision=$(json_get "riskDecision" <<<"${review_confirm_response}")"

review_queue_response="$(http_json "GET" "${API_BASE_URL}/api/fraud/reviews")"
review_case_id="$(REVIEW_PAYMENT_ID="${review_payment_id}" python3 -c '
import json
import os
import sys

payment_id = os.environ["REVIEW_PAYMENT_ID"]
items = json.loads(sys.stdin.read() or "[]")
for item in items:
    if item.get("paymentId") == payment_id:
        print(item["id"])
        break
else:
    raise SystemExit(1)
' <<<"${review_queue_response}")"

log "manual-review case id=${review_case_id}"
log "seed step finished"

cat <<EOF
DEMO_NAMESPACE=${demo_namespace}
PAYER_ACCOUNT_ID=${payer_account_id}
PAYEE_ACCOUNT_ID=${payee_account_id}
CAPTURED_PAYMENT_ID=${approved_payment_id}
MANUAL_REVIEW_PAYMENT_ID=${review_payment_id}
MANUAL_REVIEW_CASE_ID=${review_case_id}
EOF
