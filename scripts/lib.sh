#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/.." >/dev/null 2>&1 && pwd)"

API_BASE_URL="${API_BASE_URL:-http://127.0.0.1:8080}"
COMPANY_ID="${COMPANY_ID:-demo-company}"
DEFAULT_CURRENCY="${DEFAULT_CURRENCY:-USD}"
IDEMPOTENCY_PREFIX="${IDEMPOTENCY_PREFIX:-ledgerforge-local}"

timestamp() {
  date -u +"%Y-%m-%dT%H:%M:%SZ"
}

log() {
  printf "[%s] %s\n" "$(timestamp)" "$*"
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
      -H "Content-Type: application/json" \
      ${extra_header:+-H "${extra_header}"} \
      --data "${body}"
  else
    curl -fsS -X "${method}" "${url}" \
      ${extra_header:+-H "${extra_header}"}
  fi
}

rand_id() {
  if command -v uuidgen >/dev/null 2>&1; then
    uuidgen | tr '[:upper:]' '[:lower:]'
  else
    date +%s%N
  fi
}

json_field() {
  local path="$1"

  python3 -c '
import json
import sys

path = [segment for segment in sys.argv[1].split(".") if segment]
payload = sys.stdin.read() or "{}"
value = json.loads(payload)

for segment in path:
    if isinstance(value, dict):
        value = value.get(segment, "")
    elif isinstance(value, list):
        try:
            value = value[int(segment)]
        except (ValueError, IndexError):
            value = ""
    else:
        value = ""
        break

if value is None:
    print("")
elif isinstance(value, bool):
    print("true" if value else "false")
elif isinstance(value, (dict, list)):
    print(json.dumps(value))
else:
    print(value)
' "$path"
}

json_array_length() {
  python3 -c '
import json
import sys

value = json.loads(sys.stdin.read() or "[]")
print(len(value) if isinstance(value, list) else 0)
'
}
