#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/.." >/dev/null 2>&1 && pwd)"

cd "${ROOT_DIR}"

resolve_range() {
  if [[ -n "${GITHUB_BASE_REF:-}" ]]; then
    if ! git show-ref --verify --quiet "refs/remotes/origin/${GITHUB_BASE_REF}"; then
      git fetch --no-tags --depth=1 origin "${GITHUB_BASE_REF}:refs/remotes/origin/${GITHUB_BASE_REF}" >/dev/null 2>&1 || \
        git fetch --no-tags origin "${GITHUB_BASE_REF}" >/dev/null 2>&1
    fi
    printf 'origin/%s...HEAD\n' "${GITHUB_BASE_REF}"
    return
  fi

  if [[ -n "$(git status --porcelain)" ]]; then
    printf '\n'
    return
  fi

  if git rev-parse --verify HEAD~1 >/dev/null 2>&1; then
    printf 'HEAD~1...HEAD\n'
    return
  fi

  printf '\n'
}

collect_changed_files() {
  local range="$1"

  if [[ -n "${range}" ]]; then
    git diff --name-only --diff-filter=ACMR "${range}"
    return
  fi

  git diff --name-only --diff-filter=ACMR HEAD
  git ls-files --others --exclude-standard
}

range="$(resolve_range)"

changed_files=()
while IFS= read -r path; do
  changed_files+=("${path}")
done < <(collect_changed_files "${range}" | awk 'NF' | sort -u)

if [[ "${#changed_files[@]}" -eq 0 ]]; then
  if [[ -n "${range}" ]]; then
    echo "No changed files detected for ${range}."
  else
    echo "No local changes detected."
  fi
  exit 0
fi

has_changelog=false
has_docs=false

for path in "${changed_files[@]}"; do
  if [[ "${path}" == "CHANGELOG.md" ]]; then
    has_changelog=true
  fi

  if [[ "${path}" == "README.md" || "${path}" == docs/* || "${path}" == "scripts/README.md" ]]; then
    has_docs=true
  fi
done

if [[ "${has_changelog}" == true && "${has_docs}" == true ]]; then
  echo "Governance check passed: changelog and documentation updates are present."
  exit 0
fi

if [[ -z "${range}" ]]; then
  printf 'Changed files for local workspace:\n'
else
  printf 'Changed files for %s:\n' "${range}"
fi
printf ' - %s\n' "${changed_files[@]}"

if [[ "${has_changelog}" != true ]]; then
  echo "Missing required CHANGELOG.md update."
fi

if [[ "${has_docs}" != true ]]; then
  echo "Missing required documentation update in README.md, docs/, or scripts/README.md."
fi

exit 1
