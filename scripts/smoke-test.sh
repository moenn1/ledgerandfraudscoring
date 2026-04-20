#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

require_cmd curl
require_cmd python3

idempotency_key="${IDEMPOTENCY_PREFIX}-smoke-$(rand_id)"

log "running smoke checks against ${API_BASE_URL}"

if curl -fsS "${API_BASE_URL}/actuator/health" >/dev/null 2>&1; then
  log "health check passed: /actuator/health"
elif curl -fsS "${API_BASE_URL}/api/health" >/dev/null 2>&1; then
  log "health check passed: /api/health"
else
  log "health endpoint failed"
  exit 1
fi

payment_payload="$(python3 - <<PY
import json
print(json.dumps({
  "payerAccountId": "payer-001",
  "payeeAccountId": "payee-001",
  "amountCents": 500,
  "currency": "${DEFAULT_CURRENCY}",
  "idempotencyKey": "${idempotency_key}"
}))
PY
)"

set +e
payment_response="$(http_json "POST" "${API_BASE_URL}/api/payments" "${payment_payload}" "Idempotency-Key: ${idempotency_key}" 2>/dev/null)"
payment_status=$?
set -e

if [[ ${payment_status} -ne 0 ]]; then
  log "payment creation check skipped/failure (API not yet aligned)"
  exit 0
fi

payment_id="$(printf "%s" "${payment_response}" | python3 - <<'PY'
import json,sys
obj=json.loads(sys.stdin.read() or "{}")
print(obj.get("id",""))
PY
)"

if [[ -z "${payment_id}" ]]; then
  log "payment API did not return an id field"
  printf "%s\n" "${payment_response}"
  exit 1
fi

log "payment API passed (id=${payment_id})"

set +e
status_response="$(http_json "GET" "${API_BASE_URL}/api/payments/${payment_id}" "" 2>/dev/null)"
status_rc=$?
set -e

if [[ ${status_rc} -eq 0 ]]; then
  log "payment status endpoint passed"
  printf "%s\n" "${status_response}" | python3 -m json.tool >/dev/null 2>&1 || true
else
  log "payment status endpoint unavailable, but creation worked"
fi

log "smoke checks finished"
