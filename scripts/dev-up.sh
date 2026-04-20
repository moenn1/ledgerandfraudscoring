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

services=(postgres)
profile_args=()

if [[ "${1:-}" == "--extended" ]]; then
  services+=(redis kafka)
  profile_args+=(--profile extended)
fi

log "starting local dependencies: ${services[*]}"
docker compose -f "${ROOT_DIR}/docker-compose.yml" "${profile_args[@]}" up -d "${services[@]}"
log "dependencies are starting"
