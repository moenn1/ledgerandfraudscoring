#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

determine_base_ref() {
  if [[ -n "${CI_DOCS_BASE:-}" ]]; then
    printf '%s\n' "$CI_DOCS_BASE"
    return
  fi

  if [[ -n "${GITHUB_EVENT_NAME:-}" && -n "${GITHUB_EVENT_PATH:-}" && -f "${GITHUB_EVENT_PATH:-}" ]]; then
    case "$GITHUB_EVENT_NAME" in
      pull_request|pull_request_target)
        jq -r '.pull_request.base.sha // empty' "$GITHUB_EVENT_PATH"
        return
        ;;
      push)
        local before
        before="$(jq -r '.before // empty' "$GITHUB_EVENT_PATH")"
        if [[ "$before" != "0000000000000000000000000000000000000000" ]]; then
          printf '%s\n' "$before"
          return
        fi
        ;;
    esac
  fi

  if git rev-parse --verify HEAD^ >/dev/null 2>&1; then
    printf 'HEAD^\n'
    return
  fi

  if git rev-parse --verify origin/main >/dev/null 2>&1; then
    printf 'origin/main\n'
  fi
}

base_ref="$(determine_base_ref)"
if [[ -z "$base_ref" ]]; then
  echo "Unable to determine a diff base for docs/changelog enforcement."
  exit 1
fi

changed_files=()
while IFS= read -r changed_file; do
  [[ -n "$changed_file" ]] || continue
  changed_files+=("$changed_file")
done < <(
  {
    git diff --name-only "$base_ref"...HEAD
    git diff --name-only HEAD
    git ls-files --others --exclude-standard
  } | awk '!seen[$0]++'
)

if ((${#changed_files[@]} == 0)); then
  echo "No changed files detected between $base_ref and HEAD."
  exit 0
fi

docs_changed=false
changelog_changed=false
source_changed=false

for file in "${changed_files[@]}"; do
  case "$file" in
    CHANGELOG.md)
      changelog_changed=true
      ;;
  esac

  case "$file" in
    README.md|docs/*|frontend/README.md|scripts/README.md|postman/README.md)
      docs_changed=true
      ;;
  esac

  case "$file" in
    .github/workflows/*|backend/*|docker-compose.yml|frontend/*|postman/*|scripts/*)
      source_changed=true
      ;;
  esac
done

echo "Docs gate diff base: $base_ref"
printf 'Changed files:\n'
printf '  %s\n' "${changed_files[@]}"

errors=()

if [[ "$changelog_changed" != true ]]; then
  errors+=("CHANGELOG.md must be updated in every push or pull request.")
fi

if [[ "$source_changed" == true && "$docs_changed" != true ]]; then
  errors+=("Code, workflow, script, compose, or Postman changes require a nearest relevant documentation update in README or docs.")
fi

if ((${#errors[@]} > 0)); then
  printf '\nPolicy violations:\n'
  printf '  - %s\n' "${errors[@]}"
  exit 1
fi

echo "Docs and changelog policy checks passed."
