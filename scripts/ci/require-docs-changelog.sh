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

changelog_changed=false
doc_files=()
changed_areas=()

contains_value() {
  local needle="$1"
  shift

  local value
  for value in "$@"; do
    if [[ "$value" == "$needle" ]]; then
      return 0
    fi
  done

  return 1
}

add_doc_file() {
  local file="$1"
  if ! contains_value "$file" "${doc_files[@]:-}"; then
    doc_files+=("$file")
  fi
}

add_area() {
  local area="$1"
  if ! contains_value "$area" "${changed_areas[@]:-}"; then
    changed_areas+=("$area")
  fi
}

is_documentation_file() {
  local file="$1"

  case "$file" in
    CHANGELOG.md|README.md|docs/*.md|frontend/README.md|scripts/README.md|postman/README.md|postman/*.json)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

classify_area_change() {
  local file="$1"

  case "$file" in
    backend/*)
      add_area "backend"
      ;;
    frontend/*)
      add_area "frontend"
      ;;
    .github/CODEOWNERS|.github/workflows/*|.githooks/*|docker-compose.yml|scripts/*)
      add_area "platform"
      ;;
    postman/*)
      add_area "postman"
      ;;
  esac
}

area_has_required_docs() {
  local area="$1"
  local doc_file

  for doc_file in "${doc_files[@]}"; do
    case "$area:$doc_file" in
      backend:docs/architecture.md|backend:docs/state-machine.md|backend:docs/ledger-invariants.md|backend:docs/fraud-scoring.md|backend:docs/failure-scenarios.md|backend:docs/observability-security.md|backend:docs/runbook-local-demo.md|backend:postman/README.md|backend:postman/*.json)
        return 0
        ;;
      frontend:frontend/README.md|frontend:docs/state-machine.md|frontend:docs/fraud-scoring.md|frontend:docs/failure-scenarios.md|frontend:docs/observability-security.md|frontend:docs/runbook-local-demo.md)
        return 0
        ;;
      platform:README.md|platform:docs/ci-quality-gates.md|platform:docs/documentation-governance.md|platform:scripts/README.md)
        return 0
        ;;
      postman:postman/README.md|postman:postman/*.json|postman:docs/architecture.md|postman:docs/state-machine.md|postman:docs/failure-scenarios.md|postman:docs/runbook-local-demo.md)
        return 0
        ;;
    esac
  done

  return 1
}

for file in "${changed_files[@]}"; do
  case "$file" in
    CHANGELOG.md)
      changelog_changed=true
      ;;
  esac

  if is_documentation_file "$file"; then
    if [[ "$file" != "CHANGELOG.md" ]]; then
      add_doc_file "$file"
    fi
    continue
  fi

  classify_area_change "$file"
done

echo "Docs gate diff base: $base_ref"
printf 'Changed files:\n'
printf '  %s\n' "${changed_files[@]}"

if ((${#doc_files[@]} > 0)); then
  printf 'Documentation updates in diff:\n'
  printf '  %s\n' "${doc_files[@]}"
fi

if ((${#changed_areas[@]} > 0)); then
  printf 'Changed areas requiring coverage:\n'
  printf '  %s\n' "${changed_areas[@]}"
fi

errors=()

if [[ "$changelog_changed" != true ]]; then
  errors+=("CHANGELOG.md must be updated in every push or pull request.")
fi

for area in "${changed_areas[@]}"; do
  if ! area_has_required_docs "$area"; then
    case "$area" in
      backend)
        errors+=("Backend changes require one of: docs/architecture.md, docs/state-machine.md, docs/ledger-invariants.md, docs/fraud-scoring.md, docs/failure-scenarios.md, docs/observability-security.md, docs/runbook-local-demo.md, postman/README.md, or postman/*.json.")
        ;;
      frontend)
        errors+=("Frontend changes require one of: frontend/README.md, docs/state-machine.md, docs/fraud-scoring.md, docs/failure-scenarios.md, docs/observability-security.md, or docs/runbook-local-demo.md.")
        ;;
      platform)
        errors+=("Workflow, script, hook, CODEOWNERS, or compose changes require one of: README.md, docs/ci-quality-gates.md, docs/documentation-governance.md, or scripts/README.md.")
        ;;
      postman)
        errors+=("Postman changes require one of: postman/README.md, postman/*.json, docs/architecture.md, docs/state-machine.md, docs/failure-scenarios.md, or docs/runbook-local-demo.md.")
        ;;
    esac
  fi
done

if ((${#errors[@]} > 0)); then
  printf '\nPolicy violations:\n'
  printf '  - %s\n' "${errors[@]}"
  exit 1
fi

echo "Docs and changelog policy checks passed."
