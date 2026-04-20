#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." >/dev/null 2>&1 && pwd)"
cd "${ROOT_DIR}"

assert_contains() {
  local file_path="$1"
  local needle="$2"
  local description="$3"

  if ! grep -Fq "${needle}" "${file_path}"; then
    echo "${description} is missing ${needle}."
    exit 1
  fi
}

assert_contains "CHANGELOG.md" "## Unreleased" "CHANGELOG.md"

while IFS= read -r doc_file; do
  doc_name="$(basename "${doc_file}")"
  assert_contains "docs/README.md" "\`${doc_name}\`" "docs/README.md"
done < <(find "docs" -maxdepth 1 -type f -name "*.md" ! -name "README.md" | sort)

while IFS= read -r workflow_file; do
  workflow_path="${workflow_file#./}"
  assert_contains "README.md" "\`${workflow_path}\`" "README.md"
  assert_contains "docs/repository-governance.md" "\`${workflow_path}\`" "docs/repository-governance.md"
done < <(find ".github/workflows" -maxdepth 1 -type f \( -name "*.yml" -o -name "*.yaml" \) | sort)

assert_contains "scripts/README.md" "\`./scripts/check-docs-index.sh\`" "scripts/README.md"
assert_contains "scripts/README.md" "\`./scripts/check-governance-docs.sh\`" "scripts/README.md"

echo "Documentation coverage checks passed."
