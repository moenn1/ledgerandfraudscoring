#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." >/dev/null 2>&1 && pwd)"
cd "${ROOT_DIR}"

EMPTY_TREE="$(git hash-object -t tree /dev/null)"
NULL_SHA="0000000000000000000000000000000000000000"
BASE_COMMIT=""
HEAD_COMMIT="HEAD"
RANGE_SOURCE=""

is_null_sha() {
  local candidate="$1"
  [[ "${candidate}" == "${NULL_SHA}" ]]
}

resolve_commit() {
  local candidate="$1"
  local role="$2"

  if is_null_sha "${candidate}"; then
    printf '%s\n' "${EMPTY_TREE}"
    return 0
  fi

  if ! git rev-parse --verify "${candidate}^{commit}" >/dev/null 2>&1; then
    echo "Unable to resolve ${role} ref '${candidate}' for governance validation." >&2
    exit 1
  fi

  git rev-parse --verify "${candidate}^{commit}"
}

if [[ -n "${GOVERNANCE_DOCS_BASE_REF:-}" ]]; then
  BASE_COMMIT="$(resolve_commit "${GOVERNANCE_DOCS_BASE_REF}" "base")"
  HEAD_COMMIT="$(resolve_commit "${GOVERNANCE_DOCS_HEAD_REF:-HEAD}" "head")"
  RANGE_SOURCE="explicit"
elif [[ -n "${GITHUB_BASE_REF:-}" ]] && git rev-parse --verify "origin/${GITHUB_BASE_REF}" >/dev/null 2>&1; then
  BASE_COMMIT="$(git merge-base HEAD "origin/${GITHUB_BASE_REF}")"
  RANGE_SOURCE="pull_request"
elif git rev-parse --verify HEAD^ >/dev/null 2>&1; then
  BASE_COMMIT="$(git rev-parse HEAD^)"
  RANGE_SOURCE="local"
else
  BASE_COMMIT="${EMPTY_TREE}"
  RANGE_SOURCE="initial"
fi

changed_files=()
while IFS= read -r file_path; do
  if [[ -n "${file_path}" ]]; then
    changed_files+=("${file_path}")
  fi
done < <(git diff --name-only "${BASE_COMMIT}" "${HEAD_COMMIT}")

if [[ ${#changed_files[@]} -eq 0 ]]; then
  echo "No committed file changes detected for the ${RANGE_SOURCE} governance range."
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

echo "Governance documentation checks passed for the ${RANGE_SOURCE} range."
