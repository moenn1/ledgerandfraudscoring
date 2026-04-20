#!/bin/sh
set -eu

template="/usr/share/nginx/html/runtime-config.template.js"
target="/usr/share/nginx/html/runtime-config.js"

if [ -f "$template" ]; then
  envsubst '${LEDGERFORGE_API_BASE_URL} ${LEDGERFORGE_OPERATOR_BEARER_TOKEN}' < "$template" > "$target"
fi

