#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

require_cmd docker
require_cmd curl

if ! docker compose version >/dev/null 2>&1; then
  log "docker compose is required but was not found"
  exit 1
fi

mode="core"
auth_enabled=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --extended)
      mode="extended"
      shift
      ;;
    --full-stack)
      mode="full"
      shift
      ;;
    --auth)
      auth_enabled=true
      shift
      ;;
    *)
      log "unsupported option: $1"
      exit 1
      ;;
  esac
done

if [[ "${auth_enabled}" == true && "${mode}" != "full" ]]; then
  log "--auth requires --full-stack"
  exit 1
fi

compose_env_file="${COMPOSE_ENV_FILE:-${ROOT_DIR}/tmp/docker-compose.env}"
render_args=(--output "${compose_env_file}")
if [[ "${auth_enabled}" == true ]]; then
  render_args+=(--auth-mode oidc)
else
  render_args+=(--auth-mode hmac)
fi

"${SCRIPT_DIR}/render-compose-env.sh" "${render_args[@]}"

compose_cmd=(docker compose --env-file "${compose_env_file}" -f "${ROOT_DIR}/docker-compose.yml")
services=(postgres)
profile_args=()

case "${mode}" in
  extended)
    services+=(redis kafka zipkin prometheus)
    profile_args+=(--profile platform)
    ;;
  full)
    services+=(redis kafka zipkin prometheus backend)
    profile_args+=(--profile platform --profile full)
    if [[ "${auth_enabled}" == true ]]; then
      services+=(keycloak)
      profile_args+=(--profile auth)
    fi
    ;;
esac

log "starting local services: ${services[*]}"
"${compose_cmd[@]}" "${profile_args[@]}" up -d "${services[@]}"

if [[ "${auth_enabled}" == true ]]; then
  wait_for_http "Keycloak discovery" \
    "${LEDGERFORGE_KEYCLOAK_BASE_URL:-http://127.0.0.1:8081}/realms/${LEDGERFORGE_KEYCLOAK_REALM:-ledgerforge}/.well-known/openid-configuration" \
    "${TIMEOUT_SECONDS:-120}"
  operator_token="$("${SCRIPT_DIR}/fetch-keycloak-token.sh")"
  "${SCRIPT_DIR}/render-compose-env.sh" \
    --auth-mode oidc \
    --operator-token "${operator_token}" \
    --output "${compose_env_file}"
fi

if [[ "${mode}" == "full" ]]; then
  wait_for_http "backend health" "${API_BASE_URL}/actuator/health" "${TIMEOUT_SECONDS:-180}"
  log "starting operator UI container"
  "${compose_cmd[@]}" "${profile_args[@]}" up -d frontend
fi

log "local stack is starting"
