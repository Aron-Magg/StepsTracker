# Deployment su VPS

Prerequisiti: Docker Compose, un record DNS verso la VPS e porte 80/443 aperte.

```bash
cp .env.example .env
# compilare .env con password casuali, JWT_SECRET >= 32 caratteri e dominio reale
docker compose --env-file .env -f infra/compose.yaml --profile production up -d --build
docker compose --env-file .env -f infra/compose.yaml ps
curl https://$DOMAIN/health
```

PostgreSQL è raggiungibile solo dalla rete Docker. La porta 8080 dell'API è vincolata a localhost per diagnostica; Caddy è l'unico ingresso pubblico.

## Aggiornamento

Eseguire prima un backup, poi:

```bash
git pull --ff-only
docker compose --env-file .env -f infra/compose.yaml --profile production up -d --build
```

Flyway applica automaticamente migrazioni additive all'avvio.

