#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." >/dev/null 2>&1 && pwd)"
SCRIPT_UNDER_TEST="${ROOT_DIR}/scripts/check-governance-docs.sh"
TMP_DIR="$(mktemp -d)"
TEST_REPO="${TMP_DIR}/repo"

cleanup() {
  rm -rf "${TMP_DIR}"
}

fail() {
  echo "check-governance-docs regression failed: $1" >&2
  exit 1
}

assert_passes() {
  local description="$1"
  shift

  if ! "$@" >/dev/null 2>&1; then
    fail "${description}"
  fi
}

assert_fails() {
  local description="$1"
  shift

  if "$@" >/dev/null 2>&1; then
    fail "${description}"
  fi
}

trap cleanup EXIT

mkdir -p "${TEST_REPO}/docs" "${TEST_REPO}/scripts"
cp "${SCRIPT_UNDER_TEST}" "${TEST_REPO}/scripts/check-governance-docs.sh"
chmod +x "${TEST_REPO}/scripts/check-governance-docs.sh"

cd "${TEST_REPO}"
git init -q
git checkout -q -b main
git config user.name "LedgerForge Test"
git config user.email "ledgerforge-test@example.com"

cat <<'EOF' > CHANGELOG.md
# Changelog

## Unreleased
EOF
printf 'baseline docs\n' > docs/repository-governance.md
printf 'baseline code\n' > service.txt

git add CHANGELOG.md docs/repository-governance.md service.txt
git commit -q -m "Initial state"
base_commit="$(git rev-parse HEAD)"
git update-ref refs/remotes/origin/main "${base_commit}"

git checkout -q -b feature/governance-range
printf 'documented change\n' >> service.txt
printf '\n### Fixed\n- Documented governance-safe change.\n' >> CHANGELOG.md
printf 'documented update\n' >> docs/repository-governance.md
git add CHANGELOG.md docs/repository-governance.md service.txt
git commit -q -m "Documented feature change"
documented_commit="$(git rev-parse HEAD)"

printf 'undocumented change\n' >> service.txt
git add service.txt
git commit -q -m "Undocumented follow-up"
undocumented_commit="$(git rev-parse HEAD)"

assert_passes \
  "aggregate branch diff should still pass when earlier commits on the branch updated docs and changelog" \
  env GITHUB_BASE_REF=main ./scripts/check-governance-docs.sh

assert_passes \
  "explicit push range should pass when the pushed commits include docs and changelog" \
  env GOVERNANCE_DOCS_BASE_REF="${base_commit}" GOVERNANCE_DOCS_HEAD_REF="${documented_commit}" ./scripts/check-governance-docs.sh

assert_fails \
  "explicit push range should fail when the pushed commits omit docs and changelog even if earlier branch commits included them" \
  env GOVERNANCE_DOCS_BASE_REF="${documented_commit}" GOVERNANCE_DOCS_HEAD_REF="${undocumented_commit}" ./scripts/check-governance-docs.sh

echo "Governance range regression checks passed."
