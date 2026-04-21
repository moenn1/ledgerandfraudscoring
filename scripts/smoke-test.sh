#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

require_cmd curl
require_cmd python3

idempotency_key="${IDEMPOTENCY_PREFIX}-smoke-$(rand_id)"
confirm_idempotency_key="${IDEMPOTENCY_PREFIX}-smoke-confirm-$(rand_id)"
capture_idempotency_key="${IDEMPOTENCY_PREFIX}-smoke-capture-$(rand_id)"
smoke_amount_cents="${SMOKE_PAYMENT_AMOUNT_CENTS:-500}"

log "running smoke checks against ${API_BASE_URL}"

if curl -fsS "${API_BASE_URL}/actuator/health" >/dev/null 2>&1; then
  log "health check passed: /actuator/health"
elif curl -fsS "${API_BASE_URL}/api/health" >/dev/null 2>&1; then
  log "health check passed: /api/health"
else
  log "health endpoint failed"
  exit 1
fi

assert_json_field() {
  local response="$1"
  local path="$2"
  local expected="$3"
  local description="$4"

  local actual
  actual="$(printf "%s" "${response}" | json_field "${path}")"
  if [[ "${actual}" != "${expected}" ]]; then
    log "${description}: expected ${path}=${expected}, got ${actual:-<empty>}"
    printf "%s\n" "${response}"
    exit 1
  fi
}

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

  local response
  response="$(http_json "POST" "${API_BASE_URL}/api/accounts" "${payload}")"
  local account_id
  account_id="$(printf "%s" "${response}" | json_field "id")"

  if [[ -z "${account_id}" ]]; then
    log "account creation response did not include an id"
    printf "%s\n" "${response}"
    exit 1
  fi

  printf "%s\n" "${account_id}"
}

payer_account_id="$(create_account "smoke-payer-$(rand_id)")"
payee_account_id="$(create_account "smoke-payee-$(rand_id)")"

payment_payload="$(python3 - <<PY
import json
print(json.dumps({
  "payerAccountId": "${payer_account_id}",
  "payeeAccountId": "${payee_account_id}",
  "amountCents": ${smoke_amount_cents},
  "currency": "${DEFAULT_CURRENCY}",
  "idempotencyKey": "${idempotency_key}"
}))
PY
)"

payment_response="$(http_json "POST" "${API_BASE_URL}/api/payments" "${payment_payload}" "Idempotency-Key: ${idempotency_key}")"

payment_id="$(printf "%s" "${payment_response}" | json_field "id")"

if [[ -z "${payment_id}" ]]; then
  log "payment API did not return an id field"
  printf "%s\n" "${payment_response}"
  exit 1
fi

assert_json_field "${payment_response}" "status" "CREATED" "payment creation status"
log "payment create passed (id=${payment_id})"

status_response="$(http_json "GET" "${API_BASE_URL}/api/payments/${payment_id}")"
assert_json_field "${status_response}" "id" "${payment_id}" "payment lookup id"
assert_json_field "${status_response}" "status" "CREATED" "payment lookup status"

confirm_payload="$(python3 - <<'PY'
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

confirm_response="$(http_json "POST" "${API_BASE_URL}/api/payments/${payment_id}/confirm" "${confirm_payload}" "Idempotency-Key: ${confirm_idempotency_key}")"
assert_json_field "${confirm_response}" "status" "RESERVED" "payment confirm status"
log "payment confirm passed"

risk_response="$(http_json "GET" "${API_BASE_URL}/api/payments/${payment_id}/risk")"
assert_json_field "${risk_response}" "paymentId" "${payment_id}" "payment risk id"
assert_json_field "${risk_response}" "paymentStatus" "RESERVED" "payment risk status"
assert_json_field "${risk_response}" "riskDecision" "APPROVE" "payment risk decision"

capture_response="$(http_json "POST" "${API_BASE_URL}/api/payments/${payment_id}/capture" "" "Idempotency-Key: ${capture_idempotency_key}")"
assert_json_field "${capture_response}" "status" "CAPTURED" "payment capture status"
log "payment capture passed"

ledger_response="$(http_json "GET" "${API_BASE_URL}/api/payments/${payment_id}/ledger")"
ledger_entry_count="$(printf "%s" "${ledger_response}" | json_array_length)"
if [[ "${ledger_entry_count}" -lt 4 ]]; then
  log "payment ledger endpoint returned too few entries: ${ledger_entry_count}"
  printf "%s\n" "${ledger_response}"
  exit 1
fi

verification_response="$(http_json "GET" "${API_BASE_URL}/api/ledger/verification")"
assert_json_field "${verification_response}" "allChecksPassed" "true" "ledger verification status"
assert_json_field "${verification_response}" "issueCount" "0" "ledger verification issue count"

log "smoke checks finished"
