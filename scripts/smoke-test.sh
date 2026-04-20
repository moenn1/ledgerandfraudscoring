#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

require_cmd curl
require_cmd python3

smoke_namespace="${SMOKE_NAMESPACE:-smoke-$(date -u +%Y%m%d%H%M%S)-$(rand_id | cut -c1-8)}"
create_key="${IDEMPOTENCY_PREFIX}-${smoke_namespace}-create"
confirm_key="${IDEMPOTENCY_PREFIX}-${smoke_namespace}-confirm"
capture_key="${IDEMPOTENCY_PREFIX}-${smoke_namespace}-capture"

log "running smoke checks against ${API_BASE_URL}"

if curl -fsS "${API_BASE_URL}/actuator/health" >/dev/null 2>&1; then
  log "health check passed: /actuator/health"
elif curl -fsS "${API_BASE_URL}/api/health" >/dev/null 2>&1; then
  log "health check passed: /api/health"
else
  log "health endpoint failed"
  exit 1
fi

payer_account_id="$(create_account "${smoke_namespace}-payer")"
payee_account_id="$(create_account "${smoke_namespace}-payee")"
log "created smoke accounts payer=${payer_account_id} payee=${payee_account_id}"

payment_payload="$(python3 - <<PY
import json
print(json.dumps({
  "payerAccountId": "${payer_account_id}",
  "payeeAccountId": "${payee_account_id}",
  "amountCents": 500,
  "currency": "${DEFAULT_CURRENCY}",
  "idempotencyKey": "${create_key}"
}))
PY
)"

set +e
payment_response="$(http_json "POST" "${API_BASE_URL}/api/payments" "${payment_payload}" "Idempotency-Key: ${create_key}" 2>/dev/null)"
payment_status=$?
set -e

if [[ ${payment_status} -ne 0 ]]; then
  log "payment creation check failed"
  exit 1
fi

payment_id="$(json_get "id" <<<"${payment_response}")"

if [[ -z "${payment_id}" ]]; then
  log "payment API did not return an id field"
  printf "%s\n" "${payment_response}"
  exit 1
fi

log "payment API passed (id=${payment_id})"

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

confirm_response="$(http_json "POST" "${API_BASE_URL}/api/payments/${payment_id}/confirm" "${confirm_payload}" "Idempotency-Key: ${confirm_key}")"
confirm_status="$(json_get "status" <<<"${confirm_response}")"
if [[ "${confirm_status}" != "RESERVED" ]]; then
  log "confirm failed, expected RESERVED but got ${confirm_status}"
  exit 1
fi
log "confirm API passed"

capture_response="$(http_json "POST" "${API_BASE_URL}/api/payments/${payment_id}/capture" "" "Idempotency-Key: ${capture_key}")"
capture_status="$(json_get "status" <<<"${capture_response}")"
if [[ "${capture_status}" != "CAPTURED" ]]; then
  log "capture failed, expected CAPTURED but got ${capture_status}"
  exit 1
fi
log "capture API passed"

status_response="$(http_json "GET" "${API_BASE_URL}/api/payments/${payment_id}")"
status_value="$(json_get "status" <<<"${status_response}")"
if [[ "${status_value}" != "CAPTURED" ]]; then
  log "payment status endpoint returned unexpected state ${status_value}"
  exit 1
fi
log "payment status endpoint passed"

log "smoke checks finished"
