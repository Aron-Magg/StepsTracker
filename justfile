set dotenv-load := true
set shell := ["bash", "-euo", "pipefail", "-c"]

compose := "docker compose --env-file .env -f infra/compose.yaml"

# Mostra i comandi disponibili
default:
    @just --list

# Crea .env dal template senza sovrascrivere configurazioni esistenti
init:
    @if [[ ! -f .env ]]; then cp .env.example .env; echo "Creato .env: configura segreti e dominio prima della produzione."; else echo ".env esiste già."; fi

# Valida la configurazione Docker Compose
compose-validate: require-env
    {{compose}} config --quiet

# Avvia API e PostgreSQL per lo sviluppo locale
dev: require-env
    {{compose}} up -d --build --wait

# Ferma lo stack locale mantenendo il database
down: require-env
    {{compose}} down

# Ferma lo stack locale ed elimina anche i volumi (distruttivo)
reset: require-env
    @read -r -p "Eliminare container e database locale? [y/N] " answer; [[ "$answer" =~ ^[Yy]$ ]] && {{compose}} down -v

# Mostra lo stato dei servizi locali
status: require-env
    {{compose}} ps

# Segue tutti i log locali
logs: require-env
    {{compose}} logs -f --tail=200

# Segue i log dell'API
api-logs: require-env
    {{compose}} logs -f --tail=200 api

# Segue i log di PostgreSQL
db-logs: require-env
    {{compose}} logs -f --tail=200 postgres

# Controlla l'health endpoint locale
health: require-env
    @source .env; curl -fsS "http://127.0.0.1:${API_PORT:-8080}/health"; echo

# Compila l'immagine Docker del backend
backend-build:
    docker build -t stepstracker-api:local backend

# Esegue i test backend in un container Gradle isolato
backend-test:
    docker run --rm --user "$(id -u):$(id -g)" -e GRADLE_USER_HOME=/tmp/gradle -v "$PWD/backend:/src" -w /src gradle:8.12-jdk17 gradle test --no-daemon

# Pulisce gli artefatti backend generati localmente
backend-clean:
    docker run --rm --user "$(id -u):$(id -g)" -e GRADLE_USER_HOME=/tmp/gradle -v "$PWD/backend:/src" -w /src gradle:8.12-jdk17 gradle clean --no-daemon

# Compila l'APK debug Android
android-build:
    ./android/gradlew -p android assembleDebug --no-daemon

# Esegue i test unitari Android
android-test:
    ./android/gradlew -p android testDebugUnitTest --no-daemon

# Installa l'APK debug su un dispositivo collegato
android-install:
    ./android/gradlew -p android installDebug --no-daemon

# Pulisce gli artefatti Android
android-clean:
    ./android/gradlew -p android clean --no-daemon

# Esegue l'intera suite di test
test: backend-test android-test

# Compila backend e APK Android
build: backend-build android-build

# Avvia lo stack VPS con Caddy e HTTPS
prod-up: require-env
    {{compose}} --profile production up -d --build --wait

# Ferma lo stack VPS mantenendo i volumi
prod-down: require-env
    {{compose}} --profile production down

# Mostra lo stato dello stack VPS
prod-status: require-env
    {{compose}} --profile production ps

# Segue i log dello stack VPS
prod-logs: require-env
    {{compose}} --profile production logs -f --tail=200

# Crea un dump PostgreSQL in backups/ oppure nel percorso indicato
backup output="": require-env
    @source .env; mkdir -p backups; target="{{output}}"; [[ -n "$target" ]] || target="backups/stepstracker-$(date -u +%Y%m%dT%H%M%SZ).dump"; {{compose}} exec -T postgres pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc > "$target"; echo "Backup creato: $target"

# Ripristina un dump nel database corrente (distruttivo)
restore file: require-env
    @test -f "{{file}}" || { echo "File non trovato: {{file}}" >&2; exit 1; }; read -r -p "Sovrascrivere il database corrente? [y/N] " answer; [[ "$answer" =~ ^[Yy]$ ]]; source .env; {{compose}} stop api; {{compose}} exec -T postgres dropdb -U "$POSTGRES_USER" --if-exists "$POSTGRES_DB"; {{compose}} exec -T postgres createdb -U "$POSTGRES_USER" "$POSTGRES_DB"; {{compose}} exec -T postgres pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists < "{{file}}"; {{compose}} start api

# Verifica che .env sia presente
[private]
require-env:
    @test -f .env || { echo "File .env mancante. Esegui: just init" >&2; exit 1; }
