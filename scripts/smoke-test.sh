#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

require_cmd curl
require_cmd python3

idempotency_key="${IDEMPOTENCY_PREFIX}-smoke-$(rand_id)"
owner_suffix="$(printf "%s" "${idempotency_key}" | tr -cd '[:alnum:]' | tail -c 9)"
confirm_idempotency_key="${idempotency_key}-confirm"
capture_idempotency_key="${idempotency_key}-capture"
refund_idempotency_key="${idempotency_key}-refund"

log "running smoke checks against ${API_BASE_URL}"

if curl -fsS "${API_BASE_URL}/actuator/health" >/dev/null 2>&1; then
  log "health check passed: /actuator/health"
elif curl -fsS "${API_BASE_URL}/api/health" >/dev/null 2>&1; then
  log "health check passed: /api/health"
else
  log "health endpoint failed"
  exit 1
fi

create_account() {
  local owner_id="$1"
  local payload

  payload="$(python3 - <<PY
import json
print(json.dumps({
  "ownerId": "${owner_id}",
  "currency": "${DEFAULT_CURRENCY}"
}))
PY
)"

  http_json "POST" "${API_BASE_URL}/api/accounts" "${payload}"
}

assert_json_field_equals() {
  local json="$1"
  local field="$2"
  local expected="$3"
  local actual

  actual="$(printf "%s" "${json}" | json_field "${field}")"
  if [[ "${actual}" != "${expected}" ]]; then
    log "expected ${field}=${expected}, got ${actual}"
    printf "%s\n" "${json}"
    exit 1
  fi
}

payer_account_response="$(create_account "smoke-payer-${owner_suffix}")"
payee_account_response="$(create_account "smoke-payee-${owner_suffix}")"

payer_account_id="$(printf "%s" "${payer_account_response}" | json_field "id")"
payee_account_id="$(printf "%s" "${payee_account_response}" | json_field "id")"

if [[ -z "${payer_account_id}" || -z "${payee_account_id}" ]]; then
  log "account API did not return ids for smoke setup"
  printf "%s\n%s\n" "${payer_account_response}" "${payee_account_response}"
  exit 1
fi

log "accounts created for smoke payment"

payment_payload="$(python3 - <<PY
import json
print(json.dumps({
  "payerAccountId": "${payer_account_id}",
  "payeeAccountId": "${payee_account_id}",
  "amountCents": 500,
  "currency": "${DEFAULT_CURRENCY}",
  "idempotencyKey": "${idempotency_key}"
}))
PY
)"

payment_response="$(http_json "POST" "${API_BASE_URL}/api/payments" "${payment_payload}" "Idempotency-Key: ${idempotency_key}")"
assert_json_field_equals "${payment_response}" "status" "CREATED"

payment_id="$(printf "%s" "${payment_response}" | json_field "id")"
if [[ -z "${payment_id}" ]]; then
  log "payment API did not return an id field"
  printf "%s\n" "${payment_response}"
  exit 1
fi

replayed_payment_response="$(http_json "POST" "${API_BASE_URL}/api/payments" "${payment_payload}" "Idempotency-Key: ${idempotency_key}")"
assert_json_field_equals "${replayed_payment_response}" "id" "${payment_id}"
assert_json_field_equals "${replayed_payment_response}" "status" "CREATED"
log "payment creation idempotency passed (id=${payment_id})"

status_response="$(http_json "GET" "${API_BASE_URL}/api/payments/${payment_id}" "")"
assert_json_field_equals "${status_response}" "status" "CREATED"
log "payment status endpoint passed"

confirm_response="$(http_json "POST" "${API_BASE_URL}/api/payments/${payment_id}/confirm" "" "Idempotency-Key: ${confirm_idempotency_key}")"
assert_json_field_equals "${confirm_response}" "status" "RESERVED"

replayed_confirm_response="$(http_json "POST" "${API_BASE_URL}/api/payments/${payment_id}/confirm" "" "Idempotency-Key: ${confirm_idempotency_key}")"
assert_json_field_equals "${replayed_confirm_response}" "status" "RESERVED"
log "payment confirm idempotency passed"

capture_response="$(http_json "POST" "${API_BASE_URL}/api/payments/${payment_id}/capture" "" "Idempotency-Key: ${capture_idempotency_key}")"
assert_json_field_equals "${capture_response}" "status" "CAPTURED"
log "payment capture passed"

refund_payload='{"reason":"smoke-test"}'
refund_response="$(http_json "POST" "${API_BASE_URL}/api/payments/${payment_id}/refund" "${refund_payload}" "Idempotency-Key: ${refund_idempotency_key}")"
assert_json_field_equals "${refund_response}" "status" "REFUNDED"
log "payment refund passed"

final_status_response="$(http_json "GET" "${API_BASE_URL}/api/payments/${payment_id}" "")"
assert_json_field_equals "${final_status_response}" "status" "REFUNDED"

ledger_response="$(http_json "GET" "${API_BASE_URL}/api/payments/${payment_id}/ledger" "")"
ledger_entry_count="$(printf "%s" "${ledger_response}" | json_array_length)"
if [[ "${ledger_entry_count}" -lt 8 ]]; then
  log "expected at least 8 ledger entries, got ${ledger_entry_count}"
  printf "%s\n" "${ledger_response}"
  exit 1
fi
log "payment ledger endpoint passed"

verification_response="$(http_json "GET" "${API_BASE_URL}/api/ledger/verification" "")"
assert_json_field_equals "${verification_response}" "allChecksPassed" "true"
assert_json_field_equals "${verification_response}" "issueCount" "0"
log "ledger verification endpoint passed"

log "smoke checks finished"
