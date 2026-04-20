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
payment_amount="${PAYMENT_AMOUNT:-12500}"
payment_idempotency_key="${PAYMENT_IDEMPOTENCY_KEY:-${IDEMPOTENCY_PREFIX}-${demo_namespace}-create}"
confirm_idempotency_key="${CONFIRM_IDEMPOTENCY_KEY:-${IDEMPOTENCY_PREFIX}-${demo_namespace}-confirm}"
capture_idempotency_key="${CAPTURE_IDEMPOTENCY_KEY:-${IDEMPOTENCY_PREFIX}-${demo_namespace}-capture}"

log "seeding demo data"
payer_account_id="$(create_account "${payer_owner_id}")"
payee_account_id="$(create_account "${payee_owner_id}")"
log "created demo accounts payer=${payer_account_id} payee=${payee_account_id}"

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
  log "could not create demo payment"
  exit 1
fi

payment_id="$(json_get "id" <<<"${payment_response}")"

if [[ -z "${payment_id}" ]]; then
  log "payment created but ID was not found; raw response follows"
  printf "%s\n" "${payment_response}"
  exit 1
fi

log "demo payment created: ${payment_id}"

confirm_payload="$(python3 - <<PY
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

http_json "POST" "${API_BASE_URL}/api/payments/${payment_id}/confirm" "${confirm_payload}" "Idempotency-Key: ${confirm_idempotency_key}" >/dev/null
log "payment confirm succeeded: ${payment_id}"

http_json "POST" "${API_BASE_URL}/api/payments/${payment_id}/capture" "" "Idempotency-Key: ${capture_idempotency_key}" >/dev/null
log "payment capture succeeded: ${payment_id}"

log "seed step finished"

cat <<EOF
DEMO_NAMESPACE=${demo_namespace}
PAYER_ACCOUNT_ID=${payer_account_id}
PAYEE_ACCOUNT_ID=${payee_account_id}
CAPTURED_PAYMENT_ID=${payment_id}
EOF
