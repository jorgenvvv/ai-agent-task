#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${AGENT_BASE_URL:-http://localhost:8080}"
ASK_URL="${BASE_URL}/api/v1/agent/ask"
SESSION_ID="${AGENT_SESSION_ID:-}"

usage() {
  cat <<EOF
Usage: $(basename "$0") [options]

Interactive client for POST /api/v1/agent/ask

Options:
  -s, --session <id>   Use conversation memory with this sessionId
                       (same id across turns → follow-ups keep context)
  -h, --help           Show this help and exit

Environment:
  AGENT_BASE_URL       Base URL (default: http://localhost:8080)
  AGENT_SESSION_ID     Default sessionId if -s/--session is not given

Examples:
  $(basename "$0")
  $(basename "$0") --session demo-1
  AGENT_SESSION_ID=demo-1 $(basename "$0")

Session notes:
  - Omit session → each question is stateless (no history)
  - sessionId: 1–100 chars, [a-zA-Z0-9_-] only
  - Blank/whitespace session is treated as stateless by the API
  - In-memory only (lost on server restart; not shared across instances)

Server must be running, e.g.:
  export OPENAI_API_KEY=...
  ./gradlew bootRun
EOF
}

print_banner() {
  cat <<EOF
========================================
  AI Agent — interactive ask client
========================================
  Endpoint : ${ASK_URL}
  Session  : ${SESSION_ID:-'(none — stateless)'}
  Quit     : Ctrl+C or Ctrl+D
  Empty    : sent as-is (useful to test 400 validation)
  Help     : $(basename "$0") --help

  Env      : AGENT_BASE_URL, AGENT_SESSION_ID
  Flag     : -s/--session <id> for multi-turn memory (UC-06)

  Server must be running, e.g.:
    export OPENAI_API_KEY=...
    ./gradlew bootRun
========================================
EOF
}

build_payload() {
  local question="$1"
  if command -v jq >/dev/null 2>&1; then
    if [[ -n "${SESSION_ID}" ]]; then
      jq -n --arg q "$question" --arg s "$SESSION_ID" \
        '{question: $q, sessionId: $s}'
    else
      jq -n --arg q "$question" '{question: $q}'
    fi
  else
    local escaped
    escaped=$(printf '%s' "$question" | sed 's/\\/\\\\/g; s/"/\\"/g')
    if [[ -n "${SESSION_ID}" ]]; then
      local sid_escaped
      sid_escaped=$(printf '%s' "$SESSION_ID" | sed 's/\\/\\\\/g; s/"/\\"/g')
      printf '{"question":"%s","sessionId":"%s"}' "$escaped" "$sid_escaped"
    else
      printf '{"question":"%s"}' "$escaped"
    fi
  fi
}

ask() {
  local question="$1"
  local payload
  payload=$(build_payload "$question")

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

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    -s|--session)
      if [[ $# -lt 2 || -z "${2:-}" || "${2:-}" == -* ]]; then
        echo "error: $1 requires a non-empty session id" >&2
        echo "Try '$(basename "$0") --help' for more information." >&2
        exit 2
      fi
      SESSION_ID="$2"
      shift 2
      ;;
    --session=*)
      SESSION_ID="${1#*=}"
      if [[ -z "$SESSION_ID" ]]; then
        echo "error: --session requires a non-empty session id" >&2
        exit 2
      fi
      shift
      ;;
    -*)
      echo "error: unknown option: $1" >&2
      echo "Try '$(basename "$0") --help' for more information." >&2
      exit 2
      ;;
    *)
      echo "error: unexpected argument: $1" >&2
      echo "Try '$(basename "$0") --help' for more information." >&2
      exit 2
      ;;
  esac
done

print_banner

while true; do
  echo
  if [[ -n "${SESSION_ID}" ]]; then
    printf 'prompt[%s]> ' "$SESSION_ID"
  else
    printf 'prompt> '
  fi
  if ! IFS= read -r question; then
    echo
    echo "Bye."
    break
  fi

  ask "$question"
done
