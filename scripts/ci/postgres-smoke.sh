#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
log_file="${repo_root}/backend-smoke.log"
api_base_url="${API_BASE_URL:-http://127.0.0.1:8080}"
timeout_seconds="${TIMEOUT_SECONDS:-120}"
backend_pid=""

cleanup() {
  local exit_code=$?

  if [[ -n "$backend_pid" ]] && kill -0 "$backend_pid" >/dev/null 2>&1; then
    kill "$backend_pid" >/dev/null 2>&1 || true
    wait "$backend_pid" >/dev/null 2>&1 || true
  fi

  if [[ $exit_code -ne 0 && -f "$log_file" ]]; then
    echo "Backend smoke log tail:"
    tail -n 200 "$log_file"
  fi

  exit "$exit_code"
}

trap cleanup EXIT

cd "$repo_root/backend"
SPRING_PROFILES_ACTIVE=postgres mvn -B spring-boot:run >"$log_file" 2>&1 &
backend_pid=$!

cd "$repo_root"
API_BASE_URL="$api_base_url" TIMEOUT_SECONDS="$timeout_seconds" bash scripts/wait-for-backend.sh
curl -fsS "${api_base_url}/actuator/health" >/dev/null

echo "PostgreSQL-backed backend smoke check passed."
