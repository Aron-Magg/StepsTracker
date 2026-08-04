#!/usr/bin/env bash
set -euo pipefail
. "$(dirname "$0")/lib.sh"
cd "$TEMPLATE_ROOT"

# DB_ENABLED is exported by just (dotenv-load). Default to disabled.
DB_ENABLED="${DB_ENABLED:-false}"
BACKUP_DIR="$TEMPLATE_ROOT/backups/db"
mkdir -p "$BACKUP_DIR"

if [ "$DB_ENABLED" != "true" ]; then
    warn "DB_ENABLED is not 'true' — database module disabled. Set DB_ENABLED=true in .env to use it."
    exit 0
fi

command -v docker >/dev/null 2>&1 || die "docker missing — see: just pending"
[ -f .env ] || die "Missing .env — run: just setup"
# shellcheck disable=SC1091
set -a; . ./.env; set +a
compose=(docker compose --env-file .env -f infra/compose.yaml)

action="${1:-}"
case "$action" in
    backup)
        ts="$(date +%Y%m%d-%H%M%S)"
        out="$BACKUP_DIR/backup-$ts.dump"
        info "Backing up database -> $out"
        "${compose[@]}" exec -T postgres pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc > "$out"
        ok "Backup written: $out"
        ;;
    restore)
        file="${2:-}"
        [ -n "$file" ] || die "restore requires a FILE argument"
        [ -f "$file" ] || die "File not found: $file"
        info "Restoring database from $file (stopping API first)…"
        "${compose[@]}" stop api
        "${compose[@]}" exec -T postgres dropdb -U "$POSTGRES_USER" --if-exists "$POSTGRES_DB"
        "${compose[@]}" exec -T postgres createdb -U "$POSTGRES_USER" "$POSTGRES_DB"
        "${compose[@]}" exec -T postgres pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists < "$file"
        "${compose[@]}" start api
        ok "Restore complete."
        ;;
    reset)
        info "Resetting database (dropping containers + data volume)…"
        "${compose[@]}" down -v
        ok "Reset complete — run 'just run' to recreate and re-migrate."
        ;;
    *)
        die "Unknown db action: $action (use backup|restore FILE|reset)"
        ;;
esac
