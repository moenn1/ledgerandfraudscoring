#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

log "starting demo run"
"${SCRIPT_DIR}/wait-for-backend.sh"
"${SCRIPT_DIR}/seed-demo.sh"
"${SCRIPT_DIR}/smoke-test.sh"

log "demo run completed"
