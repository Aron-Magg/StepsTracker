#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/lib.sh"
cd "$TEMPLATE_ROOT"

command -v docker >/dev/null 2>&1 || die "docker missing — see: just pending"
[ -f .env ] || die "Missing .env — run: just setup"
compose=(docker compose --env-file .env -f infra/compose.yaml)

action="${1:-}"
case "$action" in
    lan-up)
        info "Starting the local stack on the trusted LAN (API bound to 0.0.0.0)…"
        API_HOST=0.0.0.0 "${compose[@]}" up -d --build --wait
        port="$(grep -E '^API_PORT=' .env | head -1 | sed 's/^[^=]*=//')"; port="${port:-8080}"
        ip="$(ip route get 1.1.1.1 | awk '{for (i=1;i<=NF;i++) if ($i=="src") {print $(i+1); exit}}')"
        ok "Stack is up on the LAN — API: http://${ip}:${port}/"
        ;;
    down)
        info "Stopping the local stack (database volume preserved)…"
        "${compose[@]}" down
        ;;
    build)
        info "Building the backend image and the Android debug APK…"
        docker build -t stepstracker-api:local backend
        bash "$SCRIPTS_DIR/unix/mobile.sh" build
        ok "Built backend image + Android APK."
        ;;
    clean)
        info "Removing backend and Android build artifacts…"
        bash "$SCRIPTS_DIR/unix/stack.sh" backend-clean
        bash "$SCRIPTS_DIR/unix/mobile.sh" clean
        ok "All build artifacts removed."
        ;;
    status)
        "${compose[@]}" ps
        ;;
    logs)
        svc="${2:-}"
        if [ -n "$svc" ]; then "${compose[@]}" logs -f --tail=200 "$svc"; else "${compose[@]}" logs -f --tail=200; fi
        ;;
    api-health)
        command -v curl >/dev/null 2>&1 || die "curl missing — see: just pending"
        port="$(grep -E '^API_PORT=' .env | head -1 | sed 's/^[^=]*=//')"; port="${port:-8080}"
        curl -fsS "http://127.0.0.1:${port}/health"; echo
        ;;
    lan-ip)
        ip route get 1.1.1.1 | awk '{for (i=1;i<=NF;i++) if ($i=="src") {print $(i+1); exit}}'
        ;;
    backend-build)
        info "Building the backend Docker image…"
        docker build -t stepstracker-api:local backend
        ok "Built image: stepstracker-api:local"
        ;;
    backend-clean)
        info "Removing backend build artifacts (Gradle container)…"
        docker run --rm --user "$(id -u):$(id -g)" \
            -e GRADLE_USER_HOME=/tmp/gradle \
            -v "$PWD/backend:/src" -w /src \
            gradle:8.12-jdk17 gradle clean --project-cache-dir /tmp/project-cache --no-daemon
        ok "Backend build cleaned."
        ;;
    prod-up)
        info "Starting the production stack (Caddy + HTTPS)…"
        "${compose[@]}" --profile production up -d --build --wait
        ;;
    prod-down)
        info "Stopping the production stack (volumes preserved)…"
        "${compose[@]}" --profile production down
        ;;
    prod-status)
        "${compose[@]}" --profile production ps
        ;;
    prod-logs)
        "${compose[@]}" --profile production logs -f --tail=200
        ;;
    *)
        die "Unknown stack action: $action (use lan-up|down|build|clean|status|logs [SVC]|api-health|lan-ip|backend-build|backend-clean|prod-up|prod-down|prod-status|prod-logs)"
        ;;
esac
