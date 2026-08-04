#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/lib.sh"
cd "$TEMPLATE_ROOT"

command -v docker >/dev/null 2>&1 || die "docker missing — see: just pending"
[ -f .env ] || die "Missing .env — run: just setup"

compose=(docker compose --env-file .env -f infra/compose.yaml)

info "Starting the local stack (API + PostgreSQL)…"
"${compose[@]}" up -d --build --wait

port="$(grep -E '^API_PORT=' .env | head -1 | sed 's/^[^=]*=//')"; port="${port:-8080}"
ok "Stack is up — API health: http://127.0.0.1:${port}/health"
