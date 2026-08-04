#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/lib.sh"
cd "$TEMPLATE_ROOT"

rc=0
info "Config check"
bash "$SCRIPTS_DIR/unix/config.sh" check || rc=1
info "Health check"
bash "$SCRIPTS_DIR/unix/health.sh" || rc=1

if command -v docker >/dev/null 2>&1 && [ -f .env ]; then
    info "Compose config check"
    docker compose --env-file .env -f infra/compose.yaml config --quiet || rc=1
fi

if [ "$rc" -eq 0 ]; then ok "check passed"; else err "check failed"; fi
exit "$rc"
