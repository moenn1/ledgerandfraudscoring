#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." >/dev/null 2>&1 && pwd)"
cd "${ROOT_DIR}"

EMPTY_TREE="$(git hash-object -t tree /dev/null)"
BASE_COMMIT=""

if [[ -n "${GITHUB_BASE_REF:-}" ]] && git rev-parse --verify "origin/${GITHUB_BASE_REF}" >/dev/null 2>&1; then
  BASE_COMMIT="$(git merge-base HEAD "origin/${GITHUB_BASE_REF}")"
elif git rev-parse --verify HEAD^ >/dev/null 2>&1; then
  BASE_COMMIT="$(git rev-parse HEAD^)"
else
  BASE_COMMIT="${EMPTY_TREE}"
fi

changed_files=()
while IFS= read -r file_path; do
  if [[ -n "${file_path}" ]]; then
    changed_files+=("${file_path}")
  fi
done < <(git diff --name-only "${BASE_COMMIT}"...HEAD)

if [[ ${#changed_files[@]} -eq 0 ]]; then
  echo "No committed file changes detected."
  exit 0
fi

requires_docs_update=0
has_changelog_update=0
has_docs_update=0

for file in "${changed_files[@]}"; do
  case "${file}" in
    CHANGELOG.md)
      has_changelog_update=1
      ;;
    README.md|docs/*|scripts/README.md)
      has_docs_update=1
      ;;
    *)
      requires_docs_update=1
      ;;
  esac
done

if [[ ${requires_docs_update} -eq 0 ]]; then
  echo "Only changelog or documentation files changed."
  exit 0
fi

if [[ ${has_changelog_update} -ne 1 ]]; then
  echo "Repository changes must update CHANGELOG.md."
  exit 1
fi

if [[ ${has_docs_update} -ne 1 ]]; then
  echo "Repository changes must update the nearest relevant documentation file."
  exit 1
fi

echo "Governance documentation checks passed."
