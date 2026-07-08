# Backup e ripristino

Backup consistente:

```bash
docker compose --env-file .env -f infra/compose.yaml exec -T postgres pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc > stepstracker.dump
```

Ripristino su database vuoto:

```bash
docker compose --env-file .env -f infra/compose.yaml stop api
docker compose --env-file .env -f infra/compose.yaml exec -T postgres dropdb -U "$POSTGRES_USER" --if-exists "$POSTGRES_DB"
docker compose --env-file .env -f infra/compose.yaml exec -T postgres createdb -U "$POSTGRES_USER" "$POSTGRES_DB"
docker compose --env-file .env -f infra/compose.yaml exec -T postgres pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists < stepstracker.dump
docker compose --env-file .env -f infra/compose.yaml start api
```

Conservare i backup cifrati fuori dalla VPS e provare periodicamente il ripristino.

