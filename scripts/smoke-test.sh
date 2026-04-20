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

accounts_response="$(http_json "GET" "${API_BASE_URL}/api/accounts")"
log "accounts endpoint passed (count=$(json_length <<<"${accounts_response}"))"

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

payment_response="$(http_json "POST" "${API_BASE_URL}/api/payments" "${payment_payload}" "Idempotency-Key: ${create_key}")"
payment_id="$(json_get "id" <<<"${payment_response}")"

if [[ -z "${payment_id}" ]]; then
  log "payment API did not return an id field"
  printf "%s\n" "${payment_response}"
  exit 1
fi

log "payment API passed (id=${payment_id})"

replay_response="$(http_json "POST" "${API_BASE_URL}/api/payments" "${payment_payload}" "Idempotency-Key: ${create_key}")"
replay_payment_id="$(json_get "id" <<<"${replay_response}")"

if [[ "${replay_payment_id}" != "${payment_id}" ]]; then
  log "create idempotency failed: ${payment_id} != ${replay_payment_id}"
  exit 1
fi

log "create idempotency passed"

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

confirm_replay_response="$(http_json "POST" "${API_BASE_URL}/api/payments/${payment_id}/confirm" "${confirm_payload}" "Idempotency-Key: ${confirm_key}")"
confirm_replay_status="$(json_get "status" <<<"${confirm_replay_response}")"
if [[ "${confirm_replay_status}" != "RESERVED" ]]; then
  log "confirm replay failed, expected RESERVED but got ${confirm_replay_status}"
  exit 1
fi

log "confirm idempotency passed"

risk_response="$(http_json "GET" "${API_BASE_URL}/api/payments/${payment_id}/risk")"
risk_decision="$(json_get "riskDecision" <<<"${risk_response}")"
if [[ "${risk_decision}" != "APPROVE" ]]; then
  log "risk endpoint returned unexpected decision ${risk_decision}"
  exit 1
fi

log "risk endpoint passed"

capture_response="$(http_json "POST" "${API_BASE_URL}/api/payments/${payment_id}/capture" "" "Idempotency-Key: ${capture_key}")"
capture_status="$(json_get "status" <<<"${capture_response}")"
if [[ "${capture_status}" != "CAPTURED" ]]; then
  log "capture failed, expected CAPTURED but got ${capture_status}"
  exit 1
fi

capture_replay_response="$(http_json "POST" "${API_BASE_URL}/api/payments/${payment_id}/capture" "" "Idempotency-Key: ${capture_key}")"
capture_replay_status="$(json_get "status" <<<"${capture_replay_response}")"
if [[ "${capture_replay_status}" != "CAPTURED" ]]; then
  log "capture replay failed, expected CAPTURED but got ${capture_replay_status}"
  exit 1
fi

log "capture idempotency passed"

status_response="$(http_json "GET" "${API_BASE_URL}/api/payments/${payment_id}")"
status_value="$(json_get "status" <<<"${status_response}")"
if [[ "${status_value}" != "CAPTURED" ]]; then
  log "payment status endpoint returned unexpected state ${status_value}"
  exit 1
fi

ledger_response="$(http_json "GET" "${API_BASE_URL}/api/payments/${payment_id}/ledger")"
ledger_entry_count="$(json_length <<<"${ledger_response}")"
if [[ "${ledger_entry_count}" -lt 5 ]]; then
  log "ledger endpoint returned too few entries (${ledger_entry_count})"
  exit 1
fi

log "payment status endpoint passed"
log "ledger endpoint passed (entries=${ledger_entry_count})"
log "smoke checks finished"
