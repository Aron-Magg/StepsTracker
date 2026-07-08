# VPS deployment

Prerequisites: Docker Compose, a DNS record pointing to the VPS, and open ports 80/443.

```bash
cp .env.example .env
# Fill .env with random passwords, a JWT_SECRET of at least 32 characters, and the real domain
docker compose --env-file .env -f infra/compose.yaml --profile production up -d --build
docker compose --env-file .env -f infra/compose.yaml ps
curl https://$DOMAIN/health
```

PostgreSQL is reachable only from the Docker network. API port 8080 is bound to localhost for diagnostics; Caddy is the only public entry point.

## Updating

Create a backup first, then run:

```bash
git pull --ff-only
docker compose --env-file .env -f infra/compose.yaml --profile production up -d --build
```

Flyway automatically applies additive migrations during startup.

