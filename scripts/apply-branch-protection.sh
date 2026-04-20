#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/.." >/dev/null 2>&1 && pwd)"

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "missing required command: $1" >&2
    exit 1
  fi
}

resolve_repo() {
  local remote_url repo_path

  remote_url="$(git -C "${ROOT_DIR}" remote get-url origin)"

  case "${remote_url}" in
    https://github.com/*)
      repo_path="${remote_url#https://github.com/}"
      ;;
    git@github.com:*)
      repo_path="${remote_url#git@github.com:}"
      ;;
    *)
      echo "unsupported origin remote: ${remote_url}" >&2
      exit 1
      ;;
  esac

  repo_path="${repo_path%.git}"
  GITHUB_OWNER="${repo_path%%/*}"
  GITHUB_REPO="${repo_path##*/}"
}

resolve_auth_args() {
  if [[ -n "${GITHUB_TOKEN:-}" ]]; then
    AUTH_ARGS=(-H "Authorization: Bearer ${GITHUB_TOKEN}")
    return
  fi

  local credentials username password
  credentials="$(printf 'protocol=https\nhost=github.com\n\n' | git credential fill)"
  username="$(printf '%s\n' "${credentials}" | awk -F= '/^username=/{print $2}')"
  password="$(printf '%s\n' "${credentials}" | awk -F= '/^password=/{print $2}')"

  if [[ -z "${username}" || -z "${password}" ]]; then
    echo "missing GitHub credentials; set GITHUB_TOKEN or configure git credential storage" >&2
    exit 1
  fi

  AUTH_ARGS=(-u "${username}:${password}")
}

apply_repo_merge_policy() {
  local payload
  payload="$(jq -n \
    --argjson allow_merge_commit false \
    --argjson allow_rebase_merge false \
    --argjson allow_squash_merge true \
    --argjson delete_branch_on_merge true \
    '{
      allow_merge_commit: $allow_merge_commit,
      allow_rebase_merge: $allow_rebase_merge,
      allow_squash_merge: $allow_squash_merge,
      delete_branch_on_merge: $delete_branch_on_merge
    }'
  )"

  curl -fsS \
    "${AUTH_ARGS[@]}" \
    -H "Accept: application/vnd.github+json" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    -X PATCH \
    "https://api.github.com/repos/${GITHUB_OWNER}/${GITHUB_REPO}" \
    -d "${payload}" >/dev/null
}

build_status_checks_json() {
  if [[ -z "${REQUIRED_STATUS_CHECKS:-}" ]]; then
    printf 'null'
    return
  fi

  jq -n --arg contexts "${REQUIRED_STATUS_CHECKS}" '
    {
      strict: true,
      contexts: ($contexts | split(",") | map(gsub("^\\s+|\\s+$"; "")) | map(select(length > 0)))
    }'
}

apply_branch_protection() {
  local branch="$1" status_checks payload
  status_checks="$(build_status_checks_json)"

  payload="$(jq -n \
    --argjson required_status_checks "${status_checks}" \
    '{
      required_status_checks: $required_status_checks,
      enforce_admins: true,
      required_pull_request_reviews: {
        dismiss_stale_reviews: true,
        require_code_owner_reviews: true,
        require_last_push_approval: true,
        required_approving_review_count: 1
      },
      restrictions: null,
      required_linear_history: true,
      allow_force_pushes: false,
      allow_deletions: false,
      block_creations: false,
      required_conversation_resolution: true,
      lock_branch: false,
      allow_fork_syncing: true
    }'
  )"

  curl -fsS \
    "${AUTH_ARGS[@]}" \
    -H "Accept: application/vnd.github+json" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    -X PUT \
    "https://api.github.com/repos/${GITHUB_OWNER}/${GITHUB_REPO}/branches/${branch}/protection" \
    -d "${payload}" >/dev/null
}

print_summary() {
  local branch="$1"
  curl -fsS \
    "${AUTH_ARGS[@]}" \
    -H "Accept: application/vnd.github+json" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    "https://api.github.com/repos/${GITHUB_OWNER}/${GITHUB_REPO}/branches/${branch}/protection" | \
    jq '{
      required_status_checks,
      enforce_admins,
      required_pull_request_reviews,
      required_linear_history,
      allow_force_pushes,
      allow_deletions,
      required_conversation_resolution
    }'
}

main() {
  local branch="${1:-main}"

  require_cmd curl
  require_cmd git
  require_cmd jq

  resolve_repo
  resolve_auth_args
  apply_repo_merge_policy
  apply_branch_protection "${branch}"
  print_summary "${branch}"
}

main "$@"
