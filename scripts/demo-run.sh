#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"

"${SCRIPT_DIR}/wait-for-backend.sh"
"${SCRIPT_DIR}/seed-demo.sh"
"${SCRIPT_DIR}/smoke-test.sh"

echo "demo run completed"
