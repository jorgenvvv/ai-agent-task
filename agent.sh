#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${AGENT_BASE_URL:-http://localhost:8080}"
ASK_URL="${BASE_URL}/api/v1/agent/ask"

print_banner() {
  cat <<EOF
========================================
  AI Agent — interactive ask client
========================================
  Endpoint : ${ASK_URL}
  Quit     : Ctrl+C or Ctrl+D
  Empty    : sent as-is (useful to test 400 validation)
  Env      : AGENT_BASE_URL (default http://localhost:8080)


  Server must be running, e.g.:
    export OPENAI_API_KEY=...
    ./gradlew bootRun
========================================
EOF
}

ask() {
  local question="$1"
  local payload

  if command -v jq >/dev/null 2>&1; then
    payload=$(jq -n --arg q "$question" '{question: $q}')
  else
    local escaped
    escaped=$(printf '%s' "$question" | sed 's/\\/\\\\/g; s/"/\\"/g')
    payload="{\"question\":\"${escaped}\"}"
  fi

  local tmp http_code
  tmp=$(mktemp)
  http_code=$(curl -sS -o "$tmp" -w '%{http_code}' \
    -X POST "$ASK_URL" \
    -H 'Content-Type: application/json' \
    -d "$payload" 2>/dev/null || echo "000")

  echo
  echo "--- HTTP ${http_code} ---"
  if command -v jq >/dev/null 2>&1 && jq empty "$tmp" 2>/dev/null; then
    jq . "$tmp"
  else
    cat "$tmp"
    echo
  fi
  echo "----------------"
  rm -f "$tmp"
}

print_banner

while true; do
  echo
  printf 'prompt> '
  if ! IFS= read -r question; then
    echo
    echo "Bye."
    break
  fi

  ask "$question"
done

