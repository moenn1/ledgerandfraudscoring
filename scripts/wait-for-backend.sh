#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

require_cmd curl

TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-60}"
SLEEP_SECONDS="${SLEEP_SECONDS:-2}"
deadline=$((SECONDS + TIMEOUT_SECONDS))

log "waiting for backend at ${API_BASE_URL}"

while (( SECONDS < deadline )); do
  if curl -fsS "${API_BASE_URL}/actuator/health" >/dev/null 2>&1; then
    log "backend is healthy on /actuator/health"
    exit 0
  fi

  if curl -fsS "${API_BASE_URL}/api/health" >/dev/null 2>&1; then
    log "backend is healthy on /api/health"
    exit 0
  fi

  sleep "${SLEEP_SECONDS}"
done

log "backend did not become healthy within ${TIMEOUT_SECONDS}s"
exit 1
