set dotenv-load := true
set shell := ["bash", "-euo", "pipefail", "-c"]

compose := "docker compose --env-file .env -f infra/compose.yaml"

# List available commands
default:
    @just --list

# Create .env from the template without overwriting existing configuration
init:
    @if [[ ! -f .env ]]; then cp .env.example .env; echo "Created .env: configure secrets and the domain before production deployment."; else echo ".env already exists."; fi

# Validate the Docker Compose configuration
compose-validate: require-env
    {{compose}} config --quiet

# Start the API and PostgreSQL for local development
dev: require-env
    {{compose}} up -d --build --wait

# Stop the local stack while preserving the database
down: require-env
    {{compose}} down

# Stop the local stack and delete its volumes (destructive)
reset: require-env
    @read -r -p "Delete local containers and database? [y/N] " answer; [[ "$answer" =~ ^[Yy]$ ]] && {{compose}} down -v

# Show local service status
status: require-env
    {{compose}} ps

# Follow all local logs
logs: require-env
    {{compose}} logs -f --tail=200

# Follow API logs
api-logs: require-env
    {{compose}} logs -f --tail=200 api

# Follow PostgreSQL logs
db-logs: require-env
    {{compose}} logs -f --tail=200 postgres

# Check the local health endpoint
health: require-env
    @source .env; curl -fsS "http://127.0.0.1:${API_PORT:-8080}/health"; echo

# Build the backend Docker image
backend-build:
    docker build -t stepstracker-api:local backend

# Run backend tests in an isolated Gradle container
backend-test:
    docker run --rm --user "$(id -u):$(id -g)" -e GRADLE_USER_HOME=/tmp/gradle -v "$PWD/backend:/src" -w /src gradle:8.12-jdk17 gradle test --no-daemon

# Remove locally generated backend artifacts
backend-clean:
    docker run --rm --user "$(id -u):$(id -g)" -e GRADLE_USER_HOME=/tmp/gradle -v "$PWD/backend:/src" -w /src gradle:8.12-jdk17 gradle clean --no-daemon

# Build the Android debug APK
android-build:
    ./android/gradlew -p android assembleDebug --no-daemon

# Run Android unit tests
android-test:
    ./android/gradlew -p android testDebugUnitTest --no-daemon

# Install the debug APK on a connected device
android-install:
    ./android/gradlew -p android installDebug --no-daemon

# Remove Android build artifacts
android-clean:
    ./android/gradlew -p android clean --no-daemon

# Run the complete test suite
test: backend-test android-test

# Build the backend and Android APK
build: backend-build android-build

# Start the VPS stack with Caddy and HTTPS
prod-up: require-env
    {{compose}} --profile production up -d --build --wait

# Stop the VPS stack while preserving volumes
prod-down: require-env
    {{compose}} --profile production down

# Show VPS stack status
prod-status: require-env
    {{compose}} --profile production ps

# Follow VPS stack logs
prod-logs: require-env
    {{compose}} --profile production logs -f --tail=200

# Create a PostgreSQL dump in backups/ or at the specified path
backup output="": require-env
    @source .env; mkdir -p backups; target="{{output}}"; [[ -n "$target" ]] || target="backups/stepstracker-$(date -u +%Y%m%dT%H%M%SZ).dump"; {{compose}} exec -T postgres pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc > "$target"; echo "Backup created: $target"

# Restore a dump into the current database (destructive)
restore file: require-env
    @test -f "{{file}}" || { echo "File not found: {{file}}" >&2; exit 1; }; read -r -p "Overwrite the current database? [y/N] " answer; [[ "$answer" =~ ^[Yy]$ ]]; source .env; {{compose}} stop api; {{compose}} exec -T postgres dropdb -U "$POSTGRES_USER" --if-exists "$POSTGRES_DB"; {{compose}} exec -T postgres createdb -U "$POSTGRES_USER" "$POSTGRES_DB"; {{compose}} exec -T postgres pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists < "{{file}}"; {{compose}} start api

# Verify that .env exists
[private]
require-env:
    @test -f .env || { echo "Missing .env file. Run: just init" >&2; exit 1; }
