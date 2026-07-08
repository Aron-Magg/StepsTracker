set dotenv-load
set shell := ["bash", "-euo", "pipefail", "-c"]

compose := "docker compose --env-file .env -f infra/compose.yaml"

# Print the recipe list. Invoked when `just` is called with no argument.
default:
    @just --list

# Create .env from the template without overwriting existing configuration.
[group('setup')]
init:
    @if [[ ! -f .env ]]; then cp .env.example .env; echo "Created .env: configure secrets and the domain before production deployment."; else echo ".env already exists."; fi

# Validate the Docker Compose configuration without starting services.
[group('setup')]
compose-validate: require-env
    {{ compose }} config --quiet

# Start the API and PostgreSQL for local development.
[group('run')]
dev: require-env
    {{ compose }} up -d --build --wait

# Start the development stack on the trusted LAN for phone access.
[group('run')]
lan-up: require-env
    API_HOST=0.0.0.0 {{ compose }} up -d --build --wait

# Check the local API health endpoint.
[group('run')]
health: require-env
    @source .env; curl -fsS "http://127.0.0.1:${API_PORT:-8080}/health"; echo

# Build the debug APK (android/app/build/outputs/apk/debug/app-debug.apk).
[group('android')]
android-build:
    @sdk="${ANDROID_HOME:-$HOME/Android/Sdk}"; test -d "$sdk" || { echo "Android SDK not found. Set ANDROID_HOME or install it in $HOME/Android/Sdk." >&2; exit 1; }; ANDROID_HOME="$sdk" ./android/gradlew -p android assembleDebug --no-daemon

# Build and install the debug APK on a connected device or emulator via adb.
[group('android')]
android-install:
    @sdk="${ANDROID_HOME:-$HOME/Android/Sdk}"; test -d "$sdk" || { echo "Android SDK not found. Set ANDROID_HOME or install it in $HOME/Android/Sdk." >&2; exit 1; }; ANDROID_HOME="$sdk" ./android/gradlew -p android installDebug --no-daemon

# Build and install a debug APK configured for this computer's LAN API.
[group('android')]
android-install-lan ip="": require-env
    @sdk="${ANDROID_HOME:-$HOME/Android/Sdk}"; test -d "$sdk" || { echo "Android SDK not found. Set ANDROID_HOME or install it in $HOME/Android/Sdk." >&2; exit 1; }; host="{{ ip }}"; [[ -n "$host" ]] || host="$(ip route get 1.1.1.1 | awk '{for (i=1; i<=NF; i++) if ($i=="src") {print $(i+1); exit}}')"; source .env; echo "Installing with API URL http://$host:${API_PORT:-8080}/"; ANDROID_HOME="$sdk" ./android/gradlew -p android installDebug -PAPI_BASE_URL="http://$host:${API_PORT:-8080}/" --no-daemon

# Run Android JVM unit tests.
[group('android')]
android-test:
    @sdk="${ANDROID_HOME:-$HOME/Android/Sdk}"; test -d "$sdk" || { echo "Android SDK not found. Set ANDROID_HOME or install it in $HOME/Android/Sdk." >&2; exit 1; }; ANDROID_HOME="$sdk" ./android/gradlew -p android testDebugUnitTest --no-daemon

# Remove Android build artifacts.
[group('android')]
android-clean:
    @sdk="${ANDROID_HOME:-$HOME/Android/Sdk}"; test -d "$sdk" || { echo "Android SDK not found. Set ANDROID_HOME or install it in $HOME/Android/Sdk." >&2; exit 1; }; ANDROID_HOME="$sdk" ./android/gradlew -p android clean --no-daemon

# Stop the local stack while preserving the database volume.
[group('stack')]
stack-down: require-env
    {{ compose }} down

# Follow logs from every local stack service.
[group('stack')]
stack-logs: require-env
    {{ compose }} logs -f --tail=200

# Follow logs from one service, or every service if SVC is empty.
[group('stack')]
stack-logs-service SVC="": require-env
    @if [[ -n "{{ SVC }}" ]]; then {{ compose }} logs -f --tail=200 "{{ SVC }}"; else {{ compose }} logs -f --tail=200; fi

# Follow API logs.
[group('stack')]
api-logs: require-env
    {{ compose }} logs -f --tail=200 api

# Follow PostgreSQL logs.
[group('stack')]
db-logs: require-env
    {{ compose }} logs -f --tail=200 postgres

# Show the current local service status.
[group('stack')]
stack-status: require-env
    {{ compose }} ps

# Build the backend Docker image.
[group('backend')]
backend-build:
    docker build -t stepstracker-api:local backend

# Run backend tests in an isolated Gradle container.
[group('backend')]
backend-test:
    docker run --rm --user "$(id -u):$(id -g)" -e GRADLE_USER_HOME=/tmp/gradle -v "$PWD/backend:/src" -w /src gradle:8.12-jdk17 gradle test --project-cache-dir /tmp/project-cache --no-daemon

# Remove locally generated backend artifacts.
[group('backend')]
backend-clean:
    docker run --rm --user "$(id -u):$(id -g)" -e GRADLE_USER_HOME=/tmp/gradle -v "$PWD/backend:/src" -w /src gradle:8.12-jdk17 gradle clean --project-cache-dir /tmp/project-cache --no-daemon

# Run the complete backend and Android test suite.
[group('test')]
test: backend-test android-test

# Run only backend JVM tests.
[group('test')]
test-backend: backend-test

# Run only Android JVM tests.
[group('test')]
test-android: android-test

# Create a PostgreSQL dump in backups/ or at the specified path.
[group('db')]
backup output="": require-env
    @source .env; mkdir -p backups; target="{{ output }}"; [[ -n "$target" ]] || target="backups/stepstracker-$(date -u +%Y%m%dT%H%M%SZ).dump"; {{ compose }} exec -T postgres pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc > "$target"; echo "Backup created: $target"

# Restore a dump into the current database (destructive).
[group('db')]
restore file: require-env
    @test -f "{{ file }}" || { echo "File not found: {{ file }}" >&2; exit 1; }; read -r -p "Overwrite the current database? [y/N] " answer; [[ "$answer" =~ ^[Yy]$ ]]; source .env; {{ compose }} stop api; {{ compose }} exec -T postgres dropdb -U "$POSTGRES_USER" --if-exists "$POSTGRES_DB"; {{ compose }} exec -T postgres createdb -U "$POSTGRES_USER" "$POSTGRES_DB"; {{ compose }} exec -T postgres pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists < "{{ file }}"; {{ compose }} start api

# Delete local containers and the database volume after confirmation.
[group('db')]
wipe-db: require-env
    @read -r -p "Delete local containers and database? [y/N] " answer; [[ "$answer" =~ ^[Yy]$ ]] && {{ compose }} down -v

# Start the VPS stack with Caddy and HTTPS.
[group('production')]
prod-up: require-env
    {{ compose }} --profile production up -d --build --wait

# Stop the VPS stack while preserving volumes.
[group('production')]
prod-down: require-env
    {{ compose }} --profile production down

# Show VPS stack status.
[group('production')]
prod-status: require-env
    {{ compose }} --profile production ps

# Follow VPS stack logs.
[group('production')]
prod-logs: require-env
    {{ compose }} --profile production logs -f --tail=200

# Build the backend image and Android debug APK.
[group('tools')]
build: backend-build android-build

# Print the computer's primary local IPv4 address.
[group('tools')]
lan-ip:
    @ip route get 1.1.1.1 | awk '{for (i=1; i<=NF; i++) if ($i=="src") {print $(i+1); exit}}'

# Remove backend and Android build artifacts.
[group('tools')]
clean: backend-clean android-clean

[private]
require-env:
    @test -f .env || { echo "Missing .env file. Run: just init" >&2; exit 1; }

alias up := dev
alias down := stack-down
alias logs := stack-logs
alias wipe := wipe-db
alias reset := wipe-db
alias status := stack-status
