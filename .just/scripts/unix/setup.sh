#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/lib.sh"
cd "$TEMPLATE_ROOT"

info "Setting up project…"
bash "$SCRIPTS_DIR/unix/config.sh" init || true

info "Checking tools…"
bash "$SCRIPTS_DIR/unix/health.sh" || warn "Some tools are missing — run: just cure-plan"

if command -v docker >/dev/null 2>&1 && [ -f .env ]; then
    info "Validating Docker Compose configuration…"
    if docker compose --env-file .env -f infra/compose.yaml config --quiet; then
        ok "Compose configuration is valid."
    else
        warn "Compose configuration invalid — check infra/compose.yaml and .env"
    fi
fi

ok "Setup complete. Start the stack with: just run"
