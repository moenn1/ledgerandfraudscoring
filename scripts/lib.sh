#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/.." >/dev/null 2>&1 && pwd)"

API_BASE_URL="${API_BASE_URL:-http://localhost:8080}"
COMPANY_ID="${COMPANY_ID:-demo-company}"
DEFAULT_CURRENCY="${DEFAULT_CURRENCY:-USD}"
IDEMPOTENCY_PREFIX="${IDEMPOTENCY_PREFIX:-ledgerforge-local}"
LEDGERFORGE_AUTH_ISSUER="${LEDGERFORGE_AUTH_ISSUER:-https://auth.ledgerforge.local}"
LEDGERFORGE_AUTH_AUDIENCE="${LEDGERFORGE_AUTH_AUDIENCE:-ledgerforge-operator-api}"
LEDGERFORGE_AUTH_HMAC_SECRET="${LEDGERFORGE_AUTH_HMAC_SECRET:-ledgerforge-dev-operator-signing-secret-change-before-shared-envs}"
OPERATOR_SUBJECT="${OPERATOR_SUBJECT:-operator.admin@ledgerforge.local}"
OPERATOR_ROLE="${OPERATOR_ROLE:-ADMIN}"
OPERATOR_TOKEN="${OPERATOR_TOKEN:-$("${SCRIPT_DIR}/generate-operator-token.py" --subject "${OPERATOR_SUBJECT}" --role "${OPERATOR_ROLE}")}"
AUTH_HEADER="Authorization: Bearer ${OPERATOR_TOKEN}"

timestamp() {
  date -u +"%Y-%m-%dT%H:%M:%SZ"
}

log() {
  printf "[%s] %s\n" "$(timestamp)" "$*" >&2
}

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    log "missing required command: $1"
    exit 1
  fi
}

http_json() {
  local method="$1"
  local url="$2"
  local body="${3:-}"
  local extra_header="${4:-}"

  if [[ -n "${body}" ]]; then
    curl -fsS -X "${method}" "${url}" \
      -H "${AUTH_HEADER}" \
      -H "Content-Type: application/json" \
      ${extra_header:+-H "${extra_header}"} \
      --data "${body}"
  else
    curl -fsS -X "${method}" "${url}" \
      -H "${AUTH_HEADER}" \
      ${extra_header:+-H "${extra_header}"}
  fi
}

json_get() {
  local path="$1"

  python3 -c '
import json
import sys

path = [segment for segment in sys.argv[1].split(".") if segment]
value = json.loads(sys.stdin.read() or "null")

for segment in path:
    if isinstance(value, list):
        value = value[int(segment)]
    elif isinstance(value, dict):
        value = value.get(segment)
    else:
        raise SystemExit(1)

if value is None:
    raise SystemExit(1)

if isinstance(value, (dict, list)):
    print(json.dumps(value))
else:
    print(value)
' "$path"
}

json_length() {
  python3 -c '
import json
import sys

value = json.loads(sys.stdin.read() or "null")
if isinstance(value, (list, dict)):
    print(len(value))
else:
    raise SystemExit(1)
'
}

create_account() {
  local owner_id="$1"
  local currency="${2:-${DEFAULT_CURRENCY}}"
  local payload
  local response

  payload="$(python3 - <<PY
import json
print(json.dumps({
  "ownerId": "${owner_id}",
  "currency": "${currency}"
}))
PY
)"

  response="$(http_json "POST" "${API_BASE_URL}/api/accounts" "${payload}")"
  json_get "id" <<<"${response}"
}

create_payment() {
  local payer_account_id="$1"
  local payee_account_id="$2"
  local amount_cents="$3"
  local idempotency_key="$4"
  local payload
  local response

  payload="$(python3 - <<PY
import json
print(json.dumps({
  "payerAccountId": "${payer_account_id}",
  "payeeAccountId": "${payee_account_id}",
  "amountCents": int("${amount_cents}"),
  "currency": "${DEFAULT_CURRENCY}",
  "idempotencyKey": "${idempotency_key}"
}))
PY
)"

  response="$(http_json "POST" "${API_BASE_URL}/api/payments" "${payload}" "Idempotency-Key: ${idempotency_key}")"
  json_get "id" <<<"${response}"
}

rand_id() {
  if command -v uuidgen >/dev/null 2>&1; then
    uuidgen | tr '[:upper:]' '[:lower:]'
  else
    date +%s%N
  fi
}
