#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

require_cmd curl
require_cmd python3

base_url="${LEDGERFORGE_KEYCLOAK_BASE_URL:-http://127.0.0.1:8081}"
realm="${LEDGERFORGE_KEYCLOAK_REALM:-ledgerforge}"
client_id="${LEDGERFORGE_KEYCLOAK_CLIENT_ID:-ledgerforge-local-cli}"
username="${LEDGERFORGE_KEYCLOAK_USERNAME:-operator.admin}"
password="${LEDGERFORGE_KEYCLOAK_PASSWORD:-operator-admin}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --base-url)
      base_url="${2:-}"
      shift 2
      ;;
    --realm)
      realm="${2:-}"
      shift 2
      ;;
    --client-id)
      client_id="${2:-}"
      shift 2
      ;;
    --username)
      username="${2:-}"
      shift 2
      ;;
    --password)
      password="${2:-}"
      shift 2
      ;;
    *)
      log "unsupported option: $1"
      exit 1
      ;;
  esac
done

response="$(
  curl -fsS -X POST \
    "${base_url}/realms/${realm}/protocol/openid-connect/token" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    --data-urlencode "grant_type=password" \
    --data-urlencode "client_id=${client_id}" \
    --data-urlencode "username=${username}" \
    --data-urlencode "password=${password}"
)"

printf '%s' "${response}" | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])'
