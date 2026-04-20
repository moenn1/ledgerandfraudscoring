#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

require_cmd curl
require_cmd python3

payer_account_id="${PAYER_ACCOUNT_ID:-payer-001}"
payee_account_id="${PAYEE_ACCOUNT_ID:-payee-001}"
payment_amount="${PAYMENT_AMOUNT:-12500}"
payment_idempotency_key="${PAYMENT_IDEMPOTENCY_KEY:-${IDEMPOTENCY_PREFIX}-seed-$(rand_id)}"

create_account() {
  local account_id="$1"
  local owner_id="$2"
  local starting_balance_cents="$3"

  local payload
  payload="$(python3 - <<PY
import json
print(json.dumps({
  "id": "${account_id}",
  "ownerId": "${owner_id}",
  "currency": "${DEFAULT_CURRENCY}",
  "startingBalanceCents": ${starting_balance_cents}
}))
PY
)"

  if http_json "POST" "${API_BASE_URL}/api/accounts" "${payload}" >/dev/null 2>&1; then
    log "account ensured: ${account_id}"
  else
    log "could not create account ${account_id} (endpoint may differ); continuing"
  fi
}

log "seeding demo data"
create_account "${payer_account_id}" "alice" 500000
create_account "${payee_account_id}" "bob" 100000

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

payment_id="$(printf "%s" "${payment_response}" | python3 - <<'PY'
import json,sys
obj=json.loads(sys.stdin.read() or "{}")
print(obj.get("id",""))
PY
)"

if [[ -z "${payment_id}" ]]; then
  log "payment created but ID was not found; raw response follows"
  printf "%s\n" "${payment_response}"
  exit 0
fi

log "demo payment created: ${payment_id}"

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
