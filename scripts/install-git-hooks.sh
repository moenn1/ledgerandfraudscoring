#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

git config core.hooksPath "$repo_root/.githooks"
echo "Configured Git hooks at $repo_root/.githooks"
