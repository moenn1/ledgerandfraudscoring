#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

auth_mode="hmac"
operator_token=""
output_path="${ROOT_DIR}/tmp/docker-compose.env"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --auth-mode)
      auth_mode="${2:-}"
      shift 2
      ;;
    --operator-token)
      operator_token="${2:-}"
      shift 2
      ;;
    --output)
      output_path="${2:-}"
      shift 2
      ;;
    *)
      log "unsupported option: $1"
      exit 1
      ;;
  esac
done

case "${auth_mode}" in
  hmac|oidc)
    ;;
  *)
    log "unsupported auth mode: ${auth_mode}"
    exit 1
    ;;
esac

mkdir -p "$(dirname -- "${output_path}")"

api_base_url="${LEDGERFORGE_API_BASE_URL:-http://127.0.0.1:8080}"
auth_hmac_secret="${LEDGERFORGE_AUTH_HMAC_SECRET:-ledgerforge-dev-operator-signing-secret-change-before-shared-envs}"
auth_audience="${LEDGERFORGE_AUTH_AUDIENCE:-ledgerforge-operator-api}"
data_protection_key="${LEDGERFORGE_DATA_PROTECTION_KEY:-ledgerforge-dev-data-protection-key-change-before-shared-envs}"
keycloak_base_url="${LEDGERFORGE_KEYCLOAK_BASE_URL:-http://127.0.0.1:8081}"
keycloak_realm="${LEDGERFORGE_KEYCLOAK_REALM:-ledgerforge}"
keycloak_client_id="${LEDGERFORGE_KEYCLOAK_CLIENT_ID:-ledgerforge-local-cli}"
keycloak_username="${LEDGERFORGE_KEYCLOAK_USERNAME:-operator.admin}"
keycloak_password="${LEDGERFORGE_KEYCLOAK_PASSWORD:-operator-admin}"

if [[ "${auth_mode}" == "oidc" ]]; then
  auth_issuer="${keycloak_base_url}/realms/${keycloak_realm}"
  auth_jwk_set_uri="http://keycloak:8080/realms/${keycloak_realm}/protocol/openid-connect/certs"
else
  auth_issuer="${LEDGERFORGE_AUTH_ISSUER:-https://auth.ledgerforge.local}"
  auth_jwk_set_uri=""
  if [[ -z "${operator_token}" ]]; then
    operator_token="$("${SCRIPT_DIR}/generate-operator-token.py" \
      --subject "${OPERATOR_SUBJECT:-operator.admin@ledgerforge.local}" \
      --role "${OPERATOR_ROLE:-ADMIN}" \
      --ttl-seconds "${OPERATOR_TTL_SECONDS:-86400}" \
      --issuer "${auth_issuer}" \
      --audience "${auth_audience}" \
      --secret "${auth_hmac_secret}")"
  fi
fi

cat >"${output_path}" <<EOF
LEDGERFORGE_API_BASE_URL=${api_base_url}
LEDGERFORGE_OPERATOR_BEARER_TOKEN=${operator_token}
LEDGERFORGE_AUTH_HMAC_SECRET=${auth_hmac_secret}
LEDGERFORGE_AUTH_ISSUER=${auth_issuer}
LEDGERFORGE_AUTH_AUDIENCE=${auth_audience}
LEDGERFORGE_AUTH_JWK_SET_URI=${auth_jwk_set_uri}
LEDGERFORGE_DATA_PROTECTION_KEY=${data_protection_key}
LEDGERFORGE_KEYCLOAK_BASE_URL=${keycloak_base_url}
LEDGERFORGE_KEYCLOAK_REALM=${keycloak_realm}
LEDGERFORGE_KEYCLOAK_CLIENT_ID=${keycloak_client_id}
LEDGERFORGE_KEYCLOAK_USERNAME=${keycloak_username}
LEDGERFORGE_KEYCLOAK_PASSWORD=${keycloak_password}
EOF

log "wrote compose runtime env to ${output_path}"
