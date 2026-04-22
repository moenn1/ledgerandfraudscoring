#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

require_cmd curl
require_cmd python3

payer_owner_id="${PAYER_OWNER_ID:-alice}"
payee_owner_id="${PAYEE_OWNER_ID:-bob}"
payment_amount="${PAYMENT_AMOUNT:-12500}"
payment_idempotency_key="${PAYMENT_IDEMPOTENCY_KEY:-${IDEMPOTENCY_PREFIX}-seed-$(rand_id)}"
confirm_idempotency_key="${CONFIRM_IDEMPOTENCY_KEY:-${payment_idempotency_key}-confirm}"
capture_idempotency_key="${CAPTURE_IDEMPOTENCY_KEY:-${payment_idempotency_key}-capture}"

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

log "seeding demo data"

payer_account_response="$(create_account "${payer_owner_id}")"
payee_account_response="$(create_account "${payee_owner_id}")"

payer_account_id="$(printf "%s" "${payer_account_response}" | json_field "id")"
payee_account_id="$(printf "%s" "${payee_account_response}" | json_field "id")"

if [[ -z "${payer_account_id}" || -z "${payee_account_id}" ]]; then
  log "account API did not return expected ids"
  printf "%s\n%s\n" "${payer_account_response}" "${payee_account_response}"
  exit 1
fi

log "accounts created: payer=${payer_account_id} payee=${payee_account_id}"

payment_payload="$(python3 - <<PY
import json
print(json.dumps({
  "payerAccountId": "${payer_account_id}",
  "payeeAccountId": "${payee_account_id}",
  "amountCents": ${payment_amount},
  "currency": "${DEFAULT_CURRENCY}",
  "idempotencyKey": "${payment_idempotency_key}"
}))
PY
)"

payment_response="$(http_json "POST" "${API_BASE_URL}/api/payments" "${payment_payload}" "Idempotency-Key: ${payment_idempotency_key}")"
payment_id="$(printf "%s" "${payment_response}" | json_field "id")"

if [[ -z "${payment_id}" ]]; then
  log "payment API did not return an id field"
  printf "%s\n" "${payment_response}"
  exit 1
fi

assert_json_field_equals "${payment_response}" "status" "CREATED"
log "demo payment created: ${payment_id}"

confirm_response="$(http_json "POST" "${API_BASE_URL}/api/payments/${payment_id}/confirm" "" "Idempotency-Key: ${confirm_idempotency_key}")"
assert_json_field_equals "${confirm_response}" "status" "RESERVED"
log "payment reserved: ${payment_id}"

capture_response="$(http_json "POST" "${API_BASE_URL}/api/payments/${payment_id}/capture" "" "Idempotency-Key: ${capture_idempotency_key}")"
assert_json_field_equals "${capture_response}" "status" "CAPTURED"
log "payment captured: ${payment_id}"

log "seed step finished"
