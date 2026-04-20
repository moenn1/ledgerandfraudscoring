#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

require_cmd curl

if wait_for_http "backend health" "${API_BASE_URL}/actuator/health" "${TIMEOUT_SECONDS:-60}"; then
  exit 0
fi

if wait_for_http "backend fallback health" "${API_BASE_URL}/api/health" "${TIMEOUT_SECONDS:-60}"; then
  exit 0
fi

exit 1
