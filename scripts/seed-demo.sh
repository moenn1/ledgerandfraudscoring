#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

require_cmd curl
require_cmd python3

payer_owner_id="${PAYER_OWNER_ID:-alice-$(rand_id)}"
payee_owner_id="${PAYEE_OWNER_ID:-bob-$(rand_id)}"
payment_amount="${PAYMENT_AMOUNT:-12500}"
payment_idempotency_key="${PAYMENT_IDEMPOTENCY_KEY:-${IDEMPOTENCY_PREFIX}-seed-$(rand_id)}"

create_account() {
  local owner_id="$2"
  local label="$1"

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
  if ! response="$(http_json "POST" "${API_BASE_URL}/api/accounts" "${payload}" 2>/dev/null)"; then
    log "could not create ${label} account for owner ${owner_id} (endpoint may differ); continuing" >&2
    return 1
  fi

  local account_id
  account_id="$(printf "%s" "${response}" | json_field "id")"
  if [[ -z "${account_id}" ]]; then
    log "${label} account response did not include an id; continuing" >&2
    printf "%s\n" "${response}" >&2
    return 1
  fi

  log "${label} account created: ${account_id}" >&2
  printf "%s\n" "${account_id}"
}

log "seeding demo data"
payer_account_id="$(create_account "payer" "${payer_owner_id}")" || payer_account_id=""
payee_account_id="$(create_account "payee" "${payee_owner_id}")" || payee_account_id=""

if [[ -z "${payer_account_id}" || -z "${payee_account_id}" ]]; then
  log "demo seed could not create both accounts; continuing"
  exit 0
fi

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

set +e
payment_response="$(http_json "POST" "${API_BASE_URL}/api/payments" "${payment_payload}" "Idempotency-Key: ${payment_idempotency_key}" 2>/dev/null)"
payment_status=$?
set -e

if [[ ${payment_status} -ne 0 ]]; then
  log "could not create demo payment (endpoint may differ); continuing"
  exit 0
fi

payment_id="$(printf "%s" "${payment_response}" | json_field "id")"

if [[ -z "${payment_id}" ]]; then
  log "payment created but ID was not found; raw response follows"
  printf "%s\n" "${payment_response}"
  exit 0
fi

payment_status_value="$(printf "%s" "${payment_response}" | json_field "status")"
log "demo payment created: ${payment_id} (status=${payment_status_value})"

for action in confirm capture; do
  set +e
  http_json "POST" "${API_BASE_URL}/api/payments/${payment_id}/${action}" "{}" >/dev/null 2>&1
  rc=$?
  set -e
  if [[ ${rc} -eq 0 ]]; then
    log "payment ${action} succeeded: ${payment_id}"
  else
    log "payment ${action} skipped/failure for ${payment_id} (endpoint or lifecycle may differ)"
  fi
done

log "seed step finished"
