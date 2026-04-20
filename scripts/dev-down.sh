#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

require_cmd docker

if ! docker compose version >/dev/null 2>&1; then
  log "docker compose is required but was not found"
  exit 1
fi

remove_volumes=()
if [[ "${1:-}" == "--volumes" ]]; then
  remove_volumes+=(--volumes)
fi

log "stopping local dependencies"
docker compose \
  --env-file "${COMPOSE_ENV_FILE:-${ROOT_DIR}/tmp/docker-compose.env}" \
  -f "${ROOT_DIR}/docker-compose.yml" \
  down "${remove_volumes[@]}"
log "dependencies stopped"
